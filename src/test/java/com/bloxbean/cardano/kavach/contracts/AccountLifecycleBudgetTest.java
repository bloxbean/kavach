package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.ProofDomains;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import com.bloxbean.cardano.kavach.sdk.AccountAdministration;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Maximum registry, broad authority, complete new-key possession and global transaction counts. */
class AccountLifecycleBudgetTest {
    @ParameterizedTest
    @ValueSource(strings = {"config", "freeze", "unfreeze", "start", "cancel", "complete"})
    void mutationsFitCombinedLedgerLimitsWithAllowance(String operation) throws Exception {
        var f = new AccountFixtures(true);
        var destination = new AccountFixtures(true);
        var lifecycle = new AccountLifecycleTest(); lifecycle.f = f;
        var old = f.state; Action action; int[] signers;
        long lower = 86400000, upper = 86409999;
        var target = destination.state.authConfig();
        switch (operation) {
            case "config" -> { action = new ReplaceConfig(target); signers = new int[]{8,9,10,11,12,13,14,15}; }
            case "freeze" -> { action = new Freeze(); signers = new int[]{8,9,10,11,12,13,14,15}; }
            case "unfreeze" -> { old = lifecycle.state(old, 1, 0, 0, new Frozen()); action = new Unfreeze(); signers = new int[]{8,9,10,11,12,13,14,15}; }
            case "start" -> { action = new StartRecovery(BigInteger.ONE, target); signers = new int[]{0,1,2,3,4,5,6,7}; }
            case "cancel", "complete" -> {
                old = lifecycle.state(old, 1, 1, 3600000, new RecoveryPending(new byte[32], BigInteger.valueOf(lower), target));
                action = operation.equals("cancel") ? new CancelRecovery(BigInteger.ONE, new byte[32]) : new CompleteRecovery(BigInteger.ONE, target);
                signers = new int[]{8,9,10,11,12,13,14,15};
            }
            default -> throw new AssertionError(operation);
        }
        var request = lifecycle.intent(old, action, lower, upper);
        var oldApproval = lifecycle.approval(old, request, signers).operationProof();
        JulcList<Signature> possession = JulcList.empty();
        if (operation.equals("config") || operation.equals("complete")) {
            var message = operation.equals("config") ? ProofDomains.configuration(AccountCodec.data(old), AccountCodec.data(request))
                    : ProofDomains.target(AccountCodec.data(old), AccountCodec.data(request));
            var signatures = new ArrayList<Signature>();
            for (int id = 0; id < 16; id++) signatures.add(AccountFixtures.sign(id, destination.keys.get(id), WireFormat.digest(message)));
            possession = AccountFixtures.list(signatures.toArray(Signature[]::new));
            if (operation.equals("complete")) oldApproval = Optional.empty();
        }
        var coreReceipt = new RewardReceipt(f.core, BigInteger.valueOf(13));
        var moduleReceipt = new RewardReceipt(f.module, BigInteger.valueOf(15));
        var receipts = Arrays.compareUnsigned(f.coreScript.getScriptHash(), f.moduleScript.getScriptHash()) < 0
                ? AccountFixtures.list(coreReceipt, moduleReceipt) : AccountFixtures.list(moduleReceipt, coreReceipt);
        var invocation = new ModuleRedeemer(BigInteger.ONE, request, oldApproval, possession, receipts);
        var next = AccountAdministration.prepare(old, invocation, Optional.empty(), BigInteger.valueOf(lower), BigInteger.valueOf(upper), true).successor();
        long memory = 0, cpu = 0, paddedMemory = 0, paddedCpu = 0;
        for (var role : List.of("state", "core", "module")) {
            var context = lifecycle.context(role, old, next, invocation, lower, upper)
                    .withdrawal(f.core, BigInteger.ONE).withdrawal(f.module, BigInteger.ONE);
            for (int index = 0; index < 14; index++) context.input(new TxInInfo(AccountFixtures.ref(41 + index), AccountFixtures.output(f.sink, 1000000)))
                    .output(AccountFixtures.output(f.sink, index == 13 ? 1000002 : 1000000));
            for (int index = 0; index < 4; index++) context.referenceInput(new TxInInfo(AccountFixtures.ref(100 + index), AccountFixtures.output(f.sink, 1000000)));
            var evaluated = f.evaluate(role, AccountFixtures.orderedInputs(context.buildPlutusData()));
            var result = assertInstanceOf(EvalResult.Success.class, evaluated, operation + " " + role + ": " + evaluated);
            memory += result.consumed().memoryUnits(); cpu += result.consumed().cpuSteps();
            paddedMemory += (result.consumed().memoryUnits() * 105 + 99) / 100;
            paddedCpu += (result.consumed().cpuSteps() * 105 + 99) / 100;
        }
        System.out.println("Phase 2 maximum " + operation + " memory=" + memory + " cpu=" + cpu);
        assertTrue(paddedMemory <= 16500000, operation + " combined memory with 5% allowance " + memory);
        assertTrue(paddedCpu <= 10000000000L, operation + " combined CPU with 5% allowance " + cpu);
    }
}
