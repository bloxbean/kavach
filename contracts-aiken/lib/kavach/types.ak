//// Typed views of `protocol/v1/kavach.cddl`, mirroring the Java `AccountTypes`.
////
//// Field order and variant order are wire ABI, not implementation detail. Every record
//// below is constructor 0 with its fields in CDDL order; every enum's variant order is
//// its constructor index. These must agree with the Java records byte-for-byte, which
//// `conformance/v1/state.hex` pins.

use aiken/crypto.{Blake2b_256, Hash, ScriptHash}
use cardano/address.{Address, Credential}
use cardano/transaction.{OutputReference}

/// `account-id = #6.121([policy: hash28, name: bytes .size 0])`
pub type AccountId {
  policy: ByteArray,
  name: ByteArray,
}

/// `deployment-domain = #6.121([network-id, network-magic, deployment-id: hash32])`
pub type DeploymentDomain {
  network_id: Int,
  network_magic: Int,
  deployment_id: ByteArray,
}

/// `core-binding = #6.121([state-validator, asset-validator, checkpoint])`, all hash28.
pub type CoreBinding {
  state_validator: ByteArray,
  asset_validator: ByteArray,
  checkpoint: ByteArray,
}

/// `auth-module-ref = #6.121([script-hash: hash28, abi-version: 1])`
pub type AuthModuleRef {
  script_hash: ByteArray,
  abi_version: Int,
}

/// `mode = #6.121([]) / #6.122([]) / #6.123([commitment, execute-after, target-config])`
pub type AccountMode {
  Normal
  Frozen
  RecoveryPending {
    proposal_commitment: Hash<Blake2b_256, ByteArray>,
    execute_after: Int,
    target_config: Data,
  }
}

/// `account-state = #6.121([...12 fields...])`. Timings are fixed at creation in V1.
pub type AccountState {
  schema_version: Int,
  account_id: AccountId,
  deployment_domain: DeploymentDomain,
  core_binding: CoreBinding,
  state_version: Int,
  auth_module: AuthModuleRef,
  auth_config: Data,
  recovery_sequence: Int,
  recovery_delay_millis: Int,
  recovery_cooldown_millis: Int,
  recovery_not_before: Int,
  mode: AccountMode,
}

/// `intent-domain = #6.121([protocol-version, deployment-domain, account-id,
/// core-binding, state-version, state-ref])`
pub type IntentDomain {
  protocol_version: Int,
  deployment_domain: DeploymentDomain,
  account_id: AccountId,
  core_binding: CoreBinding,
  state_version: Int,
  state_ref: OutputReference,
}

/// `validity = #6.121([not-before: uint63, expires-at: uint63])`
pub type Validity {
  not_before: Int,
  expires_at: Int,
}

/// `asset = #6.121([policy, name, quantity])`
pub type Asset {
  policy: ByteArray,
  name: ByteArray,
  quantity: Int,
}

/// `recipient = #6.121([output-index, address, value])`
pub type Recipient {
  output_index: Int,
  address: Address,
  value: List<Asset>,
}

/// Fixed action indices 0..8. `TransferWholeUtxo` is index 8, not 7; placing anything
/// else there would silently change the meaning of every previously signed intent.
pub type Action {
  Spend { account_inputs: List<OutputReference>, recipients: List<Recipient>, max_account_fee: Int }
  ReplaceConfig { new_config: Data }
  ReplaceModule { new_module: AuthModuleRef, new_config: Data }
  Freeze
  Unfreeze
  StartRecovery { next_sequence: Int, replacement_config: Data }
  CancelRecovery { current_sequence: Int, proposal_commitment: ByteArray }
  CompleteRecovery { current_sequence: Int, replacement_config: Data }
  TransferWholeUtxo {
    account_input: OutputReference,
    recipient_index: Int,
    recipient_address: Address,
    input_value_digest: ByteArray,
  }
}

/// `intent-envelope = #6.121([protocol-tag: bytes .size 14, intent-domain, validity, action])`
pub type IntentEnvelope {
  protocol_tag: ByteArray,
  domain: IntentDomain,
  validity: Validity,
  action: Action,
}

/// `reward-receipt = #6.121([reward-credential: #6.122([hash28]), output-index])`
pub type RewardReceipt {
  reward_credential: Credential,
  output_index: Int,
}

/// `core-redeemer = #6.121([abi-version: 1, intent-envelope, receipts: [*3 reward-receipt]])`
pub type CoreRedeemer {
  abi_version: Int,
  intent: IntentEnvelope,
  receipts: List<RewardReceipt>,
}

/// `signature = #6.121([credential-id: uint, signature: bytes])`
pub type Signature {
  credential_id: Int,
  signature: ByteArray,
}

/// `proof = #6.121([scheme: uint, signatures: [* signature]])`
pub type Proof {
  scheme: Int,
  signatures: List<Signature>,
}

/// Module invocation variants; constructor order is wire ABI (tags 0, 1, 2).
pub type ModuleInvocation {
  /// Ordinary invocation; configuration possession must be empty for transfers.
  ModuleRedeemer {
    abi_version: Int,
    intent: IntentEnvelope,
    operation_proof: Option<Proof>,
    config_possession: List<Signature>,
    receipts: List<RewardReceipt>,
  }
  /// Module registration only.
  ModuleRegistration
  /// Initialization has a distinct all-key proof domain and no ordinary intent.
  GenesisModuleRedeemer {
    abi_version: Int,
    state: AccountState,
    config_possession: List<Signature>,
    receipts: List<RewardReceipt>,
  }
}

/// `asset-redeemer = #6.121([abi-version: 1, intent-digest: hash32])`
pub type AssetRedeemer {
  abi_version: Int,
  intent_digest: ByteArray,
}

/// Separate genesis possession preimage, assembled after derived script hashes exist.
pub type GenesisProofEnvelope {
  tag: ByteArray,
  deployment_domain: DeploymentDomain,
  account_id: AccountId,
  core_binding: CoreBinding,
  auth_module: AuthModuleRef,
  config_digest: ByteArray,
}

/// `recovery-commitment = #6.121([intent-domain, next-sequence, auth-module-ref,
/// replacement-config, old-delay, old-cooldown])` — the six-field preimage, in wire order.
pub type ProposalEnvelope {
  intent_domain: IntentDomain,
  next_sequence: Int,
  module: AuthModuleRef,
  replacement_config: Data,
  delay: Int,
  cooldown: Int,
}

/// Unused alias retained so `ScriptHash` import documents the hash width the protocol uses.
pub type CoreScriptHash =
  ScriptHash
