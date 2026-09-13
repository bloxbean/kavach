//// Port of the Java `StateTransitionLib`: consumed-state authentication and state-deposit
//// conservation. No account assets may be spent in a mutation transaction. The immutable
//// state validator enforces successors; core and modules independently authenticate the
//// same old state and intent.

use aiken/collection/list
use aiken/collection/pairs
use cardano/assets.{lovelace_of}
use cardano/transaction.{Input, Output, ScriptPurpose, Transaction, Withdraw}
use kavach/account_lib.{
  asset_count, contains_policy, is_key, plain, positive_ada_only, receipt_at,
  script_credential, shape, state_output, transaction_shape,
}
use kavach/lifecycle.{state_shape}
use kavach/types.{AccountState, CoreRedeemer, DeploymentDomain, IntentDomain}

/// Resolves only the consumed reference named by the signed domain. The result is untrusted;
/// callers must authenticate it. Mirrors `StateTransitionLib.resolve`.
pub fn resolve(binding: IntentDomain, tx: Transaction) -> AccountState {
  account_lib.resolve_from(binding, tx.inputs)
}

/// Authenticates singleton NFT custody and the exact domain against the consumed old state.
/// Mirrors `StateTransitionLib.authenticate`.
pub fn authenticate(
  previous: AccountState,
  binding: IntentDomain,
  deployment: DeploymentDomain,
  custody_hash: ByteArray,
  tx: Transaction,
) -> Bool {
  if !state_shape(previous, deployment, custody_hash) || !transaction_shape(tx) || asset_count(
    tx.mint,
  ) != 0 {
    False
  } else if !account_lib.authenticate_from(
    previous,
    binding,
    deployment,
    custody_hash,
    tx.inputs,
  ) {
    False
  } else {
    // No reference input may be the signed state reference or carry the account's NFT.
    list.all(
      tx.reference_inputs,
      fn(reference) {
        reference.output_reference != binding.state_ref && !contains_policy(
          reference.output.value,
          previous.account_id.policy,
        )
      },
    )
  }
}

/// Every non-state input must be a plain ADA-only key input, so a mutation cannot also
/// consume account assets or unrelated script inputs.
/// Mirrors `StateTransitionLib.sponsorInputs`.
pub fn sponsor_inputs(invocation: CoreRedeemer, tx: Transaction) -> Bool {
  list.all(
    tx.inputs,
    fn(input) {
      if input.output_reference == invocation.intent.domain.state_ref {
        True
      } else {
        is_key(input.output.address.payment_credential) && plain(input.output) && positive_ada_only(
          input.output.value,
        )
      }
    },
  )
}

/// Requires the exact core invocation under the old immutable checkpoint credential: the
/// Rewarding redeemer and the withdrawal must both match this state invocation.
/// Mirrors `StateTransitionLib.coreBinding`.
pub fn core_binding(
  invocation: CoreRedeemer,
  previous: AccountState,
  tx: Transaction,
) -> Bool {
  let credential = script_credential(previous.core_binding.checkpoint)
  let purpose: ScriptPurpose = Withdraw(credential)
  let invocation_data: Data = invocation
  invocation.abi_version == 1 && shape(invocation, 0, 3) && pairs.has_key(
    tx.withdrawals,
    credential,
  ) && when pairs.get_first(tx.redeemers, purpose) is {
    Some(redeemer) -> redeemer == invocation_data
    None -> False
  }
}

/// Exactly one NFT successor with nondecreasing state ADA. All other outputs are plain
/// ADA-only key outputs, so account change and hidden script effects reject.
/// Mirrors `StateTransitionLib.outputs`.
pub fn outputs(
  invocation: CoreRedeemer,
  previous: AccountState,
  next_state: AccountState,
  tx: Transaction,
) -> Bool {
  if !state_shape(
    next_state,
    previous.deployment_domain,
    previous.core_binding.state_validator,
  ) {
    False
  } else {
    let old_ada =
      list.foldl(
        tx.inputs,
        0,
        fn(input: Input, acc: Int) {
          if input.output_reference == invocation.intent.domain.state_ref {
            lovelace_of(input.output.value)
          } else {
            acc
          }
        },
      )
    let result =
      check_outputs(
        tx.outputs,
        0,
        0,
        True,
        invocation,
        previous,
        next_state,
        old_ada,
      )
    let Pair(matches, valid) = result
    valid && matches == 1
  }
}

/// Walks outputs in order, carrying the NFT match count and validity, so each output is
/// classified exactly once against its own index.
fn check_outputs(
  remaining: List<Output>,
  index: Int,
  matches: Int,
  valid: Bool,
  invocation: CoreRedeemer,
  previous: AccountState,
  next_state: AccountState,
  old_ada: Int,
) -> Pair<Int, Bool> {
  when remaining is {
    [] -> Pair(matches, valid)
    [output, ..rest] ->
      if contains_policy(output.value, previous.account_id.policy) {
        let ok =
          state_output(
            output,
            next_state,
            previous.core_binding.state_validator,
          ) && lovelace_of(output.value) >= old_ada && !receipt_at(
            invocation.receipts,
            index,
          )
        check_outputs(
          rest,
          index + 1,
          matches + 1,
          valid && ok,
          invocation,
          previous,
          next_state,
          old_ada,
        )
      } else {
        let ok =
          is_key(output.address.payment_credential) && plain(output) && positive_ada_only(
            output.value,
          )
        check_outputs(
          rest,
          index + 1,
          matches,
          valid && ok,
          invocation,
          previous,
          next_state,
          old_ada,
        )
      }
  }
}
