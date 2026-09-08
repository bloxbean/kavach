package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.WithdrawValidator;

/**
 * Disposable Phase 0 experiment, NOT an account validator or production ABI.
 * A dedicated Ed25519 key approves a domain-bound, consumed-input challenge.
 * No assets should be sent to this script's payment address.
 *
 * <p>Immutable parameter order: {@code publicKey, deploymentDomain}.</p>
 */
@WithdrawValidator
public class WithdrawalProbe {
    @Param static byte[] publicKey;
    @Param static byte[] deploymentDomain;

    public record Challenge(byte[] domain, TxOutRef consumedInput) {}
    public record Authorization(Challenge challenge, byte[] signature) {}

    /**
     * Checks the parameterized key signature and consumed-input replay resource.
     * This isolated fixture does not authorize a complete Kavach account operation.
     * @param authorization untrusted probe-specific evidence and declared inputs
     * @param ctx ledger-supplied context for this isolated feasibility probe
     * @return whether this probe accepts; malformed Data may instead raise a script error
     */
    @Entrypoint
    public static boolean validate(Authorization authorization, ScriptContext ctx) {
        boolean withdrawalPresent = switch (ctx.scriptInfo()) {
            case ScriptInfo.RewardingScript rewarding -> ctx.txInfo().withdrawals().containsKey(rewarding.credential());
            default -> false;
        };
        if (!withdrawalPresent) {
            return false;
        }
        var challenge = authorization.challenge();
        if (!Builtins.equalsByteString(challenge.domain(), deploymentDomain)) {
            return false;
        }
        // Typed projection alone does not enforce constructor tags or field counts.
        // Reconstruct both protocol records and compare their complete Data encodings.
        var canonical = new Authorization(new Challenge(challenge.domain(), challenge.consumedInput()),
                authorization.signature());
        if (!Builtins.equalsData((PlutusData) (Object) authorization, (PlutusData) (Object) canonical)) {
            return false;
        }
        boolean consumed = false;
        for (var input : ctx.txInfo().inputs()) {
            if (input.outRef().equals(challenge.consumedInput())) {
                consumed = true;
            }
        }
        if (!consumed) {
            return false;
        }
        return Builtins.verifyEd25519Signature(publicKey,
                Builtins.blake2b_256(Builtins.serialiseData((PlutusData) (Object) challenge)),
                authorization.signature());
    }
}
