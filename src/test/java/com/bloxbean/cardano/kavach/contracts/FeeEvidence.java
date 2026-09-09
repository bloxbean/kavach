package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bloxbean.cardano.client.util.HexUtil;

/**
 * Auditable fee decomposition for the controlled DevKit transfer fixtures. The caller
 * supplies all referenced scripts; these fixtures reference each script exactly once and
 * spend no reference-script-bearing inputs. This is reporting, not transaction balancing.
 */
final class FeeEvidence {
    private FeeEvidence() {
    }

    /**
     * Prices the final declared budgets, including the evaluator allowance. Uses Conway's
     * 25,600-byte reference tiers and 1.2 multiplier, pinned by the qualification environment.
     * Retains any difference from the paid fee explicitly instead of attributing it to scripts.
     */
    static Map<String, Object> analyze(Transaction transaction, ProtocolParams parameters,
                                       List<PlutusV3Script> referencedScripts) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        int bytes = transaction.serialize().length;
        BigInteger base = BigInteger.valueOf(parameters.getMinFeeA()).multiply(BigInteger.valueOf(bytes))
                .add(BigInteger.valueOf(parameters.getMinFeeB()));
        BigDecimal execution = BigDecimal.ZERO;
        var costs = new ArrayList<Map<String, Object>>();
        for (var redeemer : transaction.getWitnessSet().getRedeemers()) {
            var units = redeemer.getExUnits();
            BigDecimal cost = new BigDecimal(units.getMem()).multiply(parameters.getPriceMem())
                    .add(new BigDecimal(units.getSteps()).multiply(parameters.getPriceStep()));
            execution = execution.add(cost);
            costs.add(Map.of("purpose", redeemer.getTag().toString(), "index", redeemer.getIndex(),
                    "memory", units.getMem(), "cpu", units.getSteps(), "lovelaceBeforeRounding", cost));
        }
        int totalBytes = 0;
        var scripts = new ArrayList<Map<String, Object>>();
        var unique = new HashSet<String>();
        for (var script : referencedScripts) {
            String hash = HexUtil.encodeHexString(script.getScriptHash());
            if (!unique.add(hash)) throw new IllegalArgumentException("Duplicate reference script in fixture");
            int size = script.scriptRefBytes().length;
            totalBytes += size;
            scripts.add(Map.of("hash", hash, "chargedBytes", size));
        }
        BigDecimal reference = BigDecimal.ZERO;
        BigDecimal price = parameters.getMinFeeRefScriptCostPerByte();
        int remaining = totalBytes;
        while (remaining > 0) {
            int tier = Math.min(25_600, remaining);
            reference = reference.add(price.multiply(BigDecimal.valueOf(tier)));
            remaining -= tier;
            price = price.multiply(new BigDecimal("1.2"));
        }
        BigInteger executionFee = execution.setScale(0, RoundingMode.CEILING).toBigIntegerExact();
        BigInteger referenceFee = reference.setScale(0, RoundingMode.FLOOR).toBigIntegerExact();
        result.put("transactionBytes", bytes);
        result.put("baseFeeLovelace", base);
        result.put("executionFeeLovelace", executionFee);
        result.put("referenceScriptFeeLovelace", referenceFee);
        result.put("referenceScriptBytes", totalBytes);
        result.put("paidFeeLovelace", transaction.getBody().getFee());
        result.put("paidMinusCalculatedLovelace", transaction.getBody().getFee().subtract(base).subtract(executionFee).subtract(referenceFee));
        result.put("redeemers", costs);
        result.put("referenceScripts", scripts);
        return result;
    }
}
