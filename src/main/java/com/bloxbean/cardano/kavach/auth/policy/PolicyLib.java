package com.bloxbean.cardano.kavach.auth.policy;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.kavach.auth.browser.BrowserEvidenceLib;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Lib;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Lib.*;
import com.bloxbean.cardano.kavach.contracts.AccountLib;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import java.math.BigInteger;

/** Bounded amount policies and immutable-per-configuration credential evidence methods. */
@OnchainLibrary
public class PolicyLib {
    /**
     * Canonical five-field module configuration (constructor 0).
     * @param schemaVersion exactly one
     * @param roles existing unique key registry and six defensive role policies; spend is strong
     * @param coseIds ordered registered IDs requiring COSE; all others require transaction witnesses
     * @param smallPaymentLimit inclusive per-transaction lovelace allocation plus account fee bound
     * @param smallSpend small-payment threshold, subordinate to the strong spend policy
     */
    public record PolicyConfig(BigInteger schemaVersion, Ed25519Config roles,
            JulcList<BigInteger> coseIds, BigInteger smallPaymentLimit, ThresholdPolicy smallSpend) {}

    /** Validates all bounds, role independence and the implication strong approval implies small approval. */
    public static boolean configuration(PolicyConfig config) {
        if (!AccountLib.shape((PlutusData)(Object)config, 0, 5)
                || !config.schemaVersion().equals(BigInteger.ONE)
                || Builtins.lengthOfByteString(Builtins.serialiseData((PlutusData)(Object)config)) > 1024
                || !AccountLib.uint63(config.smallPaymentLimit())
                || !Ed25519Lib.configuration(config.roles())) return false;
        var roles = config.roles();
        var low = config.smallSpend();
        // A strict subset inherits registry membership and defensive separation from strong spend.
        if (!AccountLib.shape((PlutusData)(Object)low, 0, 2) || low.credentialIds().isEmpty()
                || AccountLib.lessThan(low.threshold(), BigInteger.ONE)
                || AccountLib.lessThan(BigInteger.valueOf(AccountLib.listSize((JulcList<PlutusData>)(Object)low.credentialIds())), low.threshold())
                || !subset(config.smallSpend().credentialIds(), roles.spend().credentialIds())) return false;
        long extra = AccountLib.listSize((JulcList<PlutusData>)(Object)roles.spend().credentialIds())
                - AccountLib.listSize((JulcList<PlutusData>)(Object)config.smallSpend().credentialIds());
        if (AccountLib.lessThan(roles.spend().threshold().subtract(BigInteger.valueOf(extra)), config.smallSpend().threshold())) return false;
        BigInteger previous = BigInteger.valueOf(-1); boolean valid = true;
        for (var id : config.coseIds()) {
            if (AccountLib.atMost(id, previous) || !known(id, roles.keys())) valid = false;
            previous = id;
        }
        return valid;
    }

    /** Ordered subset check; both policies have already passed uniqueness validation. */
    static boolean subset(JulcList<BigInteger> small, JulcList<BigInteger> large) {
        if (small.isEmpty()) return true;
        if (large.isEmpty()) return false;
        int order = small.head().compareTo(large.head());
        return order == 0 ? subset(small.tail(), large.tail())
                : order > 0 && subset(small, large.tail());
    }

    /** Resolves a configured ID without accepting an evidence-supplied public key. */
    static boolean known(BigInteger id, JulcList<KeyEntry> registry) {
        if (registry.isEmpty()) return false;
        return id.equals(registry.head().credentialId()) || known(id, registry.tail());
    }

    /** Selects the small policy only for explicitly bounded ADA allocations; whole transfers use strong. */
    public static ThresholdPolicy spendPolicy(PolicyConfig config, Action action) {
        return switch (action) {
            case Spend spend -> {
                BigInteger debit = spend.maxAccountFee(); boolean adaOnly = AccountLib.atMost(BigInteger.ZERO, debit);
                for (var recipient : spend.recipients()) {
                    for (var asset : recipient.value()) {
                        if (Builtins.lengthOfByteString(asset.policy()) != 0 || Builtins.lengthOfByteString(asset.name()) != 0
                                || AccountLib.atMost(asset.quantity(), BigInteger.ZERO)) adaOnly = false;
                        debit = debit.add(asset.quantity());
                    }
                }
                yield adaOnly && AccountLib.atMost(debit, config.smallPaymentLimit()) ? config.smallSpend() : config.roles().spend();
            }
            default -> config.roles().spend();
        };
    }

    /** Verifies every distinct ordered proof in the method fixed by authenticated configuration. */
    public static boolean signatures(JulcList<Signature> evidence, PolicyConfig config, byte[] digest,
            int maximum, BigInteger network, ScriptContext ctx) {
        return !evidence.isEmpty() && AccountLib.listSize((JulcList<PlutusData>)(Object)evidence) <= maximum
                && verifySorted(evidence, config.roles().keys(), config.coseIds(), digest, network, ctx);
    }

    /** Ordered merge prevents duplicate credentials, unknown keys and mixed-mode double counting. */
    static boolean verifySorted(JulcList<Signature> evidence, JulcList<KeyEntry> registry,
            JulcList<BigInteger> coseIds, byte[] digest, BigInteger network, ScriptContext ctx) {
        if (evidence.isEmpty()) return true;
        if (registry.isEmpty()) return false;
        var proof = evidence.head(); var key = registry.head();
        int order = proof.credentialId().compareTo(key.credentialId());
        if (order < 0) return false;
        if (order > 0) return verifySorted(evidence, registry.tail(), coseIds, digest, network, ctx);
        if (!AccountLib.shape((PlutusData)(Object)proof, 0, 2)) return false;
        boolean valid = coseIds.contains(key.credentialId())
                ? BrowserEvidenceLib.cose(key.publicKey(), digest, proof.signature(), network)
                : Builtins.lengthOfByteString(proof.signature()) == 0
                    && ctx.txInfo().signatories().contains(new PubKeyHash(Builtins.blake2b_224(key.publicKey())));
        return valid && verifySorted(evidence.tail(), registry.tail(), coseIds, digest, network, ctx);
    }
}
