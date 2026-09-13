//// Wire-parity proof against the shared conformance fixtures.
////
//// The size comparison is only meaningful if both implementations encode the same protocol.
//// These tests build `conformance/v1/state.hex` from the Aiken types and require
//// `serialise_data` to reproduce it byte-for-byte, and require the documented Blake2b-256
//// digest to match. If the Aiken field or variant order drifted from the Java records, the
//// bytes would differ and these fail.
////
//// This is the first time `kavach.cddl` and these fixtures have had to hold on their own,
//// without the Java records being both the schema and its only implementation.

use aiken/builtin
use aiken/collection/list
use aiken/crypto
use kavach/types.{
  AccountId, AccountState, AuthModuleRef, CoreBinding, DeploymentDomain, Normal,
}

/// `conformance/v1/state.hex`, the canonical serialized Normal-mode account state.
const state_fixture: ByteArray =
  #"d8799f01d8799f581c0101010101010101010101010101010101010101010101010101010140ffd8799f00182a58200202020202020202020202020202020202020202020202020202020202020202ffd8799f581c03030303030303030303030303030303030303030303030303030303581c04040404040404040404040404040404040404040404040404040404581c05050505050505050505050505050505050505050505050505050505ff00d8799f581c0707070707070707070707070707070707070707070707070707070701ffd8799f019fd8799f005820b060cdbd3fe1625b92d04736ad0aa3d061c023f8353fdca377e1b9b73154441fffd8799f015820af17af62b0cc23e8f8da2e3d26ff82ec733866a1a5320f99bf75f2b0ce8b446effd8799f02582090a88623b94d936224aa6f9a8023497a2d507b2243b74ba963b011e79e627e94ffffd8799f019f00ffffd8799f019f01ffffd8799f019f00ffffd8799f019f01ffffd8799f019f02ffffd8799f019f01ffffff001a05265c001a0036ee8000d87980ff"

/// `state.digest.blake2b256` from `conformance/v1/state-rendering.txt`.
const state_digest: ByteArray =
  #"c5b99387aaeaa32f868e51f16e990f7f55ae5b1d307776c63dc05c2dde269096"

/// `key-entry = #6.121([credential-id, public-key: bytes .size 32])`
fn key_entry(id: Int, public_key: ByteArray) -> Data {
  builtin.constr_data(0, [builtin.i_data(id), builtin.b_data(public_key)])
}

/// `threshold-policy = #6.121([threshold, credential-ids])`
fn threshold_policy(threshold: Int, ids: List<Int>) -> Data {
  builtin.constr_data(
    0,
    [
      builtin.i_data(threshold),
      builtin.list_data(
        list.map(ids, fn(id) { builtin.i_data(id) }),
      ),
    ],
  )
}

/// The opaque `ed25519-config` the fixture carries. The core never interprets it; it is
/// reproduced here only so the enclosing state serializes to the exact fixture bytes.
fn fixture_config() -> Data {
  builtin.constr_data(
    0,
    [
      builtin.i_data(1),
      builtin.list_data(
        [
          key_entry(
            0,
            #"b060cdbd3fe1625b92d04736ad0aa3d061c023f8353fdca377e1b9b73154441f",
          ),
          key_entry(
            1,
            #"af17af62b0cc23e8f8da2e3d26ff82ec733866a1a5320f99bf75f2b0ce8b446e",
          ),
          key_entry(
            2,
            #"90a88623b94d936224aa6f9a8023497a2d507b2243b74ba963b011e79e627e94",
          ),
        ],
      ),
      threshold_policy(1, [0]),
      threshold_policy(1, [1]),
      threshold_policy(1, [0]),
      threshold_policy(1, [1]),
      threshold_policy(1, [2]),
      threshold_policy(1, [1]),
    ],
  )
}

/// The fixture state, field for field from `conformance/v1/state-rendering.txt`.
fn fixture_state() -> AccountState {
  AccountState {
    schema_version: 1,
    account_id: AccountId {
      policy: #"01010101010101010101010101010101010101010101010101010101",
      name: "",
    },
    deployment_domain: DeploymentDomain {
      network_id: 0,
      network_magic: 42,
      deployment_id: #"0202020202020202020202020202020202020202020202020202020202020202",
    },
    core_binding: CoreBinding {
      state_validator: #"03030303030303030303030303030303030303030303030303030303",
      asset_validator: #"04040404040404040404040404040404040404040404040404040404",
      checkpoint: #"05050505050505050505050505050505050505050505050505050505",
    },
    state_version: 0,
    auth_module: AuthModuleRef {
      script_hash: #"07070707070707070707070707070707070707070707070707070707",
      abi_version: 1,
    },
    auth_config: fixture_config(),
    recovery_sequence: 0,
    recovery_delay_millis: 86400000,
    recovery_cooldown_millis: 3600000,
    recovery_not_before: 0,
    mode: Normal,
  }
}

test aiken_state_encoding_matches_the_conformance_fixture() {
  builtin.serialise_data(fixture_state()) == state_fixture
}

test aiken_state_digest_matches_the_conformance_fixture() {
  crypto.blake2b_256(builtin.serialise_data(fixture_state())) == state_digest
}
