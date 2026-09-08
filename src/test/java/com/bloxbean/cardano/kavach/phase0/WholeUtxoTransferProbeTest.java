package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.*;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class WholeUtxoTransferProbeTest {
    private final Address account = new Address(new Credential.ScriptCredential(new ScriptHash(new byte[28])), Optional.empty());
    private final Address recipient = new Address(new Credential.PubKeyCredential(new PubKeyHash(new byte[28])), Optional.empty());
    private final TxOutRef ref = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
    private Value value(int count, long ada) {
        Value value = Value.lovelace(BigInteger.valueOf(ada));
        for (int i = count - 1; i >= 0; i--) { byte[] name = new byte[32]; name[0] = (byte) (i >> 8); name[1] = (byte) i;
            value = value.merge(Value.singleton(new PolicyId(new byte[28]), new TokenName(name), BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))); }
        return value;
    }
    private TxOut output(Address address, Value value) { return new TxOut(address, value, new OutputDatum.NoOutputDatum(), Optional.empty()); }
    private PlutusData transfer(Value value) {
        return PlutusData.constr(0, ref.toPlutusData(), PlutusData.integer(0), recipient.toPlutusData(), PlutusData.bytes(WireFormat.ledgerValueDigest(value.toPlutusData())));
    }
    private ScriptContextTestBuilder context(Value incoming, Value outgoing) {
        return ScriptContextTestBuilder.rewarding(account.credential()).redeemer(transfer(incoming))
                .input(new TxInInfo(ref, output(account, incoming))).output(output(recipient, outgoing));
    }
    private EvalResult eval(ScriptContextTestBuilder context) {
        var script = JulcScriptLoader.load(WholeUtxoTransferProbe.class, PlutusDataAdapter.toClientLib(account.toPlutusData()));
        return JulcVm.create("Java").evaluateWithArgs(JulcScriptAdapter.toProgram(script.getCborHex()),
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), List.of(context.buildPlutusData()), new ExBudget(2_000_000_000L, 2_000_000), EvalOptions.DEFAULT);
    }
    @Test void largeWholeValueFitsWithExternalAdaTopup() throws Exception {
        int count = 1; while (Builtins.serialiseData(value(count + 1, 30_000_000).toPlutusData()).length <= 8192) count++;
        Value incoming = value(count, 30_000_000); var ctx = context(incoming, value(count, 31_000_000));
        for (int i = 1; i < 16; i++) ctx.input(new TxInInfo(new TxOutRef(new TxId(new byte[32]), BigInteger.valueOf(i)), output(recipient, Value.lovelace(BigInteger.ONE))));
        for (int i = 1; i < 16; i++) ctx.output(output(recipient, Value.lovelace(BigInteger.ONE)));
        var result = eval(ctx); var success = assertInstanceOf(EvalResult.Success.class, result, result.toString());
        Files.createDirectories(Path.of("build/phase0"));
        Files.writeString(Path.of("build/phase0/whole-utxo-budget.json"), JsonUtil.getPrettyJson(Map.of("scope", "Synthetic 8192-byte-cap whole-value stress; exceeds current ledger value-size limit", "nativeAssets", count,
                "valueDataBytes", Builtins.serialiseData(incoming.toPlutusData()).length, "cpu", success.consumed().cpuSteps(), "memory", success.consumed().memoryUnits())));
    }
    @ParameterizedTest @ValueSource(strings={"native-loss", "native-extra", "ada-loss", "digest", "input", "index", "second-account-input", "account-change", "foreign-script-input", "datum", "recipient", "extra-field", "wrong-tag", "ancillary-token", "mint", "certificate"})
    void rejectsMisallocationAndMalformedTransfers(String mutation) {
        Value incoming = value(20, 30_000_000); Value outgoing = value(mutation.equals("native-loss") ? 19 : mutation.equals("native-extra") ? 21 : 20,
                mutation.equals("ada-loss") ? 29_999_999 : 30_000_000);
        var ctx = context(incoming, outgoing); var fields = new ArrayList<>(((PlutusData.ConstrData) transfer(incoming)).fields()); int tag = 0;
        switch (mutation) {
            case "ancillary-token" -> ctx.output(output(recipient, incoming));
            case "mint" -> ctx.mint(Value.singleton(new PolicyId(new byte[28]), new TokenName(new byte[0]), BigInteger.ONE));
            case "certificate" -> ctx.certificate(new TxCert.RegStaking(recipient.credential(), Optional.empty()));
            case "digest" -> fields.set(3, PlutusData.bytes(new byte[32]));
            case "input" -> fields.set(0, new TxOutRef(new TxId(new byte[32]), BigInteger.ONE).toPlutusData());
            case "index" -> fields.set(1, PlutusData.integer(16));
            case "second-account-input" -> ctx.input(new TxInInfo(new TxOutRef(new TxId(new byte[32]), BigInteger.ONE), output(account, incoming)));
            case "account-change" -> ctx.output(output(account, Value.lovelace(BigInteger.ONE)));
            case "foreign-script-input" -> { byte[] hash = new byte[28]; hash[0] = 1;
                ctx.input(new TxInInfo(new TxOutRef(new TxId(new byte[32]), BigInteger.ONE), output(new Address(new Credential.ScriptCredential(new ScriptHash(hash)), Optional.empty()), incoming))); }
            case "datum" -> { fields.set(1, PlutusData.integer(1)); ctx.output(new TxOut(recipient, outgoing, new OutputDatum.OutputDatumInline(PlutusData.integer(1)), Optional.empty())); }
            case "recipient" -> fields.set(2, account.toPlutusData());
            case "extra-field" -> fields.add(PlutusData.integer(0));
            case "wrong-tag" -> tag = 1;
        }
        assertInstanceOf(EvalResult.Failure.class, eval(ctx.redeemer(new PlutusData.ConstrData(tag, fields))));
    }
    @Test void disposableHolderStillRequiresItsOwnAuthorityAndSpendingPurpose() {
        byte[] authority = new byte[28];
        var script = JulcScriptLoader.load(OwnedAssetFixture.class, PlutusDataAdapter.toClientLib(PlutusData.bytes(authority)));
        for (int i = 0; i < 3; i++) {
            var ctx = i == 2 ? ScriptContextTestBuilder.rewarding(account.credential()) : ScriptContextTestBuilder.spending(ref);
            if (i != 1) ctx.signer(new PubKeyHash(authority));
            ctx.redeemer(PlutusData.integer(0));
            var result = JulcVm.create("Java").evaluateWithArgs(JulcScriptAdapter.toProgram(script.getCborHex()), LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), List.of(ctx.buildPlutusData()), new ExBudget(100_000_000, 1_000_000), EvalOptions.DEFAULT);
            if (i == 0) assertInstanceOf(EvalResult.Success.class, result); else assertInstanceOf(EvalResult.Failure.class, result);
        }
    }
}
