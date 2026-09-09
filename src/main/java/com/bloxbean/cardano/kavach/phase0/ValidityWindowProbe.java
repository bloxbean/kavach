package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.IntervalBoundType;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.WithdrawValidator;

import java.math.BigInteger;

/**
 * Time-containment/deadline feasibility component; deadline authority is supplied by the caller in this probe.
 * <p>Immutable parameter order: {@code authority}.</p>
 */
@WithdrawValidator
public class ValidityWindowProbe {
    @Param
    static byte[] authority;

    public record Window(BigInteger signedLower, BigInteger signedUpper, BigInteger deadline) {
    }

    /**
     * Checks authority-signed interval containment and a caller-supplied deadline.
     * This isolated fixture does not authorize a complete Kavach account operation.
     *
     * @param window untrusted probe-specific evidence and declared inputs
     * @param ctx    ledger-supplied context for this isolated feasibility probe
     * @return whether this probe accepts; malformed Data may instead raise a script error
     */
    @Entrypoint
    public static boolean validate(Window window, ScriptContext ctx) {
        boolean rewarding = switch (ctx.scriptInfo()) {
            case ScriptInfo.RewardingScript reward -> true;
            default -> false;
        };
        boolean signed = false;
        for (var signer : ctx.txInfo().signatories())
            if (Builtins.equalsByteString(signer.hash(), authority)) signed = true;
        BigInteger signedLower = window.signedLower();
        BigInteger signedUpper = window.signedUpper();
        BigInteger deadline = window.deadline();
        if (!rewarding || !signed || signedLower.signum() < 0 || signedUpper.compareTo(BigInteger.valueOf(9223372036854775807L)) > 0
                || deadline.signum() < 0 || deadline.compareTo(BigInteger.valueOf(9223372036854775807L)) > 0
                || signedUpper.compareTo(signedLower) <= 0 || signedUpper.subtract(signedLower).compareTo(BigInteger.valueOf(300000)) > 0
                || !Builtins.equalsData((PlutusData) (Object) window, (PlutusData) (Object) new Window(signedLower, signedUpper, deadline)))
            return false;
        var range = ctx.txInfo().validRange();
        BigInteger lower = switch (range.from().boundType()) {
            case IntervalBoundType.Finite finite -> finite.time();
            default -> BigInteger.valueOf(-1);
        };
        BigInteger upper = switch (range.to().boundType()) {
            case IntervalBoundType.Finite finite -> finite.time();
            default -> BigInteger.valueOf(-1);
        };
        return lower.compareTo(signedLower) >= 0 && lower.compareTo(deadline) >= 0 && lower.compareTo(upper) < 0
                && (upper.compareTo(signedUpper) < 0 || (upper == signedUpper && !range.to().isInclusive()));
    }
}
