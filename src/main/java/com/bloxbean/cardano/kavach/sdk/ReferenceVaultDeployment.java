package com.bloxbean.cardano.kavach.sdk;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.AccountId;
import com.bloxbean.cardano.kavach.contracts.ReferenceVault;

import java.nio.charset.StandardCharsets;

/** Version-one reference vault deployment and explicit canonical reclamation encoding. */
public final class ReferenceVaultDeployment {
    private ReferenceVaultDeployment() { }

    /**
     * Derives publisher-owned reference custody, independent of the account's spending authority.
     * @param publisherHash publisher payment-key hash, exactly 28 bytes
     * @param accountId full state NFT identity, policy 28 bytes and name 0 through 32 bytes
     * @return applied Plutus V3 custody script
     * @throws IllegalArgumentException for null or invalid-width parameters
     */
    public static PlutusV3Script derive(byte[] publisherHash, AccountId accountId) {
        if (publisherHash == null || publisherHash.length != 28)
            throw new IllegalArgumentException("Publisher payment hash must be 28 bytes");
        validateAccount(accountId);
        return JulcScriptLoader.load(ReferenceVault.class,
                PlutusDataAdapter.toClientLib(PlutusData.bytes(publisherHash.clone())),
                PlutusDataAdapter.toClientLib(PlutusData.bytes("kavach-acc-ref".getBytes(StandardCharsets.US_ASCII))),
                PlutusDataAdapter.toClientLib(AccountCodec.data(accountId)));
    }

    /**
     * Returns the enterprise script address for the selected network.
     * @param publisherHash publisher payment-key hash
     * @param accountId full state NFT identity
     * @param network ledger address network
     * @return Bech32 custody address
     * @throws IllegalArgumentException for invalid publisher or account widths
     * @throws CborSerializationException if script hashing fails
     */
    public static String address(byte[] publisherHash, AccountId accountId, Network network) throws CborSerializationException {
        return AddressProvider.getEntAddress(derive(publisherHash, accountId), network).toBech32();
    }

    /**
     * Encodes constructor zero containing version one and the exact full account identifier.
     * @param accountId full state NFT identity
     * @return canonical client-library Data for the explicit reclamation request
     * @throws IllegalArgumentException for null or invalid account widths
     */
    public static com.bloxbean.cardano.client.plutus.spec.PlutusData reclaim(AccountId accountId) {
        validateAccount(accountId);
        return PlutusDataAdapter.toClientLib(PlutusData.constr(0, PlutusData.integer(1), AccountCodec.data(accountId)));
    }

    private static void validateAccount(AccountId accountId) {
        if (accountId == null || accountId.policy() == null || accountId.policy().length != 28
                || accountId.name() == null || accountId.name().length > 32)
            throw new IllegalArgumentException("Account policy must be 28 bytes and name at most 32 bytes");
    }
}
