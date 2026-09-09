package com.bloxbean.cardano.kavach.protocol;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.AccountId;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.Budget;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.Configuration;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.Usage;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/** Explicit JVM codec for the core budget envelope; raw data never authenticates a counter. */
public final class PeriodicBudgetCodec {
    private PeriodicBudgetCodec() {}
    /** Exact structural discriminator; contents remain untrusted. */
    public static boolean isProfile(PlutusData data) {
        return data instanceof PlutusData.ConstrData c && c.tag() == 0 && c.fields().size() == 3;
    }
    /** Validates the complete envelope and the mixed-authorization payload. */
    public static Configuration decode(PlutusData data) {
        var fields = fields(data, 0, 3);
        require(integer(fields.get(0)).equals(BigInteger.ONE), "Unsupported budget configuration schema");
        require(Builtins.serialiseData(data).length <= WireFormat.MAX_CONFIG_BYTES, "Budget configuration size");
        var optional = fields.get(1);
        Optional<Budget> budget;
        if (optional instanceof PlutusData.ConstrData c && c.tag() == 1 && c.fields().isEmpty()) budget = Optional.empty();
        else {
            var b = fields(fields(optional, 0, 1).getFirst(), 0, 3);
            var id = fields(b.get(0), 0, 2);
            byte[] policy = bytes(id.get(0)); byte[] name = bytes(id.get(1));
            require(policy.length == 28 && name.length == 0, "Budget counter identifier");
            var period = integer(b.get(1)); var limit = integer(b.get(2));
            duration(period);
            require(unsigned(limit), "Budget limit must be nonnegative lovelace");
            budget = Optional.of(new Budget(new AccountId(policy, name), period, limit));
        }
        require(PolicyConfigCodec.isPolicy(fields.get(2)), "Budget profile requires mixed authorization");
        PolicyConfigCodec.validate(fields.get(2));
        return new Configuration(BigInteger.ONE, budget, fields.get(2));
    }
    /** Returns module-owned data; callers must separately validate and authenticate the envelope. */
    public static PlutusData authorization(PlutusData data) {
        return isProfile(data) ? fields(data, 0, 3).get(2) : data;
    }
    /** Validates and decodes an inline counter datum after its full NFT and address are authenticated. */
    public static Usage usage(PlutusData data) {
        var f = fields(data, 0, 4);
        var version = integer(f.get(0)); var period = integer(f.get(1));
        var start = integer(f.get(2)); var spent = integer(f.get(3));
        require(version.equals(BigInteger.ONE) && unsigned(start) && unsigned(spent), "Counter schema or quantity");
        if (period.equals(BigInteger.ZERO)) require(start.signum() == 0 && spent.signum() == 0, "Unused counter must be zero");
        else require(window(start, period).equals(start), "Counter window alignment");
        return new Usage(version, period, start, spent);
    }
    /** Fixed UTC duration; arbitrary user-defined durations are unsupported. */
    public static BigInteger duration(BigInteger period) {
        if (period.equals(BigInteger.ONE)) return BigInteger.valueOf(86_400_000);
        if (period.equals(BigInteger.TWO)) return BigInteger.valueOf(604_800_000);
        throw new IllegalArgumentException("Budget period must be daily (1) or weekly (2)");
    }
    /** Calendar window matching on-chain arithmetic, using chain-derived POSIX milliseconds. */
    public static BigInteger window(BigInteger timestamp, BigInteger period) {
        var duration = duration(period);
        var anchor = period.equals(BigInteger.TWO) ? BigInteger.valueOf(345_600_000) : BigInteger.ZERO;
        require(unsigned(timestamp) && timestamp.compareTo(anchor) >= 0, "Unsupported ledger time");
        return anchor.add(timestamp.subtract(anchor).divide(duration).multiply(duration));
    }
    /**
     * Prepares the exact counter successor for a half-open ledger interval. The contract independently
     * checks its actual ledger interval, NFT identity and ADA debit; this is not authorization.
     */
    public static Usage successor(Usage previous, Budget budget, BigInteger debit, BigInteger lower, BigInteger upper) {
        require(unsigned(debit) && unsigned(upper) && upper.compareTo(lower) > 0, "Invalid debit or validity interval");
        var start = window(lower, budget.period());
        require(upper.compareTo(start.add(duration(budget.period()))) <= 0, "Transaction crosses budget window; prepare a narrower interval");
        require(previous.windowStart().compareTo(lower) <= 0, "Counter is ahead of this validity interval");
        var carried = previous.period().equals(budget.period()) && previous.windowStart().equals(start) ? previous.spent() : BigInteger.ZERO;
        var total = carried.add(debit);
        require(unsigned(total) && total.compareTo(budget.limit()) <= 0, "Periodic ADA budget exceeded");
        return new Usage(BigInteger.ONE, budget.period(), start, total);
    }
    private static boolean unsigned(BigInteger value) { return value.signum() >= 0 && value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0; }
    private static List<PlutusData> fields(PlutusData data, long tag, int count) {
        require(data instanceof PlutusData.ConstrData c && c.tag() == tag && c.fields().size() == count, "Budget record shape");
        return ((PlutusData.ConstrData)data).fields();
    }
    private static BigInteger integer(PlutusData data) { require(data instanceof PlutusData.IntData, "Budget integer"); return ((PlutusData.IntData)data).value(); }
    private static byte[] bytes(PlutusData data) { require(data instanceof PlutusData.BytesData, "Budget bytes"); return ((PlutusData.BytesData)data).value(); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
