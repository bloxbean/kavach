//// Port of the Java `PolicyLib`: bounded amount policies and immutable-per-configuration
//// credential evidence methods.

use aiken/builtin
use aiken/collection/list
use aiken/crypto
use cardano/transaction.{Transaction}
use kavach/account_lib.{shape, uint63}
use kavach/auth/browser_evidence
use kavach/auth/ed25519.{Ed25519Config, KeyEntry, ThresholdPolicy}
use kavach/types.{Action, Signature, Spend}

/// Canonical five-field module configuration (constructor 0).
pub type PolicyConfig {
  schema_version: Int,
  roles: Ed25519Config,
  cose_ids: List<Int>,
  small_payment_limit: Int,
  small_spend: ThresholdPolicy,
}

/// All bounds, role independence and the implication that strong approval implies small
/// approval. Mirrors `PolicyLib.configuration`.
pub fn configuration(config: PolicyConfig) -> Bool {
  if !shape(config, 0, 5) || config.schema_version != 1 || builtin.length_of_bytearray(
    builtin.serialise_data(config),
  ) > 1024 || !uint63(config.small_payment_limit) || !ed25519.configuration(
    config.roles,
  ) {
    False
  } else {
    let roles = config.roles
    let low = config.small_spend
    let low_count = list.length(low.credential_ids)
    // A strict subset inherits registry membership and defensive separation from strong spend.
    if !shape(low, 0, 2) || low.credential_ids == [] || low.threshold < 1 || low_count < low.threshold || !subset(
      low.credential_ids,
      roles.spend.credential_ids,
    ) {
      False
    } else {
      let extra = list.length(roles.spend.credential_ids) - low_count
      if roles.spend.threshold - extra < low.threshold {
        False
      } else {
        cose_ids_valid(config.cose_ids, -1, roles.keys)
      }
    }
  }
}

fn cose_ids_valid(ids: List<Int>, previous: Int, registry: List<KeyEntry>) -> Bool {
  when ids is {
    [] -> True
    [id, ..rest] ->
      previous < id && known(id, registry) && cose_ids_valid(rest, id, registry)
  }
}

/// Ordered subset check. Mirrors `PolicyLib.subset`.
fn subset(small: List<Int>, large: List<Int>) -> Bool {
  when small is {
    [] -> True
    [s, ..small_rest] ->
      when large is {
        [] -> False
        [l, ..large_rest] ->
          if s == l {
            subset(small_rest, large_rest)
          } else {
            s > l && subset(small, large_rest)
          }
      }
  }
}

/// Resolves a configured ID without accepting an evidence-supplied key.
/// Mirrors `PolicyLib.known`.
fn known(id: Int, registry: List<KeyEntry>) -> Bool {
  when registry is {
    [] -> False
    [key, ..rest] -> id == key.credential_id || known(id, rest)
  }
}

/// Small policy only for explicitly bounded ADA allocations; whole transfers use strong.
/// Mirrors `PolicyLib.spendPolicy`.
pub fn spend_policy(config: PolicyConfig, action: Action) -> ThresholdPolicy {
  when action is {
    Spend { recipients, max_account_fee, .. } -> {
      let (ada_only, debit) =
        list.foldl(
          recipients,
          (0 <= max_account_fee, max_account_fee),
          fn(recipient, outer) {
            list.foldl(
              recipient.value,
              outer,
              fn(asset, acc) {
                let (ok, total) = acc
                (
                  ok && builtin.length_of_bytearray(asset.policy) == 0 && builtin.length_of_bytearray(
                    asset.name,
                  ) == 0 && asset.quantity > 0,
                  total + asset.quantity,
                )
              },
            )
          },
        )
      if ada_only && debit <= config.small_payment_limit {
        config.small_spend
      } else {
        config.roles.spend
      }
    }
    _ -> config.roles.spend
  }
}

/// Every distinct ordered proof in the method fixed by authenticated configuration.
/// Mirrors `PolicyLib.signatures`.
pub fn signatures(
  evidence: List<Signature>,
  config: PolicyConfig,
  message: ByteArray,
  maximum: Int,
  network: Int,
  tx: Transaction,
) -> Bool {
  evidence != [] && list.length(evidence) <= maximum && verify_sorted(
    evidence,
    config.roles.keys,
    config.cose_ids,
    message,
    network,
    tx,
  )
}

fn verify_sorted(
  evidence: List<Signature>,
  registry: List<KeyEntry>,
  cose_ids: List<Int>,
  message: ByteArray,
  network: Int,
  tx: Transaction,
) -> Bool {
  when evidence is {
    [] -> True
    [proof, ..rest_evidence] ->
      when registry is {
        [] -> False
        [key, ..rest_keys] ->
          if proof.credential_id < key.credential_id {
            False
          } else if proof.credential_id > key.credential_id {
            verify_sorted(evidence, rest_keys, cose_ids, message, network, tx)
          } else if !shape(proof, 0, 2) {
            False
          } else {
            let valid =
              if list.has(cose_ids, key.credential_id) {
                browser_evidence.cose(key.public_key, message, proof.signature, network)
              } else {
                builtin.length_of_bytearray(proof.signature) == 0 && list.has(
                  tx.extra_signatories,
                  crypto.blake2b_224(key.public_key),
                )
              }
            valid && verify_sorted(
              rest_evidence,
              rest_keys,
              cose_ids,
              message,
              network,
              tx,
            )
          }
      }
  }
}
