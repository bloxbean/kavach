//// Port of the Java `StateNftPolicy`.
////
//// Creator-bound one-shot state NFT policy. The seed and required creator transaction
//// signature authenticate the final state without placing its own policy ID in parameters.
//// Initialization requires the selected module's exact genesis Rewarding invocation.
//// Burning and subsequent minting are unsupported. Mint redeemer is integer zero.
////
//// Immutable parameters, in order: `core_version`, `deployment_domain`, `seed`, `creator`,
//// `state_validator_hash` — the Java `@Param` order.

use aiken/collection/list
use aiken/collection/pairs
use cardano/address.{Script, VerificationKey}
use cardano/assets.{PolicyId, quantity_of}
use cardano/transaction.{
  InlineDatum, OutputReference, ScriptPurpose, Transaction, Withdraw,
}
use kavach/account_lib.{
  asset_count, is_key, normal_state, receipt_at, state_output, transaction_shape,
}
use kavach/types.{
  AccountState, DeploymentDomain, GenesisModuleRedeemer, ModuleInvocation,
}

validator state_nft(
  core_version: Int,
  deployment_domain: DeploymentDomain,
  seed: OutputReference,
  creator: ByteArray,
  state_validator_hash: ByteArray,
) {
  /// Initializes exactly one correctly formed NFT state output with creator and module
  /// approval.
  mint(redeemer: Int, policy_id: PolicyId, tx: Transaction) {
    if redeemer != 0 || core_version != 1 || !transaction_shape(tx) {
      False
    } else {
      initialize(
        policy_id,
        tx,
        deployment_domain,
        seed,
        creator,
        state_validator_hash,
      )
    }
  }

  else(_) {
    fail @"only the minting purpose is supported"
  }
}

/// The one-shot seed, exact supply and canonical module-bound genesis state.
fn initialize(
  own_policy: PolicyId,
  tx: Transaction,
  deployment_domain: DeploymentDomain,
  seed: OutputReference,
  creator: ByteArray,
  state_validator_hash: ByteArray,
) -> Bool {
  let signed = list.has(tx.extra_signatories, creator)
  let inputs_valid =
    list.all(tx.inputs, fn(input) { is_key(input.output.address.payment_credential) })
  let consumed =
    list.foldl(
      tx.inputs,
      False,
      fn(input, acc) {
        if input.output_reference == seed {
          when input.output.address.payment_credential is {
            VerificationKey(hash) -> hash == creator
            _ -> False
          }
        } else {
          acc
        }
      },
    )
  if !signed || !consumed || !inputs_valid || asset_count(tx.mint) != 1 || quantity_of(
    tx.mint,
    own_policy,
    "",
  ) != 1 || list.length(tx.withdrawals) != 1 {
    False
  } else {
    let (matches, state_index, _) =
      list.foldl(
        tx.outputs,
        (0, -1, 0),
        fn(output, acc) {
          let (matches, state_index, position) = acc
          if quantity_of(output.value, own_policy, "") != 0 {
            (matches + 1, position, position + 1)
          } else {
            (matches, state_index, position + 1)
          }
        },
      )
    if matches != 1 {
      False
    } else {
      expect Some(output) = list.at(tx.outputs, state_index)
      let state: AccountState =
        when output.datum is {
          InlineDatum(inline) -> {
            expect decoded: AccountState = inline
            decoded
          }
          _ -> fail @"state output does not carry an inline datum"
        }
      if !normal_state(state, deployment_domain, state_validator_hash) || state.account_id.policy != own_policy || state.state_version != 0 || state.recovery_sequence != 0 || state.recovery_not_before != 0 || !state_output(
        output,
        state,
        state_validator_hash,
      ) {
        False
      } else {
        let module = Script(state.auth_module.script_hash)
        let purpose: ScriptPurpose = Withdraw(module)
        if !pairs.has_key(tx.withdrawals, module) {
          False
        } else {
          when pairs.get_first(tx.redeemers, purpose) is {
            None -> False
            Some(genesis_data) -> {
              expect genesis: ModuleInvocation = genesis_data
              when genesis is {
                GenesisModuleRedeemer { config_possession, receipts, .. } -> {
                  let expected: Data =
                    GenesisModuleRedeemer {
                      abi_version: 1,
                      state,
                      config_possession,
                      receipts,
                    }
                  genesis_data == expected && account_lib.receipts(receipts, tx) && !receipt_at(
                    receipts,
                    state_index,
                  )
                }
                _ -> False
              }
            }
          }
        }
      }
    }
  }
}
