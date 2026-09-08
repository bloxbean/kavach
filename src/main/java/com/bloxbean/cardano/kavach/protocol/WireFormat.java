package com.bloxbean.cardano.kavach.protocol;

import com.bloxbean.cardano.julc.core.PlutusData;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Strict candidate-profile decoder/renderer. Off-chain specification support, not a validator. */
public final class WireFormat {
    public static final int MAX_INTENT_BYTES = 1536;
    public static final int MAX_STATE_BYTES = 3072;
    public static final int MAX_NORMAL_STATE_BYTES = 1536;
    public static final int MAX_CONFIG_BYTES = 1024;
    private static final byte[] TAG = "KAVACH_INTENT\u0001".getBytes(StandardCharsets.UTF_8);
    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private byte[] sourceAssetHash;
    private PlutusData resolvedInputValue;
    private final StringBuilder display = new StringBuilder();
    /**
     * Returns a fresh copy of the exact protocol tag; callers cannot mutate the shared constant.
     * @return protocol name and version byte
     */
    public static byte[] protocolTag() { return TAG.clone(); }
    /**
     * Validates and renders an intent that does not require a resolved whole-input Value.
     * @param data complete canonical intent Data
     * @return deterministic field-by-field signing text including its digest
     * @throws IllegalArgumentException for malformed or unsupported intent data, including a whole transfer without its Value
     */
    public static String renderIntent(PlutusData data) {
        return renderIntent(data, null);
    }
    /**
     * Strictly validates and deterministically renders the complete signed intent.
     * This checks representation and declared semantics, not NFT ownership or actual ledger inputs.
     * @param data complete canonical intent Data
     * @param resolvedInputValue authenticated complete input Value for a whole transfer, otherwise null
     * @return deterministic signing text with raw addresses, quantities and digest
     * @throws IllegalArgumentException if the intent or required resolved Value violates the profile
     */
    public static String renderIntent(PlutusData data, PlutusData resolvedInputValue) {
        var decoder = new WireFormat();
        decoder.resolvedInputValue = resolvedInputValue;
        guard(data);
        require(Builtins.serialiseData(data).length <= MAX_INTENT_BYTES, "intent size");
        var envelope = record(data, 0, 4);
        require(Arrays.equals(bytes(envelope.get(0), 14), TAG), "protocol tag");
        decoder.line("protocol", "KAVACH_INTENT_V1");
        decoder.domain(envelope.get(1), "domain");
        decoder.validity(envelope.get(2));
        decoder.action(envelope.get(3));
        decoder.line("digest.blake2b256", HexFormat.of().formatHex(digest(data)));
        return decoder.display.toString();
    }
    /**
     * Renders an intent with matching current state, including all policy and recovery fields.
     * The caller must independently authenticate the state UTxO's complete NFT identity,
     * quantity and custody address against the ledger before displaying or signing it.
     * @param intent complete untrusted intent Data
     * @param state authenticated resolved state datum
     * @param stateRef canonical reference of that authenticated state UTxO
     * @param inputValue authenticated whole-transfer input Value, otherwise null
     * @return deterministic intent and state display, with digests for cross-checking
     * @throws IllegalArgumentException if the intent, state or supplied binding is invalid
     */
    public static String renderSigningRequest(PlutusData intent, PlutusData state, PlutusData stateRef, PlutusData inputValue) {
        validateState(state);
        String envelopeDisplay = renderIntent(intent, inputValue);
        var domain = record(record(intent, 0, 4).get(1), 0, 6);
        var current = record(state, 0, 12);
        require(domain.get(1).equals(current.get(2)) && domain.get(2).equals(current.get(1))
                && domain.get(3).equals(current.get(3)) && domain.get(4).equals(current.get(4))
                && domain.get(5).equals(stateRef), "resolved state/domain mismatch");
        var action = constructor(record(intent, 0, 4).get(3));
        if (action.tag() == 2) require(!record(action.fields().get(0), 0, 2).get(0)
                .equals(record(current.get(5), 0, 2).get(0)), "same module hash; use ReplaceConfig");
        return envelopeDisplay + renderState(state);
    }
    /**
     * Validates and renders all current state fields, including roles and pending recovery data.
     * @param state untrusted complete state Data
     * @return deterministic state display including its digest
     * @throws IllegalArgumentException if state shape or first-module configuration is invalid
     */
    public static String renderState(PlutusData state) {
        validateState(state);
        var decoder = new WireFormat(); var f = record(state, 0, 12);
        decoder.line("state.schemaVersion", "1");
        decoder.account(f.get(1), "state.account"); decoder.deployment(f.get(2), "state.deployment");
        decoder.core(f.get(3), "state.core"); decoder.number(f.get(4), "state.version", MAX);
        decoder.module(f.get(5), "state.module"); decoder.config(f.get(6), "state.config");
        decoder.number(f.get(7), "state.recoverySequence", MAX);
        decoder.number(f.get(8), "state.recoveryDelayMs", MAX);
        decoder.number(f.get(9), "state.recoveryCooldownMs", MAX);
        decoder.number(f.get(10), "state.recoveryNotBefore", MAX);
        var mode = constructor(f.get(11));
        decoder.line("state.mode", mode.tag() == 0 ? "Normal" : mode.tag() == 1 ? "Frozen" : "RecoveryPending");
        if (mode.tag() == 2) {
            decoder.hex(mode.fields().get(0), "state.pending.proposalCommitment", 32);
            decoder.number(mode.fields().get(1), "state.pending.executeAfter", MAX);
            decoder.config(mode.fields().get(2), "state.pending.targetConfig");
        }
        decoder.line("state.digest.blake2b256", HexFormat.of().formatHex(digest(state)));
        return decoder.display.toString();
    }
    /**
     * Checks state structure, bounds, lifecycle data and first-module configuration.
     * A valid datum alone does not establish an account: the full NFT and custody address
     * must be authenticated against the ledger separately.
     * @param data untrusted state datum
     * @throws IllegalArgumentException if any supported wire-profile invariant fails
     */
    public static void validateState(PlutusData data) {
        guard(data);
        require(Builtins.serialiseData(data).length <= MAX_STATE_BYTES, "state size");
        var decoder = new WireFormat(); var f = record(data, 0, 12);
        exact(f.get(0), 1); decoder.account(f.get(1), "account"); decoder.deployment(f.get(2), "deployment"); decoder.core(f.get(3), "core");
        uint(f.get(4), MAX); decoder.module(f.get(5), "module");
        require(!record(f.get(5), 0, 2).get(0).equals(record(f.get(3), 0, 3).get(2)), "module/core reward credential collision");
        decoder.config(f.get(6), "config");
        var sequence = uint(f.get(7), MAX);
        require(sequence.compareTo(uint(f.get(4), MAX)) <= 0, "sequence exceeds state version");
        range(f.get(8), RecoveryModel.MIN_DELAY, RecoveryModel.MAX_DELAY);
        range(f.get(9), RecoveryModel.MIN_COOLDOWN, RecoveryModel.MAX_COOLDOWN);
        uint(f.get(10), MAX);
        var mode = constructor(f.get(11));
        if (mode.tag() == 0 || mode.tag() == 1) { record(mode, mode.tag(), 0);
            require(Builtins.serialiseData(data).length <= MAX_NORMAL_STATE_BYTES, "normal/frozen state size"); }
        else { var pending = record(mode, 2, 3); bytes(pending.get(0), 32); uint(pending.get(1), MAX);
            decoder.config(pending.get(2), "pending.targetConfig");
            require(sequence.signum() > 0, "pending without recovery sequence"); }
    }
    /**
     * Checks the first Ed25519 module configuration, key validity and defensive role independence.
     * This does not prove possession of any private key.
     * @param data complete untrusted configuration
     * @throws IllegalArgumentException if its schema, keys or policies are invalid
     */
    public static void validateConfig(PlutusData data) { new WireFormat().config(data, "config"); }
    /**
     * Hashes the bounded Data encoding without treating it as a validated protocol object.
     * @param data Data to encode using the pinned serialiseData implementation
     * @return 32-byte Blake2b digest
     * @throws IllegalArgumentException if the generic Data complexity guard fails
     */
    public static byte[] digest(PlutusData data) {
        guard(data);
        return hashEncoded(Builtins.serialiseData(data));
    }
    private static byte[] hashEncoded(byte[] encoded) {
        var hash = new Blake2bDigest(256); hash.update(encoded, 0, encoded.length);
        byte[] result = new byte[32]; hash.doFinal(result, 0); return result;
    }
    /**
     * Validates the whole-transfer ledger Value profile before hashing its complete encoding.
     * @param value complete canonical ledger Value Data, not a flattened asset list
     * @return 32-byte Blake2b digest
     * @throws IllegalArgumentException if the Value is not canonical or violates the whole-transfer bounds
     */
    public static byte[] ledgerValueDigest(PlutusData value) {
        new WireFormat().resolvedValue(value);
        return hashEncoded(Builtins.serialiseData(value));
    }
    private void domain(PlutusData data, String path) {
        var f = record(data, 0, 6); exact(f.get(0), 1); line(path + ".protocolVersion", "1");
        deployment(f.get(1), path + ".deployment"); account(f.get(2), path + ".account"); core(f.get(3), path + ".core");
        number(f.get(4), path + ".stateVersion", MAX); input(f.get(5), path + ".stateRef");
    }
    private void deployment(PlutusData data, String path) {
        var f = record(data, 0, 3); number(f.get(0), path + ".networkId", BigInteger.ONE);
        number(f.get(1), path + ".networkMagic", new BigInteger("4294967295")); hex(f.get(2), path + ".deploymentId", 32);
    }
    private void account(PlutusData data, String path) {
        var f = record(data, 0, 2); hex(f.get(0), path + ".policy", 28); hex(f.get(1), path + ".name", 0);
    }
    private void core(PlutusData data, String path) {
        var f = record(data, 0, 3); String[] names = {"stateValidator", "assetValidator", "checkpoint"};
        for (int i = 0; i < 3; i++) hex(f.get(i), path + "." + names[i], 28);
        if (path.equals("domain.core")) sourceAssetHash = bytes(f.get(1), 28);
    }
    private void module(PlutusData data, String path) {
        var f = record(data, 0, 2); hex(f.get(0), path + ".scriptHash", 28); exact(f.get(1), 1); line(path + ".abiVersion", "1");
    }
    private void validity(PlutusData data) {
        var f = record(data, 0, 2); var lower = number(f.get(0), "validity.notBeforeInclusive", MAX);
        var upper = number(f.get(1), "validity.expiresAtExclusive", MAX);
        require(upper.compareTo(lower) > 0 && upper.subtract(lower).compareTo(BigInteger.valueOf(300_000)) <= 0, "validity width");
    }
    private void action(PlutusData data) {
        var c = constructor(data); int tag = c.tag();
        String[] names = {"Spend", "ReplaceConfig", "ReplaceModule", "Freeze", "Unfreeze", "StartRecovery", "CancelRecovery", "CompleteRecovery", "TransferWholeUtxo"};
        require(tag >= 0 && tag < names.length, "action tag"); line("action", names[tag]);
        int[] counts = {3, 1, 2, 0, 0, 2, 2, 2, 4}; var f = record(c, tag, counts[tag]);
        switch (tag) {
            case 0 -> spend(f);
            case 1 -> config(f.get(0), "action.newConfig");
            case 2 -> { module(f.get(0), "action.newModule"); config(f.get(1), "action.newConfig"); }
            case 3, 4 -> { }
            case 5 -> { number(f.get(0), "action.nextSequence", MAX); require(uint(f.get(0), MAX).signum() > 0, "next sequence"); config(f.get(1), "action.replacementConfig"); }
            case 6 -> { require(number(f.get(0), "action.currentSequence", MAX).signum() > 0, "current sequence"); hex(f.get(1), "action.proposalCommitment", 32); }
            case 7 -> { require(number(f.get(0), "action.currentSequence", MAX).signum() > 0, "current sequence"); config(f.get(1), "action.replacementConfig"); }
            case 8 -> {
                input(f.get(0), "action.input"); number(f.get(1), "action.recipientIndex", BigInteger.valueOf(15));
                address(f.get(2), "action.recipientAddress");
                require(constructor(record(f.get(2), 0, 2).get(0)).tag() == 0, "whole UTxO requires key recipient");
                byte[] expected = bytes(f.get(3), 32); hex(f.get(3), "action.inputValueDigest", 32);
                require(resolvedInputValue != null, "whole UTxO signing requires resolved input value");
                resolvedValue(resolvedInputValue);
                require(Arrays.equals(expected, hashEncoded(Builtins.serialiseData(resolvedInputValue))), "resolved value digest mismatch");
                line("action.maxAccountFeeLovelace", "0");
            }
            default -> throw new IllegalArgumentException("action");
        }
    }
    private void spend(List<PlutusData> f) {
        var inputs = list(f.get(0), 1, 8); byte[] previousHash = null; BigInteger previousIndex = null;
        for (int i = 0; i < inputs.size(); i++) {
            var ref = record(inputs.get(i), 0, 2); byte[] hash = bytes(ref.get(0), 32); BigInteger index = uint(ref.get(1), BigInteger.valueOf(65535));
            if (previousHash != null) require(Arrays.compareUnsigned(previousHash, hash) < 0 ||
                    Arrays.equals(previousHash, hash) && previousIndex.compareTo(index) < 0, "input order/duplicate");
            input(inputs.get(i), "action.inputs[" + i + "]"); previousHash = hash; previousIndex = index;
        }
        var recipients = list(f.get(1), 0, 8); int previous = -1; Set<String> union = new HashSet<>();
        for (int i = 0; i < recipients.size(); i++) {
            var r = record(recipients.get(i), 0, 3); String path = "action.recipients[" + i + "]";
            int index = number(r.get(0), path + ".index", BigInteger.valueOf(15)).intValueExact();
            require(index > previous, "recipient order/duplicate"); previous = index;
            address(r.get(1), path + ".address");
            var payment = constructor(record(r.get(1), 0, 2).get(0));
            require(payment.tag() != 1 || !Arrays.equals(bytes(payment.fields().get(0), 28), sourceAssetHash), "recipient is source account");
            value(r.get(2), path + ".value", union);
        }
        require(union.size() <= 12, "recipient asset union"); number(f.get(2), "action.maxAccountFeeLovelace", BigInteger.valueOf(5_000_000));
    }
    private void input(PlutusData data, String path) {
        var f = record(data, 0, 2); hex(f.get(0), path + ".txId", 32); number(f.get(1), path + ".index", BigInteger.valueOf(65535));
    }
    private void address(PlutusData data, String path) {
        var f = record(data, 0, 2); credential(f.get(0), path + ".payment"); var stake = constructor(f.get(1));
        if (stake.tag() == 1) { record(stake, 1, 0); line(path + ".stake", "none"); }
        else {
            var present = record(stake, 0, 1); var cred = constructor(present.get(0));
            if (cred.tag() == 0) credential(record(cred, 0, 1).get(0), path + ".stake.hash");
            else { var ptr = record(cred, 1, 3); for (int i = 0; i < 3; i++) number(ptr.get(i), path + ".stake.pointer[" + i + "]", MAX); }
        }
    }
    private void credential(PlutusData data, String path) {
        var c = constructor(data); require(c.tag() == 0 || c.tag() == 1, "credential tag");
        line(path + ".kind", c.tag() == 0 ? "key" : "script"); hex(record(c, c.tag(), 1).get(0), path + ".hash", 28);
    }
    private void value(PlutusData data, String path, Set<String> union) {
        var entries = list(data, 1, 12); byte[] previousPolicy = null; byte[] previousName = null;
        for (int i = 0; i < entries.size(); i++) {
            var f = record(entries.get(i), 0, 3); byte[] policy = anyBytes(f.get(0)); byte[] name = anyBytes(f.get(1));
            require((policy.length == 0 && name.length == 0) || (policy.length == 28 && name.length <= 32), "asset identifier");
            if (i == 0) require(policy.length == 0, "output ADA missing");
            if (previousPolicy != null) require(Arrays.compareUnsigned(previousPolicy, policy) < 0 ||
                    Arrays.equals(previousPolicy, policy) && Arrays.compareUnsigned(previousName, name) < 0, "asset order/duplicate");
            line(path + "[" + i + "].policy", HexFormat.of().formatHex(policy)); line(path + "[" + i + "].name", HexFormat.of().formatHex(name));
            require(number(f.get(2), path + "[" + i + "].quantity", MAX).signum() > 0, "zero output asset");
            union.add(HexFormat.of().formatHex(policy) + ":" + HexFormat.of().formatHex(name)); previousPolicy = policy; previousName = name;
        }
    }
    private void resolvedValue(PlutusData value) {
        require(value instanceof PlutusData.MapData, "ledger value map");
        var policies = ((PlutusData.MapData) value).entries();
        require(!policies.isEmpty() && policies.size() <= 2048, "ledger value policy count");
        byte[] previousPolicy = null; int count = 0;
        for (var policyEntry : policies) {
            byte[] policy = anyBytes(policyEntry.key());
            require(policy.length == 0 || policy.length == 28, "ledger policy length");
            require(previousPolicy == null ? policy.length == 0 : Arrays.compareUnsigned(previousPolicy, policy) < 0, "ledger policy order");
            require(policyEntry.value() instanceof PlutusData.MapData, "ledger token map");
            var tokens = ((PlutusData.MapData) policyEntry.value()).entries();
            require(!tokens.isEmpty() && tokens.size() <= 2048, "ledger token count"); byte[] previousName = null;
            for (var token : tokens) {
                require(++count <= 2048, "ledger value entry count"); byte[] name = anyBytes(token.key());
                require(name.length <= 32 && (policy.length != 0 || name.length == 0), "ledger asset name");
                require(previousName == null || Arrays.compareUnsigned(previousName, name) < 0, "ledger name order");
                String path = "action.resolvedInputValue[" + (count - 1) + "]";
                line(path + ".policy", HexFormat.of().formatHex(policy)); line(path + ".name", HexFormat.of().formatHex(name));
                require(number(token.value(), path + ".quantity", BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)).signum() > 0, "ledger value quantity"); previousName = name;
            }
            previousPolicy = policy;
        }
        require(Builtins.serialiseData(value).length <= 8192, "resolved ledger value byte bound");
    }
    private void config(PlutusData data, String path) {
        guard(data);
        require(Builtins.serialiseData(data).length <= MAX_CONFIG_BYTES, "config size"); var f = record(data, 0, 8); exact(f.get(0), 1); line(path + ".schemaVersion", "1");
        var keys = list(f.get(1), 3, 16); Set<Integer> registry = new HashSet<>(); Set<String> publicKeys = new HashSet<>(); int previous = -1;
        for (int i = 0; i < keys.size(); i++) {
            var key = record(keys.get(i), 0, 2); int id = number(key.get(0), path + ".keys[" + i + "].id", BigInteger.valueOf(15)).intValueExact();
            require(id > previous && registry.add(id), "key order/duplicate"); previous = id;
            String hex = hex(key.get(1), path + ".keys[" + i + "].publicKey", 32); require(publicKeys.add(hex), "public key alias");
            require(Ed25519.validatePublicKeyFull(bytes(key.get(1), 32), 0), "invalid Ed25519 public key");
        }
        String[] roles = {"spend", "admin", "freeze", "unfreeze", "recovery", "cancel"}; List<Set<Integer>> sets = new ArrayList<>();
        for (int i = 0; i < roles.length; i++) {
            var policy = record(f.get(i + 2), 0, 2); var members = list(policy.get(1), 1, 8);
            int threshold = number(policy.get(0), path + "." + roles[i] + ".threshold", BigInteger.valueOf(8)).intValueExact();
            require(threshold >= 1 && threshold <= members.size(), "threshold"); Set<Integer> ids = new HashSet<>(); previous = -1;
            for (int j = 0; j < members.size(); j++) {
                int id = number(members.get(j), path + "." + roles[i] + ".keys[" + j + "]", BigInteger.valueOf(15)).intValueExact();
                require(id > previous && registry.contains(id), "policy key order/unknown key"); previous = id; ids.add(id);
            }
            sets.add(ids);
        }
        require(disjoint(sets.get(4), sets.get(3)) && disjoint(sets.get(4), sets.get(5)), "recovery/defensive overlap");
        require(disjoint(sets.get(0), sets.get(3)) && disjoint(sets.get(0), sets.get(5)), "spend/defensive overlap");
        var admin = record(f.get(3), 0, 2); var threshold = uint(admin.get(0), BigInteger.valueOf(8)).intValueExact();
        int everydayAdminKeys = 0; for (int id : sets.get(0)) if (sets.get(1).contains(id)) everydayAdminKeys++;
        require(everydayAdminKeys < threshold, "everyday spend keys can satisfy admin");
    }
    private static boolean disjoint(Set<Integer> a, Set<Integer> b) { return a.stream().noneMatch(b::contains); }
    private void line(String path, String value) { display.append(path).append('=').append(value).append('\n'); }
    private String hex(PlutusData d, String path, int length) { String hex = HexFormat.of().formatHex(bytes(d, length)); line(path, hex); return hex; }
    private BigInteger number(PlutusData d, String path, BigInteger max) { var n = uint(d, max); line(path, n.toString()); return n; }
    private static void exact(PlutusData d, long n) { require(uint(d, MAX).equals(BigInteger.valueOf(n)), "version"); }
    private static void range(PlutusData d, BigInteger min, BigInteger max) { require(uint(d, max).compareTo(min) >= 0, "minimum"); }
    private static BigInteger uint(PlutusData d, BigInteger max) {
        require(d instanceof PlutusData.IntData, "integer type"); var n = ((PlutusData.IntData) d).value();
        require(n.signum() >= 0 && n.compareTo(max) <= 0, "integer bound"); return n;
    }
    private static byte[] bytes(PlutusData d, int length) { var bytes = anyBytes(d); require(bytes.length == length, "byte length"); return bytes; }
    private static byte[] anyBytes(PlutusData d) { require(d instanceof PlutusData.BytesData, "bytes type"); return ((PlutusData.BytesData) d).value(); }
    private static PlutusData.ConstrData constructor(PlutusData d) {
        require(d instanceof PlutusData.ConstrData, "constructor type");
        var c = (PlutusData.ConstrData) d; require(c.constructorTag().signum() >= 0 && c.constructorTag().compareTo(BigInteger.valueOf(8)) <= 0, "constructor range"); return c;
    }
    private static List<PlutusData> record(PlutusData d, int tag, int count) {
        var c = constructor(d); require(c.constructorTag().equals(BigInteger.valueOf(tag)) && c.fields().size() == count, "constructor tag/arity"); return c.fields();
    }
    private static List<PlutusData> list(PlutusData d, int min, int max) {
        require(d instanceof PlutusData.ListData, "list type"); var list = ((PlutusData.ListData) d).items();
        require(list.size() >= min && list.size() <= max, "list bound"); return list;
    }
    private record Frame(PlutusData data, int depth) {}
    private static void guard(PlutusData data) {
        var pending = new ArrayDeque<Frame>(); pending.push(new Frame(data, 0));
        int nodes = 0; int bytes = 0;
        while (!pending.isEmpty()) {
            var frame = pending.pop(); require(++nodes <= 4096 && frame.depth <= 32, "Data complexity");
            List<PlutusData> children = List.of();
            if (frame.data instanceof PlutusData.ConstrData c) children = c.fields();
            else if (frame.data instanceof PlutusData.ListData l) children = l.items();
            else if (frame.data instanceof PlutusData.BytesData b) { bytes += b.value().length; require(bytes <= 8192, "Data byte budget"); }
            else if (frame.data instanceof PlutusData.IntData n) require(n.value().bitLength() <= 64, "Data integer size");
            else throw new IllegalArgumentException("Maps unsupported in this candidate profile");
            require(children.size() <= 256, "Data branching");
            for (var child : children) pending.push(new Frame(child, frame.depth + 1));
        }
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
    private WireFormat() {}
}
