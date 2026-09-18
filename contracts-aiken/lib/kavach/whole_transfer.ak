//// Port of the Java `WholeTransferLib`: full-map transfer semantics, called only after
//// immutable core identity, intent and module checks.

use aiken/builtin
use aiken/collection/list
use aiken/crypto
use cardano/address.{Address}
use cardano/assets.{Value, lovelace_of}
use cardano/transaction.{NoDatum, OutputReference, Transaction}
use kavach/account_lib.{asset_count, is_key, receipt_at, shape}
use kavach/types.{Action, RewardReceipt}

/// Preserves every native token and prohibits account-funded fee or change.
/// Mirrors `WholeTransferLib.validate`. The Java also requires a Spending script purpose;
/// here the only caller is the spend handler, so that holds by construction.
pub fn validate(
  action: Action,
  account_input: OutputReference,
  recipient_index: Int,
  recipient_address: Address,
  input_value_digest: ByteArray,
  account_address: Address,
  receipts: List<RewardReceipt>,
  tx: Transaction,
) -> Bool {
  let outputs = list.length(tx.outputs)
  if receipt_at(receipts, recipient_index) || !is_key(
    recipient_address.payment_credential,
  ) || list.length(tx.inputs) > 16 || outputs > 16 || list.length(
    tx.reference_inputs,
  ) > 4 || asset_count(tx.mint) != 0 || tx.certificates != [] || tx.votes != [] || tx.proposal_procedures != [] || tx.current_treasury_amount != None || tx.treasury_donation != None || recipient_index < 0 || outputs <= recipient_index || builtin.length_of_bytearray(
    input_value_digest,
  ) != 32 || !shape(action, 8, 4) {
    False
  } else {
    expect Some(recipient) = list.at(tx.outputs, recipient_index)
    if recipient.datum != NoDatum || recipient.reference_script != None || recipient.address != recipient_address {
      False
    } else {
      let Pair(inputs_valid, matches) =
        list.foldl(
          tx.inputs,
          Pair(True, 0),
          fn(input, acc) {
            let Pair(valid, matches) = acc
            if input.output.address.payment_credential == account_address.payment_credential {
              let value_data: Data = input.output.value
              let encoded = builtin.serialise_data(value_data)
              Pair(
                valid && input.output_reference == account_input && input.output.address == account_address && builtin.length_of_bytearray(
                  encoded,
                ) <= 8192 && crypto.blake2b_256(encoded) == input_value_digest && preserves_value(
                  input.output.value,
                  recipient.value,
                ),
                matches + 1,
              )
            } else {
              Pair(valid && is_key(input.output.address.payment_credential), matches)
            }
          },
        )
      let Pair(outputs_valid, _) =
        list.foldl(
          tx.outputs,
          Pair(True, 0),
          fn(output, acc) {
            let Pair(valid, position) = acc
            let not_account =
              output.address.payment_credential != account_address.payment_credential
            let other_ok =
              position == recipient_index || is_key(
                output.address.payment_credential,
              ) && output.datum == NoDatum && output.reference_script == None && ada_only(
                output.value,
              )
            Pair(valid && not_account && other_ok, position + 1)
          },
        )
      inputs_valid && outputs_valid && matches == 1
    }
  }
}

/// Ledger Values are canonical; one empty policy means ADA only.
/// Mirrors `WholeTransferLib.adaOnly`.
pub fn ada_only(value: Value) -> Bool {
  let data: Data = value
  when builtin.un_map_data(data) is {
    [Pair(policy, _)] -> policy == builtin.b_data("")
    _ -> False
  }
}

/// Native maps equal byte-for-byte while permitting only an ADA top-up.
/// Mirrors `WholeTransferLib.preservesValue`.
pub fn preserves_value(input: Value, output: Value) -> Bool {
  let input_data: Data = input
  let output_data: Data = output
  when builtin.un_map_data(input_data) is {
    [] -> False
    [Pair(input_policy, _), ..input_rest] ->
      when builtin.un_map_data(output_data) is {
        [] -> False
        [Pair(output_policy, _), ..output_rest] -> {
          let empty = builtin.b_data("")
          if input_policy != empty || output_policy != empty {
            False
          } else {
            let input_ada = lovelace_of(input)
            let output_ada = lovelace_of(output)
            0 < input_ada && input_ada <= output_ada && builtin.map_data(input_rest) == builtin.map_data(
              output_rest,
            )
          }
        }
      }
  }
}
