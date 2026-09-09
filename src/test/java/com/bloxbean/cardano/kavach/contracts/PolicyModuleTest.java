package com.bloxbean.cardano.kavach.contracts;

import static org.junit.jupiter.api.Assertions.*;

import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.PolicyConfigCodec;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import com.bloxbean.cardano.kavach.sdk.browser.BrowserSignatures;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Compiled mixed-module composition; synthetic contexts do not establish ledger deployment.
 */
class PolicyModuleTest {
    @ParameterizedTest
    @ValueSource(strings = {"small", "strong", "missing-cose", "missing-witness", "swapped-method",
            "wrong-digest", "duplicate", "wrong-scheme", "fee-payer-only", "low-above-boundary"})
    void fixedMethodsAndAmountTier(String scenario) throws Exception {
        var f = new AccountFixtures(false, 3);
        long fee = scenario.equals("small") ? 0 : 1;
        var intent = f.spend(fee);
        byte[] digest = AccountCodec.intentDigest(intent, null);
        if (scenario.equals("wrong-digest")) digest[0] ^= 1;
        var witness = new Signature(BigInteger.ZERO, new byte[0]);
        var cose = BrowserModuleTest.proofs(f, 2, digest, 1).head();
        var signatures = AccountFixtures.list(witness, cose);
        if (List.of("small", "missing-cose", "low-above-boundary").contains(scenario))
            signatures = AccountFixtures.list(witness);
        if (scenario.equals("swapped-method"))
            signatures = AccountFixtures.list(witness, new Signature(BigInteger.ONE, new byte[0]));
        if (scenario.equals("duplicate")) signatures = AccountFixtures.list(witness, witness, cose);
        var auth = new ModuleRedeemer(BigInteger.ONE, intent,
                Optional.of(new Proof(BigInteger.valueOf(scenario.equals("wrong-scheme") ? 2 : 3), signatures)),
                JulcList.empty(), JulcList.empty());
        var ctx = f.context("module", intent, auth);
        if (!scenario.equals("missing-witness")) ctx.signer(BrowserSignatures.keyHash(
                AccountFixtures.publicKey(f.keys.get(scenario.equals("fee-payer-only") ? 2 : 0))));
        if (scenario.equals("swapped-method"))
            ctx.signer(BrowserSignatures.keyHash(AccountFixtures.publicKey(f.keys.get(1))));
        var result = f.evaluate("module", ctx);
        boolean success = scenario.equals("small") || scenario.equals("strong");
        if (success) assertInstanceOf(EvalResult.Success.class, result, result.toString());
        else assertInstanceOf(EvalResult.Failure.class, result, scenario);
        assertEquals(fee == 0 ? BigInteger.ONE : BigInteger.TWO,
                PolicyConfigCodec.spendPolicy(f.state.authConfig(), intent.action()).threshold());
    }
}
