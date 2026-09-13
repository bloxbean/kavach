//// Port of the Java `PeriodicBudgetNftPolicy`.
////
//// Creator-bound one-shot mint of a budget counter NFT carrying the initial usage datum at
//// the budget validator address.
////
//// Immutable parameters, in order: `seed`, `creator`, `budget_validator_hash` — the Java
//// `@Param` order.

use aiken/collection/list
use cardano/address.{VerificationKey}
use cardano/assets.{PolicyId, lovelace_of, quantity_of}
use cardano/transaction.{InlineDatum, OutputReference, Transaction}
use kavach/account_lib.{asset_count, enterprise, is_key, transaction_shape, uint63}
use kavach/periodic_budget

validator periodic_budget_nft(
  seed: OutputReference,
  creator: ByteArray,
  budget_validator_hash: ByteArray,
) {
  mint(redeemer: Int, policy_id: PolicyId, tx: Transaction) {
    if redeemer != 0 || !transaction_shape(tx) {
      False
    } else {
      let own = policy_id
      let signed = list.has(tx.extra_signatories, creator)
      let inputs_valid =
        list.all(
          tx.inputs,
          fn(input) { is_key(input.output.address.payment_credential) },
        )
      let consumed =
        list.foldl(
          tx.inputs,
          False,
          fn(input, acc) {
            if input.output_reference == seed {
              when input.output.address.payment_credential is {
                VerificationKey(hash) -> hash == creator
                _ -> False
              }
            } else {
              acc
            }
          },
        )
      let initial: Data = periodic_budget.initial()
      let budget_address = enterprise(budget_validator_hash)
      let Pair(outputs_valid, matches) =
        list.foldl(
          tx.outputs,
          Pair(True, 0),
          fn(output, acc) {
            let Pair(valid, matches) = acc
            if quantity_of(output.value, own, "") != 0 {
              let datum_matches =
                when output.datum is {
                  InlineDatum(inline) -> inline == initial
                  _ -> False
                }
              let ada = lovelace_of(output.value)
              Pair(
                valid && datum_matches && output.address == budget_address && output.reference_script == None && asset_count(
                  output.value,
                ) == 2 && quantity_of(output.value, own, "") == 1 && uint63(ada) && ada > 0,
                matches + 1,
              )
            } else {
              acc
            }
          },
        )
      inputs_valid && outputs_valid && signed && consumed && matches == 1 && asset_count(
        tx.mint,
      ) == 1 && quantity_of(tx.mint, own, "") == 1
    }
  }

  else(_) {
    fail @"only the minting purpose is supported"
  }
}
