//// Port of the Java `BudgetPolicyModule`.
////
//// Policy module whose configuration is wrapped in the core periodic-budget envelope. Independently authenticates canonical state, resolves configured keys
//// and binds the entire intent/receipt table to the immutable core. Genesis is not supported;
//// accounts are created through the mixed setup module.
////
//// Immutable parameters, in order: `module_version`, `abi_version`, `deployment_domain`,
//// `state_validator_hash`, `core_checkpoint_hash`, `reward_sink` — the Java
//// `@Param` order.

use aiken/builtin
use aiken/collection/list
use aiken/collection/pairs
use cardano/address.{Address, Credential, Script}
use cardano/certificate.{Certificate}
use cardano/transaction.{ScriptPurpose, Spend, Transaction, Withdraw}
use kavach/account_lib.{asset_count, envelope, module_shape, shape, transaction_shape}
use kavach/auth/budget_policy_lifecycle
use kavach/state_transition
use kavach/types.{
  AccountState, CoreRedeemer, DeploymentDomain, IntentEnvelope, ModuleInvocation,
  ModuleRedeemer, Proof,
  ReplaceModule, RewardReceipt, Signature, TransferWholeUtxo,
}

validator budget_policy_module(
  module_version: Int,
  abi_version: Int,
  deployment_domain: DeploymentDomain,
  state_validator_hash: ByteArray,
  core_checkpoint_hash: ByteArray,
  reward_sink: Address,
) {
  /// Certifying cannot satisfy either operation approval or genesis possession.
  publish(redeemer: Data, certificate: Certificate, _tx: Transaction) {
    module_version == 1 && abi_version == 1 && redeemer == builtin.constr_data(
      1,
      [],
    ) && account_lib.registration(certificate)
  }

  /// Dispatches distinct ordinary/genesis ABI variants.
  withdraw(redeemer: Data, account: Credential, tx: Transaction) {
    if module_version != 1 || abi_version != 1 {
      False
    } else {
      expect invocation: ModuleInvocation = redeemer
      let network = deployment_domain.network_id
      when invocation is {
        ModuleRedeemer {
          abi_version: invocation_abi,
          intent,
          operation_proof,
          config_possession,
          receipts,
        } ->
          when intent.action is {
            types.Spend { .. } | TransferWholeUtxo { .. } ->
              authorize(
                redeemer,
                invocation_abi,
                intent,
                operation_proof,
                config_possession,
                receipts,
                account,
                tx,
                deployment_domain,
                state_validator_hash,
                core_checkpoint_hash,
                reward_sink,
                network,
              )
            _ ->
              mutate(
                redeemer,
                invocation_abi,
                intent,
                operation_proof,
                config_possession,
                receipts,
                account,
                tx,
                deployment_domain,
                state_validator_hash,
                core_checkpoint_hash,
                reward_sink,
                network,
              )
          }
        _ -> False
      }
    }
  }

  else(_) {
    fail @"unsupported script purpose"
  }
}

/// Spend-role evidence under the existing authenticated state.
fn authorize(
  invocation: Data,
  invocation_abi: Int,
  intent: IntentEnvelope,
  operation_proof: Option<Proof>,
  config_possession: List<Signature>,
  receipts: List<RewardReceipt>,
  account: Credential,
  tx: Transaction,
  deployment_domain: DeploymentDomain,
  state_validator_hash: ByteArray,
  core_checkpoint_hash: ByteArray,
  reward_sink: Address,
  network: Int,
) -> Bool {
  if !transaction_shape(tx) || invocation_abi != 1 || !envelope(intent, tx) || list.length(
    tx.withdrawals,
  ) != 2 || asset_count(tx.mint) != 0 {
    False
  } else {
    let state = account_lib.resolve(intent.domain, tx)
    if !account_lib.authenticate(
      state,
      intent.domain,
      deployment_domain,
      state_validator_hash,
      tx,
    ) || state.core_binding.checkpoint != core_checkpoint_hash {
      False
    } else {
      let own = Script(state.auth_module.script_hash)
      bound(invocation, intent, receipts, state, own, account, tx, reward_sink) && budget_policy_lifecycle.current(
        intent,
        operation_proof,
        config_possession,
        state,
        network,
        tx,
      )
    }
  }
}

/// Independently authenticates old state; candidate validation cannot substitute for old
/// authority.
fn mutate(
  invocation: Data,
  invocation_abi: Int,
  intent: IntentEnvelope,
  operation_proof: Option<Proof>,
  config_possession: List<Signature>,
  receipts: List<RewardReceipt>,
  account: Credential,
  tx: Transaction,
  deployment_domain: DeploymentDomain,
  state_validator_hash: ByteArray,
  core_checkpoint_hash: ByteArray,
  reward_sink: Address,
  network: Int,
) -> Bool {
  if invocation_abi != 1 || !envelope(intent, tx) {
    False
  } else {
    let previous = state_transition.resolve(intent.domain, tx)
    if !state_transition.authenticate(
      previous,
      intent.domain,
      deployment_domain,
      state_validator_hash,
      tx,
    ) || previous.core_binding.checkpoint != core_checkpoint_hash {
      False
    } else {
      let own = account
      let old_credential = Script(previous.auth_module.script_hash)
      let core_invocation: Data =
        CoreRedeemer { abi_version: 1, intent, receipts }
      let state_purpose: ScriptPurpose = Spend(intent.domain.state_ref)
      if !bound(invocation, intent, receipts, previous, own, account, tx, reward_sink) || pairs.get_first(
        tx.redeemers,
        state_purpose,
      ) != Some(core_invocation) {
        False
      } else {
        let withdrawals = list.length(tx.withdrawals)
        when intent.action is {
          ReplaceModule { new_module, new_config } -> {
            let candidate = Script(new_module.script_hash)
            module_shape(new_module) && candidate != old_credential && candidate != Script(
              core_checkpoint_hash,
            ) && withdrawals == 3 && pairs.has_key(tx.withdrawals, old_credential) && pairs.has_key(
              tx.withdrawals,
              candidate,
            ) && if own == old_credential {
              budget_policy_lifecycle.current(
                intent,
                operation_proof,
                config_possession,
                previous,
                network,
                tx,
              )
            } else {
              own == candidate && budget_policy_lifecycle.candidate(
                intent,
                operation_proof,
                config_possession,
                new_module,
                new_config,
                network,
                tx,
              )
            }
          }
          _ ->
            withdrawals == 2 && own == old_credential && budget_policy_lifecycle.current(
              intent,
              operation_proof,
              config_possession,
              previous,
              network,
              tx,
            )
        }
      }
    }
  }
}

/// Exact purpose/envelope/receipt binding for reference-state and consumed-state operations.
fn bound(
  invocation: Data,
  intent: IntentEnvelope,
  receipts: List<RewardReceipt>,
  previous: AccountState,
  expected_own: Credential,
  account: Credential,
  tx: Transaction,
  reward_sink: Address,
) -> Bool {
  let own_purpose: ScriptPurpose = Withdraw(expected_own)
  let core_invocation = CoreRedeemer { abi_version: 1, intent, receipts }
  account == expected_own && state_transition.core_binding(
    core_invocation,
    previous,
    tx,
  ) && shape(invocation, 0, 5) && pairs.has_key(tx.withdrawals, expected_own) && pairs.get_first(
    tx.redeemers,
    own_purpose,
  ) == Some(invocation) && account_lib.receipts(receipts, tx) && account_lib.own_sink(
    expected_own,
    reward_sink,
    receipts,
    tx,
  )
}
