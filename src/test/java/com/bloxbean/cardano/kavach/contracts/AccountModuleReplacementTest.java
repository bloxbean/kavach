package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.Interval;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.ScriptPurpose;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Module;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.ProofDomains;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import com.bloxbean.cardano.kavach.sdk.AccountAdministration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Four-script replacement composition; candidate possession never supplies old administration.
 */
class AccountModuleReplacementTest {
    private AccountLifecycleTest lifecycle;
    private AccountFixtures f;
    private PlutusV3Script candidateScript;
    private Credential candidate;
    private IntentEnvelope request;
    private AccountState successor;
    private ModuleRedeemer oldApproval;
    private ModuleRedeemer candidateApproval;
    private boolean maximumProfile;
    private Address candidateSink;

    AccountModuleReplacementTest() throws Exception {
        initialize(false);
    }

    private void initialize(boolean maximum) throws Exception {
        maximumProfile = maximum;
        f = new AccountFixtures(maximum);
        lifecycle = new AccountLifecycleTest();
        lifecycle.f = f;
        var sinkHash = new byte[28];
        sinkHash[0] = 1;
        candidateSink = new Address(new Credential.PubKeyCredential(new PubKeyHash(sinkHash)), Optional.empty());
        candidateScript = AccountFixtures.load(Ed25519Module.class, BigInteger.ONE, BigInteger.ONE,
                f.deployment, f.stateScript.getScriptHash(), f.coreScript.getScriptHash(), candidateSink);
        candidate = new Credential.ScriptCredential(new ScriptHash(candidateScript.getScriptHash()));
        var replacement = new AuthModuleRef(candidateScript.getScriptHash(), BigInteger.ONE);
        request = lifecycle.intent(f.state, new ReplaceModule(replacement, f.state.authConfig()), 1000, 9999);
        successor = new AccountState(f.state.schemaVersion(), f.state.accountId(), f.deployment, f.state.coreBinding(),
                BigInteger.ONE, replacement, f.state.authConfig(), BigInteger.ZERO, f.state.recoveryDelayMillis(),
                f.state.recoveryCooldownMillis(), BigInteger.ZERO, new Normal());
        oldApproval = lifecycle.approval(f.state, request, maximum ? new int[]{8, 9, 10, 11, 12, 13, 14, 15} : new int[]{0, 1});
        var digest = WireFormat.digest(ProofDomains.configuration(AccountCodec.data(f.state), AccountCodec.data(request)));
        var proofs = new ArrayList<Signature>();
        for (int id = 0; id < f.keys.size(); id++) proofs.add(AccountFixtures.sign(id, f.keys.get(id), digest));
        candidateApproval = new ModuleRedeemer(BigInteger.ONE, request, Optional.empty(),
                AccountFixtures.list(proofs.toArray(Signature[]::new)), JulcList.empty());
        if (maximum) {
            var entries = new ArrayList<RewardReceipt>(List.of(new RewardReceipt(f.core, BigInteger.valueOf(12)),
                    new RewardReceipt(f.module, BigInteger.valueOf(13)), new RewardReceipt(candidate, BigInteger.valueOf(15))));
            entries.sort((a, b) -> Arrays.compareUnsigned(((Credential.ScriptCredential) a.rewardCredential()).hash().hash(),
                    ((Credential.ScriptCredential) b.rewardCredential()).hash().hash()));
            var receipts = AccountFixtures.list(entries.toArray(RewardReceipt[]::new));
            oldApproval = new ModuleRedeemer(BigInteger.ONE, request, oldApproval.operationProof(), oldApproval.configPossession(), receipts);
            candidateApproval = new ModuleRedeemer(BigInteger.ONE, request, Optional.empty(), candidateApproval.configPossession(), receipts);
        }
    }

    private PlutusData context(String role, ModuleRedeemer old, ModuleRedeemer next) {
        var core = new CoreRedeemer(BigInteger.ONE, request, old.receipts());
        var builder = role.equals("state") ? ScriptContextTestBuilder.spending(f.stateRef)
                : ScriptContextTestBuilder.rewarding(role.equals("core") ? f.core : role.equals("candidate") ? candidate : f.module);
        var base = f.stateInput().resolved();
        if (maximumProfile) {
            for (int index = 0; index < 14; index++) {
                builder.input(new TxInInfo(AccountFixtures.ref(41 + index), AccountFixtures.output(f.sink, 1000000)))
                        .output(AccountFixtures.output(f.sink, 1000000));
            }
            for (int index = 0; index < 4; index++)
                builder.referenceInput(new TxInInfo(AccountFixtures.ref(100 + index),
                        AccountFixtures.output(f.sink, 1000000)));
        }
        return AccountFixtures.orderedInputs(builder.redeemer(AccountCodec.data(role.equals("candidate") ? next : role.equals("module") ? old : core))
                .input(f.stateInput()).input(new TxInInfo(AccountFixtures.ref(40), AccountFixtures.output(f.sink, 10000000)))
                .output(new TxOut(base.address(), base.value(), new OutputDatum.OutputDatumInline(AccountCodec.data(successor)), Optional.empty()))
                .output(AccountFixtures.output(maximumProfile ? candidateSink : f.sink, maximumProfile ? 9800003 : 9800000)).fee(BigInteger.valueOf(200000))
                .validRange(Interval.between(BigInteger.valueOf(1000), BigInteger.valueOf(9999)))
                .withdrawal(f.core, maximumProfile ? BigInteger.ONE : BigInteger.ZERO)
                .withdrawal(f.module, maximumProfile ? BigInteger.ONE : BigInteger.ZERO).withdrawal(candidate, maximumProfile ? BigInteger.ONE : BigInteger.ZERO)
                .redeemerEntry(new ScriptPurpose.Spending(f.stateRef), AccountCodec.data(core))
                .redeemerEntry(new ScriptPurpose.Rewarding(f.core), AccountCodec.data(core))
                .redeemerEntry(new ScriptPurpose.Rewarding(f.module), AccountCodec.data(old))
                .redeemerEntry(new ScriptPurpose.Rewarding(candidate), AccountCodec.data(next)).buildPlutusData());
    }

    private EvalResult evaluate(String role, PlutusData context) {
        if (!role.equals("candidate")) return f.evaluate(role, context);
        return JulcVm.create("Java").evaluateWithArgs(JulcScriptAdapter.toProgram(candidateScript.getCborHex()),
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), List.of(context),
                new ExBudget(10000000000L, 16500000), EvalOptions.DEFAULT);
    }

    @Test
    void maximumOldApprovalAndCandidatePossessionFitTogether() throws Exception {
        initialize(true);
        oldAdministrationAndAllCandidateKeysAuthorizeReplacement();
    }

    @Test
    void oldAdministrationAndAllCandidateKeysAuthorizeReplacement() {
        assertEquals(AccountCodec.data(successor), AccountCodec.data(AccountAdministration.prepare(f.state, oldApproval, Optional.of(candidateApproval),
                BigInteger.valueOf(1000), BigInteger.valueOf(9999), true).successor()), "SDK module successor");
        long memory = 0, cpu = 0, paddedMemory = 0, paddedCpu = 0;
        for (var role : List.of("state", "core", "module", "candidate")) {
            var evaluated = evaluate(role, context(role, oldApproval, candidateApproval));
            var accepted = assertInstanceOf(EvalResult.Success.class, evaluated, role + ": " + evaluated);
            System.out.println("replacement " + role + " " + accepted.consumed());
            memory += accepted.consumed().memoryUnits();
            cpu += accepted.consumed().cpuSteps();
            paddedMemory += (accepted.consumed().memoryUnits() * 105 + 99) / 100;
            paddedCpu += (accepted.consumed().cpuSteps() * 105 + 99) / 100;
        }
        System.out.println("Phase 2 module replacement memory=" + memory + " cpu=" + cpu);
        assertTrue(paddedMemory <= 16500000, "combined memory with 5% allowance " + memory);
        assertTrue(paddedCpu <= 10000000000L, "combined CPU with 5% allowance " + cpu);
    }

    @ParameterizedTest
    @ValueSource(strings = {"spend-only", "candidate-self-approval", "old-possession", "missing-key", "ordinary-digest", "candidate-operation-proof", "different-intent"})
    void replacementRejectsAuthorityAndPossessionSubstitution(String attack) throws Exception {
        var old = oldApproval;
        var next = candidateApproval;
        String rejectingRole = "candidate";
        switch (attack) {
            case "spend-only" -> {
                old = lifecycle.approval(f.state, request, 0);
                rejectingRole = "module";
            }
            case "candidate-self-approval" -> {
                old = candidateApproval;
                rejectingRole = "module";
            }
            case "old-possession" -> {
                old = new ModuleRedeemer(BigInteger.ONE, request, oldApproval.operationProof(), candidateApproval.configPossession(), JulcList.empty());
                rejectingRole = "module";
            }
            case "missing-key" -> next = new ModuleRedeemer(BigInteger.ONE, request, Optional.empty(),
                    AccountFixtures.list(candidateApproval.configPossession().head()), JulcList.empty());
            case "ordinary-digest" -> {
                var proofs = new ArrayList<Signature>();
                for (int id = 0; id < f.keys.size(); id++)
                    proofs.add(AccountFixtures.sign(id, f.keys.get(id), WireFormat.digest(AccountCodec.data(request))));
                next = new ModuleRedeemer(BigInteger.ONE, request, Optional.empty(), AccountFixtures.list(proofs.toArray(Signature[]::new)), JulcList.empty());
            }
            case "candidate-operation-proof" ->
                    next = new ModuleRedeemer(BigInteger.ONE, request, oldApproval.operationProof(), next.configPossession(), JulcList.empty());
            case "different-intent" ->
                    next = new ModuleRedeemer(BigInteger.ONE, lifecycle.intent(f.state, new Freeze(), 1000, 9999),
                            Optional.empty(), next.configPossession(), JulcList.empty());
            default -> throw new AssertionError(attack);
        }
        assertInstanceOf(EvalResult.Failure.class, evaluate(rejectingRole, context(rejectingRole, old, next)), attack);
        var suppliedOld = old;
        var suppliedNext = next;
        assertThrows(IllegalArgumentException.class, () -> AccountAdministration.prepare(f.state, suppliedOld,
                Optional.of(suppliedNext), BigInteger.valueOf(1000), BigInteger.valueOf(9999), true), attack);
    }
}
