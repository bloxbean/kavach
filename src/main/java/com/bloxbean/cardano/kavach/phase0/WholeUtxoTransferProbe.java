package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.WithdrawValidator;

import java.math.BigInteger;

/**
 * Exact-value transfer feasibility component. Caller must separately authenticate intent and account lifecycle.
 * <p>Immutable parameter order: {@code accountAddress}.</p>
 */
@WithdrawValidator
public class WholeUtxoTransferProbe {
    @Param
    static Address accountAddress;

    public record Transfer(TxOutRef input, BigInteger recipientIndex, Address recipient, byte[] valueDigest) {
    }

    /**
     * Checks whole-input value preservation and an optional sponsor-funded lovelace top-up.
     * This isolated fixture does not authorize a complete Kavach account operation.
     *
     * @param transfer untrusted probe-specific evidence and declared inputs
     * @param ctx      ledger-supplied context for this isolated feasibility probe
     * @return whether this probe accepts; malformed Data may instead raise a script error
     */
    @Entrypoint
    public static boolean validate(Transfer transfer, ScriptContext ctx) {
        boolean rewarding = switch (ctx.scriptInfo()) {
            case ScriptInfo.RewardingScript reward -> true;
            default -> false;
        };
        BigInteger index = transfer.recipientIndex();
        var tx = ctx.txInfo();
        boolean keyRecipient = switch (transfer.recipient().credential()) {
            case Credential.PubKeyCredential key -> true;
            default -> false;
        };
        if (!rewarding || !keyRecipient || tx.inputs().size() > 16 || tx.outputs().size() > 16 || tx.referenceInputs().size() > 4
                || ValuesLib.flattenTyped(tx.mint()).size() != 0 || tx.certificates().size() != 0 || tx.votes().size() != 0
                || tx.proposalProcedures().size() != 0 || tx.currentTreasuryAmount().isPresent() || tx.treasuryDonation().isPresent()
                || index.signum() < 0 || index.compareTo(BigInteger.valueOf(tx.outputs().size())) >= 0 || Builtins.lengthOfByteString(transfer.valueDigest()) != 32
                || !Builtins.equalsData((PlutusData) (Object) transfer, (PlutusData) (Object) new Transfer(transfer.input(), transfer.recipientIndex(), transfer.recipient(), transfer.valueDigest())))
            return false;
        var recipient = tx.outputs().get(index.intValue());
        boolean plain = switch (recipient.datum()) {
            case OutputDatum.NoOutputDatum none -> true;
            default -> false;
        };
        if (!plain || recipient.referenceScript().isPresent() || !recipient.address().equals(transfer.recipient()))
            return false;
        boolean valid = true;
        int matches = 0;
        for (var input : tx.inputs()) {
            if (input.resolved().address().credential().equals(accountAddress.credential())) {
                matches = matches + 1;
                var encoded = Builtins.serialiseData((PlutusData) (Object) input.resolved().value());
                if (!input.outRef().equals(transfer.input()) || !input.resolved().address().equals(accountAddress)
                        || Builtins.lengthOfByteString(encoded) > 8192
                        || !Builtins.equalsByteString(Builtins.blake2b_256(encoded), transfer.valueDigest())
                        || !preservesValue(input.resolved().value(), recipient.value())) valid = false;
            } else {
                boolean keyInput = switch (input.resolved().address().credential()) {
                    case Credential.PubKeyCredential key -> true;
                    default -> false;
                };
                if (!keyInput) valid = false;
            }
        }
        int outputPosition = 0;
        for (var output : tx.outputs()) {
            if (output.address().credential().equals(accountAddress.credential())) valid = false;
            if (BigInteger.valueOf(outputPosition) != index) {
                boolean keyOutput = switch (output.address().credential()) {
                    case Credential.PubKeyCredential key -> true;
                    default -> false;
                };
                boolean noDatum = switch (output.datum()) {
                    case OutputDatum.NoOutputDatum none -> true;
                    default -> false;
                };
                if (!keyOutput || !noDatum || output.referenceScript().isPresent() || !adaOnly(output.value()))
                    valid = false;
            }
            outputPosition = outputPosition + 1;
        }
        return valid && matches == 1;
    }

    static boolean adaOnly(Value value) {
        var entries = Builtins.unMapData(value);
        return !Builtins.nullList(entries) && Builtins.nullList(Builtins.tailList(entries))
                && Builtins.equalsData(Builtins.fstPair(Builtins.headList(entries)), Builtins.bData(new byte[]{}));
    }

    static boolean preservesValue(Value input, Value output) {
        var incoming = Builtins.unMapData(input);
        var outgoing = Builtins.unMapData(output);
        if (Builtins.nullList(incoming) || Builtins.nullList(outgoing)) return false;
        var empty = Builtins.bData(new byte[]{});
        if (!Builtins.equalsData(Builtins.fstPair(Builtins.headList(incoming)), empty)
                || !Builtins.equalsData(Builtins.fstPair(Builtins.headList(outgoing)), empty)) return false;
        BigInteger inputAda = ValuesLib.lovelaceOf(input);
        BigInteger outputAda = ValuesLib.lovelaceOf(output);
        return inputAda.signum() > 0 && outputAda.compareTo(inputAda) >= 0
                && Builtins.equalsData(Builtins.mapData(Builtins.tailList(incoming)), Builtins.mapData(Builtins.tailList(outgoing)));
    }
}
