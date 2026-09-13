//// Port of the Java `LifecycleLib`: immutable V1 lifecycle semantics.
////
//// Computes the sole permitted successor from an authenticated old state and a canonical
//// intent. Verifies no signatures, NFT custody or transaction outputs — the state validator
//// composes those. Timings use the effective ledger interval, never a builder-supplied clock.

use aiken/builtin
use aiken/crypto
use aiken/interval.{Finite}
use cardano/transaction.{Transaction}
use kavach/account_lib.{shape, uint63}
use kavach/types.{
  AccountState, AuthModuleRef, CancelRecovery, CompleteRecovery, DeploymentDomain,
  Freeze, Frozen, IntentEnvelope, Normal, ProposalEnvelope, RecoveryPending,
  ReplaceConfig, ReplaceModule, Spend, StartRecovery, TransferWholeUtxo, Unfreeze,
}

/// All lifecycle datum fields, without interpreting opaque module configuration.
/// Mirrors `LifecycleLib.stateShape`.
pub fn state_shape(
  previous: AccountState,
  deployment: DeploymentDomain,
  custody_hash: ByteArray,
) -> Bool {
  if !account_lib.state_fields(previous, deployment, custody_hash) {
    False
  } else {
    let bytes = builtin.length_of_bytearray(builtin.serialise_data(previous))
    when previous.mode is {
      Normal -> shape(previous.mode, 0, 0) && bytes <= 1536
      Frozen -> shape(previous.mode, 1, 0) && bytes <= 1536
      RecoveryPending { proposal_commitment, execute_after, target_config } ->
        shape(previous.mode, 2, 3) && 0 < previous.recovery_sequence && builtin.length_of_bytearray(
          proposal_commitment,
        ) == 32 && uint63(execute_after) && builtin.length_of_bytearray(
          builtin.serialise_data(target_config),
        ) <= 1024 && bytes <= 3072
    }
  }
}

/// Bounded sum or script error; no saturation and no wraparound.
/// Mirrors `LifecycleLib.checkedAdd`.
pub fn checked_add(left: Int, right: Int) -> Int {
  let result = left + right
  if !uint63(left) || !uint63(right) || !uint63(result) {
    fail @"checked addition left the protocol's unsigned 63-bit range"
  } else {
    result
  }
}

/// Derives the initiation commitment from authenticated fields, never a caller-selected hash.
/// Mirrors `LifecycleLib.proposal`.
pub fn proposal(
  previous: AccountState,
  request: IntentEnvelope,
  attempt: Int,
  target: Data,
) -> ByteArray {
  let preimage =
    ProposalEnvelope {
      intent_domain: request.domain,
      next_sequence: attempt,
      module: previous.auth_module,
      replacement_config: target,
      delay: previous.recovery_delay_millis,
      cooldown: previous.recovery_cooldown_millis,
    }
  crypto.blake2b_256(builtin.serialise_data(preimage))
}

/// Extends the core-managed deadline monotonically, from the conservative upper endpoint.
/// Mirrors `LifecycleLib.extendCooldown`.
pub fn extend_cooldown(previous: AccountState, endpoint: Int) -> Int {
  let candidate = checked_add(endpoint, previous.recovery_cooldown_millis)
  if candidate < previous.recovery_not_before {
    previous.recovery_not_before
  } else {
    candidate
  }
}

/// Rebuilds every state field explicitly, so no transition can accidentally mutate an
/// immutable field. Mirrors `LifecycleLib.copy`.
pub fn copy(
  previous: AccountState,
  version: Int,
  module: AuthModuleRef,
  config: Data,
  sequence: Int,
  next_time: Int,
  next_mode: types.AccountMode,
) -> AccountState {
  AccountState {
    schema_version: previous.schema_version,
    account_id: previous.account_id,
    deployment_domain: previous.deployment_domain,
    core_binding: previous.core_binding,
    state_version: version,
    auth_module: module,
    auth_config: config,
    recovery_sequence: sequence,
    recovery_delay_millis: previous.recovery_delay_millis,
    recovery_cooldown_millis: previous.recovery_cooldown_millis,
    recovery_not_before: next_time,
    mode: next_mode,
  }
}

/// Selects the sole permitted successor; unsupported modes or actions error. Callers must
/// first authenticate the old state and domain. Configuration validity and authority remain
/// mandatory independent module checks. Mirrors `LifecycleLib.successor`.
pub fn successor(
  previous: AccountState,
  request: IntentEnvelope,
  tx: Transaction,
) -> AccountState {
  let lower =
    when tx.validity_range.lower_bound.bound_type is {
      Finite(time) -> time
      _ -> -1
    }
  let upper =
    when tx.validity_range.upper_bound.bound_type is {
      Finite(time) -> time
      _ -> -1
    }
  if !account_lib.envelope(request, tx) || !uint63(lower) || !uint63(upper) {
    fail @"envelope or ledger interval is outside the signed profile"
  } else {
    let version = checked_add(previous.state_version, 1)
    let normal = previous.mode == Normal
    let frozen = previous.mode == Frozen
    when request.action is {
      ReplaceConfig { new_config } -> {
        expect normal && shape(request.action, 1, 1)
        copy(
          previous,
          version,
          previous.auth_module,
          new_config,
          previous.recovery_sequence,
          previous.recovery_not_before,
          Normal,
        )
      }
      ReplaceModule { new_module, new_config } -> {
        expect normal && shape(request.action, 2, 2) && account_lib.module_shape(
          new_module,
        ) && new_module.script_hash != previous.auth_module.script_hash && new_module.script_hash != previous.core_binding.checkpoint
        copy(
          previous,
          version,
          new_module,
          new_config,
          previous.recovery_sequence,
          previous.recovery_not_before,
          Normal,
        )
      }
      Freeze -> {
        expect normal && shape(request.action, 3, 0)
        copy(
          previous,
          version,
          previous.auth_module,
          previous.auth_config,
          previous.recovery_sequence,
          previous.recovery_not_before,
          Frozen,
        )
      }
      Unfreeze -> {
        expect frozen && shape(request.action, 4, 0)
        copy(
          previous,
          version,
          previous.auth_module,
          previous.auth_config,
          previous.recovery_sequence,
          previous.recovery_not_before,
          Normal,
        )
      }
      StartRecovery { next_sequence, replacement_config } -> {
        let sequence = checked_add(previous.recovery_sequence, 1)
        expect ( normal || frozen ) && shape(request.action, 5, 2) && next_sequence == sequence && lower >= previous.recovery_not_before
        let deadline = checked_add(upper, previous.recovery_delay_millis)
        let next_time = extend_cooldown(previous, upper)
        let pending =
          RecoveryPending {
            proposal_commitment: proposal(
              previous,
              request,
              sequence,
              replacement_config,
            ),
            execute_after: deadline,
            target_config: replacement_config,
          }
        copy(
          previous,
          version,
          previous.auth_module,
          previous.auth_config,
          sequence,
          next_time,
          pending,
        )
      }
      CancelRecovery { current_sequence, proposal_commitment } -> {
        let allowed =
          when previous.mode is {
            RecoveryPending { proposal_commitment: pending_commitment, .. } ->
              current_sequence == previous.recovery_sequence && proposal_commitment == pending_commitment
            _ -> False
          }
        expect allowed && shape(request.action, 6, 2)
        copy(
          previous,
          version,
          previous.auth_module,
          previous.auth_config,
          previous.recovery_sequence,
          extend_cooldown(previous, upper),
          Frozen,
        )
      }
      CompleteRecovery { current_sequence, replacement_config } -> {
        let allowed =
          when previous.mode is {
            RecoveryPending { execute_after, target_config, .. } ->
              current_sequence == previous.recovery_sequence && execute_after <= lower && replacement_config == target_config
            _ -> False
          }
        expect allowed && shape(request.action, 7, 2)
        copy(
          previous,
          version,
          previous.auth_module,
          replacement_config,
          previous.recovery_sequence,
          previous.recovery_not_before,
          Normal,
        )
      }
      // Spend and TransferWholeUtxo are asset operations, not state mutations. The Java
      // `successor` reaches its `default -> error` branch for both.
      Spend { .. } -> fail @"Spend is not a state mutation"
      TransferWholeUtxo { .. } -> fail @"TransferWholeUtxo is not a state mutation"
    }
  }
}
