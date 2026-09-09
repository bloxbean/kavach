package com.bloxbean.cardano.kavach.auth.ed25519;

import com.bloxbean.cardano.kavach.contracts.AccountLib;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.kavach.contracts.AccountTypes;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.Signature;

import java.math.BigInteger;

/**
 * Bounded raw-Ed25519 registry and role policies; no key may be supplied by signature evidence.
 */
@OnchainLibrary
public class Ed25519Lib {
    /**
     * Canonical unique credential ID mapped to one public key.
     *
     * @param credentialId unsigned credential ID resolved through the authenticated registry
     * @param publicKey    32-byte raw Ed25519 public key; possession is checked separately
     */
    public record KeyEntry(BigInteger credentialId, byte[] publicKey) {
    }

    /**
     * Ordered member IDs with a nonzero achievable threshold.
     *
     * @param threshold     positive required number of distinct authorized credentials
     * @param credentialIds strictly ordered unique registry IDs belonging to this role
     */
    public record ThresholdPolicy(BigInteger threshold, JulcList<BigInteger> credentialIds) {
    }

    /**
     * Full first-module configuration, in normative CDDL field order.
     *
     * @param schemaVersion wire schema version, currently 1
     * @param keys          strictly ID-ordered registry with unique public keys
     * @param spend         ordinary transfer threshold policy
     * @param admin         configuration and module administration policy
     * @param freeze        freeze authorization policy
     * @param unfreeze      unfreeze policy independent of recovery authority
     * @param recovery      recovery initiation threshold policy
     * @param cancel        cancellation policy independent of recovery authority
     */
    public record Ed25519Config(BigInteger schemaVersion, JulcList<KeyEntry> keys, ThresholdPolicy spend,
                                ThresholdPolicy admin, ThresholdPolicy freeze, ThresholdPolicy unfreeze,
                                ThresholdPolicy recovery, ThresholdPolicy cancel) {
    }

    /**
     * Validates canonical schema, unique keys and mandatory defensive role independence.
     *
     * @param config first-module configuration from authenticated state or a proposed genesis
     * @return whether the full canonical configuration and role independence checks succeed; possession is separate
     */
    public static boolean configuration(Ed25519Config config) {
        if (!config.schemaVersion().equals(BigInteger.ONE) || AccountLib.listSize((JulcList<PlutusData>) (Object) config.keys()) < 3 || AccountLib.listSize((JulcList<PlutusData>) (Object) config.keys()) > 16
                || Builtins.lengthOfByteString(Builtins.serialiseData((PlutusData) (Object) config)) > 1024
                || !AccountLib.shape((PlutusData) (Object) config, 0, 8)) return false;
        boolean valid = true;
        BigInteger previous = BigInteger.valueOf(-1);
        for (var key : config.keys()) {
            if (AccountLib.atMost(key.credentialId(), previous) || AccountLib.lessThan(BigInteger.valueOf(15), key.credentialId())
                    || Builtins.lengthOfByteString(key.publicKey()) != 32
                    || !AccountLib.shape((PlutusData) (Object) key, 0, 2)) valid = false;
            previous = key.credentialId();
        }
        return valid && uniqueKeys(config.keys()) && policy(config.spend(), config.keys()) && policy(config.admin(), config.keys())
                && policy(config.freeze(), config.keys()) && policy(config.unfreeze(), config.keys())
                && policy(config.recovery(), config.keys()) && policy(config.cancel(), config.keys())
                && overlap(config.recovery(), config.unfreeze()) == 0 && overlap(config.recovery(), config.cancel()) == 0
                && overlap(config.spend(), config.unfreeze()) == 0 && overlap(config.spend(), config.cancel()) == 0
                && AccountLib.lessThan(BigInteger.valueOf(overlap(config.spend(), config.admin())), config.admin().threshold());
    }

    /**
     * Checks the transfer-facing schema and threshold on authenticated immutable state.
     * Full registry uniqueness, all defensive roles and usable keys are established by
     * genesis possession (and must be re-established by every future installation or
     * configuration transition). Rechecking unrelated role relationships on every
     * transfer is unnecessary; Phase 1 state cannot be mutated at all.
     *
     * @param config first-module configuration from authenticated state or a proposed genesis
     * @return whether the transfer-facing configuration checks succeed; genesis validity is a prerequisite
     */
    public static boolean spendConfiguration(Ed25519Config config) {
        var spend = config.spend();
        if (!config.schemaVersion().equals(BigInteger.ONE) || AccountLib.listSize((JulcList<PlutusData>) (Object) config.keys()) < 3 || AccountLib.listSize((JulcList<PlutusData>) (Object) config.keys()) > 16
                || spend.credentialIds().isEmpty() || AccountLib.listSize((JulcList<PlutusData>) (Object) spend.credentialIds()) > 8 || AccountLib.atMost(spend.threshold(), BigInteger.ZERO)
                || AccountLib.lessThan(BigInteger.valueOf(AccountLib.listSize((JulcList<PlutusData>) (Object) spend.credentialIds())), spend.threshold())
                || !AccountLib.shape((PlutusData) (Object) spend, 0, 2)
                || !AccountLib.shape((PlutusData) (Object) config, 0, 8)) return false;
        BigInteger previous = BigInteger.valueOf(-1);
        boolean valid = true;
        for (BigInteger id : spend.credentialIds()) {
            if (AccountLib.atMost(id, previous) || AccountLib.lessThan(BigInteger.valueOf(15), id)) valid = false;
            previous = id;
        }
        BigInteger previousKey = BigInteger.valueOf(-1);
        for (var key : config.keys()) {
            if (AccountLib.atMost(key.credentialId(), previousKey) || AccountLib.lessThan(BigInteger.valueOf(15), key.credentialId())
                    || Builtins.lengthOfByteString(key.publicKey()) != 32
                    || !AccountLib.shape((PlutusData) (Object) key, 0, 2)) valid = false;
            previousKey = key.credentialId();
        }
        return valid;
    }

    /**
     * Rejects unknown, duplicate or unsorted policy members and malformed threshold records.
     */
    static boolean policy(ThresholdPolicy policy, JulcList<KeyEntry> registry) {
        if (policy.credentialIds().isEmpty() || AccountLib.listSize((JulcList<PlutusData>) (Object) policy.credentialIds()) > 8 || AccountLib.atMost(policy.threshold(), BigInteger.ZERO)
                || AccountLib.lessThan(BigInteger.valueOf(AccountLib.listSize((JulcList<PlutusData>) (Object) policy.credentialIds())), policy.threshold())
                || !AccountLib.shape((PlutusData) (Object) policy, 0, 2)) return false;
        return knownMembers(policy.credentialIds(), registry);
    }

    /**
     * Linear ordered subset check against a strictly increasing registry. Matching consumes
     * both heads, so unknown, duplicate or descending member IDs reject without a second pass.
     */
    static boolean knownMembers(JulcList<BigInteger> members, JulcList<KeyEntry> registry) {
        if (members.isEmpty()) return true;
        if (registry.isEmpty()) return false;
        int order = members.head().compareTo(registry.head().credentialId());
        if (order < 0) return false;
        if (order > 0) return knownMembers(members, registry.tail());
        return knownMembers(members.tail(), registry.tail());
    }

    /**
     * Checks each public-key pair exactly once; distinct IDs cannot alias one signing key.
     */
    static boolean uniqueKeys(JulcList<KeyEntry> registry) {
        if (registry.isEmpty()) return true;
        return !publicKeyKnown(registry.head().publicKey(), registry.tail()) && uniqueKeys(registry.tail());
    }

    /**
     * Looks up raw public-key identity, independently of credential IDs.
     *
     * @param selected exact public-key bytes
     * @param registry validated registry to inspect
     * @return whether those public bytes already appear in the registry
     */
    public static boolean publicKeyKnown(byte[] selected, JulcList<KeyEntry> registry) {
        if (registry.isEmpty()) return false;
        return Builtins.equalsByteString(selected, registry.head().publicKey()) || publicKeyKnown(selected, registry.tail());
    }

    /**
     * Counts overlapping canonical role IDs through a linear sorted intersection.
     */
    static int overlap(ThresholdPolicy a, ThresholdPolicy b) {
        return intersect(a.credentialIds(), b.credentialIds());
    }

    /**
     * Both role lists must first pass policy ordering and registry membership validation.
     */
    static int intersect(JulcList<BigInteger> left, JulcList<BigInteger> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        int order = left.head().compareTo(right.head());
        if (order < 0) return intersect(left.tail(), right);
        if (order > 0) return intersect(left, right.tail());
        return 1 + intersect(left.tail(), right.tail());
    }

    /**
     * Verifies every supplied signature and its canonical registry ID, with no ignored evidence.
     * Genesis callers require one proof per registry key; transfers separately require the spend threshold.
     *
     * @param evidence credential-ID-ordered signature evidence
     * @param registry authenticated registry ordered by unique credential ID
     * @param message  canonical 32-byte digest under the relevant signature domain
     * @param maximum  maximum evidence count for the current operation
     * @return whether every supplied proof verifies; role threshold satisfaction is checked separately
     */
    public static boolean signatures(JulcList<Signature> evidence, JulcList<KeyEntry> registry, byte[] message, int maximum) {
        if (evidence.isEmpty() || AccountLib.listSize((JulcList<PlutusData>) (Object) evidence) > maximum) return false;
        return verifySorted(evidence, registry, message);
    }

    /**
     * Validates evidence while merging with the authenticated strictly ordered registry.
     * Advancing both lists on a match makes duplicate/descending proof IDs fail at the
     * next comparison; no separate ordering pass or ignored signature is needed.
     */
    static boolean verifySorted(JulcList<Signature> evidence, JulcList<KeyEntry> registry, byte[] message) {
        if (evidence.isEmpty()) return true;
        if (registry.isEmpty()) return false;
        var proof = evidence.head();
        var key = registry.head();
        int order = proof.credentialId().compareTo(key.credentialId());
        if (order < 0) return false;
        if (order > 0) return verifySorted(evidence, registry.tail(), message);
        return Builtins.lengthOfByteString(proof.signature()) == 64
                && AccountLib.shape((PlutusData) (Object) proof, 0, 2)
                && Builtins.verifyEd25519Signature(key.publicKey(), message, proof.signature())
                && verifySorted(evidence.tail(), registry.tail(), message);
    }

    /**
     * Only verified evidence from the selected role contributes to its threshold.
     *
     * @param evidence credential-ID-ordered signature evidence
     * @param policy   validated threshold policy for the selected role
     * @return whether enough already verified evidence belongs to the selected role; performs no cryptography
     */
    public static boolean threshold(JulcList<Signature> evidence, ThresholdPolicy policy) {
        return AccountLib.atMost(policy.threshold(), BigInteger.valueOf(countMembers(evidence, policy.credentialIds())));
    }

    /**
     * Linear intersection of two strictly ordered, duplicate-free credential lists.
     */
    static int countMembers(JulcList<Signature> evidence, JulcList<BigInteger> members) {
        if (evidence.isEmpty() || members.isEmpty()) return 0;
        int order = evidence.head().credentialId().compareTo(members.head());
        if (order < 0) return countMembers(evidence.tail(), members);
        if (order > 0) return countMembers(evidence, members.tail());
        return 1 + countMembers(evidence.tail(), members.tail());
    }
}
