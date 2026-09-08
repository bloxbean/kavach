package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import java.math.BigInteger;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import java.util.Optional;

/**
 * Typed on-chain views of {@code protocol/v1/kavach.cddl}.
 * Record fields and sealed variant order are wire ABI, not implementation details.
 * Validators must reconstruct and compare untrusted records: a typed field access
 * alone does not establish constructor tags or exact field counts in JuLC.
 * Configuration remains opaque to the core and is interpreted by its installed module.
 */
@OnchainLibrary
public final class AccountTypes {
    private AccountTypes() {}
    /**
     * Full one-shot NFT identity; V1 requires an empty asset name.
     * @param policy 28-byte one-shot state NFT minting policy ID
     * @param name asset name bytes; empty for the state NFT and lovelace
     */
    public record AccountId(byte[] policy, byte[] name) {}
    /**
     * Immutable network and deployment discriminator.
     * @param networkId ledger network ID, 0 for test networks or 1 for mainnet
     * @param networkMagic unsigned 32-bit network magic
     * @param deploymentId 32-byte discriminator separating deployments on the same network
     */
    public record DeploymentDomain(BigInteger networkId, BigInteger networkMagic, byte[] deploymentId) {}
    /**
     * Immutable state, asset and transaction checkpoint payment/script hashes.
     * @param stateValidator 28-byte immutable state custody script hash
     * @param assetValidator 28-byte account asset payment script hash
     * @param checkpoint 28-byte immutable core Rewarding script hash
     */
    public record CoreBinding(byte[] stateValidator, byte[] assetValidator, byte[] checkpoint) {}
    /**
     * Installed module identity and supported ABI.
     * @param scriptHash 28-byte installed module script hash
     * @param abiVersion supported module or invocation ABI version, currently 1
     */
    public record AuthModuleRef(byte[] scriptHash, BigInteger abiVersion) {}
    /** Lifecycle tags; Phase 1 authorizes transfers only in Normal mode. */
    public sealed interface AccountMode permits Normal, Frozen, RecoveryPending {}
    /** Transfer-enabled mode. */
    public record Normal() implements AccountMode {}
    /** Frozen mode; transfers reject. */
    public record Frozen() implements AccountMode {}
    /**
     * Pending recovery; retained target cannot grant current spending authority.
     * @param proposalCommitment 32-byte commitment to the exact recovery proposal
     * @param executeAfter earliest completion time in POSIX milliseconds
     * @param targetConfig opaque proposed configuration; never current spending authority
     */
    public record RecoveryPending(byte[] proposalCommitment, BigInteger executeAfter, PlutusData targetConfig) implements AccountMode {}
    /**
     * Complete authenticated state datum. Timings are fixed at creation in V1.
     * @param schemaVersion wire schema version, currently 1
     * @param accountId full one-shot state NFT identity
     * @param deploymentDomain immutable network and deployment identity
     * @param coreBinding immutable state, asset and checkpoint script identities
     * @param stateVersion monotonic state version included in signature domains
     * @param authModule installed authorization module and ABI
     * @param authConfig opaque configuration interpreted by the installed module
     * @param recoverySequence authoritative monotonic recovery attempt counter
     * @param recoveryDelayMillis immutable recovery delay in milliseconds
     * @param recoveryCooldownMillis immutable recovery initiation cooldown in milliseconds
     * @param recoveryNotBefore earliest permitted recovery initiation in POSIX milliseconds
     * @param mode current lifecycle variant
     */
    public record AccountState(BigInteger schemaVersion, AccountId accountId, DeploymentDomain deploymentDomain,
            CoreBinding coreBinding, BigInteger stateVersion, AuthModuleRef authModule, PlutusData authConfig,
            BigInteger recoverySequence, BigInteger recoveryDelayMillis, BigInteger recoveryCooldownMillis,
            BigInteger recoveryNotBefore, AccountMode mode) {}
    /**
     * Signature domain includes the exact current state reference.
     * @param protocolVersion protocol version, currently 1
     * @param deploymentDomain immutable network and deployment identity
     * @param accountId full one-shot state NFT identity
     * @param coreBinding immutable state, asset and checkpoint script identities
     * @param stateVersion monotonic state version included in signature domains
     * @param stateRef exact authenticated state UTxO reference
     */
    public record IntentDomain(BigInteger protocolVersion, DeploymentDomain deploymentDomain, AccountId accountId,
            CoreBinding coreBinding, BigInteger stateVersion, TxOutRef stateRef) {}
    /**
     * Finite half-open POSIX millisecond validity interval.
     * @param notBefore inclusive lower time bound in POSIX milliseconds
     * @param expiresAt exclusive upper time bound in POSIX milliseconds
     */
    public record Validity(BigInteger notBefore, BigInteger expiresAt) {}
    /**
     * Canonically ordered positive asset quantity.
     * @param policy 28-byte policy ID, or empty bytes for lovelace in an Asset
     * @param name 0–32 byte native asset name, empty for lovelace
     * @param quantity positive asset quantity in its smallest unit
     */
    public record Asset(byte[] policy, byte[] name, BigInteger quantity) {}
    /**
     * Signed exact output index, full address and complete value.
     * @param outputIndex zero-based transaction output index
     * @param address complete recipient address including its stake position
     * @param value complete positive asset list in canonical policy/name order, including lovelace
     */
    public record Recipient(BigInteger outputIndex, Address address, JulcList<Asset> value) {}
    /** Fixed action indices; administration variants are retained but fail closed in Phase 1. */
    public sealed interface Action permits Spend, ReplaceConfig, ReplaceModule, Freeze, Unfreeze,
            StartRecovery, CancelRecovery, CompleteRecovery, TransferWholeUtxo {}
    /**
     * Exact-input partial transfer, including zero-recipient consolidation.
     * @param accountInputs nonempty strictly ordered exact account input references
     * @param recipients strictly output-index-ordered allocations; empty for consolidation
     * @param maxAccountFee maximum net account fee debit in lovelace
     */
    public record Spend(JulcList<TxOutRef> accountInputs, JulcList<Recipient> recipients, BigInteger maxAccountFee) implements Action {}
    /**
     * Phase 2 configuration replacement.
     * @param newConfig complete proposed module configuration
     */
    public record ReplaceConfig(PlutusData newConfig) implements Action {}
    /**
     * Phase 2 module replacement.
     * @param newModule proposed replacement module identity
     * @param newConfig complete proposed module configuration
     */
    public record ReplaceModule(AuthModuleRef newModule, PlutusData newConfig) implements Action {}
    /** Phase 2 freeze. */
    public record Freeze() implements Action {}
    /** Phase 2 unfreeze. */
    public record Unfreeze() implements Action {}
    /**
     * Phase 2 delayed recovery initiation.
     * @param nextSequence current recovery sequence plus one
     * @param replacementConfig complete proposed recovery target configuration
     */
    public record StartRecovery(BigInteger nextSequence, PlutusData replacementConfig) implements Action {}
    /**
     * Phase 2 independent cancellation.
     * @param currentSequence exact recovery attempt being cancelled or completed
     * @param proposalCommitment 32-byte commitment to the exact recovery proposal
     */
    public record CancelRecovery(BigInteger currentSequence, byte[] proposalCommitment) implements Action {}
    /**
     * Phase 2 target-authorized completion.
     * @param currentSequence exact recovery attempt being cancelled or completed
     * @param replacementConfig complete proposed recovery target configuration
     */
    public record CompleteRecovery(BigInteger currentSequence, PlutusData replacementConfig) implements Action {}
    /**
     * One full input transferred without account-funded fees.
     * @param accountInput the sole account input consumed by this whole-value transfer
     * @param recipientIndex zero-based output receiving the complete input value
     * @param recipientAddress complete destination address
     * @param inputValueDigest 32-byte digest of the complete canonical input Value
     */
    public record TransferWholeUtxo(TxOutRef accountInput, BigInteger recipientIndex, Address recipientAddress,
            byte[] inputValueDigest) implements Action {}
    /**
     * The sole signed operation preimage; never accept a separate caller-selected digest.
     * @param protocolTag exact KAVACH_INTENT protocol tag with version byte
     * @param domain full signature domain including current state reference
     * @param validity signed finite half-open time interval
     * @param action typed action; only Spend and TransferWholeUtxo execute in Phase 1
     */
    public record IntentEnvelope(byte[] protocolTag, IntentDomain domain, Validity validity, Action action) {}
    /**
     * Signature resolves its key from authenticated configuration.
     * @param credentialId unsigned credential ID resolved through the authenticated registry
     * @param signature 64-byte raw Ed25519 signature
     */
    public record Signature(BigInteger credentialId, byte[] signature) {}
    /**
     * Scheme 0 is raw Ed25519 over the canonical 32-byte envelope digest.
     * @param scheme signature scheme identifier; only raw Ed25519 scheme 0 is supported
     * @param signatures strictly credential-ID-ordered proofs without duplicates
     */
    public record Proof(BigInteger scheme, JulcList<Signature> signatures) {}
    /**
     * Positive reward allocation; output indices must be globally disjoint.
     * @param rewardCredential script stake credential receiving the positive withdrawal
     * @param outputIndex zero-based transaction output index
     */
    public record RewardReceipt(Credential rewardCredential, BigInteger outputIndex) {}
    /** Core's purpose-specific invocation variants. */
    public sealed interface CoreInvocation permits CoreRedeemer, CoreRegistration {}
    /**
     * Complete ordinary core invocation.
     * @param abiVersion supported module or invocation ABI version, currently 1
     * @param intent complete canonical signed operation envelope
     * @param receipts canonical positive-withdrawal allocations; not part of the signed envelope but bound across invocations
     */
    public record CoreRedeemer(BigInteger abiVersion, IntentEnvelope intent, JulcList<RewardReceipt> receipts) implements CoreInvocation {}
    /** Deposit-bearing registration only, never deregistration or authorization. */
    public record CoreRegistration() implements CoreInvocation {}
    /** Module's purpose-specific invocation variants. */
    public sealed interface ModuleInvocation permits ModuleRedeemer, ModuleRegistration, GenesisModuleRedeemer {}
    /**
     * Ordinary invocation; configuration possession must be empty for transfers.
     * @param abiVersion supported module or invocation ABI version, currently 1
     * @param intent complete canonical signed operation envelope
     * @param operationProof selected operation evidence; required for ordinary transfers
     * @param configPossession all-key possession evidence for genesis, empty for ordinary transfers
     * @param receipts canonical positive-withdrawal allocations; not part of the signed envelope but bound across invocations
     */
    public record ModuleRedeemer(BigInteger abiVersion, IntentEnvelope intent, Optional<Proof> operationProof,
            JulcList<Signature> configPossession, JulcList<RewardReceipt> receipts) implements ModuleInvocation {}
    /** Module registration only. */
    public record ModuleRegistration() implements ModuleInvocation {}
    /**
     * Initialization has a distinct all-key proof domain and no ordinary intent.
     * @param abiVersion supported module or invocation ABI version, currently 1
     * @param state complete proposed genesis state
     * @param configPossession all-key possession evidence for genesis, empty for ordinary transfers
     * @param receipts canonical positive-withdrawal allocations; not part of the signed envelope but bound across invocations
     */
    public record GenesisModuleRedeemer(BigInteger abiVersion, AccountState state,
            JulcList<Signature> configPossession, JulcList<RewardReceipt> receipts) implements ModuleInvocation {}
    /**
     * Per-input binding to the immutable checkpoint's canonical intent digest.
     * @param abiVersion supported module or invocation ABI version, currently 1
     * @param intentDigest 32-byte digest recomputed by the core from its full intent
     */
    public record AssetRedeemer(BigInteger abiVersion, byte[] intentDigest) {}
    /**
     * Separate genesis possession preimage, assembled after derived script hashes exist.
     * @param tag distinct genesis possession domain tag
     * @param deploymentDomain immutable network and deployment identity
     * @param accountId full one-shot state NFT identity
     * @param coreBinding immutable state, asset and checkpoint script identities
     * @param authModule installed authorization module and ABI
     * @param configDigest 32-byte digest of the full canonical module configuration
     */
    public record GenesisProofEnvelope(byte[] tag, DeploymentDomain deploymentDomain, AccountId accountId,
            CoreBinding coreBinding, AuthModuleRef authModule, byte[] configDigest) {}
}
