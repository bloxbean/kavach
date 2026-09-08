package com.bloxbean.cardano.kavach.protocol;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.*;
import com.bloxbean.cardano.kavach.phase0.WireDigestProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import static com.bloxbean.cardano.kavach.protocol.WireFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class WireFormatTest {
    private String resource(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) { assertNotNull(stream); return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
    }
    private PlutusData replace(PlutusData data, int field, PlutusData replacement) {
        var c = (PlutusData.ConstrData) data; var fields = new ArrayList<>(c.fields()); fields.set(field, replacement);
        return new PlutusData.ConstrData(c.tag(), fields);
    }
    @Test void allNineVectorsAgreeAcrossDecoderRendererCclAndCompiledDigestSignature() throws Exception {
        var vectors = JsonUtil.parseJson(resource("/v1/intent-vectors.json")); assertEquals(9, vectors.size());
        var program = JulcScriptAdapter.toProgram(JulcScriptLoader.load(WireDigestProbe.class).getCborHex());
        for (var vector : vectors) {
            var cbor = HexFormat.of().parseHex(vector.get("cbor").asText());
            var decoded = PlutusDataAdapter.fromClientLib(com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(cbor));
            int tag = vector.get("actionTag").asInt(); assertEquals(envelope(tag), decoded);
            assertEquals(vector.get("rendering").asText(), WireFormat.renderIntent(decoded, tag == 8 ? wholeValue() : null));
            assertArrayEquals(cbor, PlutusDataAdapter.toClientLib(decoded).serializeToBytes());
            byte[] digest = Blake2bUtil.blake2bHash256(cbor);
            assertEquals(vector.get("digest").asText(), HexFormat.of().formatHex(digest));
            byte[] pub = HexFormat.of().parseHex(vector.get("publicKey").asText());
            byte[] sig = HexFormat.of().parseHex(vector.get("signature").asText());
            var redeemer = rec(decoded, PlutusData.bytes(digest), PlutusData.bytes(pub), PlutusData.bytes(sig));
            var ctx = ScriptContextTestBuilder.rewarding(new Credential.ScriptCredential(new ScriptHash(new byte[28]))).redeemer(redeemer);
            var result = JulcVm.create("Java").evaluateWithArgs(program, LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                    List.of(ctx.buildPlutusData()), new ExBudget(500_000_000, 2_000_000), EvalOptions.DEFAULT);
            assertInstanceOf(EvalResult.Success.class, result, "action " + tag + ": " + result);
            sig[0] ^= 1;
            var bad = ScriptContextTestBuilder.rewarding(new Credential.ScriptCredential(new ScriptHash(new byte[28])))
                    .redeemer(rec(decoded, PlutusData.bytes(digest), PlutusData.bytes(pub), PlutusData.bytes(sig)));
            assertInstanceOf(EvalResult.Failure.class, JulcVm.create("Java").evaluateWithArgs(program,
                    LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), List.of(bad.buildPlutusData()), new ExBudget(500_000_000, 2_000_000), EvalOptions.DEFAULT));
        }
    }
    @Test void nativeTokenAndAddressEdgesAgreeWithCompiledCanonicalDigests() throws Exception {
        var vectors = JsonUtil.parseJson(resource("/v1/edge-vectors.json")); assertEquals(3, vectors.size());
        var program = JulcScriptAdapter.toProgram(JulcScriptLoader.load(WireDigestProbe.class).getCborHex());
        for (var vector : vectors) {
            byte[] cbor = HexFormat.of().parseHex(vector.get("cbor").asText());
            var data = PlutusDataAdapter.fromClientLib(com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(cbor));
            var resolved = vector.has("resolvedInputValueCbor") ? PlutusDataAdapter.fromClientLib(
                    com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(HexFormat.of().parseHex(vector.get("resolvedInputValueCbor").asText()))) : null;
            assertEquals(vector.get("rendering").asText(), WireFormat.renderIntent(data, resolved));
            assertArrayEquals(cbor, PlutusDataAdapter.toClientLib(data).serializeToBytes());
            byte[] digest = HexFormat.of().parseHex(vector.get("digest").asText());
            assertArrayEquals(digest, WireFormat.digest(data));
            var context = ScriptContextTestBuilder.rewarding(new Credential.ScriptCredential(new ScriptHash(new byte[28])))
                    .redeemer(rec(data, PlutusData.bytes(digest), PlutusData.bytes(HexFormat.of().parseHex(vector.get("publicKey").asText())),
                            PlutusData.bytes(HexFormat.of().parseHex(vector.get("signature").asText()))));
            var result = JulcVm.create("Java").evaluateWithArgs(program, LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                    List.of(context.buildPlutusData()), new ExBudget(500_000_000, 2_000_000), EvalOptions.DEFAULT);
            assertInstanceOf(EvalResult.Success.class, result, vector.get("name").asText() + ": " + result);
        }
    }
    @Test void wholeTransferRenderingRequiresTheExactResolvedValue() {
        assertThrows(IllegalArgumentException.class, () -> WireFormat.renderIntent(envelope(8)));
        var wrong = Value.lovelace(BigInteger.valueOf(1)).toPlutusData();
        assertThrows(IllegalArgumentException.class, () -> WireFormat.renderIntent(envelope(8), wrong));
        String rendered = WireFormat.renderIntent(envelope(8), wholeValue());
        assertTrue(rendered.contains("digest.blake2b256="));
        assertThrows(IllegalArgumentException.class, () -> WireFormat.ledgerValueDigest(number(0)));
    }
    @Test void signerStateDisplayBindsTheResolvedReferenceAndIncludesRecoveryTerms() {
        var ref = ((PlutusData.ConstrData) domain()).fields().get(5);
        String display = WireFormat.renderSigningRequest(envelope(5), state(), ref, null);
        assertTrue(display.contains("state.recoveryDelayMs=86400000\n"));
        assertTrue(display.contains("state.recoveryCooldownMs=3600000\n"));
        assertThrows(IllegalArgumentException.class, () -> WireFormat.renderSigningRequest(envelope(5), state(), input(99), null));
        assertThrows(IllegalArgumentException.class, () -> WireFormat.renderSigningRequest(envelope(5), replace(state(), 4, number(1)), ref, null));
        var pending = pendingState(new byte[32]);
        assertTrue(WireFormat.renderState(pending).contains("state.pending.proposalCommitment="));
        assertTrue(WireFormat.renderState(pending).contains("state.pending.targetConfig.spend.threshold="));
    }
    @Test void stateVectorAndModesValidateStrictly() throws Exception {
        WireFormat.validateState(state());
        assertEquals(resource("/v1/state-rendering.txt"), WireFormat.renderState(state()));
        assertEquals(resource("/v1/state.hex").strip(), PlutusDataAdapter.toClientLib(state()).serializeToHex());
        WireFormat.validateState(replace(state(), 11, PlutusData.constr(1)));
        var pending = replace(replace(state(), 4, number(1)), 7, number(1));
        WireFormat.validateState(replace(pending, 11, PlutusData.constr(2, bytes(32, 1), number(1_000_000), config())));
        assertThrows(IllegalArgumentException.class, () -> WireFormat.validateState(replace(state(), 11, PlutusData.constr(2, bytes(32, 1), number(1_000_000), config()))));
        assertThrows(IllegalArgumentException.class, () -> WireFormat.validateState(replace(state(), 11, PlutusData.constr(2))));
        assertThrows(IllegalArgumentException.class, () -> WireFormat.validateState(replace(state(), 8, number(0))));
    }
    @ParameterizedTest @ValueSource(strings = {"tag", "version", "extra-field", "unknown-action", "interval-zero", "interval-wide", "negative-version", "overflow-version", "input-duplicate", "input-empty", "recipient-duplicate", "fee", "negative-asset", "overflow-asset", "asset-duplicate", "missing-ada", "bad-key-alias", "invalid-key", "recipient-source", "defensive-overlap", "admin-overlap", "threshold-zero", "deep", "huge-bytes"})
    void malformedOrUnsafePayloadsReject(String mutation) {
        PlutusData payload = envelope(0); var spend = action(0);
        switch (mutation) {
            case "tag" -> payload = replace(payload, 0, bytes(14, 0));
            case "version" -> payload = replace(payload, 1, replace(domain(), 0, number(2)));
            case "extra-field" -> { var f = new ArrayList<>(((PlutusData.ConstrData) payload).fields()); f.add(number(0)); payload = new PlutusData.ConstrData(0, f); }
            case "unknown-action" -> payload = replace(payload, 3, PlutusData.constr(9));
            case "interval-zero" -> payload = replace(payload, 2, rec(number(1), number(1)));
            case "interval-wide" -> payload = replace(payload, 2, rec(number(0), number(300001)));
            case "negative-version" -> payload = replace(payload, 1, replace(domain(), 4, number(-1)));
            case "overflow-version" -> payload = replace(payload, 1, replace(domain(), 4, PlutusData.integer(BigInteger.ONE.shiftLeft(63))));
            case "input-duplicate" -> payload = replace(payload, 3, replace(spend, 0, list(input(1), input(1))));
            case "input-empty" -> payload = replace(payload, 3, replace(spend, 0, list()));
            case "recipient-duplicate" -> payload = replace(payload, 3, replace(spend, 1, list(rec(number(0), address(), value()), rec(number(0), address(), value()))));
            case "fee" -> payload = replace(payload, 3, replace(spend, 2, number(5_000_001)));
            case "overflow-asset" -> payload = replace(payload, 3, replace(spend, 1, list(rec(number(0), address(), list(rec(bytes(0,0), bytes(0,0), PlutusData.integer(BigInteger.ONE.shiftLeft(63))))))));
            case "negative-asset" -> payload = replace(payload, 3, replace(spend, 1, list(rec(number(0), address(), list(rec(bytes(0,0), bytes(0,0), number(-1)))))));
            case "asset-duplicate" -> { var ada = rec(bytes(0,0), bytes(0,0), number(1)); payload = replace(payload, 3, replace(spend, 1, list(rec(number(0), address(), list(ada, ada))))); }
            case "missing-ada" -> payload = replace(payload, 3, replace(spend, 1, list(rec(number(0), address(), list(rec(bytes(28,1), bytes(0,0), number(1)))))));
            case "recipient-source" -> payload = replace(payload, 3, replace(spend, 1, list(rec(number(0), rec(PlutusData.constr(1, bytes(28, 4)), PlutusData.constr(1)), value()))));
            case "invalid-key" -> payload = replace(payload, 3, PlutusData.constr(1, replace(config(), 1, list(rec(number(0), bytes(32, 0)), key(1), key(2)))));
            case "bad-key-alias" -> payload = replace(payload, 3, PlutusData.constr(1, replace(config(), 1, list(key(0), rec(number(1), ((PlutusData.ConstrData) key(0)).fields().get(1)), key(2)))));
            case "defensive-overlap" -> payload = replace(payload, 3, PlutusData.constr(1, replace(config(), 5, policy(2))));
            case "admin-overlap" -> payload = replace(payload, 3, PlutusData.constr(1, replace(config(), 3, policy(0))));
            case "threshold-zero" -> payload = replace(payload, 3, PlutusData.constr(1, replace(config(), 2, rec(number(0), list(number(0))))));
            case "deep" -> { PlutusData deep = number(0); for(int i=0;i<40;i++) deep=rec(deep); payload = replace(payload, 3, PlutusData.constr(1, deep)); }
            case "huge-bytes" -> payload = replace(payload, 0, bytes(8193, 0));
            default -> throw new AssertionError(mutation);
        }
        var malformed = payload;
        assertThrows(IllegalArgumentException.class, () -> WireFormat.renderIntent(malformed));
    }
}
