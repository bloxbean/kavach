//// Port of the Java `BudgetSpendLib`: `SpendLib` semantics that additionally admit the
//// budget counter input and output at the budget address when the account is budgeted.

use aiken/builtin
use aiken/collection/list
use cardano/address.{Address}
use cardano/transaction.{Input, Output, OutputReference, Transaction}
use kavach/account_lib.{index, is_key, plain, receipt_at, shape}
use kavach/native_accounting
use kavach/spend_lib
use kavach/types.{Action, Recipient, RewardReceipt}

/// Complete signed input/output sets, then every account asset.
/// Mirrors `BudgetSpendLib.validate`; `action` is the enclosing Spend action as signed.
pub fn validate(
  action: Action,
  account_inputs: List<OutputReference>,
  recipients: List<Recipient>,
  fee_limit: Int,
  account_address: Address,
  budget_address: Address,
  budgeted: Bool,
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
      budget_address,
      budgeted,
      receipts,
      recipient_mask,
      tx,
    ) && native_accounting.conserve(account_address, recipient_mask, fee_limit, tx)
  }
}

/// Strictly ordered signed references merged against ledger-ordered consumed inputs;
/// recipients, account change and reward receipts are disjoint. Mirrors `BudgetSpendLib.semantics`.
fn semantics(
  account_inputs: List<OutputReference>,
  recipients: List<Recipient>,
  account_address: Address,
  budget_address: Address,
  budgeted: Bool,
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
    budget_address,
    budgeted,
  ) && recipients_match(
    recipients,
    -1,
    account_address,
    budget_address,
    budgeted,
    receipts,
    tx,
  ) && outputs_match(
    tx.outputs,
    0,
    account_address,
    budget_address,
    budgeted,
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
  budget_address: Address,
  budgeted: Bool,
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
                budget_address,
                budgeted,
              )
          }
        }
      } else {
        ( is_key(input.output.address.payment_credential) || budgeted && input.output.address == budget_address ) && inputs_match(
          rest,
          remaining,
          count,
          expected,
          account_address,
          budget_address,
          budgeted,
        )
      }
  }
}

fn recipients_match(
  recipients: List<Recipient>,
  previous: Int,
  account_address: Address,
  budget_address: Address,
  budgeted: Bool,
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
      ) || recipient.address.payment_credential == account_address.payment_credential || budgeted && recipient.address.payment_credential == budget_address.payment_credential || !shape(
        recipient,
        0,
        3,
      ) {
        False
      } else {
        expect Some(output) = list.at(tx.outputs, position)
        output.address == recipient.address && plain(output) && spend_lib.exact_value(
          recipient.value,
          output.value,
        ) && recipients_match(
          rest,
          position,
          account_address,
          budget_address,
          budgeted,
          receipts,
          tx,
        )
      }
    }
  }
}

fn outputs_match(
  outputs: List<Output>,
  position: Int,
  account_address: Address,
  budget_address: Address,
  budgeted: Bool,
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
        } else if recipient || budgeted && output.address == budget_address {
          True
        } else {
          is_key(output.address.payment_credential) && plain(output)
        }
      valid && outputs_match(
        rest,
        position + 1,
        account_address,
        budget_address,
        budgeted,
        receipts,
        recipient_mask,
      )
    }
  }
}

