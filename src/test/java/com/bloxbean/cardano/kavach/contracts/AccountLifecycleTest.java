package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.Interval;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptPurpose;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.ProofDomains;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import com.bloxbean.cardano.kavach.sdk.AccountAdministration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigInteger;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** Full immutable-state/core/current-module composition; synthetic contexts are not ledger evidence. */
class AccountLifecycleTest {
    AccountFixtures f = new AccountFixtures();
    AccountLifecycleTest() throws Exception {}

    AccountState state(AccountState original, long version, long sequence, long nextTime, AccountMode mode) {
        return new AccountState(original.schemaVersion(), original.accountId(), original.deploymentDomain(), original.coreBinding(),
                BigInteger.valueOf(version), original.authModule(), original.authConfig(), BigInteger.valueOf(sequence),
                original.recoveryDelayMillis(), original.recoveryCooldownMillis(), BigInteger.valueOf(nextTime), mode);
    }
    IntentEnvelope intent(AccountState old, Action action, long lower, long upper) {
        return new IntentEnvelope(WireFormat.protocolTag(), new IntentDomain(BigInteger.ONE, f.deployment, old.accountId(),
                old.coreBinding(), old.stateVersion(), f.stateRef), new Validity(BigInteger.valueOf(lower), BigInteger.valueOf(upper+1)), action);
    }
    ModuleRedeemer approval(AccountState old, IntentEnvelope request, int... ids) throws Exception {
        var digest = WireFormat.digest(AccountCodec.data(request));
        var proofs = new ArrayList<Signature>();
        for (int id : ids) proofs.add(AccountFixtures.sign(id,f.keys.get(id),digest));
        return new ModuleRedeemer(BigInteger.ONE,request,Optional.of(new Proof(BigInteger.ZERO,
                AccountFixtures.list(proofs.toArray(Signature[]::new)))),JulcList.empty(),JulcList.empty());
    }
    ScriptContextTestBuilder context(String role, AccountState old, AccountState next, ModuleRedeemer authorization, long lower, long upper) {
        var invocation = new CoreRedeemer(BigInteger.ONE, authorization.intent(), authorization.receipts());
        var builder = role.equals("state") ? ScriptContextTestBuilder.spending(f.stateRef)
                : ScriptContextTestBuilder.rewarding(role.equals("core") ? f.core : f.module);
        var base = f.stateInput().resolved();
        return builder.redeemer(AccountCodec.data(role.equals("module") ? authorization : invocation))
                .input(new TxInInfo(f.stateRef,new TxOut(base.address(),base.value(),new OutputDatum.OutputDatumInline(AccountCodec.data(old)),Optional.empty())))
                .input(new TxInInfo(AccountFixtures.ref(40),AccountFixtures.output(f.sink,10000000)))
                .output(new TxOut(base.address(),base.value(),new OutputDatum.OutputDatumInline(AccountCodec.data(next)),Optional.empty()))
                .output(AccountFixtures.output(f.sink,9800000)).fee(BigInteger.valueOf(200000))
                .validRange(Interval.between(BigInteger.valueOf(lower),BigInteger.valueOf(upper)))
                .withdrawal(f.core,BigInteger.ZERO).withdrawal(f.module,BigInteger.ZERO)
                .redeemerEntry(new ScriptPurpose.Spending(f.stateRef),AccountCodec.data(invocation))
                .redeemerEntry(new ScriptPurpose.Rewarding(f.core),AccountCodec.data(invocation))
                .redeemerEntry(new ScriptPurpose.Rewarding(f.module),AccountCodec.data(authorization));
    }
    @ParameterizedTest @ValueSource(strings={"config","freeze","unfreeze","start-normal","start-frozen","cancel","complete"})
    void fullCurrentModuleLifecycle(String operation) throws Exception {
        long lower=1000, upper=9999;
        var old=f.state; Action action=new Freeze(); AccountState next=state(old,1,0,0,new Frozen()); int[] signers={1};
        switch(operation) {
            case "config" -> {action=new ReplaceConfig(old.authConfig());next=state(old,1,0,0,new Normal());signers=new int[]{0,1};}
            case "unfreeze" -> {old=state(old,1,0,0,new Frozen());action=new Unfreeze();next=state(old,2,0,0,new Normal());signers=new int[]{2};}
            case "start-normal", "start-frozen" -> {
                if(operation.equals("start-frozen"))old=state(old,1,0,0,new Frozen());
                action=new StartRecovery(BigInteger.ONE,old.authConfig());
                var request=intent(old,action,lower,upper);
                var commitment=WireFormat.digest(ProofDomains.recoveryCommitment(AccountCodec.data(old),AccountCodec.data(request)));
                next=state(old,old.stateVersion().longValueExact()+1,1,upper+3600000,new RecoveryPending(commitment,BigInteger.valueOf(upper+86400000),old.authConfig()));
            }
            case "cancel", "complete" -> {
                var commitment=new byte[32];old=state(old,1,1,3600000,new RecoveryPending(commitment,BigInteger.valueOf(86400000),old.authConfig()));
                if(operation.equals("cancel")) {action=new CancelRecovery(BigInteger.ONE,commitment);next=state(old,2,1,upper+3600000,new Frozen());signers=new int[]{2};}
                else {lower=86400000;upper=86409999;action=new CompleteRecovery(BigInteger.ONE,old.authConfig());next=state(old,2,1,3600000,new Normal());}
            }
        }
        var request=intent(old,action,lower,upper);var auth=approval(old,request,signers);
        if(operation.equals("complete")) {
            var digest=WireFormat.digest(ProofDomains.target(AccountCodec.data(old),AccountCodec.data(request)));
            auth=new ModuleRedeemer(BigInteger.ONE,request,Optional.empty(),AccountFixtures.list(AccountFixtures.sign(0,f.keys.get(0),digest)),JulcList.empty());
        }
        assertEquals(AccountCodec.data(next), AccountCodec.data(AccountAdministration.prepare(old, auth, Optional.empty(),
                BigInteger.valueOf(lower), BigInteger.valueOf(upper), true).successor()), "SDK successor " + operation);
        long memory=0,cpu=0;
        for(var role:List.of("state","core","module")) {
            var evaluated=f.evaluate(role,context(role,old,next,auth,lower,upper));
            var result=assertInstanceOf(EvalResult.Success.class,evaluated,operation+" "+role+": "+evaluated);
            memory+=result.consumed().memoryUnits();cpu+=result.consumed().cpuSteps();
        }
        System.out.println("Phase 2 "+operation+" memory="+memory+" cpu="+cpu);
        assertTrue(memory<=16500000,"combined memory "+memory);assertTrue(cpu<=10000000000L,"combined cpu "+cpu);
    }
    @ParameterizedTest @ValueSource(strings={"everyday-freeze","guardian-unfreeze","guardian-cancel","old-spend-completion"})
    void wrongAuthoritiesRejectAtModule(String attack) throws Exception {
        var old=f.state; Action action=new Freeze();var next=state(old,1,0,0,new Frozen());int signer=0;
        long lower=1000,upper=9999;
        if(attack.equals("guardian-unfreeze")) {
            old=state(old,1,0,0,new Frozen());action=new Unfreeze();next=state(old,2,0,0,new Normal());signer=1;
        } else if(attack.equals("guardian-cancel")) {
            old=state(old,1,1,3600000,new RecoveryPending(new byte[32],BigInteger.valueOf(86400000),old.authConfig()));
            action=new CancelRecovery(BigInteger.ONE,new byte[32]);next=state(old,2,1,upper+3600000,new Frozen());signer=1;
        } else if(attack.equals("old-spend-completion")) {
            old=state(old,1,1,3600000,new RecoveryPending(new byte[32],BigInteger.valueOf(86400000),old.authConfig()));
            lower=86400000;upper=86409999;action=new CompleteRecovery(BigInteger.ONE,old.authConfig());next=state(old,2,1,3600000,new Normal());
        }
        var request=intent(old,action,lower,upper);var auth=approval(old,request,signer);
        assertInstanceOf(EvalResult.Failure.class,f.evaluate("module",context("module",old,next,auth,lower,upper)),attack);
        var resolvedOld = old; long start = lower, end = upper;
        assertThrows(IllegalArgumentException.class, () -> AccountAdministration.prepare(resolvedOld, auth, Optional.empty(),
                BigInteger.valueOf(start), BigInteger.valueOf(end), true), attack);
    }

    @ParameterizedTest @ValueSource(strings={"version","sequence","delay","cooldown","deadline","mode","deposit","nftless","core-binding"})
    void authorizedFreezeCannotChangeForbiddenStateFields(String attack) throws Exception {
        var old=f.state;var next=state(old,1,0,0,new Frozen());var request=intent(old,new Freeze(),1000,9999);
        var auth=approval(old,request,1);var ctx=context("state",old,next,auth,1000,9999).buildPlutusData();
        ctx=switch(attack) {
            case "version" -> AccountAdversarialTest.field(ctx,PlutusData.integer(2),0,2,0,2,0,4);
            case "sequence" -> AccountAdversarialTest.field(ctx,PlutusData.integer(1),0,2,0,2,0,7);
            case "delay" -> AccountAdversarialTest.field(ctx,PlutusData.integer(86400001),0,2,0,2,0,8);
            case "cooldown" -> AccountAdversarialTest.field(ctx,PlutusData.integer(3600001),0,2,0,2,0,9);
            case "deadline" -> AccountAdversarialTest.field(ctx,PlutusData.integer(1),0,2,0,2,0,10);
            case "mode" -> AccountAdversarialTest.field(ctx,AccountCodec.data(new Normal()),0,2,0,2,0,11);
            case "deposit" -> AccountAdversarialTest.field(ctx,f.stateInput().resolved().value().merge(
                    Value.lovelace(BigInteger.valueOf(-1))).toPlutusData(),0,2,0,1);
            case "nftless" -> AccountAdversarialTest.field(ctx,Value.lovelace(BigInteger.valueOf(10000000)).toPlutusData(),0,0,0,1,1);
            case "core-binding" -> AccountAdversarialTest.field(ctx,PlutusData.bytes(new byte[28]),0,2,0,2,0,3,2);
            default -> throw new AssertionError(attack);
        };
        assertInstanceOf(EvalResult.Failure.class,f.evaluate("state",ctx),attack);
    }
}
