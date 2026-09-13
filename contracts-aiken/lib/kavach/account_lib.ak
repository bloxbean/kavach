//// Port of the Java `AccountLib` helpers reached by the state validator.
////
//// Every check here mirrors one in `AccountLib.java`. Where the Java uses a JuLC-specific
//// idiom (PV11 array-length builtins, explicit `Value.lovelace` reconstruction) this uses
//// the natural Aiken equivalent with identical semantics; those choices are recorded in
//// `docs/fee-optimization/aiken-spike.md`. No check is dropped, weakened or reordered in a
//// way that changes what is accepted.

use aiken/builtin
use aiken/collection/dict
use aiken/collection/list
use aiken/interval.{Finite}
use cardano/address.{Address, Credential, Script}
use cardano/assets.{Value, lovelace_of, quantity_of, tokens}
use cardano/transaction.{Input, InlineDatum, NoDatum, Output, Transaction}
use kavach/types.{
  AccountId, AccountState, AuthModuleRef, CoreBinding, DeploymentDomain,
  IntentDomain, IntentEnvelope, RewardReceipt, Validity,
}

/// Largest signed 63-bit quantity; the protocol's unsigned arithmetic bound.
pub const max_uint63: Int = 9223372036854775807

/// Exact constructor tag and arity only. Field types and semantics remain the caller's job;
/// this is never complete datum validation. Mirrors `AccountLib.shape`.
pub fn shape(data: Data, tag: Int, fields: Int) -> Bool {
  let Pair(actual_tag, actual_fields) = builtin.un_constr_data(data)
  actual_tag == tag && list.length(actual_fields) == fields
}

/// Inclusive range 0 .. 2^63-1. Mirrors `AccountLib.uint63`.
pub fn uint63(n: Int) -> Bool {
  n >= 0 && n <= max_uint63
}

/// Total policy/name entries in a Value, including ADA when present. Empty policy maps
/// contribute zero, matching the Java flattening semantics. Mirrors `AccountLib.assetCount`.
///
/// Verified against the pinned stdlib v2.2.0: `assets.flatten` folds every entry without
/// filtering zero quantities, so this counts raw map entries exactly as the Java does.
pub fn asset_count(value: Value) -> Int {
  value
    |> assets.flatten
    |> list.length
}

/// Strictly positive canonical ADA-only value. Mirrors `AccountLib.positiveAdaOnly`.
pub fn positive_ada_only(value: Value) -> Bool {
  let amount = lovelace_of(value)
  amount > 0 && value == assets.from_lovelace(amount)
}

/// Whether the value carries any token under `policy`. Mirrors `ValuesLib.containsPolicy`.
pub fn contains_policy(value: Value, policy: ByteArray) -> Bool {
  !dict.is_empty(tokens(value, policy))
}

/// The only supported ordinary account address: script payment, no stake credential.
/// Mirrors `AccountLib.enterprise`.
pub fn enterprise(hash: ByteArray) -> Address {
  Address { payment_credential: Script(hash), stake_credential: None }
}

/// Script credential for exact Rewarding purpose and withdrawal lookups.
/// Mirrors `AccountLib.script`.
pub fn script_credential(hash: ByteArray) -> Credential {
  Script(hash)
}

/// Neither datum nor reference script. Mirrors `AccountLib.plain`.
pub fn plain(output: Output) -> Bool {
  when output.datum is {
    NoDatum -> output.reference_script == None
    _ -> False
  }
}

/// Whether a credential is a plain payment key. Mirrors the `PubKeyCredential` switches.
pub fn is_key(credential: Credential) -> Bool {
  when credential is {
    Script(_) -> False
    _ -> True
  }
}

/// Global transaction bounds, including sponsor entries, so unsupported ledger operations
/// cannot hide behind authorization. Mirrors `AccountLib.transactionShape`.
pub fn transaction_shape(tx: Transaction) -> Bool {
  let inputs = list.length(tx.inputs)
  let outputs = list.length(tx.outputs)
  inputs > 0 && inputs <= 16 && outputs > 0 && outputs <= 16 && list.length(
    tx.reference_inputs,
  ) <= 4 && list.length(tx.extra_signatories) <= 16 && list.length(
    tx.withdrawals,
  ) <= 3 && tx.certificates == [] && tx.votes == [] && tx.proposal_procedures == [] && tx.current_treasury_amount == None && tx.treasury_donation == None && uint63(
    tx.fee,
  )
}

/// Canonical immutable binding with 28-byte hashes. Mirrors `AccountLib.coreShape`.
pub fn core_shape(binding: CoreBinding) -> Bool {
  builtin.length_of_bytearray(binding.state_validator) == 28 && builtin.length_of_bytearray(
    binding.asset_validator,
  ) == 28 && builtin.length_of_bytearray(binding.checkpoint) == 28 && shape(
    binding,
    0,
    3,
  )
}

/// Full NFT identity with the protocol's fixed empty asset name.
/// Mirrors `AccountLib.accountShape`.
pub fn account_shape(id: AccountId) -> Bool {
  builtin.length_of_bytearray(id.policy) == 28 && builtin.length_of_bytearray(
    id.name,
  ) == 0 && shape(id, 0, 2)
}

/// Canonical module reference and supported ABI. Mirrors `AccountLib.moduleShape`.
pub fn module_shape(module: AuthModuleRef) -> Bool {
  module.abi_version == 1 && builtin.length_of_bytearray(module.script_hash) == 28 && shape(
    module,
    0,
    2,
  )
}

/// Mode-independent datum fields. The caller must separately validate the lifecycle variant
/// and total serialized size. Mirrors `AccountLib.stateFields`.
pub fn state_fields(
  state: AccountState,
  expected_domain: DeploymentDomain,
  state_hash: ByteArray,
) -> Bool {
  state.schema_version == 1 && account_shape(state.account_id) && core_shape(
    state.core_binding,
  ) && module_shape(state.auth_module) && uint63(state.state_version) && uint63(
    state.recovery_sequence,
  ) && state.recovery_sequence <= state.state_version && uint63(
    state.recovery_not_before,
  ) && 86400000 <= state.recovery_delay_millis && state.recovery_delay_millis <= 7776000000 && 3600000 <= state.recovery_cooldown_millis && state.recovery_cooldown_millis <= 2592000000 && state.deployment_domain == expected_domain && state.core_binding.state_validator == state_hash && state.auth_module.script_hash != state.core_binding.checkpoint && builtin.length_of_bytearray(
    builtin.serialise_data(state.auth_config),
  ) <= 1024 && shape(state, 0, 12)
}

/// The NFT-bearing output: exact custody address, no reference script, the complete inline
/// datum, exactly two value entries and a singleton NFT. Mirrors `AccountLib.stateOutput`.
pub fn state_output(
  output: Output,
  state: AccountState,
  state_hash: ByteArray,
) -> Bool {
  // Compare at the Data level, as the Java `equalsData` does. Parsing with `expect` would
  // abort on a malformed datum where the Java returns false, which is a different outcome.
  let expected: Data = state
  let datum_matches =
    when output.datum is {
      InlineDatum(inline) -> inline == expected
      _ -> False
    }
  output.address == enterprise(state_hash) && output.reference_script == None && datum_matches && asset_count(
    output.value,
  ) == 2 && lovelace_of(output.value) > 0 && quantity_of(
    output.value,
    state.account_id.policy,
    "",
  ) == 1
}

/// Resolves only the exact reference named by the signed domain, within the caller-selected
/// input set. Failure is a script error, never an unauthenticated default.
/// Mirrors `AccountLib.resolveFrom`.
pub fn resolve_from(domain: IntentDomain, candidates: List<Input>) -> AccountState {
  when candidates is {
    [] -> fail @"state reference not found in the selected input set"
    [candidate, ..rest] ->
      if candidate.output_reference == domain.state_ref {
        when candidate.output.datum is {
          InlineDatum(inline) -> {
            expect state: AccountState = inline
            state
          }
          _ -> fail @"state reference does not carry an inline datum"
        }
      } else {
        resolve_from(domain, rest)
      }
  }
}

/// Exact domain equality against a canonically rebuilt domain, plus singleton NFT custody
/// within the selected input set. Mirrors `AccountLib.authenticateFrom`.
pub fn authenticate_from(
  state: AccountState,
  domain: IntentDomain,
  deployment: DeploymentDomain,
  state_hash: ByteArray,
  candidates: List<Input>,
) -> Bool {
  let canonical =
    IntentDomain {
      protocol_version: 1,
      deployment_domain: deployment,
      account_id: state.account_id,
      core_binding: state.core_binding,
      state_version: state.state_version,
      state_ref: domain.state_ref,
    }
  if domain != canonical {
    False
  } else {
    let matched =
      list.filter(
        candidates,
        fn(candidate) {
          contains_policy(candidate.output.value, state.account_id.policy)
        },
      )
    let all_valid =
      list.all(
        matched,
        fn(candidate) {
          candidate.output_reference == domain.state_ref && state_output(
            candidate.output,
            state,
            state_hash,
          )
        },
      )
    all_valid && list.length(matched) == 1
  }
}

/// Finite effective ledger interval contained in the signed half-open interval.
/// Mirrors `AccountLib.validity`.
pub fn validity(window: Validity, tx: Transaction) -> Bool {
  let start = window.not_before
  let end = window.expires_at
  if !uint63(start) || !uint63(end) || end <= start || end - start > 300000 || !shape(
    window,
    0,
    2,
  ) {
    False
  } else {
    let range = tx.validity_range
    let lower =
      when range.lower_bound.bound_type is {
        Finite(time) -> time
        _ -> -1
      }
    let upper =
      when range.upper_bound.bound_type is {
        Finite(time) -> time
        _ -> -1
      }
    start <= lower && lower < upper && ( upper < end || upper == end && !range.upper_bound.is_inclusive )
  }
}

/// Exact top-level signed envelope; nested action shape is checked by the lifecycle rules.
/// Mirrors `AccountLib.envelope`.
pub fn envelope(intent: IntentEnvelope, tx: Transaction) -> Bool {
  intent.protocol_tag == #"4b41564143485f494e54454e5401" && builtin.length_of_bytearray(
    builtin.serialise_data(intent),
  ) <= 1536 && shape(intent, 0, 4) && validity(intent.validity, tx)
}

/// Whether the receipt table allocates this output index; true indicates a conflict with a
/// signed recipient or account change. Mirrors `AccountLib.receiptAt`.
pub fn receipt_at(table: List<RewardReceipt>, position: Int) -> Bool {
  when table is {
    [] -> False
    [head, ..rest] -> head.output_index == position || receipt_at(rest, position)
  }
}
