//// Port of the Java `CoreCheckpoint`.
////
//// Immutable transaction-level transfer authorization. Authenticates canonical state and
//// exact replay resources independently of module approval. The required anchor asset input
//// executes value accounting; checkpoint success alone is insufficient for a spend. State
//// mutations instead require the consumed immutable state validator as their anchor.
////
//// Immutable parameters, in order: `core_version`, `deployment_domain`,
//// `state_validator_hash`, `reward_sink` — the Java `@Param` order.

use aiken/builtin
use aiken/collection/list
use aiken/collection/pairs
use cardano/address.{Address, Credential, Script}
use cardano/certificate.{Certificate}
use cardano/transaction.{ScriptPurpose, Spend, Transaction, Withdraw}
use kavach/account_lib.{asset_count, enterprise, envelope, transaction_shape}
use kavach/state_transition
use kavach/types.{
  CancelRecovery, CompleteRecovery, CoreRedeemer, DeploymentDomain, Freeze,
  ModuleInvocation, ModuleRedeemer, ReplaceConfig, ReplaceModule, StartRecovery,
  TransferWholeUtxo, Unfreeze,
}

validator core_checkpoint(
  core_version: Int,
  deployment_domain: DeploymentDomain,
  state_validator_hash: ByteArray,
  reward_sink: Address,
) {
  /// Only the distinct empty registration redeemer under Certifying.
  publish(redeemer: Data, certificate: Certificate, _tx: Transaction) {
    core_version == 1 && redeemer == builtin.constr_data(1, []) && account_lib.registration(
      certificate,
    )
  }

  /// A canonical ordinary invocation under this checkpoint's Rewarding purpose.
  withdraw(redeemer: Data, account: Credential, tx: Transaction) {
    let Pair(tag, _) = builtin.un_constr_data(redeemer)
    if tag != 0 {
      // The Java dispatch returns false for CoreRegistration and errors on unknown tags.
      False
    } else {
      expect core: CoreRedeemer = redeemer
      when core.intent.action is {
        types.Spend { .. } | TransferWholeUtxo { .. } ->
          transfer(
            core,
            account,
            tx,
            core_version,
            deployment_domain,
            state_validator_hash,
            reward_sink,
          )
        _ ->
          mutate(
            core,
            account,
            tx,
            core_version,
            deployment_domain,
            state_validator_hash,
            reward_sink,
          )
      }
    }
  }

  else(_) {
    fail @"unsupported script purpose"
  }
}

/// Composes state, module, receipts and value/replay checks without trusting relayer hints.
fn transfer(
  core: CoreRedeemer,
  account: Credential,
  tx: Transaction,
  core_version: Int,
  deployment_domain: DeploymentDomain,
  state_validator_hash: ByteArray,
  reward_sink: Address,
) -> Bool {
  let core_data: Data = core
  let rebuilt: Data =
    CoreRedeemer {
      abi_version: core.abi_version,
      intent: core.intent,
      receipts: core.receipts,
    }
  if core_version != 1 || core.abi_version != 1 || !transaction_shape(tx) || asset_count(
    tx.mint,
  ) != 0 || list.length(tx.withdrawals) != 2 || core_data != rebuilt || !envelope(
    core.intent,
    tx,
  ) {
    False
  } else {
    let state = account_lib.resolve(core.intent.domain, tx)
    if !account_lib.authenticate(
      state,
      core.intent.domain,
      deployment_domain,
      state_validator_hash,
      tx,
    ) {
      False
    } else {
      let own = Script(state.core_binding.checkpoint)
      let module = Script(state.auth_module.script_hash)
      let module_purpose: ScriptPurpose = Withdraw(module)
      let own_purpose: ScriptPurpose = Withdraw(own)
      if account != own || !pairs.has_key(tx.withdrawals, own) || !pairs.has_key(
        tx.withdrawals,
        module,
      ) || !pairs.has_key(tx.redeemers, module_purpose) || pairs.get_first(
        tx.redeemers,
        own_purpose,
      ) != Some(core_data) {
        False
      } else {
        expect Some(auth_data) = pairs.get_first(tx.redeemers, module_purpose)
        expect auth: ModuleInvocation = auth_data
        let module_bound =
          when auth is {
            ModuleRedeemer { operation_proof, config_possession, .. } -> {
              let expected: Data =
                ModuleRedeemer {
                  abi_version: 1,
                  intent: core.intent,
                  operation_proof,
                  config_possession,
                  receipts: core.receipts,
                }
              auth_data == expected && config_possession == []
            }
            _ -> False
          }
        if !module_bound || !account_lib.receipts(core.receipts, tx) || !account_lib.own_sink(
          own,
          reward_sink,
          core.receipts,
          tx,
        ) {
          False
        } else {
          let account_address = enterprise(state.core_binding.asset_validator)
          let anchor =
            when core.intent.action is {
              types.Spend { account_inputs, .. } -> {
                expect [first, ..] = account_inputs
                first
              }
              TransferWholeUtxo { account_input, .. } -> account_input
              _ -> core.intent.domain.state_ref
            }
          let supported =
            when core.intent.action is {
              types.Spend { .. } | TransferWholeUtxo { .. } -> True
              _ -> False
            }
          // This exact consumed anchor must execute the immutable asset validator's
          // accounting branch.
          list.any(
            tx.inputs,
            fn(input) {
              input.output_reference == anchor && input.output.address == account_address
            },
          ) && supported
        }
      }
    }
  }
}

/// Requires old-module approval and a consumed immutable state anchor for a mutation.
fn mutate(
  invocation: CoreRedeemer,
  account: Credential,
  tx: Transaction,
  core_version: Int,
  deployment_domain: DeploymentDomain,
  state_validator_hash: ByteArray,
  reward_sink: Address,
) -> Bool {
  if core_version != 1 || !envelope(invocation.intent, tx) {
    False
  } else {
    let previous = state_transition.resolve(invocation.intent.domain, tx)
    if !state_transition.authenticate(
      previous,
      invocation.intent.domain,
      deployment_domain,
      state_validator_hash,
      tx,
    ) || !state_transition.core_binding(invocation, previous, tx) {
      False
    } else {
      let own = Script(previous.core_binding.checkpoint)
      let state_purpose: ScriptPurpose = Spend(invocation.intent.domain.state_ref)
      let invocation_data: Data = invocation
      if account != own || pairs.get_first(tx.redeemers, state_purpose) != Some(
        invocation_data,
      ) || !account_lib.receipts(invocation.receipts, tx) || !account_lib.own_sink(
        own,
        reward_sink,
        invocation.receipts,
        tx,
      ) {
        False
      } else {
        let old_credential = Script(previous.auth_module.script_hash)
        if !module_invocation(invocation, old_credential, tx) {
          False
        } else {
          let withdrawals = list.length(tx.withdrawals)
          when invocation.intent.action is {
            ReplaceModule { new_module, .. } -> {
              let candidate = Script(new_module.script_hash)
              account_lib.module_shape(new_module) && candidate != old_credential && candidate != own && withdrawals == 3 && module_invocation(
                invocation,
                candidate,
                tx,
              )
            }
            ReplaceConfig { .. } | Freeze | Unfreeze | StartRecovery { .. } | CancelRecovery { .. } | CompleteRecovery { .. } ->
              withdrawals == 2
            _ -> False
          }
        }
      }
    }
  }
}

/// Binds each independently executing module to the exact same envelope and receipt table.
fn module_invocation(
  invocation: CoreRedeemer,
  selected: Credential,
  tx: Transaction,
) -> Bool {
  if !pairs.has_key(tx.withdrawals, selected) {
    False
  } else {
    let purpose: ScriptPurpose = Withdraw(selected)
    when pairs.get_first(tx.redeemers, purpose) is {
      None -> False
      Some(module_data) -> {
        expect module: ModuleInvocation = module_data
        when module is {
          ModuleRedeemer { operation_proof, config_possession, .. } -> {
            let expected: Data =
              ModuleRedeemer {
                abi_version: 1,
                intent: invocation.intent,
                operation_proof,
                config_possession,
                receipts: invocation.receipts,
              }
            module_data == expected
          }
          _ -> False
        }
      }
    }
  }
}
