package com.bloxbean.cardano.kavach.protocol;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.Random;
import static com.bloxbean.cardano.kavach.protocol.RecoveryModel.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryModelTest {
    private static final String COMMITMENT = "ab".repeat(32);
    private State initial() { return new State(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, MIN_DELAY, MIN_COOLDOWN, Mode.NORMAL, null, null); }
    private Interval at(BigInteger t) { return new Interval(t, t.add(BigInteger.ONE)); }
    @Test void delayedCompletionUsesUpperForDeadlineAndLowerForEligibility() {
        var pending = apply(initial(), Action.START, Authority.RECOVERY, new Interval(BigInteger.ZERO, BigInteger.valueOf(300_000)), COMMITMENT);
        assertEquals(MIN_DELAY.add(BigInteger.valueOf(300_000)), pending.executeAfter());
        assertThrows(IllegalArgumentException.class, () -> apply(pending, Action.COMPLETE, Authority.TARGET,
                at(pending.executeAfter().subtract(BigInteger.ONE)), COMMITMENT));
        var complete = apply(pending, Action.COMPLETE, Authority.TARGET, at(pending.executeAfter()), COMMITMENT);
        assertEquals(Mode.NORMAL, complete.mode()); assertEquals(BigInteger.ONE, complete.sequence());
        assertEquals(BigInteger.TWO, complete.version()); assertEquals(pending.notBefore(), complete.notBefore());
    }
    @Test void cancelFreezeUnfreezePreservesCooldownAndDefensiveWindow() {
        var pending = apply(initial(), Action.START, Authority.RECOVERY, at(BigInteger.ZERO), COMMITMENT);
        var cancelled = apply(pending, Action.CANCEL, Authority.CANCEL, at(BigInteger.TEN), COMMITMENT);
        assertEquals(Mode.FROZEN, cancelled.mode());
        var normal = apply(cancelled, Action.UNFREEZE, Authority.UNFREEZE, at(BigInteger.TEN), null);
        assertThrows(IllegalArgumentException.class, () -> apply(normal, Action.START, Authority.RECOVERY,
                at(normal.notBefore().subtract(BigInteger.ONE)), COMMITMENT));
        var again = apply(normal, Action.START, Authority.RECOVERY, at(normal.notBefore()), COMMITMENT);
        assertEquals(BigInteger.TWO, again.sequence());
        assertThrows(IllegalArgumentException.class, () -> apply(normal, Action.COMPLETE, Authority.TARGET, at(MIN_DELAY), COMMITMENT));
    }
    @Test void pendingRejectsSpendUnfreezeAdminAndTargetSubstitution() {
        var pending = apply(initial(), Action.START, Authority.RECOVERY, at(BigInteger.ZERO), COMMITMENT);
        assertThrows(IllegalArgumentException.class, () -> apply(pending, Action.SPEND, Authority.SPEND, at(BigInteger.ZERO), null));
        assertThrows(IllegalArgumentException.class, () -> apply(pending, Action.UNFREEZE, Authority.UNFREEZE, at(BigInteger.ZERO), null));
        assertThrows(IllegalArgumentException.class, () -> apply(pending, Action.CONFIGURE, Authority.ADMIN, at(BigInteger.ZERO), null));
        assertThrows(IllegalArgumentException.class, () -> apply(pending, Action.COMPLETE, Authority.TARGET, at(pending.executeAfter()), "cd".repeat(32)));
    }
    @Test void allWrongRoleCombinationsReject() {
        for (var action : Action.values()) for (var authority : Authority.values()) {
            boolean right = switch (action) {
                case SPEND -> authority == Authority.SPEND; case CONFIGURE -> authority == Authority.ADMIN;
                case FREEZE -> authority == Authority.FREEZE; case UNFREEZE -> authority == Authority.UNFREEZE;
                case START -> authority == Authority.RECOVERY; case CANCEL -> authority == Authority.CANCEL;
                case COMPLETE -> authority == Authority.TARGET;
            };
            if (!right) assertThrows(IllegalArgumentException.class, () -> apply(initial(), action, authority, at(BigInteger.ZERO), COMMITMENT));
        }
    }
    @Test void arithmeticAndIntervalBoundsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new Interval(BigInteger.ZERO, BigInteger.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new Interval(BigInteger.ZERO, BigInteger.valueOf(300_001)));
        assertThrows(IllegalArgumentException.class, () -> new State(BigInteger.ZERO, MAX, BigInteger.ZERO, MIN_DELAY, MIN_COOLDOWN, Mode.NORMAL, null, null));
        var exhausted = new State(MAX, MAX, BigInteger.ZERO, MIN_DELAY, MIN_COOLDOWN, Mode.NORMAL, null, null);
        assertThrows(IllegalArgumentException.class, () -> apply(exhausted, Action.START, Authority.RECOVERY, at(BigInteger.ZERO), COMMITMENT));
        assertThrows(IllegalArgumentException.class, () -> apply(initial(), Action.START, Authority.RECOVERY, at(MAX.subtract(BigInteger.ONE)), COMMITMENT));
    }
    @Test void unsignedTimingChangesRejectEvenWhenTheyIncreaseSecurityDelays() {
        var old = initial();
        var changed = new State(BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO,
                MIN_DELAY.add(BigInteger.ONE), MIN_COOLDOWN, Mode.NORMAL, null, null);
        assertThrows(IllegalArgumentException.class, () -> validateSuccessorTiming(old, changed));
        var changedCooldown = new State(BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO,
                MIN_DELAY, MIN_COOLDOWN.add(BigInteger.ONE), Mode.NORMAL, null, null);
        assertThrows(IllegalArgumentException.class, () -> validateSuccessorTiming(old, changedCooldown));
    }
    @Test void deterministicRandomTransitionTracesPreserveMonotonicity() {
        var random = new Random(657113L);
        var state = initial();
        BigInteger now = BigInteger.ZERO;
        int accepted = 0;
        for (int i = 0; i < 10000; i++) {
            now = now.add(BigInteger.valueOf(random.nextInt(500_000)));
            var action = Action.values()[random.nextInt(Action.values().length)];
            var authority = Authority.values()[random.nextInt(Authority.values().length)];
            try {
                var next = apply(state, action, authority, at(now), COMMITMENT);
                assertTrue(next.version().compareTo(state.version()) >= 0);
                assertTrue(next.sequence().compareTo(state.sequence()) >= 0);
                assertTrue(next.notBefore().compareTo(state.notBefore()) >= 0);
                validateSuccessorTiming(state, next);
                state = next; accepted++;
            } catch (IllegalArgumentException expected) { /* rejected traces retain the old state */ }
        }
        assertTrue(accepted > 50, "Exercise accepted paths as well as rejection");
    }
}
