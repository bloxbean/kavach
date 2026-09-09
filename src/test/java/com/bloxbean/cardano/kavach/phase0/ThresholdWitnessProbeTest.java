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

class ThresholdWitnessProbeTest {
    private List<KeyPair> keys(int count) throws Exception {
        var keys = new ArrayList<KeyPair>();
        for (int i = 0; i < count; i++) keys.add(ProbeFixtures.keyPair());
        return keys;
    }

    private PlutusData config(int threshold, List<KeyPair> keys) {
        return PlutusData.constr(0, PlutusData.integer(threshold), new PlutusData.ListData(keys.stream()
                .map(key -> (PlutusData) PlutusData.constr(0, PlutusData.bytes(ProbeFixtures.publicKey(key)))).toList()));
    }

    private List<PlutusData> proofs(List<KeyPair> keys, PlutusData envelope) throws Exception {
        var proofs = new ArrayList<PlutusData>();
        for (int i = 0; i < keys.size(); i++)
            proofs.add(PlutusData.constr(0, PlutusData.integer(i),
                    ((PlutusData.ConstrData) ProbeFixtures.authorize(keys.get(i), envelope)).fields().get(1)));
        return proofs;
    }

    private EvalResult eval(PlutusData config, PlutusData envelope, List<PlutusData> proofs) {
        var script = JulcScriptLoader.load(ThresholdWitnessProbe.class, PlutusDataAdapter.toClientLib(config));
        var ctx = ScriptContextTestBuilder.rewarding(new Credential.ScriptCredential(new ScriptHash(new byte[28])))
                .redeemer(PlutusData.constr(0, envelope, new PlutusData.ListData(proofs)));
        return JulcVm.create("Java").evaluateWithArgs(JulcScriptAdapter.toProgram(script.getCborHex()),
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), List.of(ctx.buildPlutusData()),
                new ExBudget(2_000_000_000L, 8_000_000), EvalOptions.DEFAULT);
    }

    @Test
    void maximumKeysProofsAndEnvelopeFitMeasuredBudget() throws Exception {
        var keys = keys(8);
        int size = 8192;
        PlutusData envelope;
        do {
            envelope = PlutusData.bytes(new byte[size--]);
        } while (Builtins.serialiseData(envelope).length > 8192);
        var result = eval(config(8, keys), envelope, proofs(keys, envelope));
        var success = assertInstanceOf(EvalResult.Success.class, result, result.toString());
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("scope", "Threshold primitive maximum input-size case; opaque payload, not a valid full account operation");
        evidence.put("keyCount", 8);
        evidence.put("signatureCount", 8);
        evidence.put("serializedEnvelopeBytes", Builtins.serialiseData(envelope).length);
        evidence.put("cpu", success.consumed().cpuSteps());
        evidence.put("memory", success.consumed().memoryUnits());
        Files.createDirectories(Path.of("build/phase0"));
        Files.writeString(Path.of("build/phase0/threshold-budget.json"), JsonUtil.getPrettyJson(evidence));
        System.out.println("Maximum threshold budget: " + success.consumed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero-threshold", "unreachable-threshold", "too-many-keys", "key-alias", "duplicate-proof", "unsorted-proof", "out-of-range", "too-few", "bad-last-signature", "extra-proof-field", "wrong-proof-tag", "oversize-envelope"})
    void rejectsInvalidConfigOrEvidence(String mutation) throws Exception {
        var keys = keys(mutation.equals("too-many-keys") ? 9 : 8);
        PlutusData envelope = PlutusData.constr(0, PlutusData.bytes(new byte[32]));
        if (mutation.equals("oversize-envelope")) envelope = PlutusData.bytes(new byte[8193]);
        var proofs = proofs(keys, envelope);
        int threshold = 8;
        switch (mutation) {
            case "zero-threshold" -> threshold = 0;
            case "unreachable-threshold" -> threshold = 9;
            case "key-alias" -> keys.set(7, keys.getFirst());
            case "duplicate-proof" -> proofs.set(7, proofs.get(6));
            case "unsorted-proof" -> {
                var first = proofs.get(0);
                proofs.set(0, proofs.get(1));
                proofs.set(1, first);
            }
            case "out-of-range" ->
                    proofs.set(7, PlutusData.constr(0, PlutusData.integer(8), ((PlutusData.ConstrData) proofs.get(7)).fields().get(1)));
            case "too-few" -> proofs.removeLast();
            case "bad-last-signature" ->
                    proofs.set(7, PlutusData.constr(0, PlutusData.integer(7), PlutusData.bytes(new byte[64])));
            case "extra-proof-field" ->
                    proofs.set(7, PlutusData.constr(0, PlutusData.integer(7), ((PlutusData.ConstrData) proofs.get(7)).fields().get(1), PlutusData.integer(0)));
            case "wrong-proof-tag" ->
                    proofs.set(7, new PlutusData.ConstrData(1, ((PlutusData.ConstrData) proofs.get(7)).fields()));
        }
        assertInstanceOf(EvalResult.Failure.class, eval(config(threshold, keys), envelope, proofs));
    }
}
