//// Port of the Java `PolicyLifecycleLib`: policy-module administration and recovery proof rules,
//// scheme 3. Kept as its own file to mirror the Java structure.

use aiken/builtin
use aiken/collection/list
use aiken/crypto
use cardano/transaction.{Transaction}
use kavach/account_lib.{digest}
use kavach/auth/ed25519.{ThresholdPolicy}
use kavach/auth/policy.{PolicyConfig}
use kavach/types.{
  AccountState, AuthModuleRef, CancelRecovery, CompleteRecovery, Freeze,
  IntentDomain, IntentEnvelope, Proof, RecoveryPending, ReplaceConfig,
  ReplaceModule, Signature, Spend, StartRecovery, TransferWholeUtxo, Unfreeze,
}

/// Configuration-possession domain, distinct from operation approval.
pub type ConfigProofEnvelope {
  tag: ByteArray,
  intent_digest: ByteArray,
  destination: AuthModuleRef,
  config_digest: ByteArray,
}

/// Recovery target-possession domain.
pub type TargetProofEnvelope {
  tag: ByteArray,
  completion_domain: IntentDomain,
  sequence: Int,
  proposal_commitment: ByteArray,
  config_digest: ByteArray,
}

/// Old-role approval, including canonical optional/proof shape.
/// Mirrors `PolicyLifecycleLib.approval`.
pub fn approval(
  supplied: Option<Proof>,
  current_config: PolicyConfig,
  selected: ThresholdPolicy,
  request: IntentEnvelope,
  network: Int,
  tx: Transaction,
) -> Bool {
  when supplied is {
    None -> False
    Some(proof) -> {
      let supplied_data: Data = supplied
      let expected: Data = Some(Proof { scheme: 3, signatures: proof.signatures })
      supplied_data == expected && policy.signatures(
        proof.signatures,
        current_config,
        digest(request),
        8,
        network,
        tx,
      ) && ed25519.threshold(proof.signatures, selected)
    }
  }
}

/// Mirrors `PolicyLifecycleLib.configMessage`.
pub fn config_message(
  request: IntentEnvelope,
  destination: AuthModuleRef,
  config: Data,
) -> ByteArray {
  let preimage =
    ConfigProofEnvelope {
      tag: "KAVACH_CONFIG_POSSESSION_V1",
      intent_digest: digest(request),
      destination,
      config_digest: crypto.blake2b_256(builtin.serialise_data(config)),
    }
  crypto.blake2b_256(builtin.serialise_data(preimage))
}

/// All-key possession under the destination configuration.
/// Mirrors `PolicyLifecycleLib.possession`.
pub fn possession(
  evidence: List<Signature>,
  destination: PolicyConfig,
  message: ByteArray,
  network: Int,
  tx: Transaction,
) -> Bool {
  list.length(evidence) == list.length(destination.roles.keys) && policy.signatures(
    evidence,
    destination,
    message,
    16,
    network,
    tx,
  )
}

/// A new module's configuration and all registry keys; no old-role approval.
/// Mirrors `PolicyLifecycleLib.candidate`.
pub fn candidate(
  intent: IntentEnvelope,
  operation_proof: Option<Proof>,
  config_possession: List<Signature>,
  new_module: AuthModuleRef,
  new_config: Data,
  network: Int,
  tx: Transaction,
) -> Bool {
  if operation_proof != None {
    False
  } else {
    let config = {
      expect decoded: PolicyConfig = new_config
      decoded
    }
    policy.configuration(config) && possession(
      config_possession,
      config,
      config_message(intent, new_module, new_config),
      network,
      tx,
    )
  }
}

/// Old-module role and target checks. Mirrors `PolicyLifecycleLib.current`.
pub fn current(
  intent: IntentEnvelope,
  operation_proof: Option<Proof>,
  config_possession: List<Signature>,
  previous: AccountState,
  network: Int,
  tx: Transaction,
) -> Bool {
  let config = {
      expect decoded: PolicyConfig = previous.auth_config
      decoded
    }
  if !policy.configuration(config) {
    False
  } else {
    let completing =
      when intent.action is {
        CompleteRecovery { .. } -> True
        _ -> False
      }
    if !completing && !role_approval(intent, operation_proof, config, network, tx) {
      False
    } else {
      when intent.action is {
        Spend { .. } | TransferWholeUtxo { .. } | ReplaceModule { .. } | Freeze | Unfreeze | CancelRecovery { .. } ->
          config_possession == []
        ReplaceConfig { new_config } -> {
          let target = {
      expect decoded: PolicyConfig = new_config
      decoded
    }
          policy.configuration(target) && possession(
            config_possession,
            target,
            config_message(intent, previous.auth_module, new_config),
            network,
            tx,
          )
        }
        StartRecovery { replacement_config, .. } ->
          if config_possession != [] {
            False
          } else {
            policy.configuration({
      expect decoded: PolicyConfig = replacement_config
      decoded
    })
          }
        CompleteRecovery { current_sequence, replacement_config } ->
          if operation_proof != None {
            False
          } else {
            when previous.mode is {
              RecoveryPending { proposal_commitment, target_config, .. } -> {
                let target = {
      expect decoded: PolicyConfig = replacement_config
      decoded
    }
                let preimage =
                  TargetProofEnvelope {
                    tag: "KAVACH_RECOVERY_TARGET_V1",
                    completion_domain: intent.domain,
                    sequence: previous.recovery_sequence,
                    proposal_commitment,
                    config_digest: crypto.blake2b_256(
                      builtin.serialise_data(replacement_config),
                    ),
                  }
                current_sequence == previous.recovery_sequence && replacement_config == target_config && policy.configuration(
                  target,
                ) && possession(
                  config_possession,
                  target,
                  crypto.blake2b_256(builtin.serialise_data(preimage)),
                  network,
                  tx,
                )
              }
              _ -> False
            }
          }
      }
    }
  }
}

/// Selects one authenticated old role, then verifies its evidence exactly once.
fn role_approval(
  intent: IntentEnvelope,
  operation_proof: Option<Proof>,
  config: PolicyConfig,
  network: Int,
  tx: Transaction,
) -> Bool {
  let selected =
    when intent.action is {
      Spend { .. } -> policy.spend_policy(config, intent.action)
      TransferWholeUtxo { .. } -> config.roles.spend
      ReplaceConfig { .. } | ReplaceModule { .. } -> config.roles.admin
      Freeze -> config.roles.freeze
      Unfreeze -> config.roles.unfreeze
      StartRecovery { .. } -> config.roles.recovery
      CancelRecovery { .. } -> config.roles.cancel
      CompleteRecovery { .. } -> fail @"completion has no old role"
    }
  approval(operation_proof, config, selected, intent, network, tx)
}
