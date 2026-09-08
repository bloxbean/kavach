package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Lib.Ed25519Config;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Lib.KeyEntry;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.ProofDomains;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import com.bloxbean.cardano.kavach.sdk.AccountAdministration;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Reusing an old credential ID with new public bytes must require the new holder's separate proof. */
class AccountNewKeyPossessionTest {
    private final AccountLifecycleTest lifecycle = new AccountLifecycleTest();
    private final AccountFixtures f = lifecycle.f;
    AccountNewKeyPossessionTest() throws Exception {}

    @ParameterizedTest
    @CsvSource({"config,valid", "config,missing", "config,old-key", "config,ordinary-message", "config,retained-extra",
            "recovery,valid", "recovery,missing", "recovery,old-key", "recovery,ordinary-message", "recovery,retained-extra", "config,duplicate", "config,descending", "recovery,duplicate", "recovery,descending"})
    void introducedPublicKeyRequiresPossessionUnderTheCorrectDomain(String operation, String evidenceCase) throws Exception {
        var replacementKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var config = new Ed25519Config(BigInteger.ONE, AccountFixtures.list(new KeyEntry(BigInteger.ZERO,
                AccountFixtures.publicKey(replacementKey)), f.config.keys().get(1), f.config.keys().get(2)),
                f.config.spend(), f.config.admin(), f.config.freeze(), f.config.unfreeze(), f.config.recovery(), f.config.cancel());
        var target = AccountCodec.data(config);
        var old = f.state;
        long lower = 1000, upper = 9999;
        if (operation.equals("recovery")) {
            lower = 86400000; upper = 86409999;
            old = lifecycle.state(old, 1, 1, 3600000, new RecoveryPending(new byte[32], BigInteger.valueOf(86400000), target));
        }
        Action action = operation.equals("config") ? new ReplaceConfig(target) : new CompleteRecovery(BigInteger.ONE, target);
        var request = lifecycle.intent(old, action, lower, upper);
        var domain = operation.equals("config") ? ProofDomains.configuration(AccountCodec.data(old), AccountCodec.data(request))
                : ProofDomains.target(AccountCodec.data(old), AccountCodec.data(request));
        var digest = evidenceCase.equals("ordinary-message") ? WireFormat.digest(AccountCodec.data(request)) : WireFormat.digest(domain);
        var evidence = AccountFixtures.list(AccountFixtures.sign(0, evidenceCase.equals("old-key") ? f.keys.get(0) : replacementKey, digest));
        if (evidenceCase.equals("missing")) evidence = JulcList.empty();
        if (evidenceCase.equals("retained-extra")) evidence = AccountFixtures.list(evidence.head(), AccountFixtures.sign(1, f.keys.get(1), digest));
        if (evidenceCase.equals("duplicate")) evidence = AccountFixtures.list(evidence.head(), evidence.head());
        if (evidenceCase.equals("descending")) evidence = AccountFixtures.list(AccountFixtures.sign(1, f.keys.get(1), digest), evidence.head());
        var approval = operation.equals("config") ? lifecycle.approval(old, request, 0, 1).operationProof() : Optional.<Proof>empty();
        var invocation = new ModuleRedeemer(BigInteger.ONE, request, approval, evidence, JulcList.empty());
        // Datum computation never substitutes for cryptographic preparation.
        var next = AccountAdministration.successor(old, request, BigInteger.valueOf(lower), BigInteger.valueOf(upper), true);
        boolean allowed = evidenceCase.equals("valid") || (operation.equals("recovery") && evidenceCase.equals("retained-extra"));
        if (allowed) {
            assertNotNull(AccountAdministration.prepare(old, invocation, Optional.empty(), BigInteger.valueOf(lower), BigInteger.valueOf(upper), true));
            for (var role : List.of("state", "core", "module")) {
                var evaluated = f.evaluate(role, lifecycle.context(role, old, next, invocation, lower, upper));
                assertInstanceOf(EvalResult.Success.class, evaluated, operation + " " + evidenceCase + " " + role + ": " + evaluated);
            }
        } else {
            var previous = old; long from = lower, until = upper;
            assertThrows(IllegalArgumentException.class, () -> AccountAdministration.prepare(previous, invocation, Optional.empty(),
                    BigInteger.valueOf(from), BigInteger.valueOf(until), true));
            assertInstanceOf(EvalResult.Failure.class, f.evaluate("module", lifecycle.context("module", old, next, invocation, lower, upper)), evidenceCase);
        }
    }
}
