package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.WithdrawValidator;

/** Encoder conformance probe only: does not authorize account operations. */
@WithdrawValidator
public class WireDigestProbe {
    public record DigestCase(PlutusData envelope, byte[] expectedDigest, byte[] publicKey, byte[] signature) {}
    /**
     * Compares the compiled serialiseData digest and verifies the supplied signature.
     * This isolated fixture does not authorize a complete Kavach account operation.
     * @param test untrusted probe-specific evidence and declared inputs
     * @param ctx ledger-supplied context for this isolated feasibility probe
     * @return whether this probe accepts; malformed Data may instead raise a script error
     */
    @Entrypoint public static boolean validate(DigestCase test, ScriptContext ctx) {
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.RewardingScript reward -> Builtins.equalsByteString(
                    Builtins.blake2b_256(Builtins.serialiseData(test.envelope())), test.expectedDigest())
                    && Builtins.verifyEd25519Signature(test.publicKey(), test.expectedDigest(), test.signature());
            default -> false;
        };
    }
}
