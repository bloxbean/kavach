package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.lib.NativeValueLib;
import java.math.BigInteger;

/**
 * Candidate PV11 native-Value accounting. Must pass compiled and actual node tests
 * before acceptance; no fallback silently substitutes a different accounting algorithm.
 * Exact recipients, complete inputs and receipt disjointness are checked by SpendLib.
 */
@OnchainLibrary
public class NativeAccountingLib {
    /**
     * Reads a complete singleton ADA-only map without flattening it. Returns -1 when
     * either map has another entry or a non-ADA key; no native asset is skipped.
     */
    public static BigInteger onlyLovelace(Value value) {
        var policies = Builtins.unMapData(value);
        if (Builtins.nullList(policies) || !Builtins.nullList(Builtins.tailList(policies))
                || !Builtins.equalsData(Builtins.fstPair(Builtins.headList(policies)), Builtins.bData(new byte[]{}))) return BigInteger.valueOf(-1);
        var tokens = Builtins.unMapData(Builtins.sndPair(Builtins.headList(policies)));
        if (Builtins.nullList(tokens) || !Builtins.nullList(Builtins.tailList(tokens))
                || !Builtins.equalsData(Builtins.fstPair(Builtins.headList(tokens)), Builtins.bData(new byte[]{}))) return BigInteger.valueOf(-1);
        return Builtins.unIData(Builtins.sndPair(Builtins.headList(tokens)));
    }
    /** Bounded positive ledger quantities and native-entry count, without allocating flattened asset triples. */
    static int nativeCount(Value value) {
        BigInteger ada = onlyLovelace(value);
        if (AccountLib.atMost(BigInteger.ZERO, ada)) return AccountLib.lessThan(BigInteger.ZERO, ada) && AccountLib.uint63(ada) ? 0 : -1;
        var policies = (JulcMap<byte[], JulcMap<byte[], BigInteger>>)(Object)value;
        boolean valid = true; int count = 0;
        for (byte[] policy : policies.keys()) {
            var tokens = policies.get(policy);
            if (tokens.isEmpty()) valid = false;
            for (byte[] name : tokens.keys()) {
                BigInteger amount = tokens.get(name);
                if (AccountLib.atMost(amount, BigInteger.ZERO) || !AccountLib.uint63(amount)) valid = false;
                if (Builtins.lengthOfByteString(policy) == 0) {
                    if (Builtins.lengthOfByteString(name) != 0) valid = false;
                } else {
                    count = count + 1;
                    if (Builtins.lengthOfByteString(policy) != 28 || Builtins.lengthOfByteString(name) > 32) valid = false;
                }
            }
        }
        return valid && count <= 11 ? count : -1;
    }
    /**
     * Input keys and positive quantities were checked before union. Rechecks aggregate
     * quantity bounds and the complete distinct-entry count without searching each map
     * again by key. The final conservation check separately requires positive input ADA,
     * so twelve total entries permit at most eleven native assets.
     */
    static boolean aggregateBounds(Value value) {
        var policies = (JulcMap<byte[], JulcMap<byte[], BigInteger>>)(Object)value;
        boolean valid = true; int count = 0;
        for (var tokens : policies.values()) {
            for (BigInteger amount : tokens.values()) {
                count = count + 1;
                if (!AccountLib.uint63(amount)) valid = false;
            }
        }
        return valid && count <= 12;
    }
    /**
     * Compares complete native-asset maps after an explicitly bounded lovelace debit.
     * Requires the caller to authenticate the account address, establish an empty mint map,
     * validate exact recipient outputs and exclude reward receipts from account allocations.
     * Individual quantities and aggregate account inputs fit unsigned 63 bits; at most eight
     * inputs are added, below the native union's signed 128-bit arithmetic limit.
     * Data accumulators are intentional: this compiler cannot carry NativeValue through loops.
     *
     * @param accountAddress authenticated full account enterprise address
     * @param recipientMask two-byte mask derived from the validated signed recipient indices (0..15)
     * @param feeLimit signed maximum account debit for fees, in lovelace
     * @param ctx ledger-supplied context with empty mint value
     * @return whether every native asset is conserved and the lovelace debit is permitted
     */
    public static boolean conserve(Address accountAddress, byte[] recipientMask, BigInteger feeLimit, ScriptContext ctx) {
        var zero = NativeValueLib.fromData((PlutusData)(Object)ctx.txInfo().mint());
        PlutusData incoming = NativeValueLib.toData(zero); PlutusData allocated = NativeValueLib.toData(zero);
        int inputEntries = 0; int outputEntries = 0; int accountInputs = 0; int position = 0; boolean valid = true;
        for (var input : ctx.txInfo().inputs()) {
            if (input.resolved().address().equals(accountAddress)) {
                int count = nativeCount(input.resolved().value());
                if (count < 0) valid = false;
                inputEntries = inputEntries + count; accountInputs = accountInputs + 1;
                incoming = NativeValueLib.toData(NativeValueLib.union(NativeValueLib.fromData(incoming), NativeValueLib.fromData((PlutusData)(Object)input.resolved().value())));
            }
        }
        // Validate aggregate input quantities and distinct native-asset union after addition.
        if (!aggregateBounds((Value)(Object)incoming)) valid = false;
        for (var output : ctx.txInfo().outputs()) {
            int count = nativeCount(output.value());
            if (count < 0) valid = false;
            outputEntries = outputEntries + count;
            if (output.address().equals(accountAddress) || Builtins.readBit(recipientMask, position)) {
                allocated = NativeValueLib.toData(NativeValueLib.union(NativeValueLib.fromData(allocated), NativeValueLib.fromData((PlutusData)(Object)output.value())));
            }
            position = position + 1;
        }
        var inputNative = NativeValueLib.fromData(incoming); var outputNative = NativeValueLib.fromData(allocated);
        BigInteger inputAda = NativeValueLib.lookupCoin(new byte[]{}, new byte[]{}, inputNative);
        BigInteger outputAda = NativeValueLib.lookupCoin(new byte[]{}, new byte[]{}, outputNative);
        BigInteger fee = inputAda.subtract(outputAda);
        var inputTokens = NativeValueLib.insertCoin(new byte[]{}, new byte[]{}, BigInteger.ZERO, inputNative);
        var outputTokens = NativeValueLib.insertCoin(new byte[]{}, new byte[]{}, BigInteger.ZERO, outputNative);
        return valid && accountInputs > 0 && accountInputs <= 8 && inputEntries <= 12 && outputEntries <= 12
                && AccountLib.lessThan(BigInteger.ZERO, inputAda) && AccountLib.uint63(inputAda) && AccountLib.uint63(outputAda)
                && AccountLib.atMost(BigInteger.ZERO, fee) && AccountLib.atMost(fee, feeLimit) && AccountLib.atMost(fee, ctx.txInfo().fee())
                && Builtins.equalsData(NativeValueLib.toData(inputTokens), NativeValueLib.toData(outputTokens));
    }
}
