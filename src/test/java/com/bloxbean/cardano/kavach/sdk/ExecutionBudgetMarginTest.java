package com.bloxbean.cardano.kavach.sdk;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Tests aggregate bounds and untrusted evaluator output, independently of actual script semantics. */
class ExecutionBudgetMarginTest {
    private static ProtocolParams limits(long memory, long cpu) {
        var params = new ProtocolParams(); params.setMaxTxExMem(Long.toString(memory)); params.setMaxTxExSteps(Long.toString(cpu)); return params;
    }
    private static EvaluationResult estimate(int index, long memory, long cpu) {
        return new EvaluationResult(RedeemerTag.Reward, index, new ExUnits(BigInteger.valueOf(memory), BigInteger.valueOf(cpu)));
    }
    @SuppressWarnings("unchecked")
    private static Result<List<EvaluationResult>> result(List<EvaluationResult> values) { return Result.success("fixture").withValue(values); }
    private static byte[] transaction(int count) throws Exception {
        var tx = new Transaction(); tx.getBody().setFee(BigInteger.ZERO);
        var redeemers = new ArrayList<Redeemer>();
        for (int i = 0; i < count; i++) redeemers.add(new Redeemer(RedeemerTag.Reward, BigInteger.valueOf(i), BigIntPlutusData.of(0), new ExUnits(BigInteger.ONE, BigInteger.ONE)));
        tx.getWitnessSet().setRedeemers(redeemers); return tx.serialize();
    }
    @Test void roundsEachRedeemerUpAndDoesNotModifyCachedEstimates() throws Exception {
        var original = result(List.of(estimate(1, 21, 101), estimate(0, 1, 1)));
        var padded = new ExecutionBudgetMargin((cbor, inputs) -> original, limits(1000, 1000)).evaluateTx(transaction(2), Set.of());
        assertEquals(BigInteger.valueOf(23), padded.getValue().getFirst().getExUnits().getMem());
        assertEquals(BigInteger.valueOf(107), padded.getValue().getFirst().getExUnits().getSteps());
        assertEquals(BigInteger.TWO, padded.getValue().getLast().getExUnits().getMem());
        assertEquals(BigInteger.valueOf(21), original.getValue().getFirst().getExUnits().getMem());
        assertNotSame(original, padded);
    }
    @Test void exactPaddedLimitPassesButCombinedExcessRejectsWithoutClipping() throws Exception {
        TransactionEvaluator evaluator = (cbor, inputs) -> result(List.of(estimate(0, 100, 100), estimate(1, 100, 100)));
        assertTrue(new ExecutionBudgetMargin(evaluator, limits(210, 210)).evaluateTx(transaction(2), Set.of()).isSuccessful());
        assertThrows(IllegalStateException.class, () -> new ExecutionBudgetMargin(evaluator, limits(209, 210)).evaluateTx(transaction(2), Set.of()));
        assertThrows(IllegalStateException.class, () -> new ExecutionBudgetMargin(evaluator, limits(210, 209)).evaluateTx(transaction(2), Set.of()));
    }
    @Test void rejectsMissingDuplicateUnexpectedAndNegativeEstimates() throws Exception {
        for (var entries : List.of(List.of(estimate(0, 1, 1)), List.of(estimate(0, 1, 1), estimate(0, 1, 1)),
                List.of(estimate(0, 1, 1), estimate(2, 1, 1)), List.of(estimate(0, -1, 1), estimate(1, 1, 1)))) {
            var wrapper = new ExecutionBudgetMargin((cbor, inputs) -> result(entries), limits(1000, 1000));
            assertThrows(IllegalStateException.class, () -> wrapper.evaluateTx(transaction(2), Set.of()));
        }
    }
    @Test void measuredPositiveReceiptScenarioFitsFivePercentButNotTen() throws Exception {
        TransactionEvaluator evaluator = (cbor, inputs) -> result(List.of(estimate(0, 15164612, 6800213507L)));
        assertTrue(new ExecutionBudgetMargin(evaluator, limits(16500000, 10000000000L)).evaluateTx(transaction(1), Set.of()).isSuccessful());
        assertThrows(IllegalStateException.class, () -> new ExecutionBudgetMargin(evaluator, limits(16500000, 10000000000L), 10).evaluateTx(transaction(1), Set.of()));
    }
    @Test void passesUnderlyingRejectionThroughAndNeverCallsItSuccess() throws Exception {
        @SuppressWarnings("unchecked") Result<List<EvaluationResult>> failure = Result.error("Script rejection");
        var wrapper = new ExecutionBudgetMargin((cbor, inputs) -> failure, limits(1000, 1000));
        assertSame(failure, wrapper.evaluateTx(transaction(1), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> wrapper.evaluateTx(new byte[]{0}, Set.of()));
    }
    @Test void refusesInvalidAllowanceAndLimits() {
        TransactionEvaluator evaluator = (cbor, inputs) -> result(List.of());
        for (int percent : new int[]{0, -1, 101}) assertThrows(IllegalArgumentException.class, () -> new ExecutionBudgetMargin(evaluator, limits(1, 1), percent));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionBudgetMargin(evaluator, limits(0, 1)));
    }
}
