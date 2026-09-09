package com.bloxbean.cardano.kavach.sdk;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.ProofDomains;
import com.bloxbean.cardano.kavach.protocol.PolicyConfigCodec;
import com.bloxbean.cardano.kavach.protocol.PeriodicBudgetCodec;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import com.bloxbean.cardano.kavach.sdk.browser.BrowserSignatures;

import org.bouncycastle.math.ec.rfc8032.Ed25519;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** JVM verification for explicit raw, transaction-witness and bounded COSE module profiles. */
public final class BrowserAuthorization {
    private final int currentMode;
    private final int candidateMode;
    private final int network;
    private final Set<String> requiredSigners;

    /**
     * Selects verified deployment profiles and the exact planned required-signers set. Mode 0 is
     * raw Ed25519, 1 transaction witnesses, 2 bounded COSE, 3 mixed approval, 4 budget-aware mixed approval. This object checks preparation, not
     * transaction witnesses: the ledger must verify every required signer. Profile selection must
     * come from an independently authenticated deployment manifest.
     */
    public BrowserAuthorization(
            int currentMode, int candidateMode, int network, Set<String> requiredSigners) {
        require(
                currentMode >= 0 && currentMode <= 4 && candidateMode >= 0 && candidateMode <= 4,
                "Unsupported module profile");
        require(network == 0 || network == 1, "Network ID");
        require(
                requiredSigners != null
                        && requiredSigners.size() <= 16
                        && requiredSigners.stream()
                                .allMatch(value -> value.matches("[0-9a-f]{56}")),
                "Required signers");
        this.currentMode = currentMode;
        this.candidateMode = candidateMode;
        this.network = network;
        this.requiredSigners = Set.copyOf(requiredSigners);
    }

    /**
     * @return immutable exact planned payment-key hashes; not evidence that witnesses were
     *     collected
     */
    public Set<String> requiredSigners() {
        return requiredSigners;
    }

    /**
     * Validates browser spending proofs after the caller authenticates state and intent bindings.
     */
    public void verifySpend(AccountState state, Proof proof, byte[] digest) {
        require(currentMode < 3, "Mixed policy requires the complete signed action");
        verifySpend(state, proof, digest, null);
    }

    /** Verifies the amount-selected policy using the complete signed action. */
    public void verifySpend(AccountState state, Proof proof, byte[] digest, Action action) {
        WireFormat.validateState(AccountCodec.data(state));
        require(
                state.deploymentDomain().networkId().intValueExact() == network,
                "Profile network mismatch");
        require(proof.scheme().intValueExact() == currentMode, "Wrong operation proof mode");
        threshold(
                signatures(
                        proof.signatures(), state.authConfig(), digest, 8, currentMode),
                currentMode >= 3 ? AccountCodec.data(PolicyConfigCodec.spendPolicy(state.authConfig(), action)) : fields(state.authConfig()).get(2));
    }

    /**
     * Verifies every supplied proof, keeping old-role approval separate from destination
     * possession.
     */
    public void verify(
            AccountState old, ModuleRedeemer current, Optional<ModuleRedeemer> candidate) {
        require(
                old.deploymentDomain().networkId().intValueExact() == network,
                "Profile network mismatch");
        require(current.abiVersion().equals(BigInteger.ONE), "Unsupported current module ABI");
        var request = current.intent();
        var oldConfig = fields(PolicyConfigCodec.roles(old.authConfig()));
        var registry = registry(old.authConfig());
        if (request.action() instanceof CompleteRecovery complete) {
            require(
                    current.operationProof().isEmpty() && candidate.isEmpty(),
                    "Completion requires only target evidence");
            introduced(
                    old.authConfig(),
                    complete.replacementConfig(),
                    current.configPossession(),
                    WireFormat.digest(
                            ProofDomains.target(
                                    AccountCodec.data(old), AccountCodec.data(request))),
                    true);
            return;
        }
        int role =
                switch (request.action()) {
                    case ReplaceConfig ignored -> 3;
                    case ReplaceModule ignored -> 3;
                    case Freeze ignored -> 4;
                    case Unfreeze ignored -> 5;
                    case StartRecovery ignored -> 6;
                    case CancelRecovery ignored -> 7;
                    default ->
                            throw new IllegalArgumentException("Unsupported administration action");
                };
        require(current.operationProof().isPresent(), "Missing old-role approval");
        var proof = current.operationProof().orElseThrow();
        require(
                proof.scheme().equals(BigInteger.valueOf(currentMode)),
                "Unsupported signature scheme");
        var verified =
                signatures(
                        proof.signatures(),
                        old.authConfig(),
                        WireFormat.digest(AccountCodec.data(request)),
                        8,
                        currentMode);
        threshold(verified, oldConfig.get(role));
        if (request.action() instanceof ReplaceConfig change) {
            require(candidate.isEmpty(), "Configuration replacement has no candidate invocation");
            introduced(
                    old.authConfig(),
                    change.newConfig(),
                    current.configPossession(),
                    WireFormat.digest(
                            ProofDomains.configuration(
                                    AccountCodec.data(old), AccountCodec.data(request))),
                    false);
        } else {
            require(current.configPossession().isEmpty(), "Unexpected current possession evidence");
            if (request.action() instanceof ReplaceModule change) {
                require(candidate.isPresent(), "Missing candidate invocation");
                var next = candidate.orElseThrow();
                require(
                        next.abiVersion().equals(BigInteger.ONE)
                                && next.operationProof().isEmpty()
                                && AccountCodec.data(next.intent())
                                        .equals(AccountCodec.data(request))
                                && AccountCodec.list(next.receipts())
                                        .equals(AccountCodec.list(current.receipts())),
                        "Candidate binding or proof shape mismatch");
                var keys = registry(change.newConfig());
                var all =
                        signatures(
                                next.configPossession(),
                                change.newConfig(),
                                WireFormat.digest(
                                        ProofDomains.configuration(
                                                AccountCodec.data(old),
                                                AccountCodec.data(request))),
                                16,
                                candidateMode);
                require(all.size() == keys.size(), "Candidate must prove every key");
            } else require(candidate.isEmpty(), "Unexpected candidate invocation");
        }
    }

    /**
     * Retention compares public bytes, so reusing an old numeric ID never skips new-key possession.
     */
    private void introduced(
            PlutusData old,
            PlutusData target,
            JulcList<Signature> evidence,
            byte[] digest,
            boolean recovery) {
        var keys = registry(target);
        Set<BigInteger> proven =
                evidence.isEmpty() ? Set.of() : signatures(evidence, target, digest, 16, currentMode);
        var oldBytes = new HashSet<String>();
        for (var key : registry(old).values()) oldBytes.add(HexFormat.of().formatHex(key));
        var added = new HashSet<BigInteger>();
        for (var entry : keys.entrySet())
            if (!oldBytes.contains(HexFormat.of().formatHex(entry.getValue())))
                added.add(entry.getKey());
        if (currentMode >= 3) {
            require(proven.equals(keys.keySet()), "Mixed configuration requires all destination credentials");
            return;
        }
        require(proven.containsAll(added), "Missing newly introduced key possession");
        if (recovery) threshold(proven, fields(target).get(2));
        else require(proven.equals(added), "Extraneous configuration possession");
    }

    /**
     * Resolves ordered evidence only through the authenticated registry; no supplied public key is
     * trusted.
     */
    private Set<BigInteger> signatures(
            JulcList<Signature> proofs,
            PlutusData config,
            byte[] digest,
            int maximum,
            int mode) {
        WireFormat.validateConfig(config);
        require((mode == 4) == PeriodicBudgetCodec.isProfile(config), "Budget configuration/profile mismatch");
        require((mode >= 3) == PolicyConfigCodec.isPolicy(PeriodicBudgetCodec.authorization(config)), "Configuration/profile mismatch");
        var keys = registry(config);
        require(!proofs.isEmpty() && proofs.size() <= maximum, "Signature count");
        var verified = new HashSet<BigInteger>();
        BigInteger previous = BigInteger.valueOf(-1);
        for (var proof : proofs) {
            byte[] key = keys.get(proof.credentialId());
            require(
                    key != null && proof.credentialId().compareTo(previous) > 0,
                    "Unsorted, unknown or malformed signature");
            boolean valid =
                    switch (PolicyConfigCodec.method(config, proof.credentialId(), mode)) {
                        case 0 ->
                                proof.signature().length == 64
                                        && Ed25519.verify(
                                                proof.signature(),
                                                0,
                                                key,
                                                0,
                                                digest,
                                                0,
                                                digest.length);
                        case 1 ->
                                proof.signature().length == 0
                                        && requiredSigners.contains(
                                                HexFormat.of()
                                                        .formatHex(BrowserSignatures.keyHash(key)));
                        case 2 -> BrowserSignatures.verify(key, digest, proof.signature(), network);
                        default -> false;
                    };
            require(valid, "Invalid signature or missing required signer");
            verified.add(proof.credentialId());
            previous = proof.credentialId();
        }
        return verified;
    }

    private static void threshold(Set<BigInteger> verified, PlutusData policy) {
        var fields = fields(policy);
        long count =
                ((PlutusData.ListData) fields.get(1))
                        .items().stream()
                                .map(BrowserAuthorization::integer)
                                .filter(verified::contains)
                                .count();
        require(
                BigInteger.valueOf(count).compareTo(integer(fields.get(0))) >= 0,
                "Required role threshold not satisfied");
    }

    private static Map<BigInteger, byte[]> registry(PlutusData config) {
        var result = new TreeMap<BigInteger, byte[]>();
        for (var item : ((PlutusData.ListData) fields(PolicyConfigCodec.roles(config)).get(1)).items()) {
            var entry = fields(item);
            result.put(integer(entry.get(0)), ((PlutusData.BytesData) entry.get(1)).value());
        }
        return result;
    }

    private static List<PlutusData> fields(PlutusData data) {
        return ((PlutusData.ConstrData) data).fields();
    }

    private static BigInteger integer(PlutusData data) {
        return ((PlutusData.IntData) data).value();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
