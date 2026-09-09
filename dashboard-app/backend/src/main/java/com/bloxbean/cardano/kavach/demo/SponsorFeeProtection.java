package com.bloxbean.cardano.kavach.demo;

import com.bloxbean.cardano.client.function.TxBuilderContext;
import com.bloxbean.cardano.client.function.helper.FeeCalculators;
import com.bloxbean.cardano.client.function.helper.ScriptCostEvaluators;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import java.math.BigInteger;
import java.util.List;

/** Keeps signed allocations unchanged when a recipient is also the fee payer. */
final class SponsorFeeProtection {
    private List<TransactionOutput> original;
    private final int feeIndex;
    private final int signers;

    SponsorFeeProtection(int feeIndex, int signers) { this.feeIndex = feeIndex; this.signers = signers; }

    void capture(TxBuilderContext context, Transaction tx) {
        try { original = Transaction.deserialize(tx.serialize()).getBody().getOutputs(); }
        catch (Exception e) { throw new IllegalStateException("Cannot preserve signed output allocations", e); }
        if (original.size() != feeIndex + 1) throw new IllegalStateException("Unexpected sponsor change output layout");
    }

    void balance(TxBuilderContext context, Transaction tx) {
        if (original == null || tx.getBody().getOutputs().size() != original.size())
            throw new IllegalStateException("Output layout changed during fee balancing");
        restore(tx, tx.getBody().getFee());
        for (int attempt = 0; attempt < 6; attempt++) {
            String before;
            try { before = tx.serializeToHex(); }
            catch (Exception e) { throw new IllegalStateException(e); }
            // The final candidate must evaluate successfully; never ignore a script failure.
            ScriptCostEvaluators.evaluateScriptCost().apply(context, tx);
            FeeCalculators.feeCalculator(signers, (fee, outputs) -> restore(tx, fee)).apply(context, tx);
            var body = tx.getBody();
            if (body.getCollateralReturn() != null && body.getTotalCollateral() != null) {
                BigInteger available = body.getCollateralReturn().getValue().getCoin().add(body.getTotalCollateral());
                BigInteger required = body.getFee().multiply(context.getProtocolParams().getCollateralPercent().toBigIntegerExact())
                        .add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100));
                if (required.compareTo(available) > 0) throw new IllegalStateException("Insufficient sponsor collateral after balancing");
                body.setTotalCollateral(required);
                body.getCollateralReturn().getValue().setCoin(available.subtract(required));
            }
            try { if (before.equals(tx.serializeToHex())) return; }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        throw new IllegalStateException("Sponsor fee balancing did not converge");
    }

    private void restore(Transaction tx, BigInteger fee) {
        var outputs = tx.getBody().getOutputs();
        for (int i = 0; i < original.size(); i++) {
            if (!outputs.get(i).getAddress().equals(original.get(i).getAddress()))
                throw new IllegalStateException("Output address changed during fee balancing");
            // Only lovelace is adjusted by fee balancing; all token allocations must remain exact.
            if (!outputs.get(i).getValue().getMultiAssets().equals(original.get(i).getValue().getMultiAssets()))
                throw new IllegalStateException("Asset allocation changed during fee balancing");
            outputs.get(i).getValue().setCoin(original.get(i).getValue().getCoin());
        }
        BigInteger change = original.get(feeIndex).getValue().getCoin().subtract(fee);
        if (change.compareTo(BigInteger.valueOf(2_000_000)) < 0)
            throw new IllegalStateException("Sponsor needs a larger fee UTxO to preserve its change output");
        outputs.get(feeIndex).getValue().setCoin(change);
    }
}
