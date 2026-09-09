package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.kavach.contracts.AccountLib;
import com.bloxbean.cardano.kavach.contracts.AccountTypes;

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
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;

import java.math.BigInteger;

/**
 * Full-map transfer semantics called only after immutable core identity, intent and module checks.
 */
@OnchainLibrary
public class WholeTransferLib {
    /**
     * Preserves every native token and prohibits account-funded fee or change.
     *
     * @param transfer       untrusted whole-UTxO transfer action
     * @param accountAddress authenticated full account enterprise address
     * @param receipts       validated reward receipts that must remain disjoint from account allocations
     * @param ctx            ledger-supplied script context
     * @return whether the action satisfies exact input, allocation and complete value rules; authorization is checked separately
     */
    public static boolean validate(TransferWholeUtxo transfer, Address accountAddress, JulcList<RewardReceipt> receipts, ScriptContext ctx) {
        boolean spending = switch (ctx.scriptInfo()) {
            case ScriptInfo.SpendingScript own -> true;
            default -> false;
        };
        BigInteger index = transfer.recipientIndex();
        var tx = ctx.txInfo();
        boolean keyRecipient = switch (transfer.recipientAddress().credential()) {
            case Credential.PubKeyCredential key -> true;
            default -> false;
        };
        if (AccountLib.receiptAt(receipts, index) || !spending || !keyRecipient || AccountLib.listSize((JulcList<PlutusData>) (Object) tx.inputs()) > 16 || AccountLib.listSize((JulcList<PlutusData>) (Object) tx.outputs()) > 16 || AccountLib.listSize((JulcList<PlutusData>) (Object) tx.referenceInputs()) > 4
                || AccountLib.assetCount(tx.mint()) != 0 || AccountLib.listSize((JulcList<PlutusData>) (Object) tx.certificates()) != 0 || tx.votes().size() != 0
                || AccountLib.listSize((JulcList<PlutusData>) (Object) tx.proposalProcedures()) != 0 || tx.currentTreasuryAmount().isPresent() || tx.treasuryDonation().isPresent()
                || AccountLib.lessThan(index, BigInteger.ZERO) || AccountLib.atMost(BigInteger.valueOf(AccountLib.listSize((JulcList<PlutusData>) (Object) tx.outputs())), index) || Builtins.lengthOfByteString(transfer.inputValueDigest()) != 32
                || !AccountLib.shape((PlutusData) (Object) transfer, 8, 4)) return false;
        var recipient = tx.outputs().get(index.intValue());
        boolean plain = switch (recipient.datum()) {
            case OutputDatum.NoOutputDatum none -> true;
            default -> false;
        };
        if (!plain || recipient.referenceScript().isPresent() || !recipient.address().equals(transfer.recipientAddress()))
            return false;
        boolean valid = true;
        int matches = 0;
        for (var input : tx.inputs()) {
            if (input.resolved().address().credential().equals(accountAddress.credential())) {
                matches = matches + 1;
                var encoded = Builtins.serialiseData((PlutusData) (Object) input.resolved().value());
                if (!input.outRef().equals(transfer.accountInput()) || !input.resolved().address().equals(accountAddress)
                        || Builtins.lengthOfByteString(encoded) > 8192
                        || !Builtins.equalsByteString(Builtins.blake2b_256(encoded), transfer.inputValueDigest())
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

    /**
     * Ledger Values are canonical; one empty policy means ADA only.
     */
    static boolean adaOnly(Value value) {
        var entries = Builtins.unMapData(value);
        return !Builtins.nullList(entries) && Builtins.nullList(Builtins.tailList(entries))
                && Builtins.equalsData(Builtins.fstPair(Builtins.headList(entries)), Builtins.bData(new byte[]{}));
    }

    /**
     * Compare native maps byte-for-byte while permitting only an ADA top-up.
     */
    static boolean preservesValue(Value input, Value output) {
        var incoming = Builtins.unMapData(input);
        var outgoing = Builtins.unMapData(output);
        if (Builtins.nullList(incoming) || Builtins.nullList(outgoing)) return false;
        var empty = Builtins.bData(new byte[]{});
        if (!Builtins.equalsData(Builtins.fstPair(Builtins.headList(incoming)), empty)
                || !Builtins.equalsData(Builtins.fstPair(Builtins.headList(outgoing)), empty)) return false;
        BigInteger inputAda = ValuesLib.lovelaceOf(input);
        BigInteger outputAda = ValuesLib.lovelaceOf(output);
        return AccountLib.lessThan(BigInteger.ZERO, inputAda) && AccountLib.atMost(inputAda, outputAda)
                && Builtins.equalsData(Builtins.mapData(Builtins.tailList(incoming)), Builtins.mapData(Builtins.tailList(outgoing)));
    }
}
