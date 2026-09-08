package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.sdk.AccountAdministration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Independent SDK/compiled immutable-boundary checks; synthetic time is not elapsed ledger evidence. */
class AccountRecoveryBoundaryTest {
    private final AccountLifecycleTest lifecycle = new AccountLifecycleTest();
    private final AccountFixtures f = lifecycle.f;

    AccountRecoveryBoundaryTest() throws Exception {}

    @ParameterizedTest
    @ValueSource(strings = {"before-deadline", "exact-deadline", "after-deadline", "wrong-sequence",
            "wrong-target", "version-overflow", "sequence-overflow", "delay-overflow",
            "before-cooldown", "exact-cooldown", "wrong-cancel-commitment"})
    void recoveryBoundsAreEnforcedByImmutableState(String boundary) throws Exception {
        long deadline = 86400000, lower = deadline, upper = deadline + 100;
        var pending = new RecoveryPending(new byte[32], BigInteger.valueOf(deadline), f.state.authConfig());
        var old = lifecycle.state(f.state, 1, 1, 3600000, pending);
        Action action = new CompleteRecovery(BigInteger.ONE, old.authConfig());
        boolean allowed = boundary.equals("exact-deadline") || boundary.equals("after-deadline") || boundary.equals("exact-cooldown");
        switch (boundary) {
            case "before-deadline" -> lower--;
            case "after-deadline" -> lower++;
            case "wrong-sequence" -> action = new CompleteRecovery(BigInteger.TWO, old.authConfig());
            case "wrong-target" -> {
                var other = new AccountFixtures();
                action = new CompleteRecovery(BigInteger.ONE, other.state.authConfig());
            }
            case "version-overflow" -> old = lifecycle.state(old, Long.MAX_VALUE, 1, 3600000, pending);
            case "sequence-overflow" -> {
                old = lifecycle.state(f.state, 1, Long.MAX_VALUE, 0, new Normal());
                action = new StartRecovery(BigInteger.valueOf(Long.MAX_VALUE), old.authConfig());
            }
            case "delay-overflow" -> {
                old = f.state; lower = Long.MAX_VALUE - 1000; upper = Long.MAX_VALUE - 1;
                action = new StartRecovery(BigInteger.ONE, old.authConfig());
            }
            case "before-cooldown", "exact-cooldown" -> {
                old = lifecycle.state(f.state, 2, 1, deadline, new Frozen());
                action = new StartRecovery(BigInteger.TWO, old.authConfig());
                if (boundary.equals("before-cooldown")) lower--;
            }
            case "wrong-cancel-commitment" -> {
                var wrong = new byte[32]; wrong[0] = 1;
                action = new CancelRecovery(BigInteger.ONE, wrong);
            }
            case "exact-deadline" -> { }
            default -> throw new AssertionError(boundary);
        }
        var request = lifecycle.intent(old, action, lower, upper);
        var current = old; long start = lower, end = upper;
        AccountState next;
        if (allowed) next = AccountAdministration.successor(old, request, BigInteger.valueOf(lower), BigInteger.valueOf(upper), true);
        else {
            assertThrows(IllegalArgumentException.class, () -> AccountAdministration.successor(current, request,
                    BigInteger.valueOf(start), BigInteger.valueOf(end), true), boundary);
            next = lifecycle.state(old, 2, 1, 3600000, new Normal());
        }
        var authorization = lifecycle.approval(old, request, 1);
        var result = f.evaluate("state", lifecycle.context("state", old, next, authorization, lower, upper));
        if (allowed) assertInstanceOf(EvalResult.Success.class, result, boundary + ": " + result);
        else assertInstanceOf(EvalResult.Failure.class, result, boundary);
    }
}
