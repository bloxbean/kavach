package com.bloxbean.cardano.kavach.sdk;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.AccountState;
import com.bloxbean.cardano.kavach.protocol.WireFormat;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Portable public account locator independent of device keys, current policies and state
 * references. Preserve the backup's integrity: it identifies the account to recover but
 * neither grants authority nor proves ledger inclusion. Restoration uses an independently
 * trusted current-state provider; complete transaction validation remains mandatory.
 * The backup encoding is an SDK format, not an on-chain schema or wallet seed phrase.
 */
public final class AccountLocator {
    private static final String PREFIX = "kavach-locator-v1:";
    private final PlutusData.ConstrData data;

    private AccountLocator(PlutusData value) {
        var fields = record(value, 4);
        require(number(fields.get(0)).equals(BigInteger.ONE), "Locator version");
        var account = record(fields.get(1), 2); bytes(account.get(0), 28); bytes(account.get(1), 0);
        var deployment = record(fields.get(2), 3);
        require(number(deployment.get(0)).signum() >= 0 && number(deployment.get(0)).compareTo(BigInteger.ONE) <= 0, "Network ID");
        require(number(deployment.get(1)).signum() >= 0 && number(deployment.get(1)).bitLength() <= 32, "Network magic");
        bytes(deployment.get(2), 32);
        for (var hash : record(fields.get(3), 3)) bytes(hash, 28);
        data = (PlutusData.ConstrData)value;
    }

    /**
     * Creates a key-free locator from a state whose custody the caller has authenticated.
     * @param state complete current first-module state
     * @return locator that remains valid across configuration, module and recovery changes
     * @throws IllegalArgumentException for invalid state encoding or configuration
     */
    public static AccountLocator fromState(AccountState state) {
        WireFormat.validateState(AccountCodec.data(state));
        return new AccountLocator(PlutusData.constr(0, PlutusData.integer(1), AccountCodec.data(state.accountId()),
                AccountCodec.data(state.deploymentDomain()), AccountCodec.data(state.coreBinding())));
    }

    /**
     * Encodes the immutable locator as a versioned prefix and canonical Plutus Data CBOR hex.
     * No keys, current version, policy members or pending recovery commitment are included.
     * @return portable lowercase backup text
     */
    public String backup() { return PREFIX + HexFormat.of().formatHex(Builtins.serialiseData(data)); }

    /**
     * Parses a bounded canonical backup; trailing bytes, alternate encodings and unknown fields reject.
     * @param backup complete backup text
     * @return parsed immutable locator
     * @throws IllegalArgumentException if the prefix, canonical encoding or locator fields are invalid
     */
    public static AccountLocator parse(String backup) {
        require(backup != null && backup.startsWith(PREFIX) && backup.length() <= 1024, "Locator backup format or length");
        String encoded = backup.substring(PREFIX.length());
        require(encoded.matches("[0-9a-f]+") && encoded.length() % 2 == 0, "Locator hex encoding");
        try {
            byte[] raw = HexFormat.of().parseHex(encoded);
            var decoded = PlutusDataAdapter.fromClientLib(com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(raw));
            require(Arrays.equals(raw, Builtins.serialiseData(decoded)), "Noncanonical or trailing locator CBOR");
            return new AccountLocator(decoded);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid locator backup", failure);
        }
    }

    /**
     * Provider boundary for locating the current NFT, without an old UTxO reference or a primary key.
     * Implementations must return current ledger claims, not cached creation records.
     */
    @FunctionalInterface
    public interface StateProvider {
        /**
         * Resolves the exact asset at its immutable state address.
         * @param address expected complete state custody address
         * @param assetUnit full policy-and-name identifier
         * @return matching current outputs; zero or multiple outputs cause restoration to fail
         * @throws ApiException if the provider cannot supply current ledger data
         */
        List<Utxo> find(String address, String assetUnit) throws ApiException;
    }

    /**
     * Adapts a replacement CCL backend to exact-address/exact-asset discovery. Requesting two
     * entries is sufficient to reject ambiguous NFT claims instead of selecting the first.
     * @param backend independently trusted current ledger backend
     * @return provider with no dependency on the old device or collateral account
     */
    public static StateProvider provider(BackendService backend) {
        return (address, unit) -> {
            var result = backend.getUtxoService().getUtxos(address, unit, 2, 1, OrderEnum.asc);
            if (!result.isSuccessful() || result.getValue() == null) throw new IllegalStateException("Current state provider failed: " + result.getResponse());
            return result.getValue();
        };
    }

    /**
     * Restored provider claim after identity and custody validation. It is not an inclusion proof.
     * @param state complete current state, including the installed module and any pending target
     * @param input current singleton NFT UTxO; revalidate before transaction preparation/submission
     */
    public record Restored(AccountState state, Utxo input) {}

    /**
     * Locates and validates the current account using only the public locator and a provider.
     * It deliberately does not assume that the original module, keys, mode or state version
     * remain current. Recovery authority and a fresh key-controlled collateral provider are
     * separate requirements when constructing the next operation.
     * @param provider current ledger discovery source
     * @return exact current state and its resolved NFT UTxO
     * @throws ApiException for provider transport/API errors
     * @throws IllegalArgumentException for missing/ambiguous output, wrong identity/custody or malformed datum/value
     */
    public Restored restore(StateProvider provider) throws ApiException {
        var fields = data.fields();
        var account = record(fields.get(1), 2); var deployment = record(fields.get(2), 3); var core = record(fields.get(3), 3);
        var network = new Network(number(deployment.get(0)).intValueExact(), number(deployment.get(1)).longValueExact());
        String holder = AddressProvider.getEntAddress(Credential.fromScript(bytes(core.get(0), 28)), network).toBech32();
        String unit = HexFormat.of().formatHex(bytes(account.get(0), 28));
        var found = provider.find(holder, unit);
        require(found != null && found.size() == 1 && found.getFirst() != null, "Missing or ambiguous current account state");
        var input = found.getFirst();
        require(holder.equals(input.getAddress()) && input.getReferenceScriptHash() == null && input.getInlineDatum() != null, "Wrong state custody shape");
        require(input.getTxHash() != null && input.getTxHash().matches("[0-9a-f]{64}")
                && input.getOutputIndex() >= 0 && input.getOutputIndex() <= 65535, "Invalid resolved state reference");
        var amounts = AccountTransfer.quantities(input.getAmount());
        require(amounts.size() == 2 && BigInteger.ONE.equals(amounts.get(unit)) && amounts.get("lovelace").bitLength() <= 63, "Full NFT identity, quantity or ADA mismatch");
        AccountState state;
        try {
            var inline = PlutusDataAdapter.fromClientLib(com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(HexFormat.of().parseHex(input.getInlineDatum())));
            state = AccountCodec.decodeState(inline);
        } catch (Exception failure) { throw new IllegalArgumentException("Invalid restored state datum", failure); }
        require(AccountCodec.data(state.accountId()).equals(fields.get(1)) && AccountCodec.data(state.deploymentDomain()).equals(fields.get(2))
                && AccountCodec.data(state.coreBinding()).equals(fields.get(3)), "State differs from trusted locator identity");
        return new Restored(state, input);
    }

    private static List<PlutusData> record(PlutusData value, int arity) {
        require(value instanceof PlutusData.ConstrData c && c.constructorTag().equals(BigInteger.ZERO) && c.fields().size() == arity, "Locator record shape");
        return ((PlutusData.ConstrData)value).fields();
    }
    private static byte[] bytes(PlutusData value, int length) {
        require(value instanceof PlutusData.BytesData b && b.value().length == length, "Locator byte length");
        return ((PlutusData.BytesData)value).value();
    }
    private static BigInteger number(PlutusData value) {
        require(value instanceof PlutusData.IntData, "Locator integer type"); return ((PlutusData.IntData)value).value();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
