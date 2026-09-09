package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Receipt disjointness and purpose isolation through the complete transfer validators.
 */
class AccountReceiptsTest {
    private final AccountFixtures f = new AccountFixtures();

    AccountReceiptsTest() throws Exception {
    }

    JulcList<RewardReceipt> receipts(int coreIndex, int moduleIndex) throws Exception {
        var a = new RewardReceipt(f.core, BigInteger.valueOf(coreIndex));
        var b = new RewardReceipt(f.module, BigInteger.valueOf(moduleIndex));
        return Arrays.compareUnsigned(f.coreScript.getScriptHash(), f.moduleScript.getScriptHash()) < 0 ? AccountFixtures.list(a, b) : AccountFixtures.list(b, a);
    }

    ScriptContextTestBuilder context(String role, int coreIndex, int moduleIndex, long amount) throws Exception {
        var intent = f.spend(0);
        var original = f.authorization(intent);
        var auth = new ModuleRedeemer(BigInteger.ONE, intent, original.operationProof(), JulcList.empty(), receipts(coreIndex, moduleIndex));
        return f.context(role, intent, auth).withdrawal(f.core, BigInteger.valueOf(amount)).withdrawal(f.module, BigInteger.valueOf(amount))
                .output(AccountFixtures.output(f.sink, 2000000)).output(AccountFixtures.output(f.sink, 2000000));
    }

    @Test
    void positiveRewardsWithIndependentReceiptsAndTopupsPassAllScripts() throws Exception {
        for (var role : List.of("core", "module", "asset"))
            assertInstanceOf(EvalResult.Success.class, f.evaluate(role, context(role, 2, 3, 1)));
    }

    @Test
    void receiptReuseUnderpaymentAndZeroReceiptReject() throws Exception {
        for (var role : List.of("core", "module")) {
            assertInstanceOf(EvalResult.Failure.class, f.evaluate(role, context(role, 2, 2, 1)), role + " reuse");
            assertInstanceOf(EvalResult.Failure.class, f.evaluate(role, context(role, 2, 3, 2000001)), role + " underpayment");
            assertInstanceOf(EvalResult.Failure.class, f.evaluate(role, context(role, 2, 3, 0)), role + " zero receipt");
        }
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("asset", context("asset", 0, 3, 1)), "recipient overlap");
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("asset", context("asset", 1, 3, 1)), "change overlap");
    }

    @Test
    void distinctRegistrationBranchCannotDeregisterOrAuthorize() {
        for (var role : List.of("core", "module")) {
            var credential = role.equals("core") ? f.core : f.module;
            Object registration = role.equals("core") ? new CoreRegistration() : new ModuleRegistration();
            var register = new TxCert.RegStaking(credential, Optional.of(BigInteger.valueOf(2000000)));
            var ctx = ScriptContextTestBuilder.certifying(BigInteger.ZERO, register).redeemer(AccountCodec.data(registration));
            assertInstanceOf(EvalResult.Success.class, f.evaluate(role, ctx));
            assertInstanceOf(EvalResult.Failure.class, f.evaluate(role, ScriptContextTestBuilder.rewarding(credential).redeemer(AccountCodec.data(registration))));
            var deregister = new TxCert.UnRegStaking(credential, Optional.of(BigInteger.valueOf(2000000)));
            assertInstanceOf(EvalResult.Failure.class, f.evaluate(role, ScriptContextTestBuilder.certifying(BigInteger.ZERO, deregister).redeemer(AccountCodec.data(registration))));
        }
    }
}
