package com.bloxbean.cardano.kavach.protocol;

import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.kavach.phase0.ConfigurationPossessionProbe;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GenesisPossessionTest {
    private PlutusData decode(String hex) throws Exception {
        return PlutusDataAdapter.fromClientLib(com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(HexFormat.of().parseHex(hex)));
    }
    @Test void everyInitialKeyProvesPossessionOfTheSameFullStateBinding() throws Exception {
        var fixture = JsonUtil.parseJson(Files.readString(Path.of("conformance/v1/genesis-possession.json")));
        var state = decode(fixture.get("stateCbor").asText()); WireFormat.validateState(state);
        var proofEnvelope = ProofDomains.genesis(state);
        assertEquals(fixture.get("proofEnvelopeCbor").asText(), PlutusDataAdapter.toClientLib(proofEnvelope).serializeToHex());
        assertEquals(fixture.get("digest").asText(), HexFormat.of().formatHex(WireFormat.digest(proofEnvelope)));
        var config = (PlutusData.ConstrData) ((PlutusData.ConstrData) state).fields().get(6);
        var keys = (PlutusData.ListData) config.fields().get(1);
        var primitiveKeys = new PlutusData.ListData(keys.items().stream().map(k -> (PlutusData) PlutusData.constr(0, ((PlutusData.ConstrData) k).fields().get(1))).toList());
        var primitiveConfig = PlutusData.constr(0, PlutusData.integer(keys.items().size()), primitiveKeys);
        var script = JulcScriptLoader.load(ConfigurationPossessionProbe.class, PlutusDataAdapter.toClientLib(primitiveConfig));
        var invocation = (PlutusData.ConstrData) decode(fixture.get("invocationCbor").asText());
        assertEquals(2, invocation.tag()); assertEquals(4, invocation.fields().size()); assertEquals(state, invocation.fields().get(1));
        var proofs = (PlutusData.ListData) invocation.fields().get(2);
        for (int mutation = 0; mutation < 3; mutation++) {
            var evidence = new ArrayList<>(proofs.items()); PlutusData envelope = proofEnvelope;
            if (mutation == 1) evidence.removeLast();
            if (mutation == 2) envelope = PlutusData.constr(0, PlutusData.bytes(new byte[32]));
            var context = ScriptContextTestBuilder.rewarding(new Credential.ScriptCredential(new ScriptHash(script.getScriptHash())))
                    .redeemer(PlutusData.constr(0, envelope, new PlutusData.ListData(evidence)));
            var result = JulcVm.create("Java").evaluateWithArgs(JulcScriptAdapter.toProgram(script.getCborHex()),
                    LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), List.of(context.buildPlutusData()), new ExBudget(1_000_000_000, 3_000_000), EvalOptions.DEFAULT);
            if (mutation == 0) assertInstanceOf(EvalResult.Success.class, result, result.toString());
            else assertInstanceOf(EvalResult.Failure.class, result, result.toString());
        }
    }
}
