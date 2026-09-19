package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import com.bloxbean.cardano.kavach.sdk.browser.BrowserSignatures;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Transfer dispatch retains full configuration validation and rejects lifecycle possession evidence. */
class AuthTransferDispatchTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void transfersRejectPossessionAndInvalidDefensiveConfiguration(int mode) throws Exception {
        var f = new AccountFixtures(false, mode);
        var intent = f.spend(0);
        var digest = AccountCodec.intentDigest(intent, null);
        var signatures = new ArrayList<Signature>();
        for (int id : mode == 3 ? new int[]{0, 1} : new int[]{0}) {
            int method = mode == 3 ? (id == 1 ? 2 : 1) : mode;
            signatures.add(BrowserModuleTest.proofs(f, method, digest, id).head());
        }
        var evidence = AccountFixtures.list(signatures.toArray(Signature[]::new));
        var approval = Optional.of(new Proof(BigInteger.valueOf(mode), evidence));
        var good = new ModuleRedeemer(BigInteger.ONE, intent, approval, JulcList.empty(), JulcList.empty());
        var extra = new ModuleRedeemer(BigInteger.ONE, intent, approval, evidence, JulcList.empty());
        for (var invocation : new ModuleRedeemer[]{good, extra}) {
            var context = f.context("module", intent, invocation);
            if (mode != 2) context.signer(BrowserSignatures.keyHash(AccountFixtures.publicKey(f.keys.getFirst())));
            var result = f.evaluate("module", context);
            if (invocation == good) assertInstanceOf(EvalResult.Success.class, result);
            else assertInstanceOf(EvalResult.Failure.class, result, "Transfer cannot carry configuration-possession evidence");
        }
        // The synthetic state gives spend keys cancellation authority. Even though the spend
        // signature is valid, the full independent module configuration check must reject it.
        var badConfig = mode == 3
                ? AccountAdversarialTest.field(f.state.authConfig(), AccountCodec.data(f.config.spend()), 1, 7)
                : AccountAdversarialTest.field(f.state.authConfig(), AccountCodec.data(f.config.spend()), 7);
        var invalidState = AccountAdversarialTest.field(AccountCodec.data(f.state), badConfig, 6);
        var context = f.context("module", intent, good);
        if (mode != 2) context.signer(BrowserSignatures.keyHash(AccountFixtures.publicKey(f.keys.getFirst())));
        var malformed = AccountAdversarialTest.replace(context.buildPlutusData(), AccountCodec.data(f.state), invalidState);
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("module", malformed),
                "Transfer specialization must not assume defensive configuration validity");
    }
}
