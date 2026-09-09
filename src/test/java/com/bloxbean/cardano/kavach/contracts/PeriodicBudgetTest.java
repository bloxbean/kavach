package com.bloxbean.cardano.kavach.contracts;

import static org.junit.jupiter.api.Assertions.*;

import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.Usage;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import com.bloxbean.cardano.kavach.sdk.browser.BrowserSignatures;
import java.math.BigInteger;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Compiled full-composition counter checks; actual node acceptance is a separate gate. */
class PeriodicBudgetTest {
    @ParameterizedTest
    @ValueSource(strings = {"initial", "carry", "reset", "over-limit", "wrong-counter", "drop-counter",
            "reset-same-window", "wrong-next-debit", "cross-window", "inclusive-boundary", "counter-ada-loss", "boundary", "weekly-initial", "weekly-carry", "weekly-reset", "weekly-boundary", "weekly-cross-window", "weekly-inclusive-boundary", "maximum", "native", "whole", "whole-over-limit"})
    void counterCannotBeSkippedResetOrOverdrawn(String scenario) throws Exception {
        boolean weekly = scenario.startsWith("weekly-");
        if (weekly) scenario = scenario.substring(7);
        BigInteger period = weekly ? BigInteger.TWO : BigInteger.ONE;
        long duration = weekly ? 604_800_000 : 86_400_000;
        boolean maximum = scenario.equals("maximum");
        boolean whole = scenario.startsWith("whole");
        boolean nativeAsset = whole || scenario.equals("native");
        var f = new AccountFixtures(maximum, 4, period);
        long start = Instant.parse("2026-09-07T00:00:00Z").toEpochMilli();
        long lower = start + 1_000, upper = start + 10_000;
        if (List.of("cross-window", "inclusive-boundary", "boundary").contains(scenario)) {
            lower = start + duration - 1000; upper = start + duration;
        }
        long carried = scenario.equals("carry") ? 2_000_000 : scenario.equals("over-limit") || scenario.equals("reset-same-window") ? 4_000_000 : 0;
        var previous = scenario.equals("initial") ? PeriodicBudgetLib.initial()
                : new Usage(BigInteger.ONE, period, BigInteger.valueOf(scenario.equals("reset") ? start - duration : start), BigInteger.valueOf(carried));
        var successor = new Usage(BigInteger.ONE, period, BigInteger.valueOf(start),
                BigInteger.valueOf(scenario.equals("reset-same-window") ? 2_000_000 : scenario.equals("wrong-next-debit") ? 1_000_000 : carried + 2_000_000));
        byte[] nativePolicy = new byte[28]; Arrays.fill(nativePolicy, (byte) 85);
        var tokenValue = Value.singleton(new PolicyId(nativePolicy), TokenName.EMPTY, BigInteger.ONE);
        long accountAda = whole ? scenario.equals("whole-over-limit") ? 6_000_000 : 2_000_000 : 10_000_000;
        var inputValue = Value.lovelace(BigInteger.valueOf(accountAda));
        var recipientValue = Value.lovelace(BigInteger.valueOf(whole ? accountAda : 2_000_000));
        if (nativeAsset) { inputValue = inputValue.merge(tokenValue); recipientValue = recipientValue.merge(tokenValue); }
        var original = f.spend(0);
        if (maximum) {
            var refs = new ArrayList<TxOutRef>(); refs.add(f.assetRef);
            for (int i = 32; i <= 38; i++) refs.add(AccountFixtures.ref(i));
            var spend = (Spend) original.action();
            original = new IntentEnvelope(original.protocolTag(), original.domain(), original.validity(),
                    new Spend(AccountFixtures.list(refs.toArray(TxOutRef[]::new)), spend.recipients(), spend.maxAccountFee()));
        }
        Action action = original.action();
        if (whole) action = new TransferWholeUtxo(f.assetRef, BigInteger.ZERO, f.sink,
                WireFormat.ledgerValueDigest(inputValue.toPlutusData()));
        else if (nativeAsset) action = new Spend(AccountFixtures.list(f.assetRef), AccountFixtures.list(new Recipient(BigInteger.ZERO, f.sink,
                AccountFixtures.list(new Asset(new byte[0], new byte[0], BigInteger.valueOf(2_000_000)), new Asset(nativePolicy, new byte[0], BigInteger.ONE)))), BigInteger.ZERO);
        var intent = new IntentEnvelope(original.protocolTag(), original.domain(),
                new Validity(BigInteger.valueOf(lower), BigInteger.valueOf(upper + 2)), action);
        var digest = AccountCodec.intentDigest(intent, whole ? inputValue.toPlutusData() : null);
        var proofs = new ArrayList<Signature>();
        for (int id = 0; id < (maximum ? 8 : nativeAsset ? 2 : 1); id++) proofs.add(id == 1
                ? BrowserModuleTest.proofs(f, 2, digest, id).head() : new Signature(BigInteger.valueOf(id), new byte[0]));
        var module = new ModuleRedeemer(BigInteger.ONE, intent, Optional.of(new Proof(BigInteger.valueOf(4),
                AccountFixtures.list(proofs.toArray(Signature[]::new)))), JulcList.empty(), JulcList.empty());
        var core = new CoreRedeemer(BigInteger.ONE, intent, JulcList.empty());
        var spending = new AssetRedeemer(BigInteger.ONE, digest);
        var counterRef = AccountFixtures.ref(31);
        byte[] token = f.budgetId.policy().clone();
        if (scenario.equals("wrong-counter")) token[0] = 1;
        var value = Value.lovelace(BigInteger.valueOf(3_000_000)).merge(Value.singleton(new PolicyId(token), TokenName.EMPTY, BigInteger.ONE));
        var address = AccountLib.enterprise(f.budgetScript.getScriptHash());
        long memory = 0, cpu = 0;
        var roles = new ArrayList<>(List.of("asset", "core", "module", "budget"));
        if (maximum) for (int i = 32; i <= 38; i++) roles.add("asset" + i);
        for (String role : roles) {
            var ctx = role.startsWith("asset") || role.equals("budget")
                    ? ScriptContextTestBuilder.spending(role.equals("asset") ? f.assetRef : role.startsWith("asset") ? AccountFixtures.ref(Integer.parseInt(role.substring(5))) : counterRef)
                    : ScriptContextTestBuilder.rewarding(role.equals("core") ? f.core : f.module);
            ctx.redeemer(AccountCodec.data(role.equals("core") ? core : role.equals("module") ? module : spending))
                    .input(new TxInInfo(f.assetRef, new TxOut(f.accountAddress, inputValue, new OutputDatum.NoOutputDatum(), Optional.empty())))
                    .referenceInput(f.stateInput()).output(new TxOut(f.sink, recipientValue, new OutputDatum.NoOutputDatum(), Optional.empty()))
                    .fee(BigInteger.valueOf(200_000))
                    .validRange(new Interval(new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(lower)), true),
                            new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(scenario.equals("cross-window") ? upper + 1 : upper)), scenario.equals("inclusive-boundary"))))
                    .withdrawal(f.core, BigInteger.ZERO).withdrawal(f.module, BigInteger.ZERO)
                    .redeemerEntry(new ScriptPurpose.Rewarding(f.core), AccountCodec.data(core))
                    .redeemerEntry(new ScriptPurpose.Rewarding(f.module), AccountCodec.data(module))
                    .redeemerEntry(new ScriptPurpose.Spending(f.assetRef), AccountCodec.data(spending))
                    .redeemerEntry(new ScriptPurpose.Spending(counterRef), AccountCodec.data(spending))
                    .signer(BrowserSignatures.keyHash(AccountFixtures.publicKey(f.keys.get(0))));
            if (!whole) ctx.output(AccountFixtures.output(f.accountAddress, maximum ? 78_000_000 : 8_000_000));
            if (maximum) {
                for (int i = 32; i <= 38; i++) ctx.input(new TxInInfo(AccountFixtures.ref(i), AccountFixtures.output(f.accountAddress, 10_000_000)))
                        .redeemerEntry(new ScriptPurpose.Spending(AccountFixtures.ref(i)), AccountCodec.data(spending));
                for (int id = 2; id < 8; id++) ctx.signer(BrowserSignatures.keyHash(AccountFixtures.publicKey(f.keys.get(id))));
            }
            if (!scenario.equals("drop-counter")) {
                ctx.input(new TxInInfo(counterRef, new TxOut(address, value,
                        new OutputDatum.OutputDatumInline(AccountCodec.data(previous)), Optional.empty())));
                var nextValue = scenario.equals("counter-ada-loss") ? Value.lovelace(BigInteger.valueOf(2_000_000))
                        .merge(Value.singleton(new PolicyId(token), TokenName.EMPTY, BigInteger.ONE)) : value;
                ctx.output(new TxOut(address, nextValue, new OutputDatum.OutputDatumInline(AccountCodec.data(successor)), Optional.empty()));
            }
            var result = f.evaluate(role.startsWith("asset") ? "asset" : role, AccountFixtures.orderedInputs(ctx.buildPlutusData()));
            boolean accepted = List.of("initial", "carry", "reset", "boundary", "maximum", "native", "whole").contains(scenario);
            if (accepted) {
                var success = assertInstanceOf(EvalResult.Success.class, result, role + ": " + result);
                memory += success.consumed().memoryUnits(); cpu += success.consumed().cpuSteps();
            } else if (role.equals("budget") || (role.equals("asset") && List.of("drop-counter", "wrong-counter").contains(scenario)))
                assertInstanceOf(EvalResult.Failure.class, result, role + ": " + scenario);
        }
        assertTrue(memory * 1.05 < 16_500_000, "Combined memory allowance: " + memory);
        assertTrue(cpu * 1.05 < 10_000_000_000L, "Combined CPU allowance");
    }
}
