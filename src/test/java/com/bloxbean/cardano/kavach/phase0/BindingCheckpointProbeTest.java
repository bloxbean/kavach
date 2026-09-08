package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigInteger;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import com.bloxbean.cardano.client.util.JsonUtil;
import static org.junit.jupiter.api.Assertions.*;

class BindingCheckpointProbeTest {
    private final KeyPair key = ProbeFixtures.keyPair();
    private final byte[] policy = new byte[28];
    private final byte[] creator = new byte[28];
    private final byte[] holder = StateProbeFixtures.holder().getScriptHash();
    private final Address sink = new Address(new Credential.PubKeyCredential(new PubKeyHash(creator)), Optional.empty());
    private final BindingFixtures.Pair pair = BindingFixtures.pair(key, policy, sink, holder, creator);
    private final Credential core = new Credential.ScriptCredential(new ScriptHash(pair.core().getScriptHash()));
    private final Credential module = new Credential.ScriptCredential(new ScriptHash(pair.module().getScriptHash()));
    private final TxOutRef inputRef = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
    private final PlutusData envelope = BindingFixtures.envelope(policy, inputRef, pair);
    BindingCheckpointProbeTest() throws Exception {}

    private TxInInfo state() {
        return new TxInInfo(new TxOutRef(new TxId(new byte[32]), BigInteger.ONE),
                new TxOut(new Address(new Credential.ScriptCredential(new ScriptHash(holder)), Optional.empty()),
                        Value.lovelace(BigInteger.valueOf(4_000_000)).merge(Value.singleton(new PolicyId(policy), TokenName.EMPTY, BigInteger.ONE)),
                        new OutputDatum.OutputDatumInline(StateProbeFixtures.state(creator)), Optional.empty()));
    }
    private ScriptContextTestBuilder context(boolean isCore, PlutusData auth, long coreAmount, long moduleAmount) {
        return ScriptContextTestBuilder.rewarding(isCore ? core : module).redeemer(auth)
                .input(new TxInInfo(inputRef, new TxOut(sink, Value.lovelace(BigInteger.valueOf(20_000_000)), new OutputDatum.NoOutputDatum(), Optional.empty())))
                .referenceInput(state()).withdrawal(core, BigInteger.valueOf(coreAmount)).withdrawal(module, BigInteger.valueOf(moduleAmount))
                .redeemerEntry(new ScriptPurpose.Rewarding(core), auth).redeemerEntry(new ScriptPurpose.Rewarding(module), auth);
    }
    private EvalResult eval(boolean isCore, ScriptContextTestBuilder ctx) {
        var script = isCore ? pair.core() : pair.module();
        return JulcVm.create("Java").evaluateWithArgs(JulcScriptAdapter.toProgram(script.getCborHex()),
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), List.of(ctx.buildPlutusData()),
                new ExBudget(1_000_000_000, 4_000_000), EvalOptions.DEFAULT);
    }
    @Test void bothRolesAcceptIdenticalAuthorization() throws Exception {
        var auth = BindingFixtures.authorization(key, envelope, -1, -1);
        for (boolean isCore : List.of(false, true)) {
            var result = eval(isCore, context(isCore, auth, 0, 0));
            var success = assertInstanceOf(EvalResult.Success.class, result, result.toString());
            System.out.println((isCore ? "core" : "module") + " binding budget: " + success.consumed());
        }
    }
    @Test void sharedSinkRequiresDistinctReceiptsAndAllowsTopups() throws Exception {
        var distinct = BindingFixtures.authorization(key, envelope, 0, 1);
        var reused = BindingFixtures.authorization(key, envelope, 0, 0);
        var receipt = new TxOut(sink, Value.lovelace(BigInteger.valueOf(2_000_000)), new OutputDatum.NoOutputDatum(), Optional.empty());
        for (boolean isCore : List.of(false, true)) {
            assertInstanceOf(EvalResult.Success.class, eval(isCore, context(isCore, distinct, 1, 1).output(receipt).output(receipt)));
            assertInstanceOf(EvalResult.Failure.class, eval(isCore, context(isCore, reused, 1, 1).output(receipt)));
            assertInstanceOf(EvalResult.Failure.class, eval(isCore, context(isCore, distinct, 2_000_001, 1).output(receipt).output(receipt)));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"domain", "account", "version", "operation", "input", "core-hash", "module-hash", "extra-field", "wrong-constructor"})
    void rejectsSignedForeignOrMalformedIntent(String mutation) throws Exception {
        var fields = new ArrayList<>(((PlutusData.ConstrData) envelope).fields());
        int tag = 0;
        switch (mutation) {
            case "domain" -> fields.set(0, PlutusData.bytes(new byte[0]));
            case "account" -> fields.set(1, PlutusData.bytes(new byte[27]));
            case "version" -> fields.set(2, PlutusData.integer(1));
            case "operation" -> fields.set(3, PlutusData.integer(1));
            case "input" -> fields.set(4, new TxOutRef(new TxId(new byte[32]), BigInteger.TEN).toPlutusData());
            case "core-hash" -> fields.set(5, PlutusData.bytes(new byte[28]));
            case "module-hash" -> fields.set(6, PlutusData.bytes(new byte[28]));
            case "extra-field" -> fields.add(PlutusData.integer(0));
            case "wrong-constructor" -> tag = 1;
        }
        var altered = new PlutusData.ConstrData(tag, fields);
        var auth = BindingFixtures.authorization(key, altered, -1, -1);
        for (boolean isCore : List.of(false, true)) assertInstanceOf(EvalResult.Failure.class, eval(isCore, context(isCore, auth, 0, 0)));
    }
    @Test void coreRejectsDifferentModuleRedeemerAndWrongPurpose() throws Exception {
        var auth = BindingFixtures.authorization(key, envelope, -1, -1);
        var different = BindingFixtures.authorization(ProbeFixtures.keyPair(), envelope, -1, -1);
        assertInstanceOf(EvalResult.Failure.class, eval(true, context(true, auth, 0, 0)
                .redeemerEntry(new ScriptPurpose.Rewarding(module), different)));
        var ctx = ScriptContextTestBuilder.rewarding(core).redeemer(auth).withdrawal(core, BigInteger.ZERO).withdrawal(module, BigInteger.ZERO)
                .referenceInput(state()).input(new TxInInfo(inputRef, new TxOut(sink, Value.lovelace(BigInteger.ONE), new OutputDatum.NoOutputDatum(), Optional.empty())))
                .redeemerEntry(new ScriptPurpose.Rewarding(core), auth)
                .redeemerEntry(new ScriptPurpose.Certifying(BigInteger.ZERO, new TxCert.RegStaking(module, Optional.empty())), auth);
        assertInstanceOf(EvalResult.Failure.class, eval(true, ctx));
    }
    @Test void moduleRejectsInvalidSignatureEvenWhenCoreCanDelegate() throws Exception {
        var auth = BindingFixtures.authorization(ProbeFixtures.keyPair(), envelope, -1, -1);
        assertInstanceOf(EvalResult.Failure.class, eval(false, context(false, auth, 0, 0)));
        // Local core success is not transaction success: the paired module must execute and reject.
        assertInstanceOf(EvalResult.Success.class, eval(true, context(true, auth, 0, 0)));
    }
    @Test void duplicatedStateReferenceRejectsInBothRoles() throws Exception {
        var auth = BindingFixtures.authorization(key, envelope, -1, -1);
        for (boolean isCore : List.of(false, true)) assertInstanceOf(EvalResult.Failure.class,
                eval(isCore, context(isCore, auth, 0, 0).referenceInput(state())));
    }

    @Test void maximumContextCountsFitAndOneOverRejects() throws Exception {
        var auth = BindingFixtures.authorization(key, envelope, 14, 15);
        var results = new LinkedHashMap<String, Object>();
        for (boolean isCore : List.of(false, true)) {
            var ctx = context(isCore, auth, 1, 1);
            for (int i = 1; i < 16; i++) ctx.input(new TxInInfo(
                    new TxOutRef(new TxId(new byte[32]), BigInteger.valueOf(i + 10)),
                    new TxOut(sink, Value.lovelace(BigInteger.valueOf(Long.MAX_VALUE)), new OutputDatum.NoOutputDatum(), Optional.empty())));
            for (int i = 0; i < 3; i++) ctx.referenceInput(new TxInInfo(
                    new TxOutRef(new TxId(new byte[32]), BigInteger.valueOf(i + 100)),
                    new TxOut(sink, Value.lovelace(BigInteger.ONE), new OutputDatum.NoOutputDatum(), Optional.empty())));
            for (int i = 0; i < 16; i++) ctx.output(new TxOut(sink, Value.lovelace(BigInteger.valueOf(Long.MAX_VALUE)),
                    new OutputDatum.NoOutputDatum(), Optional.empty()));
            var success = assertInstanceOf(EvalResult.Success.class, eval(isCore, ctx));
            results.put(isCore ? "core" : "module", Map.of("cpu", success.consumed().cpuSteps(), "memory", success.consumed().memoryUnits()));
            assertInstanceOf(EvalResult.Failure.class, eval(isCore, ctx.output(new TxOut(sink, Value.lovelace(BigInteger.ONE),
                    new OutputDatum.NoOutputDatum(), Optional.empty()))));
        }
        results.put("scope", "Paired genesis binding at 16 inputs, 4 reference inputs, 16 outputs, 2 positive withdrawals; synthetic context, no value-conservation claim");
        Files.createDirectories(Path.of("build/phase0"));
        Files.writeString(Path.of("build/phase0/binding-budget.json"), JsonUtil.getPrettyJson(results));
    }
}
