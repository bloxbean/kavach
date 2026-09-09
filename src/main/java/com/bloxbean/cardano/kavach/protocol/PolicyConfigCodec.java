package com.bloxbean.cardano.kavach.protocol;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Lib.*;
import com.bloxbean.cardano.kavach.auth.policy.PolicyLib.PolicyConfig;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Explicit JVM encoding/validation of the separately versioned mixed-policy configuration. */
public final class PolicyConfigCodec {
    private PolicyConfigCodec() {}

    /** Structural discriminator only; callers must validate before interpreting any field. */
    public static boolean isPolicy(PlutusData value) {
        return value instanceof PlutusData.ConstrData c && c.tag() == 0 && c.fields().size() == 5;
    }

    /** Returns the existing eight-field role configuration; does not authenticate chain state. */
    public static PlutusData roles(PlutusData value) {
        var authorization = PeriodicBudgetCodec.authorization(value);
        return isPolicy(authorization) ? fields(authorization, 5).get(1) : authorization;
    }

    /** Decodes a validated key/role registry using explicit wire positions. */
    public static Ed25519Config decodeRoles(PlutusData value) {
        var f = fields(roles(value), 8);
        var keys = new ArrayList<KeyEntry>();
        for (var entry : items(f.get(1))) {
            var k = fields(entry, 2);
            keys.add(new KeyEntry(integer(k.get(0)), ((PlutusData.BytesData) k.get(1)).value()));
        }
        return new Ed25519Config(integer(f.get(0)), list(keys), policy(f.get(2)), policy(f.get(3)),
                policy(f.get(4)), policy(f.get(5)), policy(f.get(6)), policy(f.get(7)));
    }

    /** Validates canonical bounds, methods, registry and policy implication without JVM contract stubs. */
    public static void validate(PlutusData value) {
        var f = fields(value, 5);
        require(integer(f.get(0)).equals(BigInteger.ONE), "Unsupported policy schema");
        require(Builtins.serialiseData(value).length <= WireFormat.MAX_CONFIG_BYTES, "Policy config size");
        fields(f.get(1), 8);
        WireFormat.validateConfig(f.get(1));
        var base = decodeRoles(f.get(1));
        var methods = items(f.get(2));
        BigInteger previous = BigInteger.valueOf(-1);
        for (var entry : methods) {
            var id = integer(entry);
            require(id.compareTo(previous) > 0 && containsKey(base, id), "Unknown/duplicate COSE credential");
            previous = id;
        }
        var limit = integer(f.get(3));
        require(limit.signum() >= 0 && limit.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0, "Payment limit range");
        var low = policy(f.get(4));
        var lowIds = ids(low);
        var highIds = ids(base.spend());
        require(!lowIds.isEmpty() && lowIds.size() <= 8 && low.threshold().signum() > 0
                && low.threshold().compareTo(BigInteger.valueOf(lowIds.size())) <= 0, "Small-payment threshold");
        previous = BigInteger.valueOf(-1);
        for (var id : lowIds) {
            require(id.compareTo(previous) > 0 && highIds.contains(id), "Small policy must be ordered subset of strong spend");
            previous = id;
        }
        require(base.spend().threshold().subtract(BigInteger.valueOf(highIds.size() - lowIds.size()))
                .compareTo(low.threshold()) >= 0, "Strong spend must imply small spend");
    }

    /** Decodes the full validated module configuration. */
    public static PolicyConfig decode(PlutusData value) {
        validate(value); var f = fields(value, 5);
        return new PolicyConfig(BigInteger.ONE, decodeRoles(f.get(1)), list(items(f.get(2)).stream()
                .map(PolicyConfigCodec::integer).toList()), integer(f.get(3)), policy(f.get(4)));
    }

    /** Resolves a registered credential's fixed method (1 transaction witness, 2 COSE). */
    public static int method(PlutusData value, BigInteger id, int profile) {
        if (profile < 3) return profile;
        value = PeriodicBudgetCodec.authorization(value);
        require(isPolicy(value), "Mixed profile requires policy config");
        return items(fields(value, 5).get(2)).stream().map(PolicyConfigCodec::integer).anyMatch(id::equals) ? 2 : 1;
    }

    /** Returns the appropriate policy from the same signed action the validator examines. */
    public static ThresholdPolicy spendPolicy(PlutusData value, Action action) {
        value = PeriodicBudgetCodec.authorization(value);
        var base = decodeRoles(value);
        if (!isPolicy(value)) return base.spend();
        var config = decode(value);
        if (!(action instanceof Spend spend)) return base.spend();
        BigInteger debit = spend.maxAccountFee();
        if (debit.signum() < 0) throw new IllegalArgumentException("Negative account fee");
        for (var recipient : spend.recipients()) for (var asset : recipient.value()) {
            if (asset.policy().length != 0 || asset.name().length != 0 || asset.quantity().signum() <= 0) return base.spend();
            debit = debit.add(asset.quantity());
        }
        return debit.compareTo(config.smallPaymentLimit()) <= 0 ? config.smallSpend() : base.spend();
    }

    /** Decodes a canonical threshold record. */
    public static ThresholdPolicy policy(PlutusData value) {
        var f = fields(value, 2);
        return new ThresholdPolicy(integer(f.get(0)), list(items(f.get(1)).stream().map(PolicyConfigCodec::integer).toList()));
    }

    /** Copies ordered IDs for JVM policy selection and display. */
    public static List<BigInteger> ids(ThresholdPolicy policy) {
        var ids = new ArrayList<BigInteger>(); for (var id : policy.credentialIds()) ids.add(id); return List.copyOf(ids);
    }
    private static boolean containsKey(Ed25519Config config, BigInteger id) {
        for (var key : config.keys()) if (key.credentialId().equals(id)) return true; return false;
    }
    private static List<PlutusData> fields(PlutusData value, int size) {
        require(value instanceof PlutusData.ConstrData c && c.tag() == 0 && c.fields().size() == size, "Policy record shape");
        return ((PlutusData.ConstrData) value).fields();
    }
    private static List<PlutusData> items(PlutusData value) { return ((PlutusData.ListData) value).items(); }
    private static BigInteger integer(PlutusData value) { return ((PlutusData.IntData) value).value(); }
    private static <T> JulcList<T> list(List<T> values) {
        var result = JulcList.<T>empty(); for (int i = values.size()-1; i>=0; i--) result=result.prepend(values.get(i)); return result;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
