package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class ValueAccountingProbeTest {
    private final Address account = new Address(new Credential.ScriptCredential(new ScriptHash(new byte[28])), Optional.empty());
    private final Address receiver = new Address(new Credential.PubKeyCredential(new PubKeyHash(new byte[28])), Optional.empty());
    private TxOut output(Address address, Value value) { return new TxOut(address, value, new OutputDatum.NoOutputDatum(), Optional.empty()); }
    private Value value(int nativeCount, long ada, BigInteger quantity) {
        Value value = Value.lovelace(BigInteger.valueOf(ada));
        for (int i = nativeCount - 1; i >= 0; i--) { byte[] policy = new byte[28]; policy[0] = (byte) i;
            value = value.merge(Value.singleton(new PolicyId(policy), new TokenName(new byte[32]), quantity)); }
        return value;
    }
    private PlutusData allocation(long... indices) {
        return PlutusData.constr(0, new PlutusData.ListData(Arrays.stream(indices).mapToObj(PlutusData::integer).toList()), PlutusData.integer(200000));
    }
    private ScriptContextTestBuilder context(int count, int assets) {
        var ctx = ScriptContextTestBuilder.rewarding(account.credential()).fee(BigInteger.valueOf(200000));
        for (int i = 0; i < count; i++) ctx.input(new TxInInfo(new TxOutRef(new TxId(new byte[32]), BigInteger.valueOf(i)),
                output(account, value(assets, 20_000_000, BigInteger.valueOf(Long.MAX_VALUE / 8)))));
        return ctx;
    }
    private EvalResult eval(ScriptContextTestBuilder ctx) {
        var script = JulcScriptLoader.load(ValueAccountingProbe.class, PlutusDataAdapter.toClientLib(account.toPlutusData()));
        return JulcVm.create("Java").evaluateWithArgs(JulcScriptAdapter.toProgram(script.getCborHex()),
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), List.of(ctx.buildPlutusData()), new ExBudget(10_000_000_000L, 16_500_000), EvalOptions.DEFAULT);
    }
    @Test void maximumAssetAccountingFitsLedgerBudget() throws Exception {
        var ctx = context(0, 0).redeemer(allocation(0));
        for (int i = 0; i < 8; i++) ctx.input(new TxInInfo(new TxOutRef(new TxId(new byte[32]), BigInteger.valueOf(i)),
                output(account, value(i == 0 ? 11 : 0, 20_000_000, BigInteger.valueOf(Long.MAX_VALUE / 8)))));
        for (int i = 8; i < 16; i++) ctx.input(new TxInInfo(new TxOutRef(new TxId(new byte[32]), BigInteger.valueOf(i)), output(receiver, Value.lovelace(BigInteger.ONE))));
        ctx.output(output(receiver, value(11, 159_800_000, BigInteger.valueOf(Long.MAX_VALUE / 8))));
        for (int i = 1; i < 16; i++) ctx.output(output(receiver, Value.lovelace(BigInteger.ONE)));
        var result = eval(ctx);
        if (result instanceof EvalResult.Success success) {
            Files.createDirectories(Path.of("build/phase0"));
            Files.writeString(Path.of("build/phase0/value-budget.json"), JsonUtil.getPrettyJson(Map.of("scope", "Synthetic conservation component: 8 account inputs, one dense 12-asset value and seven ADA-only values, 16 total inputs and outputs", "cpu", success.consumed().cpuSteps(), "memory", success.consumed().memoryUnits())));
        }
        assertInstanceOf(EvalResult.Success.class, result, result.toString());
    }
    @Test void balancedNativeTotalsStillRejectAggregateOverflow() {
        var ctx = context(0, 0).redeemer(allocation(0, 1));
        for (int i = 0; i < 2; i++) {
            ctx.input(new TxInInfo(new TxOutRef(new TxId(new byte[32]), BigInteger.valueOf(i)),
                    output(account, value(1, 20_000_000, BigInteger.valueOf(Long.MAX_VALUE)))));
            ctx.output(output(receiver, value(1, 19_900_000, BigInteger.valueOf(Long.MAX_VALUE))));
        }
        assertInstanceOf(EvalResult.Failure.class, eval(ctx));
    }
    @Test void consolidationHasNoRecipientsAndAllValueInChange() {
        assertInstanceOf(EvalResult.Success.class, eval(context(1, 1).redeemer(allocation()).output(output(account, value(1, 19_800_000, BigInteger.valueOf(Long.MAX_VALUE / 8))))));
    }
    private Value token(int id, long ada) {
        byte[] policy = new byte[28]; policy[0] = (byte) id;
        return Value.lovelace(BigInteger.valueOf(ada)).merge(Value.singleton(new PolicyId(policy), new TokenName(new byte[32]), BigInteger.ONE));
    }
    @Test void dispersedAssetsRecipientsAndChangeFitTogether() throws Exception {
        var ctx = context(0, 0).redeemer(allocation(0,1,2,3,4,5,6,7));
        for (int i = 0; i < 8; i++) {
            int a = (2 * i) % 11; int b = (2 * i + 1) % 11;
            // JulcMap.insert prepends; insert larger policy first to model ledger order.
            Value incoming = i < 6 ? token(Math.max(a,b), 20_000_000).merge(token(Math.min(a,b), 0)) : Value.lovelace(BigInteger.valueOf(20_000_000));
            ctx.input(new TxInInfo(new TxOutRef(new TxId(new byte[32]), BigInteger.valueOf(i)), output(account, incoming)));
        }
        for (int i = 8; i < 16; i++) ctx.input(new TxInInfo(new TxOutRef(new TxId(new byte[32]), BigInteger.valueOf(i)), output(receiver, Value.lovelace(BigInteger.ONE))));
        for (int i = 0; i < 16; i++) ctx.output(output(i < 8 ? receiver : account, i < 12 ? token(i % 11, 9_987_500) : Value.lovelace(BigInteger.valueOf(9_987_500))));
        var result = eval(ctx); var success = assertInstanceOf(EvalResult.Success.class, result, result.toString());
        Files.writeString(Path.of("build/phase0/value-dispersed-budget.json"), JsonUtil.getPrettyJson(Map.of("scope", "Synthetic 12 native entries across 8 account inputs and 16 recipient/change outputs", "cpu", success.consumed().cpuSteps(), "memory", success.consumed().memoryUnits())));
    }
    @Test void variedMaximumEntryDistributionsRemainWithinLedgerLimits() throws Exception {
        var random = new Random(113657L); long maxCpu = 0, maxMemory = 0;
        for (int trial = 0; trial < 128; trial++) {
            long[][] incoming = new long[8][11], outgoing = new long[16][11];
            for (int unit = 0; unit < 12; unit++) {
                int asset = unit % 11;
                int in = trial < 8 ? (unit + trial) % 8 : random.nextInt(8);
                int out = trial < 16 ? (unit + trial) % 16 : random.nextInt(16);
                incoming[in][asset]++; outgoing[out][asset]++;
            }
            var ctx = context(0, 0).redeemer(allocation(0,1,2,3,4,5,6,7));
            for (int i = 0; i < 16; i++) {
                ctx.input(new TxInInfo(new TxOutRef(new TxId(new byte[32]), BigInteger.valueOf(i)),
                        output(i < 8 ? account : receiver, i < 8 ? distributedValue(incoming[i], 20_000_000) : Value.lovelace(BigInteger.ONE))));
                ctx.output(output(i < 8 ? receiver : account, distributedValue(outgoing[i], 9_987_500)));
            }
            var result = eval(ctx); var success = assertInstanceOf(EvalResult.Success.class, result, "distribution " + trial + ": " + result);
            maxCpu = Math.max(maxCpu, success.consumed().cpuSteps()); maxMemory = Math.max(maxMemory, success.consumed().memoryUnits());
        }
        Files.writeString(Path.of("build/phase0/value-distribution-budget.json"), JsonUtil.getPrettyJson(Map.of(
                "scope", "128 deterministic distributions of 12 native entries across 8 account inputs and 16 recipient/change outputs; not exhaustive", "seed", 113657,
                "cpu", maxCpu, "memory", maxMemory)));
    }
    private Value distributedValue(long[] quantities, long ada) {
        Value value = Value.lovelace(BigInteger.valueOf(ada));
        for (int i = quantities.length - 1; i >= 0; i--) if (quantities[i] != 0) {
            byte[] policy = new byte[28]; policy[0] = (byte) i;
            value = value.merge(Value.singleton(new PolicyId(policy), new TokenName(new byte[32]), BigInteger.valueOf(quantities[i])));
        }
        return value;
    }
    @ParameterizedTest @ValueSource(strings={"native-leak", "native-surplus", "fee-excess", "ada-subsidy", "duplicate-recipient", "out-of-range", "recipient-is-change", "too-many-inputs", "too-many-assets", "aggregate-overflow"})
    void rejectsInvalidAccounting(String mutation) {
        int count = mutation.equals("too-many-inputs") ? 9 : mutation.equals("aggregate-overflow") ? 7 : 1;
        int assets = mutation.equals("too-many-assets") ? 12 : 1;
        var ctx = context(count, assets); long ada = 19_800_000; BigInteger quantity = BigInteger.valueOf(Long.MAX_VALUE / 8);
        if (mutation.equals("native-leak")) quantity = quantity.subtract(BigInteger.ONE);
        if (mutation.equals("native-surplus")) quantity = quantity.add(BigInteger.ONE);
        if (mutation.equals("fee-excess")) ada--;
        if (mutation.equals("ada-subsidy")) ada = 20_000_001;
        if (mutation.equals("aggregate-overflow")) { ctx.input(new TxInInfo(new TxOutRef(new TxId(new byte[32]), BigInteger.TEN), output(account, value(1, 20_000_000, BigInteger.valueOf(Long.MAX_VALUE))))); }
        var allocation = mutation.equals("duplicate-recipient") ? allocation(0,0) : mutation.equals("out-of-range") ? allocation(1) : allocation(0);
        ctx.redeemer(allocation).output(output(mutation.equals("recipient-is-change") ? account : receiver, value(assets, ada, quantity)));
        assertInstanceOf(EvalResult.Failure.class, eval(ctx));
    }
}
