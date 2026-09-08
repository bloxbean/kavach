package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.TxCert;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.testkit.BudgetAssertions;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import java.math.BigInteger;
import java.security.KeyPair;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WithdrawalProbeTest {
    private KeyPair key;
    private Program program;
    private final TxOutRef ref = new TxOutRef(new TxId(new byte[32]), BigInteger.ONE);
    private final Credential credential = new Credential.ScriptCredential(new ScriptHash(new byte[28]));
    private final TxOut output = new TxOut(
            new Address(new Credential.PubKeyCredential(new PubKeyHash(new byte[28])), Optional.empty()),
            Value.lovelace(BigInteger.valueOf(10_000_000)), new OutputDatum.NoOutputDatum(), Optional.empty());

    @BeforeEach
    void setUp() throws Exception {
        key = ProbeFixtures.keyPair();
        program = JulcScriptAdapter.toProgram(ProbeFixtures.script(key).getCborHex());
    }

    private static void assertRejected(EvalResult result) {
        assertInstanceOf(EvalResult.Failure.class, result, "Rejection must not be mere budget exhaustion");
    }

    private PlutusData valid() throws Exception {
        return ProbeFixtures.authorize(key, ProbeFixtures.challenge(ProbeFixtures.DOMAIN, ref));
    }

    private ScriptContextTestBuilder rewarding() {
        return ScriptContextTestBuilder.rewarding(credential)
                .withdrawal(credential, BigInteger.ZERO).input(new TxInInfo(ref, output));
    }

    private EvalResult evaluate(ScriptContextTestBuilder context, PlutusData redeemer) {
        return JulcVm.create().evaluateWithArgs(program, LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                List.of(context.redeemer(redeemer).buildPlutusData()),
                new ExBudget(100_000_000, 1_000_000), EvalOptions.DEFAULT);
    }

    @Test void validSignatureAndConsumedInputSucceedWithinBudget() throws Exception {
        var result = evaluate(rewarding(), valid());
        BudgetAssertions.assertSuccess(result);
        BudgetAssertions.assertBudgetUnder(result, 100_000_000, 1_000_000);
        System.out.println("Withdrawal probe budget: " + ((EvalResult.Success) result).consumed());
    }

    @Test void identityPublicKeyCannotForgeUniversalSignatures() {
        byte[] identity = new byte[32]; identity[0] = 1;
        program = JulcScriptAdapter.toProgram(ProbeFixtures.script(identity).getCborHex());
        byte[] signature = new byte[64]; signature[0] = 1; // R = identity, S = zero
        assertRejected(evaluate(rewarding(), PlutusData.constr(0, ProbeFixtures.challenge(ProbeFixtures.DOMAIN, ref), PlutusData.bytes(signature))));
        Arrays.fill(signature, 0, 32, (byte) 0x66); signature[0] = 0x58; signature[32] = 1; // R = basepoint, S = one
        assertRejected(evaluate(rewarding(), PlutusData.constr(0, ProbeFixtures.challenge(ProbeFixtures.DOMAIN, ref), PlutusData.bytes(signature))));
    }


    @Test @Tag("toolchainGate")
    void compilerOutputMustAcceptValidAuthorizationWithoutRewriting() throws Exception {
        program = JulcScriptAdapter.toProgram(ProbeFixtures.script(key).getCborHex());
        BudgetAssertions.assertSuccess(evaluate(rewarding(), valid()));
    }

    @Test void missingWithdrawalFailsDespiteRewardingPurposeAndValidSignature() throws Exception {
        assertRejected(evaluate(ScriptContextTestBuilder.rewarding(credential)
                .input(new TxInInfo(ref, output)), valid()));
    }

    @Test void wrongWithdrawalCredentialFails() throws Exception {
        var bytes = new byte[28];
        bytes[0] = 1;
        assertRejected(evaluate(ScriptContextTestBuilder.rewarding(credential)
                .withdrawal(new Credential.ScriptCredential(new ScriptHash(bytes)), BigInteger.ZERO)
                .input(new TxInInfo(ref, output)), valid()));
    }

    @Test void registrationCertificateCannotEnterRewardingAuthorizationBranch() throws Exception {
        for (var deposit : List.of(Optional.<BigInteger>empty(), Optional.of(BigInteger.valueOf(2_000_000)))) {
            assertRejected(evaluate(ScriptContextTestBuilder.certifying(BigInteger.ZERO,
                    new TxCert.RegStaking(credential, deposit)).input(new TxInInfo(ref, output)), valid()));
        }
    }

    @Test void thirtyTwoInputBudgetProbe() throws Exception {
        var context = rewarding();
        for (int i = 2; i <= 32; i++) {
            context.input(new TxInInfo(new TxOutRef(ref.txId(), BigInteger.valueOf(i)), output));
        }
        var result = JulcVm.create().evaluateWithArgs(program, LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                List.of(context.redeemer(valid()).buildPlutusData()), new ExBudget(300_000_000, 1_000_000), EvalOptions.DEFAULT);
        BudgetAssertions.assertSuccess(result);
        BudgetAssertions.assertBudgetUnder(result, 300_000_000, 1_000_000);
        System.out.println("32-input compiled budget: " + ((EvalResult.Success) result).consumed());
    }

    @Test void languageNeutralGoldenVectorMatchesEncodingDigestAndCompiledSignatureVerification() throws Exception {
        var vector = new Properties();
        try (var stream = getClass().getResourceAsStream("/phase0/withdrawal-v0.properties")) {
            assertNotNull(stream);
            vector.load(stream);
        }
        var hex = HexFormat.of();
        var challenge = ProbeFixtures.challenge(ProbeFixtures.DOMAIN, ref);
        assertEquals(vector.getProperty("challengeCbor"), hex.formatHex(Builtins.serialiseData(challenge)));
        assertEquals(vector.getProperty("digest"), hex.formatHex(ProbeFixtures.digest(challenge)));
        program = JulcScriptAdapter.toProgram(
                ProbeFixtures.script(hex.parseHex(vector.getProperty("publicKey"))).getCborHex());
        var authorization = PlutusData.constr(0, challenge, PlutusData.bytes(hex.parseHex(vector.getProperty("signature"))));
        BudgetAssertions.assertSuccess(evaluate(rewarding(), authorization));
    }

    @Test void wrongKeyFails() throws Exception {
        assertRejected(evaluate(rewarding(), ProbeFixtures.authorize(
                ProbeFixtures.keyPair(), ProbeFixtures.challenge(ProbeFixtures.DOMAIN, ref))));
    }

    @Test void signedWrongDomainFails() throws Exception {
        assertRejected(evaluate(rewarding(), ProbeFixtures.authorize(key,
                ProbeFixtures.challenge(new byte[32], ref))));
    }

    @Test void changedIndexFails() throws Exception {
        assertRejected(evaluate(rewarding(), ProbeFixtures.authorize(key,
                ProbeFixtures.challenge(ProbeFixtures.DOMAIN, new TxOutRef(ref.txId(), BigInteger.TWO)))));
    }

    @Test void absentInputFails() throws Exception {
        assertRejected(evaluate(ScriptContextTestBuilder.rewarding(credential), valid()));
    }

    @Test void referenceInputDoesNotPreventReplay() throws Exception {
        assertRejected(evaluate(ScriptContextTestBuilder.rewarding(credential)
                .referenceInput(new TxInInfo(ref, output)), valid()));
    }

    @Test void truncatedSignatureFails() {
        assertRejected(evaluate(rewarding(), PlutusData.constr(0,
                ProbeFixtures.challenge(ProbeFixtures.DOMAIN, ref), PlutusData.bytes(new byte[63]))));
    }

    @Test void malformedRedeemerFails() {
        assertRejected(evaluate(rewarding(), PlutusData.integer(0)));
    }

    @Test void wrongAuthorizationConstructorFails() throws Exception {
        var good = (PlutusData.ConstrData) valid();
        assertRejected(evaluate(rewarding(), new PlutusData.ConstrData(1, good.fields())));
    }

    @Test void extraAuthorizationFieldFails() throws Exception {
        var good = (PlutusData.ConstrData) valid();
        assertRejected(evaluate(rewarding(), PlutusData.constr(0,
                good.fields().get(0), good.fields().get(1), PlutusData.integer(0))));
    }

    @Test void correctlySignedWrongChallengeConstructorFails() throws Exception {
        var challenge = (PlutusData.ConstrData) ProbeFixtures.challenge(ProbeFixtures.DOMAIN, ref);
        assertRejected(evaluate(rewarding(), ProbeFixtures.authorize(key,
                new PlutusData.ConstrData(1, challenge.fields()))));
    }

    @Test void correctlySignedExtraChallengeFieldFails() throws Exception {
        assertRejected(evaluate(rewarding(), ProbeFixtures.authorize(key,
                PlutusData.constr(0, PlutusData.bytes(ProbeFixtures.DOMAIN), ref.toPlutusData(), PlutusData.integer(0)))));
    }


    @Test void spendingPurposeFailsEvenWithValidAuthorization() throws Exception {
        assertRejected(evaluate(ScriptContextTestBuilder.spending(ref)
                .input(new TxInInfo(ref, output)), valid()));
    }

    @Test void mintingPurposeFailsEvenWithValidAuthorization() throws Exception {
        assertRejected(evaluate(ScriptContextTestBuilder.minting(new PolicyId(new byte[28]))
                .input(new TxInInfo(ref, output)), valid()));
    }

    @Test void deregistrationFailsEvenWithValidAuthorization() throws Exception {
        assertRejected(evaluate(ScriptContextTestBuilder.certifying(BigInteger.ZERO,
                new TxCert.UnRegStaking(credential, Optional.empty())).input(new TxInInfo(ref, output)), valid()));
    }
}
