package com.bloxbean.cardano.kavach.protocol;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Executable specification oracle; not an on-chain validator or authorization service.
 */
public final class RecoveryModel {
    public static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
    public static final BigInteger MIN_DELAY = BigInteger.valueOf(86_400_000);
    public static final BigInteger MAX_DELAY = BigInteger.valueOf(7_776_000_000L);
    public static final BigInteger MIN_COOLDOWN = BigInteger.valueOf(3_600_000);
    public static final BigInteger MAX_COOLDOWN = BigInteger.valueOf(2_592_000_000L);

    public enum Mode {NORMAL, FROZEN, PENDING}

    public enum Action {SPEND, CONFIGURE, FREEZE, UNFREEZE, START, CANCEL, COMPLETE}

    public enum Authority {SPEND, ADMIN, FREEZE, UNFREEZE, RECOVERY, CANCEL, TARGET}

    public record Interval(BigInteger lower, BigInteger upper) {
        public Interval {
            unsigned(lower);
            unsigned(upper);
            require(upper.compareTo(lower) > 0 && upper.subtract(lower).compareTo(BigInteger.valueOf(300_000)) <= 0);
        }
    }

    public record State(BigInteger version, BigInteger sequence, BigInteger notBefore,
                        BigInteger delay, BigInteger cooldown, Mode mode,
                        String commitment, BigInteger executeAfter) {
        public State {
            unsigned(version);
            unsigned(sequence);
            unsigned(notBefore);
            require(sequence.compareTo(version) <= 0);
            require(delay.compareTo(MIN_DELAY) >= 0 && delay.compareTo(MAX_DELAY) <= 0);
            require(cooldown.compareTo(MIN_COOLDOWN) >= 0 && cooldown.compareTo(MAX_COOLDOWN) <= 0);
            Objects.requireNonNull(mode);
            if (mode == Mode.PENDING) {
                require(sequence.signum() > 0);
                require(commitment != null && commitment.matches("[0-9a-f]{64}"));
                unsigned(executeAfter);
            } else require(commitment == null && executeAfter == null);
        }
    }

    /**
     * Applies the recovery specification to already classified authority; verifies no signatures.
     * Phase 1 on-chain state is sealed and cannot execute these modeled transitions.
     *
     * @param old        previous specification state
     * @param action     requested modeled operation
     * @param authority  role assumed to have been authenticated by the caller
     * @param interval   effective finite ledger validity interval, in POSIX milliseconds
     * @param commitment lowercase 32-byte hex proposal commitment for recovery operations
     * @return modeled successor, or the same state for a permitted spend
     * @throws IllegalArgumentException for invalid authority, lifecycle, time, commitment or overflow
     */
    public static State apply(State old, Action action, Authority authority, Interval interval, String commitment) {
        require(switch (action) {
            case SPEND -> authority == Authority.SPEND;
            case CONFIGURE -> authority == Authority.ADMIN;
            case FREEZE -> authority == Authority.FREEZE;
            case UNFREEZE -> authority == Authority.UNFREEZE;
            case START -> authority == Authority.RECOVERY;
            case CANCEL -> authority == Authority.CANCEL;
            case COMPLETE -> authority == Authority.TARGET;
        });
        if (action == Action.SPEND) {
            require(old.mode == Mode.NORMAL);
            return old;
        }
        BigInteger version = add(old.version, BigInteger.ONE);
        return switch (action) {
            case CONFIGURE -> {
                require(old.mode == Mode.NORMAL);
                yield copy(old, version, old.sequence, old.notBefore, Mode.NORMAL, null, null);
            }
            case FREEZE -> {
                require(old.mode == Mode.NORMAL);
                yield copy(old, version, old.sequence, old.notBefore, Mode.FROZEN, null, null);
            }
            case UNFREEZE -> {
                require(old.mode == Mode.FROZEN);
                yield copy(old, version, old.sequence, old.notBefore, Mode.NORMAL, null, null);
            }
            case START -> {
                require(old.mode != Mode.PENDING && interval.lower.compareTo(old.notBefore) >= 0);
                yield copy(old, version, add(old.sequence, BigInteger.ONE), old.notBefore.max(add(interval.upper, old.cooldown)),
                        Mode.PENDING, commitment, add(interval.upper, old.delay));
            }
            case CANCEL -> {
                require(old.mode == Mode.PENDING && old.commitment.equals(commitment));
                yield copy(old, version, old.sequence, old.notBefore.max(add(interval.upper, old.cooldown)), Mode.FROZEN, null, null);
            }
            case COMPLETE -> {
                require(old.mode == Mode.PENDING && old.commitment.equals(commitment) && interval.lower.compareTo(old.executeAfter) >= 0);
                yield copy(old, version, old.sequence, old.notBefore, Mode.NORMAL, null, null);
            }
            default -> throw new IllegalArgumentException("Unsupported transition");
        };
    }

    /**
     * Checks immutable recovery timings and a nondecreasing initiation cooldown.
     * This is a partial invariant check, not full successor or authorization validation.
     *
     * @param old  previous state
     * @param next proposed successor state
     * @throws IllegalArgumentException if an immutable timing changes or cooldown moves backwards
     */
    public static void validateSuccessorTiming(State old, State next) {
        require(next.delay.equals(old.delay) && next.cooldown.equals(old.cooldown));
        require(next.notBefore.compareTo(old.notBefore) >= 0);
    }

    private static State copy(State old, BigInteger version, BigInteger sequence, BigInteger notBefore,
                              Mode mode, String commitment, BigInteger executeAfter) {
        return new State(version, sequence, notBefore, old.delay, old.cooldown, mode, commitment, executeAfter);
    }

    private static BigInteger add(BigInteger x, BigInteger y) {
        var result = x.add(y);
        unsigned(result);
        return result;
    }

    private static void unsigned(BigInteger n) {
        require(n != null && n.signum() >= 0 && n.compareTo(MAX) <= 0);
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid recovery transition or bound");
    }

    private RecoveryModel() {
    }
}
