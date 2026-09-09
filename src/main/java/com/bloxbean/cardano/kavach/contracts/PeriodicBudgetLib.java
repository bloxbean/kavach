package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.IntervalBoundType;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.AccountId;
import java.math.BigInteger;
import java.util.Optional;

/** Shared-budget wire types and fixed UTC-window arithmetic for the separate core candidate. */
@OnchainLibrary
public class PeriodicBudgetLib {
    /**
     * Core-aware configuration envelope, constructor 0 with exactly three fields.
     * @param schemaVersion exactly one
     * @param budget absent disables cumulative accounting; present binds the full counter NFT
     * @param authorization opaque authorization-module configuration, committed by admin signatures
     */
    public record Configuration(BigInteger schemaVersion, Optional<Budget> budget, PlutusData authorization) {}

    /**
     * Fixed-period budget, constructor 0 with exactly three fields.
     * @param counter full one-shot counter NFT identifier
     * @param period one for UTC day, two for UTC week beginning Monday
     * @param limit lovelace cap; zero disables the budget while retaining its counter identity
     */
    public record Budget(AccountId counter, BigInteger period, BigInteger limit) {}

    /**
     * Authenticated counter datum, constructor 0 with exactly four fields.
     * @param schemaVersion exactly one
     * @param period zero only for an unused counter, otherwise one or two
     * @param windowStart UTC window start in POSIX milliseconds; zero for unused counter
     * @param spent cumulative actual account ADA debit; zero for unused counter
     */
    public record Usage(BigInteger schemaVersion, BigInteger period, BigInteger windowStart, BigInteger spent) {}

    /** Canonical initial counter datum; initialization alone does not authorize account spending. */
    public static Usage initial() { return new Usage(BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO); }

    /** Structural discriminator only; callers must validate every interpreted field. */
    public static boolean isProfile(PlutusData data) { return AccountLib.shape(data, 0, 3); }

    /** Returns whether the validated profile enables a counter; ordinary module configurations do not. */
    public static boolean enabled(PlutusData data) {
        if (!isProfile(data)) return false;
        var configuration = (Configuration)(Object)data;
        return configuration.budget().isPresent() && AccountLib.lessThan(BigInteger.ZERO, configuration.budget().get().limit());
    }

    /** Checks bounded period and complete counter identity before it can govern spending. */
    public static boolean budgetShape(Budget budget) {
        return AccountLib.shape((PlutusData)(Object)budget, 0, 3)
                && AccountLib.shape((PlutusData)(Object)budget.counter(), 0, 2)
                && Builtins.lengthOfByteString(budget.counter().policy()) == 28
                && Builtins.lengthOfByteString(budget.counter().name()) == 0
                && (budget.period().equals(BigInteger.ONE) || budget.period().equals(BigInteger.TWO))
                && AccountLib.uint63(budget.limit());
    }

    /** Checks the core envelope without assigning meaning to its module-owned payload. */
    public static boolean configuration(Configuration configuration) {
        if (!isProfile((PlutusData)(Object)configuration) || !configuration.schemaVersion().equals(BigInteger.ONE)
                || Builtins.lengthOfByteString(Builtins.serialiseData((PlutusData)(Object)configuration)) > 1024) return false;
        return configuration.budget().isEmpty()
                ? Builtins.equalsData((PlutusData)(Object)configuration.budget(), (PlutusData)(Object)Optional.empty())
                : Builtins.equalsData((PlutusData)(Object)configuration.budget(), (PlutusData)(Object)Optional.of(configuration.budget().get()))
                    && budgetShape(configuration.budget().get());
    }

    /** Validates counter arithmetic bounds and the unique initial unused-counter representation. */
    public static boolean usageShape(Usage usage) {
        return AccountLib.shape((PlutusData)(Object)usage, 0, 4) && usage.schemaVersion().equals(BigInteger.ONE)
                && AccountLib.uint63(usage.windowStart()) && AccountLib.uint63(usage.spent())
                && (usage.period().equals(BigInteger.ZERO)
                    ? usage.windowStart().equals(BigInteger.ZERO) && usage.spent().equals(BigInteger.ZERO)
                    : usage.period().equals(BigInteger.ONE) || usage.period().equals(BigInteger.TWO));
    }

    /** Exact UTC duration; rejects arbitrary periods instead of silently treating them as daily. */
    public static BigInteger duration(BigInteger period) {
        if (period.equals(BigInteger.ONE)) return BigInteger.valueOf(86400000);
        if (period.equals(BigInteger.TWO)) return BigInteger.valueOf(604800000);
        return (BigInteger)(Object)Builtins.error();
    }

    /** Computes a fixed calendar window from a finite ledger timestamp, never a wall clock. */
    public static BigInteger window(BigInteger timestamp, BigInteger period) {
        BigInteger anchor = period.equals(BigInteger.TWO) ? BigInteger.valueOf(345600000) : BigInteger.ZERO;
        BigInteger duration = duration(period);
        if (!AccountLib.uint63(timestamp) || timestamp.compareTo(anchor) < 0) return (BigInteger)(Object)Builtins.error();
        return anchor.add(timestamp.subtract(anchor).divide(duration).multiply(duration));
    }

    /**
     * Computes the uniquely allowed successor using the complete finite ledger validity interval.
     * Period changes explicitly authorized in account configuration start a new period's counter.
     * Toggling the same budget off/on or changing only its limit does not erase its current usage.
     * @param previous authenticated counter datum
     * @param budget authenticated current account budget
     * @param debit actual account-input ADA minus valid account change, including actual account fees
     * @param ctx ledger-supplied context
     * @return exact successor; invalid windows, backwards time, overflow and excess spending reject
     */
    public static Usage successor(Usage previous, Budget budget, BigInteger debit, ScriptContext ctx) {
        if (!usageShape(previous) || !budgetShape(budget) || !AccountLib.uint63(debit)) return (Usage)(Object)Builtins.error();
        var range = ctx.txInfo().validRange();
        BigInteger lower = switch (range.from().boundType()) { case IntervalBoundType.Finite finite -> finite.time(); default -> BigInteger.valueOf(-1); };
        BigInteger upper = switch (range.to().boundType()) { case IntervalBoundType.Finite finite -> finite.time(); default -> BigInteger.valueOf(-1); };
        BigInteger start = window(lower, budget.period());
        BigInteger end = start.add(duration(budget.period()));
        if (!AccountLib.uint63(upper) || lower.compareTo(upper) >= 0 || previous.windowStart().compareTo(lower) > 0
                || upper.compareTo(end) > 0 || (upper.equals(end) && range.to().isInclusive())) return (Usage)(Object)Builtins.error();
        BigInteger carried = previous.period().equals(budget.period()) && previous.windowStart().equals(start)
                ? previous.spent() : BigInteger.ZERO;
        BigInteger total = carried.add(debit);
        if (!AccountLib.uint63(total) || total.compareTo(budget.limit()) > 0) return (Usage)(Object)Builtins.error();
        return new Usage(BigInteger.ONE, budget.period(), start, total);
    }
}
