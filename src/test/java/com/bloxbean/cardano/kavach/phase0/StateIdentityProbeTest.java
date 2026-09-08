package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import java.util.Properties;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.DatumHash;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.StakingCredential;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StateIdentityProbeTest {
    StateIdentityProbeTest() throws Exception {}
    private final byte[] creator = new byte[28];
    private final TxOutRef seed = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
    private final TxOutRef stateRef = new TxOutRef(new TxId(new byte[32]), BigInteger.ONE);
    private final Credential key = new Credential.PubKeyCredential(new PubKeyHash(creator));
    private final byte[] holderHash = StateProbeFixtures.holder().getScriptHash();
    private final Address stateAddress = new Address(new Credential.ScriptCredential(new ScriptHash(holderHash)), Optional.empty());
    private final Program mint = JulcScriptAdapter.toProgram(StateProbeFixtures.mint(seed, creator, holderHash).getCborHex());
    private final PolicyId policy = new PolicyId(StateProbeFixtures.mint(seed, creator, holderHash).getScriptHash());
    private final Program reader = JulcScriptAdapter.toProgram(StateProbeFixtures.reader(policy.hash(), holderHash, creator).getCborHex());
    private final Credential reward = new Credential.ScriptCredential(new ScriptHash(new byte[28]));

    private Value nft(long quantity) { return Value.singleton(policy, TokenName.EMPTY, BigInteger.valueOf(quantity)); }
    private TxOut state() {
        return new TxOut(stateAddress, Value.lovelace(BigInteger.valueOf(4_000_000)).merge(nft(1)),
                new OutputDatum.OutputDatumInline(StateProbeFixtures.state(creator)), Optional.empty());
    }
    private TxInInfo seedInput(Credential credential) {
        return new TxInInfo(seed, new TxOut(new Address(credential, Optional.empty()),
                Value.lovelace(BigInteger.valueOf(20_000_000)), new OutputDatum.NoOutputDatum(), Optional.empty()));
    }
    private ScriptContextTestBuilder mintContext() {
        return ScriptContextTestBuilder.minting(policy).signer(creator).input(seedInput(key)).mint(nft(1));
    }
    private ScriptContextTestBuilder readerContext() {
        return ScriptContextTestBuilder.rewarding(reward).withdrawal(reward, BigInteger.ZERO).signer(creator);
    }
    private EvalResult eval(Program program, ScriptContextTestBuilder context) {
        return JulcVm.create("Java").evaluateWithArgs(program, LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                List.of(context.redeemer(PlutusData.integer(0)).buildPlutusData()), new ExBudget(500_000_000, 2_000_000), EvalOptions.DEFAULT);
    }
    private void rejects(Program program, ScriptContextTestBuilder context) {
        assertInstanceOf(EvalResult.Failure.class, eval(program, context), "Must reject, not exhaust the budget");
    }
    @Test void genesisEncodingMatchesLanguageNeutralFixture() throws Exception {
        var fixture = new Properties();
        try (var stream = getClass().getResourceAsStream("/phase0/state-identity-v0.properties")) {
            assertNotNull(stream);
            fixture.load(stream);
        }
        assertEquals(fixture.getProperty("stateDatumCbor"),
                PlutusDataAdapter.toClientLib(StateProbeFixtures.state(creator)).serializeToHex());
    }
    @Test void genuineCreationAndReferenceSucceedWithinBudget() {
        var mintResult = eval(mint, mintContext().output(state()));
        var m = assertInstanceOf(EvalResult.Success.class, mintResult, mintResult.toString());
        var readerResult = eval(reader, readerContext().referenceInput(new TxInInfo(stateRef, state())));
        var r = assertInstanceOf(EvalResult.Success.class, readerResult, readerResult.toString());
        System.out.println("Identity mint budget: " + m.consumed() + "; reference budget: " + r.consumed());
    }
    @ParameterizedTest
    @ValueSource(strings = {"no-seed", "reference-seed", "wrong-seed-owner", "no-creator", "burn", "two", "extra-name", "extra-policy", "wrong-purpose"})
    void rejectsUnauthorizedOrNonUniqueMint(String mutation) {
        var ctx = mintContext();
        switch (mutation) {
            case "no-seed" -> ctx = ScriptContextTestBuilder.minting(policy).signer(creator).mint(nft(1));
            case "reference-seed" -> ctx = ScriptContextTestBuilder.minting(policy).signer(creator).mint(nft(1)).referenceInput(seedInput(key));
            case "wrong-seed-owner" -> ctx = ScriptContextTestBuilder.minting(policy).signer(creator).mint(nft(1)).input(seedInput(stateAddress.credential()));
            case "no-creator" -> ctx = ScriptContextTestBuilder.minting(policy).input(seedInput(key)).mint(nft(1));
            case "burn" -> ctx.mint(nft(-1));
            case "two" -> ctx.mint(nft(2));
            case "extra-name" -> ctx.mint(nft(1).merge(Value.singleton(policy, new TokenName(new byte[]{1}), BigInteger.ONE)));
            case "extra-policy" -> ctx.mint(nft(1).merge(Value.singleton(new PolicyId(new byte[28]), TokenName.EMPTY, BigInteger.ONE)));
            case "wrong-purpose" -> ctx = readerContext().input(seedInput(key)).mint(nft(1));
            default -> throw new AssertionError(mutation);
        }
        rejects(mint, ctx.output(state()));
    }
    private TxOut mutate(String mutation) {
        var out = state();
        var address = out.address(); var value = out.value(); var datum = out.datum(); var refScript = out.referenceScript();
        switch (mutation) {
            case "key-address" -> address = new Address(key, Optional.empty());
            case "wrong-script" -> address = new Address(reward, Optional.empty());
            case "stake-credential" -> address = new Address(stateAddress.credential(), Optional.of(new StakingCredential.StakingHash(key)));
            case "no-nft" -> value = Value.lovelace(BigInteger.valueOf(4_000_000));
            case "wrong-policy" -> value = Value.lovelace(BigInteger.valueOf(4_000_000)).merge(Value.singleton(new PolicyId(new byte[28]), TokenName.EMPTY, BigInteger.ONE));
            case "wrong-name" -> value = Value.lovelace(BigInteger.valueOf(4_000_000)).merge(Value.singleton(policy, new TokenName(new byte[]{1}), BigInteger.ONE));
            case "two-nfts" -> value = value.merge(nft(1));
            case "extra-token" -> value = value.merge(Value.singleton(policy, new TokenName(new byte[]{1}), BigInteger.ONE));
            case "zero-ada" -> value = nft(1);
            case "no-datum" -> datum = new OutputDatum.NoOutputDatum();
            case "datum-hash" -> datum = new OutputDatum.OutputDatumHash(new DatumHash(new byte[32]));
            case "wrong-schema" -> datum = new OutputDatum.OutputDatumInline(PlutusData.constr(0, PlutusData.integer(1), PlutusData.bytes(StateProbeFixtures.DOMAIN), PlutusData.bytes(creator)));
            case "wrong-domain" -> datum = new OutputDatum.OutputDatumInline(PlutusData.constr(0, PlutusData.integer(0), PlutusData.bytes(new byte[0]), PlutusData.bytes(creator)));
            case "wrong-creator" -> datum = new OutputDatum.OutputDatumInline(PlutusData.constr(0, PlutusData.integer(0), PlutusData.bytes(StateProbeFixtures.DOMAIN), PlutusData.bytes(new byte[0])));
            case "extra-field" -> datum = new OutputDatum.OutputDatumInline(PlutusData.constr(0, PlutusData.integer(0), PlutusData.bytes(StateProbeFixtures.DOMAIN), PlutusData.bytes(creator), PlutusData.integer(0)));
            case "wrong-constructor" -> datum = new OutputDatum.OutputDatumInline(PlutusData.constr(1, PlutusData.integer(0), PlutusData.bytes(StateProbeFixtures.DOMAIN), PlutusData.bytes(creator)));
            case "reference-script" -> refScript = Optional.of(new ScriptHash(holderHash));
            default -> throw new AssertionError(mutation);
        }
        return new TxOut(address, value, datum, refScript);
    }
    @ParameterizedTest
    @ValueSource(strings = {"key-address", "wrong-script", "stake-credential", "no-nft", "wrong-policy", "wrong-name", "two-nfts", "extra-token", "zero-ada", "no-datum", "datum-hash", "wrong-schema", "wrong-domain", "wrong-creator", "extra-field", "wrong-constructor", "reference-script"})
    void rejectsMalformedCreationAndSpoofedReference(String mutation) {
        rejects(mint, mintContext().output(mutate(mutation)));
        rejects(reader, readerContext().referenceInput(new TxInInfo(stateRef, mutate(mutation))));
    }
    @Test void rejectsDuplicateStateOutputsAndReferences() {
        rejects(mint, mintContext().output(state()).output(state()));
        rejects(reader, readerContext().referenceInput(new TxInInfo(stateRef, state())).referenceInput(new TxInInfo(seed, state())));
    }
    @Test void consumedStateDoesNotSubstituteForReference() { rejects(reader, readerContext().input(new TxInInfo(stateRef, state()))); }
    @Test void genuineReferenceDoesNotAuthorizeMissingCreator() {
        rejects(reader, ScriptContextTestBuilder.rewarding(reward).withdrawal(reward, BigInteger.ZERO).referenceInput(new TxInInfo(stateRef, state())));
    }
    @Test void readerRejectsMintAndWrongPurpose() {
        rejects(reader, readerContext().mint(nft(1)).referenceInput(new TxInInfo(stateRef, state())));
        rejects(reader, ScriptContextTestBuilder.spending(seed).signer(creator).referenceInput(new TxInInfo(stateRef, state())));
    }
    @Test void stateHolderRejectsSpendingAndNftlessDeposits() {
        var holder = JulcScriptAdapter.toProgram(StateProbeFixtures.holder().getCborHex());
        rejects(holder, ScriptContextTestBuilder.spending(stateRef, StateProbeFixtures.state(creator)).input(new TxInInfo(stateRef, state())));
        rejects(holder, ScriptContextTestBuilder.spending(stateRef, StateProbeFixtures.state(creator)).input(new TxInInfo(stateRef, mutate("no-nft"))));
    }
}
