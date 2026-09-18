//// Port of the Java `AccountAssetValidator`.
////
//// Per-account asset lock. Every consumed asset input requires the immutable core Rewarding
//// invocation bound to this account/domain and the same canonical digest. The canonical first
//// signed input executes all-asset accounting once; the checkpoint requires that exact anchor
//// at this script, so other inputs cannot skip the check.
////
//// Immutable parameters, in order: `core_version`, `deployment_domain`, `account_id`,
//// `state_validator_hash`, `core_checkpoint_hash` — the Java `@Param` order.

use aiken/collection/pairs
use cardano/address.{Script}
use cardano/transaction.{OutputReference, ScriptPurpose, Transaction, Withdraw}
use kavach/account_lib.{digest, enterprise, shape}
use kavach/spend_lib
use kavach/types.{
  AccountId, AssetRedeemer, CoreRedeemer, DeploymentDomain, Spend, TransferWholeUtxo,
}
use kavach/whole_transfer

validator account_asset(
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
          let domain = core.intent.domain
          let binding =
            core.abi_version == 1 && domain.account_id == account_id && domain.deployment_domain == deployment_domain && domain.core_binding.state_validator == state_validator_hash && domain.core_binding.checkpoint == core_checkpoint_hash && digest(
              core.intent,
            ) == redeemer.intent_digest
          if !binding {
            False
          } else {
            let account_address = enterprise(domain.core_binding.asset_validator)
            let action = core.intent.action
            when action is {
              Spend { account_inputs, recipients, max_account_fee } ->
                when account_inputs is {
                  [] -> fail @"a spend names at least one account input"
                  [first, ..] ->
                    own_ref != first || spend_lib.validate(
                      action,
                      account_inputs,
                      recipients,
                      max_account_fee,
                      account_address,
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
                own_ref == account_input && whole_transfer.validate(
                  action,
                  account_input,
                  recipient_index,
                  recipient_address,
                  input_value_digest,
                  account_address,
                  core.receipts,
                  tx,
                )
              _ -> False
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
