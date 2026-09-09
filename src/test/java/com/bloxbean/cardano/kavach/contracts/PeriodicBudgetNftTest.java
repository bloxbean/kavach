package com.bloxbean.cardano.kavach.contracts;

import static org.junit.jupiter.api.Assertions.*;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.*;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.Usage;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Creator/seed and canonical custody rejection checks against emitted counter initialization code. */
class PeriodicBudgetNftTest {
    @ParameterizedTest
    @ValueSource(strings = {"valid", "missing-creator", "wrong-seed", "wrong-address", "nonzero-usage", "double-supply", "burn", "duplicate-output"})
    void onlyCreatorBoundUnusedCounterCanBeInitialized(String scenario) throws Exception {
        var f = new AccountFixtures(false, 4);
        var script = AccountFixtures.load(PeriodicBudgetNftPolicy.class, f.seed, new byte[28], f.budgetScript.getScriptHash());
        var id = new PolicyId(script.getScriptHash());
        var address = scenario.equals("wrong-address") ? f.sink : AccountLib.enterprise(f.budgetScript.getScriptHash());
        var usage = scenario.equals("nonzero-usage") ? new Usage(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO, BigInteger.ONE) : PeriodicBudgetLib.initial();
        var quantity = BigInteger.valueOf(scenario.equals("double-supply") ? 2 : scenario.equals("burn") ? -1 : 1);
        var output = new TxOut(address, Value.lovelace(BigInteger.valueOf(3_000_000))
                .merge(Value.singleton(id, TokenName.EMPTY, quantity)), new OutputDatum.OutputDatumInline(AccountCodec.data(usage)), Optional.empty());
        var ctx = ScriptContextTestBuilder.minting(id).redeemer(PlutusData.integer(0))
                .input(new TxInInfo(scenario.equals("wrong-seed") ? AccountFixtures.ref(11) : f.seed, AccountFixtures.output(f.sink, 10_000_000)))
                .output(output).mint(Value.singleton(id, TokenName.EMPTY, quantity));
        if (!scenario.equals("missing-creator")) ctx.signer(new byte[28]);
        if (scenario.equals("duplicate-output")) ctx.output(output);
        var result = JulcVm.create("Java").evaluateWithArgs(JulcScriptAdapter.toProgram(script.getCborHex()),
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), List.of(ctx.buildPlutusData()),
                new ExBudget(1_000_000_000, 3_000_000), EvalOptions.DEFAULT);
        if (scenario.equals("valid")) assertInstanceOf(EvalResult.Success.class, result, result.toString());
        else assertInstanceOf(EvalResult.Failure.class, result, scenario);
    }
}
