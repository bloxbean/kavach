package com.bloxbean.cardano.kavach.sdk;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Adds a caller-selected execution allowance before CCL balances fees and collateral.
 * Every redeemer must have exactly one successful estimate. Rounding is upward for each
 * redeemer, and the complete padded transaction must fit the supplied current ledger limits.
 * Budgets are never clipped to force acceptance. Node validation remains authoritative;
 * an allowance is not a guarantee for every evaluator, cost model or future script version.
 */
public final class ExecutionBudgetMargin implements TransactionEvaluator {
    /** Default tested allowance, in percent of each measured CPU and memory budget. */
    public static final int DEFAULT_PERCENT = 5;
    private static final BigInteger HUNDRED = BigInteger.valueOf(100);
    private final TransactionEvaluator delegate;
    private final BigInteger memoryLimit;
    private final BigInteger cpuLimit;
    private final BigInteger multiplier;

    /**
     * Uses the default 5% allowance and a snapshot of current protocol limits.
     * @param delegate evaluator configured for the correct network, era and cost model
     * @param parameters current ledger protocol parameters, refreshed by the caller
     */
    public ExecutionBudgetMargin(TransactionEvaluator delegate, ProtocolParams parameters) {
        this(delegate, parameters, DEFAULT_PERCENT);
    }

    /**
     * Sets an explicit allowance. Callers must refresh the wrapper when ledger limits change.
     * @param delegate underlying evaluator; failures are propagated without padding
     * @param parameters current protocol parameters containing whole-transaction limits
     * @param percent positive allowance from 1 through 100 percent
     * @throws IllegalArgumentException if the allowance or ledger limits are invalid
     */
    public ExecutionBudgetMargin(TransactionEvaluator delegate, ProtocolParams parameters, int percent) {
        this.delegate = Objects.requireNonNull(delegate);
        Objects.requireNonNull(parameters);
        if (percent < 1 || percent > 100) throw new IllegalArgumentException("Execution allowance must be 1–100 percent");
        memoryLimit = new BigInteger(parameters.getMaxTxExMem());
        cpuLimit = new BigInteger(parameters.getMaxTxExSteps());
        if (memoryLimit.signum() <= 0 || cpuLimit.signum() <= 0) throw new IllegalArgumentException("Positive ledger execution limits required");
        multiplier = BigInteger.valueOf(100L + percent);
    }

    /**
     * Checks estimate completeness, pads independently, and bounds the summed allocation.
     * @param cbor transaction being evaluated, including its complete redeemer set
     * @param inputUtxos resolved inputs passed unchanged to the underlying evaluator
     * @return fresh padded estimates, or the underlying unsuccessful result unchanged
     * @throws ApiException if the underlying evaluator cannot access its backend
     * @throws IllegalArgumentException if the transaction cannot be decoded
     * @throws IllegalStateException if estimates are missing, duplicated, malformed or over budget
     */
    @Override
    @SuppressWarnings("unchecked") // CCL 0.8.0-pre5's Result factories return raw Result.
    public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) throws ApiException {
        var expected = expectedRedeemers(cbor);
        var result = delegate.evaluateTx(cbor, inputUtxos);
        if (result == null) throw new IllegalStateException("Evaluator returned no result");
        if (!result.isSuccessful()) return result;
        if (result.getValue() == null) throw new IllegalStateException("Evaluator returned no execution estimates");
        var seen = new HashSet<Pointer>();
        var padded = new ArrayList<EvaluationResult>();
        BigInteger memory = BigInteger.ZERO;
        BigInteger cpu = BigInteger.ZERO;
        for (var estimate : result.getValue()) {
            if (estimate == null || estimate.getRedeemerTag() == null || estimate.getIndex() < 0)
                throw new IllegalStateException("Invalid redeemer estimate pointer");
            var pointer = new Pointer(estimate.getRedeemerTag(), BigInteger.valueOf(estimate.getIndex()));
            if (!expected.contains(pointer) || !seen.add(pointer)) throw new IllegalStateException("Unexpected or duplicated redeemer estimate");
            if (estimate.getExUnits() == null) throw new IllegalStateException("Missing execution units");
            var mem = pad(estimate.getExUnits().getMem());
            var steps = pad(estimate.getExUnits().getSteps());
            memory = memory.add(mem); cpu = cpu.add(steps);
            padded.add(new EvaluationResult(estimate.getRedeemerTag(), estimate.getIndex(), new ExUnits(mem, steps)));
        }
        if (!seen.equals(expected)) throw new IllegalStateException("Incomplete redeemer estimates");
        if (memory.compareTo(memoryLimit) > 0 || cpu.compareTo(cpuLimit) > 0)
            throw new IllegalStateException("Padded transaction exceeds ledger execution limit: memory=" + memory + ", cpu=" + cpu);
        Result<List<EvaluationResult>> output = Result.success("Complete execution estimates with explicit allowance");
        output.withValue(List.copyOf(padded));
        return output;
    }

    /** Counts each consumed script purpose once, independently of evaluator output ordering. */
    private static Set<Pointer> expectedRedeemers(byte[] cbor) {
        try {
            var transaction = Transaction.deserialize(cbor);
            var result = new HashSet<Pointer>();
            var redeemers = transaction.getWitnessSet().getRedeemers();
            if (redeemers != null) for (var redeemer : redeemers) {
                if (redeemer.getIndex() == null || redeemer.getIndex().signum() < 0 || redeemer.getTag() == null
                        || !result.add(new Pointer(redeemer.getTag(), redeemer.getIndex())))
                    throw new IllegalStateException("Invalid or duplicated transaction redeemer");
            }
            return result;
        } catch (CborDeserializationException e) {
            throw new IllegalArgumentException("Cannot decode transaction for execution-budget validation", e);
        }
    }
    private BigInteger pad(BigInteger measured) {
        if (measured == null || measured.signum() < 0) throw new IllegalStateException("Invalid measured execution units");
        return measured.multiply(multiplier).add(BigInteger.valueOf(99)).divide(HUNDRED);
    }
    private record Pointer(RedeemerTag tag, BigInteger index) {}
}
