//// Port of the Java `BrowserLifecycleLib`: browser-module administration and recovery
//// proof rules. Old-role authority cannot be replaced by candidate possession.

use aiken/builtin
use aiken/collection/list
use aiken/crypto
use cardano/transaction.{Transaction}
use kavach/account_lib.{digest}
use kavach/auth/browser_evidence
use kavach/auth/ed25519.{Ed25519Config, KeyEntry, ThresholdPolicy}
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
/// Mirrors `BrowserLifecycleLib.approval`.
pub fn approval(
  supplied: Option<Proof>,
  current_config: Ed25519Config,
  selected: ThresholdPolicy,
  request: IntentEnvelope,
  mode: Int,
  network: Int,
  tx: Transaction,
) -> Bool {
  when supplied is {
    None -> False
    Some(proof) -> {
      let supplied_data: Data = supplied
      let expected: Data = Some(Proof { scheme: mode, signatures: proof.signatures })
      supplied_data == expected && browser_evidence.signatures(
        proof.signatures,
        current_config.keys,
        digest(request),
        8,
        mode,
        network,
        tx,
      ) && ed25519.threshold(proof.signatures, selected)
    }
  }
}

/// Configuration-possession digest for the exact destination.
/// Mirrors `BrowserLifecycleLib.configMessage`.
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

/// Possession of each key whose public bytes are not in the old registry, and the optional
/// target threshold. Mirrors `BrowserLifecycleLib.introduced`.
pub fn introduced(
  evidence: List<Signature>,
  old_keys: List<KeyEntry>,
  destination: Ed25519Config,
  message: ByteArray,
  target_threshold: Bool,
  mode: Int,
  network: Int,
  tx: Transaction,
) -> Bool {
  if evidence != [] && !browser_evidence.signatures(
    evidence,
    destination.keys,
    message,
    16,
    mode,
    network,
    tx,
  ) {
    False
  } else {
    let introduced_count =
      introduced_keys(old_keys, destination.keys, evidence)
    introduced_count >= 0 && if target_threshold {
      ed25519.threshold(evidence, destination.spend)
    } else {
      list.length(evidence) == introduced_count
    }
  }
}

fn introduced_keys(
  old_keys: List<KeyEntry>,
  destination: List<KeyEntry>,
  evidence: List<Signature>,
) -> Int {
  when destination is {
    [] -> 0
    [candidate_key, ..rest] -> {
      let retained = ed25519.public_key_known(candidate_key.public_key, old_keys)
      let proven =
        when evidence is {
          [head, ..] -> head.credential_id == candidate_key.credential_id
          [] -> False
        }
      if !retained && !proven {
        -1
      } else {
        let next =
          if proven {
            when evidence is {
              [_, ..tail] -> tail
              [] -> []
            }
          } else {
            evidence
          }
        let remaining = introduced_keys(old_keys, rest, next)
        if remaining < 0 {
          -1
        } else if retained {
          remaining
        } else {
          remaining + 1
        }
      }
    }
  }
}

/// A new module's configuration and all registry keys; no old-role approval.
/// Mirrors `BrowserLifecycleLib.candidate`.
pub fn candidate(
  intent: IntentEnvelope,
  operation_proof: Option<Proof>,
  config_possession: List<Signature>,
  new_module: AuthModuleRef,
  new_config: Data,
  mode: Int,
  network: Int,
  tx: Transaction,
) -> Bool {
  if operation_proof != None {
    False
  } else {
    expect config: Ed25519Config = new_config
    ed25519.configuration(config) && list.length(config_possession) == list.length(
      config.keys,
    ) && browser_evidence.signatures(
      config_possession,
      config.keys,
      config_message(intent, new_module, new_config),
      16,
      mode,
      network,
      tx,
    )
  }
}

/// Old-module role and target checks; immutable core separately enforces lifecycle.
/// Mirrors `BrowserLifecycleLib.current`.
pub fn current(
  intent: IntentEnvelope,
  operation_proof: Option<Proof>,
  config_possession: List<Signature>,
  previous: AccountState,
  mode: Int,
  network: Int,
  tx: Transaction,
) -> Bool {
  expect config: Ed25519Config = previous.auth_config
  if !ed25519.configuration(config) {
    False
  } else {
    let completing =
      when intent.action is {
        CompleteRecovery { .. } -> True
        _ -> False
      }
    if !completing && !role_approval(
      intent,
      operation_proof,
      config,
      mode,
      network,
      tx,
    ) {
      False
    } else {
      when intent.action is {
        Spend { .. } | TransferWholeUtxo { .. } | ReplaceModule { .. } | Freeze | Unfreeze | CancelRecovery { .. } ->
          config_possession == []
        ReplaceConfig { new_config } -> {
          expect target: Ed25519Config = new_config
          ed25519.configuration(target) && introduced(
            config_possession,
            config.keys,
            target,
            config_message(intent, previous.auth_module, new_config),
            False,
            mode,
            network,
            tx,
          )
        }
        StartRecovery { replacement_config, .. } ->
          if config_possession != [] {
            False
          } else {
            expect target: Ed25519Config = replacement_config
            ed25519.configuration(target)
          }
        CompleteRecovery { current_sequence, replacement_config } ->
          if operation_proof != None {
            False
          } else {
            when previous.mode is {
              RecoveryPending { proposal_commitment, target_config, .. } -> {
                expect target: Ed25519Config = replacement_config
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
                current_sequence == previous.recovery_sequence && replacement_config == target_config && ed25519.configuration(
                  target,
                ) && introduced(
                  config_possession,
                  config.keys,
                  target,
                  crypto.blake2b_256(builtin.serialise_data(preimage)),
                  True,
                  mode,
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
  config: Ed25519Config,
  mode: Int,
  network: Int,
  tx: Transaction,
) -> Bool {
  let selected =
    when intent.action is {
      Spend { .. } | TransferWholeUtxo { .. } -> config.spend
      ReplaceConfig { .. } | ReplaceModule { .. } -> config.admin
      Freeze -> config.freeze
      Unfreeze -> config.unfreeze
      StartRecovery { .. } -> config.recovery
      CancelRecovery { .. } -> config.cancel
      CompleteRecovery { .. } -> fail @"completion has no old role"
    }
  approval(operation_proof, config, selected, intent, mode, network, tx)
}
