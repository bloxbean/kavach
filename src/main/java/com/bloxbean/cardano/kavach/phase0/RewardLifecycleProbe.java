package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.TxCert;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;

/**
 * Disposable single-withdrawal lifecycle experiment, not an account checkpoint.
 * <p>Immutable parameter order: {@code authority, rewardSink, registrationDeposit}.</p>
 */
@MultiValidator
public class RewardLifecycleProbe {
    @Param
    static byte[] authority;
    @Param
    static Address rewardSink;
    @Param
    static BigInteger registrationDeposit;

    // Integer redeemer is the receipt output index; zero withdrawals/registration use -1.

    /**
     * Checks the purpose-specific registration or withdrawal branch and immutable reward sink.
     * This isolated fixture does not authorize a complete Kavach account operation.
     *
     * @param receiptIndex receipt output index, or -1 for registration and zero withdrawals
     * @param ctx          ledger-supplied context for this isolated feasibility probe
     * @return whether this probe accepts; malformed Data may instead raise a script error
     */
    @Entrypoint(purpose = Purpose.CERTIFY)
    public static boolean certify(BigInteger receiptIndex, ScriptContext ctx) {
        return validate(receiptIndex, ctx);
    }

    /**
     * Checks the purpose-specific registration or withdrawal branch and immutable reward sink.
     * This isolated fixture does not authorize a complete Kavach account operation.
     *
     * @param receiptIndex receipt output index, or -1 for registration and zero withdrawals
     * @param ctx          ledger-supplied context for this isolated feasibility probe
     * @return whether this probe accepts; malformed Data may instead raise a script error
     */
    @Entrypoint(purpose = Purpose.WITHDRAW)
    public static boolean reward(BigInteger receiptIndex, ScriptContext ctx) {
        return validate(receiptIndex, ctx);
    }

    static boolean validate(BigInteger receiptIndex, ScriptContext ctx) {
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.CertifyingScript certifying -> receiptIndex == BigInteger.valueOf(-1)
                    && registrationDeposit.signum() > 0 && switch (certifying.cert()) {
                case TxCert.RegStaking registration -> registration.deposit().isPresent()
                        && registration.deposit().get() == registrationDeposit
                        && switch (registration.credential()) {
                    case Credential.ScriptCredential script -> true;
                    default -> false;
                };
                default -> false;
            };
            case ScriptInfo.RewardingScript rewarding -> withdraw(receiptIndex, ctx, rewarding);
            default -> false;
        };
    }

    static boolean withdraw(BigInteger receiptIndex, ScriptContext ctx, ScriptInfo.RewardingScript rewarding) {
        var tx = ctx.txInfo();
        // Deliberately fail closed on composition until multi-receipt ABI is specified.
        if (tx.withdrawals().size() != 1 || !tx.withdrawals().containsKey(rewarding.credential())) return false;
        boolean signed = false;
        for (var signer : tx.signatories()) {
            if (Builtins.equalsByteString(signer.hash(), authority)) signed = true;
        }
        if (!signed) return false;
        var amount = tx.withdrawals().get(rewarding.credential());
        if (amount.signum() < 0) return false;
        if (amount == BigInteger.ZERO) return receiptIndex == BigInteger.valueOf(-1);
        if (receiptIndex.signum() < 0 || receiptIndex.compareTo(BigInteger.valueOf(tx.outputs().size())) >= 0)
            return false;
        var output = tx.outputs().get(receiptIndex.intValue());
        boolean keySink = switch (rewardSink.credential()) {
            case Credential.PubKeyCredential key -> true;
            default -> false;
        };
        boolean noDatum = switch (output.datum()) {
            case OutputDatum.NoOutputDatum none -> true;
            default -> false;
        };
        return keySink && output.address().equals(rewardSink) && noDatum && output.referenceScript().isEmpty()
                && ValuesLib.flattenTyped(output.value()).size() == 1
                && ValuesLib.assetOf(output.value(), new byte[]{}, new byte[]{}).compareTo(amount) >= 0;
    }
}
