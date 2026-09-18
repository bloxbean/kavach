//// Port of the Java `PeriodicBudgetValidator`.
////
//// Budget counter custody. Spending the counter requires the account's authenticated
//// budget configuration and exactly one successor counter whose usage records the net
//// account debit within the current window.
////
//// Immutable parameters, in order: `core_version`, `deployment_domain`, `account_id`,
//// `state_validator_hash`, `core_checkpoint_hash` — the Java `@Param` order.

use aiken/collection/list
use aiken/collection/pairs
use cardano/address.{Script}
use cardano/assets.{lovelace_of, quantity_of}
use cardano/transaction.{
  InlineDatum, Input, OutputReference, ScriptPurpose, Transaction, Withdraw,
}
use kavach/account_lib.{asset_count, digest, enterprise, envelope, shape, uint63}
use kavach/periodic_budget.{Budget, Configuration, Usage} as budget_lib
use kavach/types.{
  AccountId, AssetRedeemer, CoreRedeemer, DeploymentDomain, Spend, TransferWholeUtxo,
}

validator periodic_budget(
  core_version: Int,
  deployment_domain: DeploymentDomain,
  account_id: AccountId,
  state_validator_hash: ByteArray,
  core_checkpoint_hash: ByteArray,
) {
  spend(
    _datum: Option<Data>,
    redeemer: AssetRedeemer,
    own_ref: OutputReference,
    tx: Transaction,
  ) {
    let credential = Script(core_checkpoint_hash)
    let purpose: ScriptPurpose = Withdraw(credential)
    if core_version != 1 || redeemer.abi_version != 1 || !shape(redeemer, 0, 2) || !pairs.has_key(
      tx.withdrawals,
      credential,
    ) {
      False
    } else {
      when pairs.get_first(tx.redeemers, purpose) is {
        None -> False
        Some(core_data) -> {
          expect core: CoreRedeemer = core_data
          let transfer =
            when core.intent.action is {
              Spend { .. } | TransferWholeUtxo { .. } -> True
              _ -> False
            }
          if core.abi_version != 1 || !envelope(core.intent, tx) || digest(
            core.intent,
          ) != redeemer.intent_digest || core.intent.domain.account_id != account_id || !transfer {
            False
          } else {
            counter(
              own_ref,
              core,
              tx,
              deployment_domain,
              state_validator_hash,
              core_checkpoint_hash,
            )
          }
        }
      }
    }
  }

  else(_) {
    fail @"only the spending purpose is supported"
  }
}

fn counter(
  own_ref: OutputReference,
  core: CoreRedeemer,
  tx: Transaction,
  deployment_domain: DeploymentDomain,
  state_validator_hash: ByteArray,
  core_checkpoint_hash: ByteArray,
) -> Bool {
  let domain = core.intent.domain
  let state = account_lib.resolve(domain, tx)
  if !account_lib.authenticate(
    state,
    domain,
    deployment_domain,
    state_validator_hash,
    tx,
  ) || state.core_binding.checkpoint != core_checkpoint_hash || !budget_lib.is_profile(
    state.auth_config,
  ) {
    False
  } else {
    expect configuration: Configuration = state.auth_config
    if !budget_lib.configuration(configuration) || !budget_lib.enabled(
      state.auth_config,
    ) {
      False
    } else {
      expect Some(budget) = configuration.budget
      let account_address = enterprise(state.core_binding.asset_validator)
      let (input_ada, own_matches, inputs_valid) =
        list.foldl(
          tx.inputs,
          (0, 0, True),
          fn(input, acc) {
            let (total, matches, valid) = acc
            let next_total =
              if input.output.address == account_address {
                total + lovelace_of(input.output.value)
              } else {
                total
              }
            if input.output_reference == own_ref {
              let previous = usage(input)
              let value = input.output.value
              let ada = lovelace_of(value)
              (
                next_total,
                matches + 1,
                valid && asset_count(value) == 2 && quantity_of(
                  value,
                  budget.counter.policy,
                  budget.counter.name,
                ) == 1 && uint63(ada) && ada > 0 && input.output.reference_script == None && budget_lib.usage_shape(
                  previous,
                ),
              )
            } else {
              (next_total, matches, valid)
            }
          },
        )
      let change_ada =
        list.foldl(
          tx.outputs,
          0,
          fn(output, total) {
            if output.address == account_address {
              total + lovelace_of(output.value)
            } else {
              total
            }
          },
        )
      if !inputs_valid || own_matches != 1 || !uint63(input_ada) || !uint63(
        change_ada,
      ) {
        False
      } else {
        expect Some(own) =
          list.find(tx.inputs, fn(input) { input.output_reference == own_ref })
        successor_output(own, budget, input_ada - change_ada, tx)
      }
    }
  }
}

fn usage(input: Input) -> Usage {
  when input.output.datum is {
    InlineDatum(inline) -> {
      expect previous: Usage = inline
      previous
    }
    _ -> fail @"budget counter does not carry an inline datum"
  }
}

fn successor_output(own: Input, budget: Budget, debit: Int, tx: Transaction) -> Bool {
  let next: Data = budget_lib.successor(usage(own), budget, debit, tx)
  let Pair(valid, matches) =
    list.foldl(
      tx.outputs,
      Pair(True, 0),
      fn(output, acc) {
        let Pair(valid, matches) = acc
        if quantity_of(output.value, budget.counter.policy, budget.counter.name) != 0 {
          let datum_matches =
            when output.datum is {
              InlineDatum(inline) -> inline == next
              _ -> False
            }
          Pair(
            valid && datum_matches && output.address == own.output.address && output.reference_script == None && output.value == own.output.value,
            matches + 1,
          )
        } else {
          acc
        }
      },
    )
  valid && matches == 1
}
