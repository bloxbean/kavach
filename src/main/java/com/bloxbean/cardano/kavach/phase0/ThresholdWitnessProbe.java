package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.WithdrawValidator;
import java.math.BigInteger;

/**
     * Bounded first-module cryptographic primitive experiment; core semantics are a separate obligation. 
 * <p>Immutable parameter order: {@code config}.</p>
 */
@WithdrawValidator
public class ThresholdWitnessProbe {
    public record Key(byte[] publicKey) {}
    public record Config(BigInteger threshold, JulcList<Key> keys) {}
    public record Evidence(BigInteger keyIndex, byte[] signature) {}
    public record Witness(PlutusData envelope, JulcList<Evidence> proofs) {}
    @Param static Config config;

    /**
     * Checks raw Ed25519 threshold evidence over the supplied envelope.
     * This isolated fixture does not authorize a complete Kavach account operation.
     * @param witness untrusted probe-specific evidence and declared inputs
     * @param ctx ledger-supplied context for this isolated feasibility probe
     * @return whether this probe accepts; malformed Data may instead raise a script error
     */
    @Entrypoint public static boolean validate(Witness witness, ScriptContext ctx) {
        boolean rewarding = switch (ctx.scriptInfo()) {
            case ScriptInfo.RewardingScript reward -> true;
            default -> false;
        };
        BigInteger threshold = config.threshold();
        BigInteger keyCount = BigInteger.valueOf(config.keys().size());
        BigInteger proofCount = BigInteger.valueOf(witness.proofs().size());
        if (!rewarding || config.keys().size() < 1 || config.keys().size() > 8 || threshold.signum() <= 0
                || threshold.compareTo(keyCount) > 0
                || witness.proofs().size() > 8 || proofCount.compareTo(threshold) < 0) return false;
        boolean valid = true;
        int i = 0;
        for (Key key : config.keys()) {
            if (Builtins.lengthOfByteString(key.publicKey()) != 32) valid = false;
            int j = 0;
            for (Key other : config.keys()) {
                if (j < i && Builtins.equalsByteString(key.publicKey(), other.publicKey())) valid = false;
                j = j + 1;
            }
            i = i + 1;
        }
        var canonical = new Witness(witness.envelope(), witness.proofs());
        if (!valid || !Builtins.equalsData((PlutusData) (Object) witness, (PlutusData) (Object) canonical)) return false;
        var serialized = Builtins.serialiseData(witness.envelope());
        if (Builtins.lengthOfByteString(serialized) > 8192) return false;
        var digest = Builtins.blake2b_256(serialized);
        BigInteger previous = BigInteger.valueOf(-1);
        for (Evidence proof : witness.proofs()) {
            BigInteger keyIndex = proof.keyIndex();
            var canonicalProof = new Evidence(proof.keyIndex(), proof.signature());
            if (!Builtins.equalsData((PlutusData) (Object) proof, (PlutusData) (Object) canonicalProof)
                    || keyIndex.compareTo(previous) <= 0 || keyIndex.signum() < 0
                    || keyIndex.compareTo(keyCount) >= 0) {
                valid = false;
            } else {
                var key = config.keys().get(keyIndex.intValue());
                if (!Builtins.verifyEd25519Signature(key.publicKey(), digest, proof.signature())) valid = false;
            }
            previous = proof.keyIndex();
        }
        return valid;
    }
}
