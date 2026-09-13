//// Port of the Java `BrowserEvidenceLib`. Mode 1 is ledger payment-key witnesses; mode 2
//// is the bounded CIP-30 COSE profile. The applied module parameter fixes the mode.

use aiken/builtin
use aiken/collection/list
use aiken/crypto
use cardano/transaction.{Transaction}
use kavach/account_lib.{shape}
use kavach/auth/ed25519.{KeyEntry}
use kavach/types.{Signature}

/// Every ordered proof against a registered key. Mirrors `BrowserEvidenceLib.signatures`.
pub fn signatures(
  evidence: List<Signature>,
  registry: List<KeyEntry>,
  message: ByteArray,
  maximum: Int,
  mode: Int,
  network: Int,
  tx: Transaction,
) -> Bool {
  if evidence == [] || list.length(evidence) > maximum {
    False
  } else {
    verify_sorted(evidence, registry, message, mode, network, tx)
  }
}

fn verify_sorted(
  evidence: List<Signature>,
  registry: List<KeyEntry>,
  message: ByteArray,
  mode: Int,
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
            verify_sorted(evidence, rest_keys, message, mode, network, tx)
          } else if !shape(proof, 0, 2) {
            False
          } else {
            let valid =
              if mode == 1 {
                builtin.length_of_bytearray(proof.signature) == 0 && list.has(
                  tx.extra_signatories,
                  crypto.blake2b_224(key.public_key),
                )
              } else {
                mode == 2 && cose(key.public_key, message, proof.signature, network)
              }
            valid && verify_sorted(
              rest_evidence,
              rest_keys,
              message,
              mode,
              network,
              tx,
            )
          }
      }
  }
}

/// The exact COSE structure reconstructed from bounded evidence. A trailing byte 1 selects
/// kid; it changes signed bytes and cannot bypass verification. Mirrors `BrowserEvidenceLib.cose`.
pub fn cose(
  key: ByteArray,
  message: ByteArray,
  packed: ByteArray,
  network: Int,
) -> Bool {
  let size = builtin.length_of_bytearray(packed)
  let with_kid = size == 94 || size == 122
  if size != 93 && size != 121 && !with_kid || builtin.length_of_bytearray(key) != 32 || builtin.length_of_bytearray(
    message,
  ) != 32 || network != 0 && network != 1 {
    False
  } else if with_kid && builtin.index_bytearray(packed, size - 1) != 1 {
    False
  } else {
    let address_length =
      size - 64 - if with_kid {
        1
      } else {
        0
      }
    let header = builtin.index_bytearray(packed, 0)
    let kind = header / 16
    let kind_ok =
      if address_length == 29 {
        kind == 6
      } else {
        kind == 0 || kind == 2
      }
    if header % 16 != network || !kind_ok {
      False
    } else if builtin.slice_bytearray(1, 28, packed) != crypto.blake2b_224(key) {
      False
    } else {
      let address = builtin.slice_bytearray(0, address_length, packed)
      let signature = builtin.slice_bytearray(address_length, 64, packed)
      builtin.verify_ed25519_signature(
        key,
        signing_structure_with_kid(address, message, with_kid),
        signature,
      )
    }
  }
}

/// Canonical CBOR Sig_structure with the optional address-valued kid.
/// Mirrors `BrowserEvidenceLib.signingStructureWithKid`.
pub fn signing_structure_with_kid(
  address: ByteArray,
  message: ByteArray,
  with_kid: Bool,
) -> ByteArray {
  let prefix =
    if with_kid {
      builtin.append_bytearray(
        builtin.cons_bytearray(163, #"012704"),
        short_bytes(address),
      )
    } else {
      builtin.cons_bytearray(162, #"0127")
    }
  let protected =
    builtin.append_bytearray(
      prefix,
      builtin.append_bytearray(#"6761646472657373", short_bytes(address)),
    )
  builtin.append_bytearray(
    builtin.append_bytearray(
      builtin.cons_bytearray(132, #"6a5369676e617475726531"),
      short_bytes(protected),
    ),
    builtin.append_bytearray(#"405820", message),
  )
}

/// Two-byte CBOR byte-string length prefix for lengths 24..255.
fn short_bytes(bytes: ByteArray) -> ByteArray {
  builtin.cons_bytearray(
    88,
    builtin.cons_bytearray(builtin.length_of_bytearray(bytes), bytes),
  )
}
