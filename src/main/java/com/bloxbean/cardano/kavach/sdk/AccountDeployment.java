package com.bloxbean.cardano.kavach.sdk;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Module;
import com.bloxbean.cardano.kavach.contracts.AccountAssetValidator;
import com.bloxbean.cardano.kavach.contracts.AccountStateValidator;
import com.bloxbean.cardano.kavach.contracts.CoreCheckpoint;
import com.bloxbean.cardano.kavach.contracts.StateNftPolicy;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * Recomputes the acyclic V1 script graph from local compiler artifacts and explicit
 * deployment inputs. Creators must use these derived hashes before signing genesis;
 * a relayer-provided asset binding is never an acceptable substitute.
 * Current artifacts implement Phase 2 state custody under qualification; this is not a production deployment.
 * Re-deriving from different compiler artifacts changes hashes and cannot upgrade an existing core.
 */
public final class AccountDeployment {
    private AccountDeployment() {}
    /** Applied scripts and their derived full account/core/module identities. */
    public record Scripts(PlutusV3Script state, PlutusV3Script checkpoint, PlutusV3Script module,
            PlutusV3Script nft, PlutusV3Script asset, AccountId accountId, CoreBinding coreBinding, AuthModuleRef authModule) {}

    /**
     * Applies parameters in the normative deployment order.
     * @param domain immutable deployment discriminator, chosen before initialization
     * @param seed creator-owned UTxO that must be consumed once
     * @param creator payment key hash required on the creation transaction body
     * @param coreSink immutable full key address for core rewards
     * @param moduleSink immutable full key address for module rewards
     * @return independently derived scripts and identities
     * @throws IllegalArgumentException if a deployment input has an unsupported shape
     * @throws CborSerializationException if an applied script cannot be serialized for hashing
     */
    public static Scripts derive(DeploymentDomain domain, TxOutRef seed, byte[] creator, Address coreSink, Address moduleSink) throws CborSerializationException {
        if (domain.networkId().signum() < 0 || domain.networkId().compareTo(BigInteger.ONE) > 0
                || domain.networkMagic().signum() < 0 || domain.networkMagic().compareTo(new BigInteger("4294967295")) > 0
                || domain.deploymentId().length != 32) throw new IllegalArgumentException("Invalid deployment domain");
        if (seed.txId().hash().length != 32 || seed.index().signum() < 0 || seed.index().compareTo(BigInteger.valueOf(65535)) > 0)
            throw new IllegalArgumentException("Invalid creator seed reference");
        if (!(coreSink.credential() instanceof Credential.PubKeyCredential) || !(moduleSink.credential() instanceof Credential.PubKeyCredential))
            throw new IllegalArgumentException("Immutable reward sinks require key payment credentials");
        if (creator.length != 28) throw new IllegalArgumentException("Creator payment hash must be 28 bytes");
        var state = load(AccountStateValidator.class, BigInteger.ONE, domain);
        var checkpoint = load(CoreCheckpoint.class, BigInteger.ONE, domain, state.getScriptHash(), coreSink);
        var module = load(Ed25519Module.class, BigInteger.ONE, BigInteger.ONE, domain, state.getScriptHash(), checkpoint.getScriptHash(), moduleSink);
        var nft = load(StateNftPolicy.class, BigInteger.ONE, domain, seed, creator, state.getScriptHash());
        var id = new AccountId(nft.getScriptHash(), new byte[]{});
        var asset = load(AccountAssetValidator.class, BigInteger.ONE, domain, id, state.getScriptHash(), checkpoint.getScriptHash());
        var binding = new CoreBinding(state.getScriptHash(), asset.getScriptHash(), checkpoint.getScriptHash());
        return new Scripts(state, checkpoint, module, nft, asset, id, binding, new AuthModuleRef(module.getScriptHash(), BigInteger.ONE));
    }
    /**
     * Retrieves an existing account's exact script graph after locator restoration. This
     * requires no creation seed reference, original device or original module parameters.
     * Hash binding identifies existing code; it is not a fresh approval or audit of that code.
     * @param state current state already authenticated against trusted locator and ledger custody
     * @param backend replacement backend providing the published scripts by hash
     * @return V3 artifacts whose hashes exactly match the account's immutable and current bindings
     * @throws ApiException if an artifact cannot be retrieved
     * @throws CborSerializationException if script hash computation fails
     * @throws IllegalArgumentException for invalid state or wrong script language/hash
     */
    public static Scripts restore(AccountState state, BackendService backend) throws ApiException, CborSerializationException {
        WireFormat.validateState(AccountCodec.data(state));
        var custody = restoredScript(backend, state.coreBinding().stateValidator());
        var checkpoint = restoredScript(backend, state.coreBinding().checkpoint());
        var module = restoredScript(backend, state.authModule().scriptHash());
        var nft = restoredScript(backend, state.accountId().policy());
        var asset = restoredScript(backend, state.coreBinding().assetValidator());
        return new Scripts(custody, checkpoint, module, nft, asset, state.accountId(), state.coreBinding(), state.authModule());
    }

    private static PlutusV3Script restoredScript(BackendService backend, byte[] expected) throws ApiException, CborSerializationException {
        var result = backend.getScriptService().getPlutusScript(HexUtil.encodeHexString(expected));
        if (!result.isSuccessful() || result.getValue() == null) throw new IllegalStateException("Published script unavailable: " + HexUtil.encodeHexString(expected));
        if (!(result.getValue() instanceof PlutusV3Script script) || !Arrays.equals(script.getScriptHash(), expected))
            throw new IllegalArgumentException("Restored script language/hash mismatch");
        return script;
    }

    /**
     * Builds and structurally validates the final state after all script hashes have been derived.
     * The caller must obtain all genesis possession signatures and create the NFT on-chain;
     * this method alone does not establish an account or prove control of configuration keys.
     * @param scripts locally derived script graph for this domain
     * @param domain immutable deployment identity used to derive the scripts
     * @param config complete first-module configuration Data
     * @param delayMillis immutable recovery delay in POSIX milliseconds
     * @param cooldownMillis immutable recovery initiation cooldown in milliseconds
     * @return canonical version-zero Normal state, subject to on-chain creation validation
     * @throws IllegalArgumentException if the proposed state violates the wire profile
     */
    public static AccountState genesis(Scripts scripts, DeploymentDomain domain,
            PlutusData config, BigInteger delayMillis, BigInteger cooldownMillis) {
        var state = new AccountState(BigInteger.ONE, scripts.accountId(), domain, scripts.coreBinding(), BigInteger.ZERO,
                scripts.authModule(), config, BigInteger.ZERO, delayMillis, cooldownMillis, BigInteger.ZERO, new Normal());
        WireFormat.validateState(AccountCodec.data(state));
        return state;
    }
    /** Loads unmodified compiler output and applies explicit Data parameters in declaration order. */
    private static PlutusV3Script load(Class<?> type, Object... args) {
        var parameters = Arrays.stream(args).map(AccountCodec::data).map(PlutusDataAdapter::toClientLib)
                .toArray(com.bloxbean.cardano.client.plutus.spec.PlutusData[]::new);
        return JulcScriptLoader.load(type, parameters);
    }
}
