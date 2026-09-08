package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.AssetEntry;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.WithdrawValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import java.math.BigInteger;

/**
     * Conservation cost experiment only. No signature, state identity or lifecycle authorization. 
 * <p>Immutable parameter order: {@code accountAddress}.</p>
 */
@WithdrawValidator
public class ValueAccountingProbe {
    @Param static Address accountAddress;
    public record Delta(byte[] policy, byte[] name, BigInteger credit, BigInteger debit) {}
    public record Allocation(JulcList<BigInteger> recipientIndices, BigInteger feeLimit) {}
    /**
     * Checks complete partial-transfer asset accounting for the parameterized account address.
     * This isolated fixture does not authorize a complete Kavach account operation.
     * @param allocation untrusted probe-specific evidence and declared inputs
     * @param ctx ledger-supplied context for this isolated feasibility probe
     * @return whether this probe accepts; malformed Data may instead raise a script error
     */
    @Entrypoint public static boolean validate(Allocation allocation, ScriptContext ctx) {
        boolean rewarding = switch (ctx.scriptInfo()) { case ScriptInfo.RewardingScript reward -> true; default -> false; };
        var tx = ctx.txInfo();
        BigInteger feeLimit = allocation.feeLimit();
        if (!rewarding || tx.inputs().size() > 16 || tx.outputs().size() > 16 || tx.referenceInputs().size() > 4
                || allocation.recipientIndices().size() > 8 || feeLimit.signum() < 0 || feeLimit.compareTo(BigInteger.valueOf(5000000)) > 0
                || !Builtins.equalsData((PlutusData) (Object) allocation, (PlutusData) (Object) new Allocation(allocation.recipientIndices(), allocation.feeLimit()))) return false;
        
        boolean valid = true; int accountInputs = 0; int inputNativeEntries = 0;
        JulcList<Delta> deltas = JulcList.empty();
        BigInteger inputAda = BigInteger.ZERO; BigInteger allocatedAda = BigInteger.ZERO;
        for (var input : tx.inputs()) {
            if (input.resolved().address().equals(accountAddress)) {
                accountInputs = accountInputs + 1;
                var entries = ValuesLib.flattenTyped(input.resolved().value());
                if (entries.size() > 12) valid = false;
                JulcList<Delta> additions = JulcList.empty();
                for (AssetEntry asset : entries) {
                    BigInteger amount = asset.amount();
                    if (amount.signum() < 0 || amount.compareTo(BigInteger.valueOf(9223372036854775807L)) > 0) valid = false;
                    if (Builtins.lengthOfByteString(asset.policyId()) == 0) inputAda = inputAda.add(amount);
                    else {
                        inputNativeEntries = inputNativeEntries + 1;
                        if (inputNativeEntries <= 12) additions = additions.prepend(new Delta(asset.policyId(), asset.tokenName(), amount, BigInteger.ZERO));
                    }
                }
                if (!ordered(additions)) valid = false;
                deltas = merge(deltas, additions);
            }
        }
        BigInteger previous = BigInteger.valueOf(-1);
        for (BigInteger index : allocation.recipientIndices()) {
            if (index.compareTo(previous) <= 0 || index.signum() < 0 || index.compareTo(BigInteger.valueOf(tx.outputs().size())) >= 0) valid = false;
            previous = index;
        }
        int position = 0; int nativeEntries = 0;
        for (var output : tx.outputs()) {
            boolean change = output.address().equals(accountAddress); boolean recipient = false;
            for (BigInteger index : allocation.recipientIndices()) if (index == BigInteger.valueOf(position)) recipient = true;
            if (change && recipient) valid = false;
            var entries = ValuesLib.flattenTyped(output.value());
            if (entries.size() > 12) valid = false;
            JulcList<Delta> additions = JulcList.empty();
            for (AssetEntry asset : entries) {
                BigInteger amount = asset.amount();
                if (amount.signum() < 0 || amount.compareTo(BigInteger.valueOf(9223372036854775807L)) > 0) valid = false;
                if (Builtins.lengthOfByteString(asset.policyId()) != 0) nativeEntries = nativeEntries + 1;
                if (change || recipient) {
                    if (Builtins.lengthOfByteString(asset.policyId()) == 0) allocatedAda = allocatedAda.add(amount);
                    else if (nativeEntries <= 12) additions = additions.prepend(new Delta(asset.policyId(), asset.tokenName(), BigInteger.ZERO, amount));
                }
            }
            if (!ordered(additions)) valid = false;
            deltas = merge(deltas, additions);
            position = position + 1;
        }
        if (!valid || accountInputs < 1 || accountInputs > 8 || nativeEntries > 12 || inputNativeEntries > 12) return false;
        BigInteger adaDifference = inputAda.subtract(allocatedAda);
        if (inputAda.compareTo(BigInteger.valueOf(9223372036854775807L)) > 0 || allocatedAda.compareTo(BigInteger.valueOf(9223372036854775807L)) > 0
                || adaDifference.signum() < 0 || adaDifference.compareTo(feeLimit) > 0 || adaDifference.compareTo(tx.fee()) > 0) return false;
        for (Delta delta : deltas) {
            BigInteger credit = delta.credit(); BigInteger debit = delta.debit();
            if (credit != debit || credit.compareTo(BigInteger.valueOf(9223372036854775807L)) > 0 || debit.compareTo(BigInteger.valueOf(9223372036854775807L)) > 0) valid = false;
        }
        return valid && deltas.size() <= 11;
    }
    // Descending unsigned byte order, produced by prepending the ledger's ascending entries.
    static boolean before(Delta a, Delta b) {
        return Builtins.lessThanByteString(b.policy(), a.policy()) ||
                (Builtins.equalsByteString(a.policy(), b.policy()) && Builtins.lessThanByteString(b.name(), a.name()));
    }
    static boolean ordered(JulcList<Delta> entries) {
        boolean valid = true; boolean first = true;
        Delta previous = new Delta(new byte[]{}, new byte[]{}, BigInteger.ZERO, BigInteger.ZERO);
        for (Delta entry : entries) {
            if (!first && !before(previous, entry)) valid = false;
            previous = entry;
            first = false;
        }
        return valid;
    }
    static JulcList<Delta> merge(JulcList<Delta> a, JulcList<Delta> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        Delta first = a.head(); Delta second = b.head();
        if (before(first, second)) return merge(a.tail(), b).prepend(first);
        if (before(second, first)) return merge(a, b.tail()).prepend(second);
        BigInteger credit = first.credit(); BigInteger debit = first.debit();
        var sum = new Delta(first.policy(), first.name(), credit.add(second.credit()), debit.add(second.debit()));
        return merge(a.tail(), b.tail()).prepend(sum);
    }
}
