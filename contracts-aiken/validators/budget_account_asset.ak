//// Port of the Java `BudgetAccountAssetValidator`.
////
//// Asset lock for accounts whose configuration may carry a periodic budget. Non-anchor spend
//// inputs keep their own account/domain/checkpoint/digest binding; the signed first input
//// authenticates state and the budget counter and accounts for every account input.
////
//// Immutable parameters, in order: `core_version`, `deployment_domain`, `account_id`,
//// `state_validator_hash`, `core_checkpoint_hash`, `budget_validator_hash` — the Java
//// `@Param` order.

use aiken/collection/list
use aiken/collection/pairs
use cardano/address.{Script}
use cardano/assets.{quantity_of}
use cardano/transaction.{OutputReference, ScriptPurpose, Transaction, Withdraw}
use kavach/account_lib.{digest, enterprise, shape}
use kavach/budget_spend
use kavach/budget_whole_transfer
use kavach/periodic_budget.{Configuration}
use kavach/types.{
  AccountId, AssetRedeemer, CoreRedeemer, DeploymentDomain, Spend, TransferWholeUtxo,
}

validator budget_account_asset(
  core_version: Int,
  deployment_domain: DeploymentDomain,
  account_id: AccountId,
  state_validator_hash: ByteArray,
  core_checkpoint_hash: ByteArray,
  budget_validator_hash: ByteArray,
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
          let domain = core.intent.domain
          let binding =
            core.abi_version == 1 && domain.account_id == account_id && domain.deployment_domain == deployment_domain && domain.core_binding.state_validator == state_validator_hash && domain.core_binding.checkpoint == core_checkpoint_hash && digest(
              core.intent,
            ) == redeemer.intent_digest
          if !binding {
            False
          } else {
            let action = core.intent.action
            let non_anchor =
              when action is {
                Spend { account_inputs, .. } ->
                  when account_inputs is {
                    [] -> fail @"a spend names at least one account input"
                    [first, ..] -> own_ref != first
                  }
                _ -> False
              }
            if non_anchor {
              True
            } else {
              anchor(
                own_ref,
                core,
                tx,
                deployment_domain,
                state_validator_hash,
                budget_validator_hash,
              )
            }
          }
        }
      }
    }
  }

  else(_) {
    fail @"only the spending purpose is supported"
  }
}

/// The anchor input: state, budget counter and complete accounting.
fn anchor(
  own_ref: OutputReference,
  core: CoreRedeemer,
  tx: Transaction,
  deployment_domain: DeploymentDomain,
  state_validator_hash: ByteArray,
  budget_validator_hash: ByteArray,
) -> Bool {
  let domain = core.intent.domain
  let account_address = enterprise(domain.core_binding.asset_validator)
  let state = account_lib.resolve(domain, tx)
  if !account_lib.authenticate(
    state,
    domain,
    deployment_domain,
    state_validator_hash,
    tx,
  ) {
    False
  } else {
    let budgeted = periodic_budget.enabled(state.auth_config)
    let budget_address = enterprise(budget_validator_hash)
    let counter_valid =
      if periodic_budget.is_profile(state.auth_config) {
        expect configuration: Configuration = state.auth_config
        if !periodic_budget.configuration(configuration) {
          False
        } else if budgeted {
          expect Some(budget) = configuration.budget
          let Pair(valid, matches) =
            list.foldl(
              tx.inputs,
              Pair(True, 0),
              fn(input, acc) {
                let Pair(valid, matches) = acc
                let quantity =
                  quantity_of(
                    input.output.value,
                    budget.counter.policy,
                    budget.counter.name,
                  )
                if quantity != 0 {
                  Pair(
                    valid && input.output.address == budget_address && quantity == 1,
                    matches + 1,
                  )
                } else {
                  acc
                }
              },
            )
          valid && matches == 1
        } else {
          True
        }
      } else {
        True
      }
    counter_valid && when core.intent.action is {
      Spend { account_inputs, recipients, max_account_fee } ->
        when account_inputs is {
          [] -> fail @"a spend names at least one account input"
          [first, ..] ->
            own_ref != first || budget_spend.validate(
              core.intent.action,
              account_inputs,
              recipients,
              max_account_fee,
              account_address,
              budget_address,
              budgeted,
              core.receipts,
              tx,
            )
        }
      TransferWholeUtxo {
        account_input,
        recipient_index,
        recipient_address,
        input_value_digest,
      } ->
        own_ref == account_input && budget_whole_transfer.validate(
          core.intent.action,
          account_input,
          recipient_index,
          recipient_address,
          input_value_digest,
          account_address,
          budget_address,
          budgeted,
          core.receipts,
          tx,
        )
      _ -> False
    }
  }
}
