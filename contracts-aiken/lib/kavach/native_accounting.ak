//// Port of the Java `NativeAccountingLib`: bounded native-asset conservation.
////
//// The Java uses the PV11 native-Value builtins (`unValueData`, `unionValue`, `lookupCoin`,
//// `insertCoin`). Aiken v1.1.23 does not expose them, so aggregation uses the stdlib
//// `assets.merge` / `lovelace_of` / `without_lovelace`, which compute the same canonical
//// result on ledger-supplied values (zero quantities dropped, sorted keys). This is a
//// toolchain capability difference, recorded as such in the size comparison.

use aiken/builtin
use aiken/collection/dict
use aiken/collection/list
use cardano/address.{Address}
use cardano/assets.{Value, lovelace_of, merge, without_lovelace}
use cardano/transaction.{Transaction}
use kavach/account_lib.{uint63}

/// A complete singleton ADA-only map's quantity, or -1 when either map has another entry
/// or a non-ADA key. No native asset is skipped. Mirrors `NativeAccountingLib.onlyLovelace`.
pub fn only_lovelace(value: Value) -> Int {
  let data: Data = value
  when builtin.un_map_data(data) is {
    [Pair(policy, tokens)] ->
      if policy == builtin.b_data("") {
        when builtin.un_map_data(tokens) is {
          [Pair(name, amount)] ->
            if name == builtin.b_data("") {
              builtin.un_i_data(amount)
            } else {
              -1
            }
          _ -> -1
        }
      } else {
        -1
      }
    _ -> -1
  }
}

/// Bounded positive quantities and the native-entry count, or -1.
/// Mirrors `NativeAccountingLib.nativeCount`.
pub fn native_count(value: Value) -> Int {
  let ada = only_lovelace(value)
  if 0 <= ada {
    if 0 < ada && uint63(ada) {
      0
    } else {
      -1
    }
  } else {
    let Pair(valid, count) =
      list.foldl(
        dict.to_pairs(assets.to_dict(value)),
        Pair(True, 0),
        fn(entry, acc) {
          let Pair(policy, tokens) = entry
          let Pair(valid, count) = acc
          let policy_length = builtin.length_of_bytearray(policy)
          list.foldl(
            dict.to_pairs(tokens),
            Pair(valid && !dict.is_empty(tokens), count),
            fn(token, inner) {
              let Pair(name, amount) = token
              let Pair(valid, count) = inner
              let quantity_ok = amount > 0 && uint63(amount)
              let name_length = builtin.length_of_bytearray(name)
              if policy_length == 0 {
                Pair(valid && quantity_ok && name_length == 0, count)
              } else {
                Pair(
                  valid && quantity_ok && policy_length == 28 && name_length <= 32,
                  count + 1,
                )
              }
            },
          )
        },
      )
    if valid && count <= 11 {
      count
    } else {
      -1
    }
  }
}

/// Aggregate quantity bounds and the distinct-entry count after union.
/// Mirrors `NativeAccountingLib.aggregateBounds`.
pub fn aggregate_bounds(value: Value) -> Bool {
  let entries = assets.flatten(value)
  list.all(entries, fn(entry) { uint63(entry.3rd) }) && list.length(entries) <= 12
}

/// Complete native-asset maps equal after an explicitly bounded lovelace debit.
/// Mirrors `NativeAccountingLib.conserve`, including starting both accumulators from the
/// transaction mint (which the caller's core requires to be empty).
pub fn conserve(
  account_address: Address,
  recipient_mask: ByteArray,
  fee_limit: Int,
  tx: Transaction,
) -> Bool {
  let (inputs_valid, input_entries, account_inputs, incoming) =
    list.foldl(
      tx.inputs,
      (True, 0, 0, tx.mint),
      fn(input, acc) {
        let (valid, entries, accounts, total) = acc
        if input.output.address == account_address {
          let count = native_count(input.output.value)
          (
            valid && count >= 0,
            entries + count,
            accounts + 1,
            merge(total, input.output.value),
          )
        } else {
          acc
        }
      },
    )
  let (outputs_valid, output_entries, _, allocated) =
    list.foldl(
      tx.outputs,
      (True, 0, 0, tx.mint),
      fn(output, acc) {
        let (valid, entries, position, total) = acc
        let count = native_count(output.value)
        let next =
          if output.address == account_address || builtin.read_bit(
            recipient_mask,
            position,
          ) {
            merge(total, output.value)
          } else {
            total
          }
        (valid && count >= 0, entries + count, position + 1, next)
      },
    )
  let input_ada = lovelace_of(incoming)
  let output_ada = lovelace_of(allocated)
  let fee = input_ada - output_ada
  inputs_valid && aggregate_bounds(incoming) && outputs_valid && account_inputs > 0 && account_inputs <= 8 && input_entries <= 12 && output_entries <= 12 && 0 < input_ada && uint63(
    input_ada,
  ) && uint63(output_ada) && 0 <= fee && fee <= fee_limit && fee <= tx.fee && without_lovelace(
    incoming,
  ) == without_lovelace(allocated)
}
