//// Port of the Java `SpendLib`: exact partial-transfer semantics and bounded native-asset
//// conservation. Authorization is checked by the calling core.

use aiken/builtin
use aiken/collection/list
use cardano/address.{Address}
use cardano/assets.{Value}
use cardano/transaction.{Input, Output, OutputReference, Transaction}
use kavach/account_lib.{index, is_key, plain, receipt_at, shape, uint63}
use kavach/native_accounting
use kavach/types.{Action, Asset, Recipient, RewardReceipt}

/// Complete signed input/output sets, then every account asset.
/// Mirrors `SpendLib.validate`; `action` is the enclosing Spend action as signed.
pub fn validate(
  action: Action,
  account_inputs: List<OutputReference>,
  recipients: List<Recipient>,
  fee_limit: Int,
  account_address: Address,
  receipts: List<RewardReceipt>,
  tx: Transaction,
) -> Bool {
  if account_inputs == [] || list.length(account_inputs) > 8 || list.length(
    recipients,
  ) > 8 || fee_limit < 0 || 5000000 < fee_limit || !shape(action, 0, 3) {
    False
  } else {
    // A fixed two-byte mask covers the bounded 0..15 output positions, derived solely from
    // the signed recipients.
    let Pair(valid_indices, recipient_mask) =
      list.foldl(
        recipients,
        Pair(True, #"0000"),
        fn(recipient, acc) {
          let Pair(valid, mask) = acc
          let position = recipient.output_index
          if position < 0 || 16 <= position {
            Pair(False, mask)
          } else {
            Pair(
              valid,
              builtin.or_bytearray(
                False,
                mask,
                builtin.shift_bytearray(#"0001", position),
              ),
            )
          }
        },
      )
    valid_indices && semantics(
      account_inputs,
      recipients,
      account_address,
      receipts,
      recipient_mask,
      tx,
    ) && native_accounting.conserve(account_address, recipient_mask, fee_limit, tx)
  }
}

/// Strictly ordered signed references merged against ledger-ordered consumed inputs;
/// recipients, account change and reward receipts are disjoint. Mirrors `SpendLib.semantics`.
fn semantics(
  account_inputs: List<OutputReference>,
  recipients: List<Recipient>,
  account_address: Address,
  receipts: List<RewardReceipt>,
  recipient_mask: ByteArray,
  tx: Transaction,
) -> Bool {
  strictly_ordered(account_inputs, "", -1) && inputs_match(
    tx.inputs,
    account_inputs,
    0,
    list.length(account_inputs),
    account_address,
  ) && recipients_match(recipients, -1, account_address, receipts, tx) && outputs_match(
    tx.outputs,
    0,
    account_address,
    receipts,
    recipient_mask,
  )
}

fn strictly_ordered(
  refs: List<OutputReference>,
  previous_hash: ByteArray,
  previous_index: Int,
) -> Bool {
  when refs is {
    [] -> True
    [ref, ..rest] -> {
      let hash = ref.transaction_id
      ( builtin.less_than_bytearray(previous_hash, hash) || hash == previous_hash && previous_index < ref.output_index ) && strictly_ordered(
        rest,
        hash,
        ref.output_index,
      )
    }
  }
}

fn inputs_match(
  inputs: List<Input>,
  remaining: List<OutputReference>,
  count: Int,
  expected: Int,
  account_address: Address,
) -> Bool {
  when inputs is {
    [] -> remaining == [] && count == expected
    [input, ..rest] ->
      if input.output.address.payment_credential == account_address.payment_credential {
        if input.output.address != account_address {
          False
        } else {
          when remaining is {
            [] -> False
            [head, ..tail] ->
              input.output_reference == head && inputs_match(
                rest,
                tail,
                count + 1,
                expected,
                account_address,
              )
          }
        }
      } else {
        is_key(input.output.address.payment_credential) && inputs_match(
          rest,
          remaining,
          count,
          expected,
          account_address,
        )
      }
  }
}

fn recipients_match(
  recipients: List<Recipient>,
  previous: Int,
  account_address: Address,
  receipts: List<RewardReceipt>,
  tx: Transaction,
) -> Bool {
  when recipients is {
    [] -> True
    [recipient, ..rest] -> {
      let position = recipient.output_index
      if !index(position, tx) || position <= previous || receipt_at(
        receipts,
        position,
      ) || recipient.address.payment_credential == account_address.payment_credential || !shape(
        recipient,
        0,
        3,
      ) {
        False
      } else {
        expect Some(output) = list.at(tx.outputs, position)
        output.address == recipient.address && plain(output) && exact_value(
          recipient.value,
          output.value,
        ) && recipients_match(rest, position, account_address, receipts, tx)
      }
    }
  }
}

fn outputs_match(
  outputs: List<Output>,
  position: Int,
  account_address: Address,
  receipts: List<RewardReceipt>,
  recipient_mask: ByteArray,
) -> Bool {
  when outputs is {
    [] -> True
    [output, ..rest] -> {
      // Read the mask for every output, as the Java does, before classifying it.
      let recipient = builtin.read_bit(recipient_mask, position)
      let account =
        output.address.payment_credential == account_address.payment_credential
      let valid =
        if account {
          output.address == account_address && plain(output) && !receipt_at(
            receipts,
            position,
          )
        } else if recipient {
          True
        } else {
          is_key(output.address.payment_credential) && plain(output)
        }
      valid && outputs_match(
        rest,
        position + 1,
        account_address,
        receipts,
        recipient_mask,
      )
    }
  }
}

/// Exact signed asset list versus the entire ledger Value; absent and extra assets reject.
/// Mirrors `SpendLib.exactValue`.
fn exact_value(signed: List<Asset>, actual: Value) -> Bool {
  let size = list.length(signed)
  if size == 1 {
    let ada = native_accounting.only_lovelace(actual)
    expect [head] = signed
    let expected: Data = Asset { policy: "", name: "", quantity: ada }
    let head_data: Data = head
    0 < ada && head_data == expected
  } else {
    let entries = assets.flatten(actual)
    if signed == [] || size > 12 || size != list.length(entries) {
      False
    } else {
      assets_match(signed, entries, True, "", "")
    }
  }
}

fn assets_match(
  signed: List<Asset>,
  entries: List<(ByteArray, ByteArray, Int)>,
  first: Bool,
  previous_policy: ByteArray,
  previous_name: ByteArray,
) -> Bool {
  when signed is {
    [] -> True
    [asset, ..rest] -> {
      expect [entry, ..remaining] = entries
      let policy_length = builtin.length_of_bytearray(asset.policy)
      let name_length = builtin.length_of_bytearray(asset.name)
      let matches =
        asset.policy == entry.1st && asset.name == entry.2nd && asset.quantity == entry.3rd && asset.quantity > 0 && uint63(
          asset.quantity,
        ) && shape(asset, 0, 3)
      let ordered =
        if first {
          policy_length == 0 && name_length == 0
        } else {
          policy_length == 28 && name_length <= 32 && (
            builtin.less_than_bytearray(previous_policy, asset.policy) || previous_policy == asset.policy && builtin.less_than_bytearray(
              previous_name,
              asset.name,
            )
          )
        }
      matches && ordered && assets_match(
        rest,
        remaining,
        False,
        asset.policy,
        asset.name,
      )
    }
  }
}
