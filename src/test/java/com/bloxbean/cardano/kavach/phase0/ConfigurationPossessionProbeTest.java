package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.vm.*;
import com.bloxbean.cardano.client.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationPossessionProbeTest {
    private List<KeyPair> keys(int count) throws Exception {
        var keys = new ArrayList<KeyPair>(); for (int i = 0; i < count; i++) keys.add(ProbeFixtures.keyPair()); return keys;
    }
    private PlutusData config(int threshold, List<KeyPair> keys) {
        return PlutusData.constr(0, PlutusData.integer(threshold), new PlutusData.ListData(keys.stream()
                .map(key -> (PlutusData) PlutusData.constr(0, PlutusData.bytes(ProbeFixtures.publicKey(key)))).toList()));
    }
    private List<PlutusData> proofs(List<KeyPair> keys, PlutusData envelope) throws Exception {
        var proofs = new ArrayList<PlutusData>();
        for (int i = 0; i < keys.size(); i++) proofs.add(PlutusData.constr(0, PlutusData.integer(i),
                ((PlutusData.ConstrData) ProbeFixtures.authorize(keys.get(i), envelope)).fields().get(1)));
        return proofs;
    }
    private EvalResult eval(PlutusData config, PlutusData envelope, List<PlutusData> proofs) {
        var script = JulcScriptLoader.load(ConfigurationPossessionProbe.class, PlutusDataAdapter.toClientLib(config));
        var ctx = ScriptContextTestBuilder.rewarding(new Credential.ScriptCredential(new ScriptHash(new byte[28])))
                .redeemer(PlutusData.constr(0, envelope, new PlutusData.ListData(proofs)));
        return JulcVm.create("Java").evaluateWithArgs(JulcScriptAdapter.toProgram(script.getCborHex()),
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), List.of(ctx.buildPlutusData()),
                new ExBudget(4_000_000_000L, 8_000_000), EvalOptions.DEFAULT);
    }
    @Test void maximumKeysProofsAndEnvelopeFitMeasuredBudget() throws Exception {
        var keys = keys(16); int size = 1536; PlutusData envelope;
        do { envelope = PlutusData.bytes(new byte[size--]); } while (Builtins.serialiseData(envelope).length > 1536);
        var result = eval(config(16, keys), envelope, proofs(keys, envelope));
        var success = assertInstanceOf(EvalResult.Success.class, result, result.toString());
        var evidence = new LinkedHashMap<String, Object>(); evidence.put("scope", "Configuration possession primitive maximum input-size case; opaque payload, not a valid full account operation");
        evidence.put("keyCount", 16); evidence.put("signatureCount", 16); evidence.put("serializedEnvelopeBytes", Builtins.serialiseData(envelope).length);
        evidence.put("cpu", success.consumed().cpuSteps()); evidence.put("memory", success.consumed().memoryUnits());
        Files.createDirectories(Path.of("build/phase0")); Files.writeString(Path.of("build/phase0/possession-budget.json"), JsonUtil.getPrettyJson(evidence));
        System.out.println("Maximum possession budget: " + success.consumed());
    }
    @ParameterizedTest @ValueSource(strings = {"zero-threshold", "unreachable-threshold", "too-many-keys", "key-alias", "duplicate-proof", "unsorted-proof", "out-of-range", "too-few", "bad-last-signature", "extra-proof-field", "wrong-proof-tag", "oversize-envelope"})
    void rejectsInvalidConfigOrEvidence(String mutation) throws Exception {
        var keys = keys(mutation.equals("too-many-keys") ? 17 : 16);
        PlutusData envelope = PlutusData.constr(0, PlutusData.bytes(new byte[32]));
        if (mutation.equals("oversize-envelope")) envelope = PlutusData.bytes(new byte[1537]);
        var proofs = proofs(keys, envelope); int threshold = 16;
        switch (mutation) {
            case "zero-threshold" -> threshold = 0;
            case "unreachable-threshold" -> threshold = 17;
            case "key-alias" -> keys.set(15, keys.getFirst());
            case "duplicate-proof" -> proofs.set(15, proofs.get(14));
            case "unsorted-proof" -> { var first = proofs.get(0); proofs.set(0, proofs.get(1)); proofs.set(1, first); }
            case "out-of-range" -> proofs.set(15, PlutusData.constr(0, PlutusData.integer(16), ((PlutusData.ConstrData) proofs.get(15)).fields().get(1)));
            case "too-few" -> proofs.removeLast();
            case "bad-last-signature" -> proofs.set(15, PlutusData.constr(0, PlutusData.integer(15), PlutusData.bytes(new byte[64])));
            case "extra-proof-field" -> proofs.set(15, PlutusData.constr(0, PlutusData.integer(15), ((PlutusData.ConstrData) proofs.get(15)).fields().get(1), PlutusData.integer(0)));
            case "wrong-proof-tag" -> proofs.set(15, new PlutusData.ConstrData(1, ((PlutusData.ConstrData) proofs.get(15)).fields()));
        }
        assertInstanceOf(EvalResult.Failure.class, eval(config(threshold, keys), envelope, proofs));
    }
}
