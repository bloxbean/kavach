//// Port of the Java `Ed25519Lib`: bounded raw-Ed25519 registry and role policies. No key
//// may be supplied by signature evidence.

use aiken/builtin
use aiken/collection/list
use kavach/account_lib.{shape}
use kavach/types.{Signature}

/// `key-entry = #6.121([credential-id, public-key: bytes .size 32])`
pub type KeyEntry {
  credential_id: Int,
  public_key: ByteArray,
}

/// `threshold-policy = #6.121([threshold, credential-ids])`
pub type ThresholdPolicy {
  threshold: Int,
  credential_ids: List<Int>,
}

/// `ed25519-config`, in normative CDDL field order.
pub type Ed25519Config {
  schema_version: Int,
  keys: List<KeyEntry>,
  spend: ThresholdPolicy,
  admin: ThresholdPolicy,
  freeze: ThresholdPolicy,
  unfreeze: ThresholdPolicy,
  recovery: ThresholdPolicy,
  cancel: ThresholdPolicy,
}

/// Canonical schema, unique keys and mandatory defensive role independence.
/// Mirrors `Ed25519Lib.configuration`.
pub fn configuration(config: Ed25519Config) -> Bool {
  let key_count = list.length(config.keys)
  if config.schema_version != 1 || key_count < 3 || key_count > 16 || builtin.length_of_bytearray(
    builtin.serialise_data(config),
  ) > 1024 || !shape(config, 0, 8) {
    False
  } else {
    let keys = config.keys
    canonical_keys(keys, -1) && unique_keys(keys) && policy(config.spend, keys) && policy(
      config.admin,
      keys,
    ) && policy(config.freeze, keys) && policy(config.unfreeze, keys) && policy(
      config.recovery,
      keys,
    ) && policy(config.cancel, keys) && overlap(config.recovery, config.unfreeze) == 0 && overlap(
      config.recovery,
      config.cancel,
    ) == 0 && overlap(config.spend, config.unfreeze) == 0 && overlap(
      config.spend,
      config.cancel,
    ) == 0 && overlap(config.spend, config.admin) < config.admin.threshold
  }
}

/// Transfer-facing schema and threshold on authenticated immutable state.
/// Mirrors `Ed25519Lib.spendConfiguration`.
pub fn spend_configuration(config: Ed25519Config) -> Bool {
  let spend = config.spend
  let key_count = list.length(config.keys)
  let member_count = list.length(spend.credential_ids)
  if config.schema_version != 1 || key_count < 3 || key_count > 16 || spend.credential_ids == [] || member_count > 8 || spend.threshold <= 0 || member_count < spend.threshold || !shape(
    spend,
    0,
    2,
  ) || !shape(config, 0, 8) {
    False
  } else {
    ordered_ids(spend.credential_ids, -1) && canonical_keys(config.keys, -1)
  }
}

fn ordered_ids(ids: List<Int>, previous: Int) -> Bool {
  when ids is {
    [] -> True
    [id, ..rest] -> previous < id && id <= 15 && ordered_ids(rest, id)
  }
}

fn canonical_keys(keys: List<KeyEntry>, previous: Int) -> Bool {
  when keys is {
    [] -> True
    [key, ..rest] ->
      previous < key.credential_id && key.credential_id <= 15 && builtin.length_of_bytearray(
        key.public_key,
      ) == 32 && shape(key, 0, 2) && canonical_keys(rest, key.credential_id)
  }
}

/// Rejects unknown, duplicate or unsorted policy members and malformed threshold records.
/// Mirrors `Ed25519Lib.policy`.
pub fn policy(selected: ThresholdPolicy, registry: List<KeyEntry>) -> Bool {
  let member_count = list.length(selected.credential_ids)
  if selected.credential_ids == [] || member_count > 8 || selected.threshold <= 0 || member_count < selected.threshold || !shape(
    selected,
    0,
    2,
  ) {
    False
  } else {
    known_members(selected.credential_ids, registry)
  }
}

/// Linear ordered subset check against a strictly increasing registry.
/// Mirrors `Ed25519Lib.knownMembers`.
pub fn known_members(members: List<Int>, registry: List<KeyEntry>) -> Bool {
  when members is {
    [] -> True
    [member, ..rest_members] ->
      when registry is {
        [] -> False
        [key, ..rest_keys] ->
          if member < key.credential_id {
            False
          } else if member > key.credential_id {
            known_members(members, rest_keys)
          } else {
            known_members(rest_members, rest_keys)
          }
      }
  }
}

/// Each public-key pair checked once. Mirrors `Ed25519Lib.uniqueKeys`.
pub fn unique_keys(registry: List<KeyEntry>) -> Bool {
  when registry is {
    [] -> True
    [key, ..rest] -> !public_key_known(key.public_key, rest) && unique_keys(rest)
  }
}

/// Raw public-key identity lookup. Mirrors `Ed25519Lib.publicKeyKnown`.
pub fn public_key_known(selected: ByteArray, registry: List<KeyEntry>) -> Bool {
  when registry is {
    [] -> False
    [key, ..rest] -> selected == key.public_key || public_key_known(selected, rest)
  }
}

/// Overlapping canonical role IDs. Mirrors `Ed25519Lib.overlap`.
pub fn overlap(a: ThresholdPolicy, b: ThresholdPolicy) -> Int {
  intersect(a.credential_ids, b.credential_ids)
}

fn intersect(left: List<Int>, right: List<Int>) -> Int {
  when left is {
    [] -> 0
    [l, ..left_rest] ->
      when right is {
        [] -> 0
        [r, ..right_rest] ->
          if l < r {
            intersect(left_rest, right)
          } else if l > r {
            intersect(left, right_rest)
          } else {
            1 + intersect(left_rest, right_rest)
          }
      }
  }
}

/// Verifies every supplied signature and its canonical registry ID.
/// Mirrors `Ed25519Lib.signatures`.
pub fn signatures(
  evidence: List<Signature>,
  registry: List<KeyEntry>,
  message: ByteArray,
  maximum: Int,
) -> Bool {
  if evidence == [] || list.length(evidence) > maximum {
    False
  } else {
    verify_sorted(evidence, registry, message)
  }
}

fn verify_sorted(
  evidence: List<Signature>,
  registry: List<KeyEntry>,
  message: ByteArray,
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
            verify_sorted(evidence, rest_keys, message)
          } else {
            builtin.length_of_bytearray(proof.signature) == 64 && shape(proof, 0, 2) && builtin.verify_ed25519_signature(
              key.public_key,
              message,
              proof.signature,
            ) && verify_sorted(rest_evidence, rest_keys, message)
          }
      }
  }
}

/// Only verified evidence from the selected role contributes to its threshold.
/// Mirrors `Ed25519Lib.threshold`.
pub fn threshold(evidence: List<Signature>, selected: ThresholdPolicy) -> Bool {
  selected.threshold <= count_members(evidence, selected.credential_ids)
}

fn count_members(evidence: List<Signature>, members: List<Int>) -> Int {
  when evidence is {
    [] -> 0
    [proof, ..rest_evidence] ->
      when members is {
        [] -> 0
        [member, ..rest_members] ->
          if proof.credential_id < member {
            count_members(rest_evidence, members)
          } else if proof.credential_id > member {
            count_members(evidence, rest_members)
          } else {
            1 + count_members(rest_evidence, rest_members)
          }
      }
  }
}
