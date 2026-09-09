package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.DatumHash;
import com.bloxbean.cardano.julc.ledger.Delegatee;
import com.bloxbean.cardano.julc.ledger.GovernanceAction;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.ProposalProcedure;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.StakingCredential;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.TxCert;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.ledger.Voter;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RewardLifecycleProbeTest {
    private final byte[] authority = new byte[28];
    private final Credential key = new Credential.PubKeyCredential(new PubKeyHash(authority));
    private final Credential script = new Credential.ScriptCredential(new ScriptHash(new byte[28]));
    private final Address sink = new Address(key, Optional.empty());
    private final BigInteger deposit = BigInteger.valueOf(2_000_000);
    private final Program program = JulcScriptAdapter.toProgram(JulcScriptLoader.load(RewardLifecycleProbe.class,
            PlutusDataAdapter.toClientLib(PlutusData.bytes(authority)),
            PlutusDataAdapter.toClientLib(sink.toPlutusData()),
            PlutusDataAdapter.toClientLib(PlutusData.integer(deposit))).getCborHex());

    private ScriptContextTestBuilder reward(long amount) {
        return ScriptContextTestBuilder.rewarding(script).withdrawal(script, BigInteger.valueOf(amount)).signer(authority);
    }

    private TxOut receipt(long amount) {
        return new TxOut(sink, Value.lovelace(BigInteger.valueOf(amount)), new OutputDatum.NoOutputDatum(), Optional.empty());
    }

    private EvalResult eval(ScriptContextTestBuilder ctx, long index) {
        return JulcVm.create("Java").evaluateWithArgs(program, LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                List.of(ctx.redeemer(PlutusData.integer(index)).buildPlutusData()), new ExBudget(500_000_000, 2_000_000), EvalOptions.DEFAULT);
    }

    private void rejects(ScriptContextTestBuilder ctx, long index) {
        assertInstanceOf(EvalResult.Failure.class, eval(ctx, index));
    }

    @Test
    void scalarRedeemersMatchCanonicalCborVectors() {
        assertEquals("20", PlutusDataAdapter.toClientLib(PlutusData.integer(-1)).serializeToHex());
        assertEquals("00", PlutusDataAdapter.toClientLib(PlutusData.integer(0)).serializeToHex());
        assertEquals("01", PlutusDataAdapter.toClientLib(PlutusData.integer(1)).serializeToHex());
    }

    @Test
    void acceptsExplicitRegistrationWithoutAuthoritySignature() {
        var cert = new TxCert.RegStaking(script, Optional.of(deposit));
        assertInstanceOf(EvalResult.Success.class, eval(ScriptContextTestBuilder.certifying(BigInteger.ZERO, cert), -1));
    }

    @Test
    void acceptsZeroWithoutReceipt() {
        assertInstanceOf(EvalResult.Success.class, eval(reward(0), -1));
    }

    @Test
    void acceptsFullPositiveReceiptAndExternalMinAdaTopup() {
        var exact = assertInstanceOf(EvalResult.Success.class, eval(reward(4_000_000).output(receipt(4_000_000)), 0));
        assertInstanceOf(EvalResult.Success.class, eval(reward(1).output(receipt(2_000_000)), 0));
        System.out.println("Positive reward disposition budget: " + exact.consumed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong-credential", "no-signer", "negative", "underpay", "no-output", "bad-index", "negative-index", "extra-withdrawal", "wrong-address", "stake-credential", "token", "datum", "reference-script", "datum-hash", "zero-index", "huge-index"})
    void rejectsUnsafeWithdrawal(String mutation) {
        var ctx = reward(4_000_000);
        var out = receipt(4_000_000);
        long index = 0;
        switch (mutation) {
            case "missing" -> ctx = ScriptContextTestBuilder.rewarding(script).signer(authority);
            case "wrong-credential" ->
                    ctx = ScriptContextTestBuilder.rewarding(script).withdrawal(key, BigInteger.ONE).signer(authority);
            case "no-signer" ->
                    ctx = ScriptContextTestBuilder.rewarding(script).withdrawal(script, BigInteger.valueOf(4_000_000));
            case "negative" -> ctx = reward(-1);
            case "underpay" -> out = receipt(3_999_999);
            case "no-output" -> {
                rejects(ctx, 0);
                return;
            }
            case "bad-index" -> index = 1;
            case "huge-index" -> index = Long.MAX_VALUE;
            case "negative-index" -> index = -1;
            case "extra-withdrawal" -> ctx.withdrawal(key, BigInteger.ONE);
            case "wrong-address" ->
                    out = new TxOut(new Address(script, Optional.empty()), out.value(), out.datum(), Optional.empty());
            case "stake-credential" ->
                    out = new TxOut(new Address(key, Optional.of(new StakingCredential.StakingHash(key))), out.value(), out.datum(), Optional.empty());
            case "token" ->
                    out = new TxOut(sink, out.value().merge(Value.singleton(new PolicyId(new byte[28]), TokenName.EMPTY, BigInteger.ONE)), out.datum(), Optional.empty());
            case "datum-hash" ->
                    out = new TxOut(sink, out.value(), new OutputDatum.OutputDatumHash(new DatumHash(new byte[32])), Optional.empty());
            case "datum" ->
                    out = new TxOut(sink, out.value(), new OutputDatum.OutputDatumInline(PlutusData.integer(0)), Optional.empty());
            case "reference-script" ->
                    out = new TxOut(sink, out.value(), out.datum(), Optional.of(new ScriptHash(new byte[28])));
            case "zero-index" -> ctx = reward(0);
            default -> throw new AssertionError(mutation);
        }
        rejects(ctx.output(out), index);
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "wrong-deposit", "key-credential", "deregister", "delegate", "register-delegate", "wrong-redeemer"})
    void rejectsUnsupportedCertificate(String mutation) {
        TxCert cert = new TxCert.RegStaking(script, Optional.of(deposit));
        long index = -1;
        switch (mutation) {
            case "legacy" -> cert = new TxCert.RegStaking(script, Optional.empty());
            case "wrong-deposit" -> cert = new TxCert.RegStaking(script, Optional.of(BigInteger.ONE));
            case "key-credential" -> cert = new TxCert.RegStaking(key, Optional.of(deposit));
            case "deregister" -> cert = new TxCert.UnRegStaking(script, Optional.of(deposit));
            case "delegate" ->
                    cert = new TxCert.DelegStaking(script, new Delegatee.Stake(new PubKeyHash(new byte[28])));
            case "register-delegate" ->
                    cert = new TxCert.RegDeleg(script, new Delegatee.Stake(new PubKeyHash(new byte[28])), deposit);
            case "wrong-redeemer" -> index = 0;
            default -> throw new AssertionError(mutation);
        }
        rejects(ScriptContextTestBuilder.certifying(BigInteger.ZERO, cert), index);
    }

    @Test
    void rejectsOtherPurposesThroughCompiledWrapper() {
        rejects(ScriptContextTestBuilder.voting(new Voter.DRepVoter(script)), -1);
        rejects(ScriptContextTestBuilder.proposing(BigInteger.ZERO,
                new ProposalProcedure(deposit, key, new GovernanceAction.NoConfidence(Optional.empty()))), -1);
        rejects(ScriptContextTestBuilder.minting(new PolicyId(new byte[28])), -1);
        rejects(ScriptContextTestBuilder.spending(new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO)), -1);
    }
}
