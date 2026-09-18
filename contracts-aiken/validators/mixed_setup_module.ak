//// Port of the Java `MixedSetupModule`.
////
//// Creation-only policy module. Genesis establishes all-key possession of the policy
//// configuration; the only later operation is one admin-approved replacement by the fixed
//// final module with an identical configuration.
////
//// Immutable parameters, in order: `module_version`, `abi_version`, `deployment_domain`,
//// `state_validator_hash`, `core_checkpoint_hash`, `reward_sink`, `final_module_hash` — the
//// Java `@Param` order.

use aiken/builtin
use aiken/collection/list
use aiken/collection/pairs
use aiken/crypto
use cardano/address.{Address, Credential, Script}
use cardano/assets.{quantity_of}
use cardano/certificate.{Certificate}
use cardano/transaction.{ScriptPurpose, Spend, Transaction, Withdraw}
use kavach/account_lib.{
  asset_count, envelope, module_shape, normal_state, receipt_at, shape,
  state_output, transaction_shape,
}
use kavach/auth/policy.{PolicyConfig}
use kavach/auth/policy_lifecycle
use kavach/state_transition
use kavach/types.{
  AccountState, CoreRedeemer, DeploymentDomain, GenesisModuleRedeemer,
  GenesisProofEnvelope, IntentEnvelope, ModuleInvocation, ModuleRedeemer, Proof,
  ReplaceModule, RewardReceipt, Signature,
}

validator mixed_setup_module(
  module_version: Int,
  abi_version: Int,
  deployment_domain: DeploymentDomain,
  state_validator_hash: ByteArray,
  core_checkpoint_hash: ByteArray,
  reward_sink: Address,
  final_module_hash: ByteArray,
) {
  publish(redeemer: Data, certificate: Certificate, _tx: Transaction) {
    module_version == 1 && abi_version == 1 && redeemer == builtin.constr_data(
      1,
      [],
    ) && account_lib.registration(certificate)
  }

  withdraw(redeemer: Data, account: Credential, tx: Transaction) {
    if module_version != 1 || abi_version != 1 {
      False
    } else {
      expect invocation: ModuleInvocation = redeemer
      let network = deployment_domain.network_id
      when invocation is {
        GenesisModuleRedeemer {
          abi_version: genesis_abi,
          state,
          config_possession,
          receipts,
        } ->
          initialize(
            redeemer,
            genesis_abi,
            state,
            config_possession,
            receipts,
            account,
            tx,
            deployment_domain,
            state_validator_hash,
            core_checkpoint_hash,
            reward_sink,
            network,
          )
        ModuleRedeemer {
          abi_version: invocation_abi,
          intent,
          operation_proof,
          config_possession,
          receipts,
        } ->
          finish(
            redeemer,
            invocation_abi,
            intent,
            operation_proof,
            config_possession,
            receipts,
            account,
            tx,
            deployment_domain,
            state_validator_hash,
            core_checkpoint_hash,
            reward_sink,
            final_module_hash,
            network,
          )
        _ -> False
      }
    }
  }

  else(_) {
    fail @"unsupported script purpose"
  }
}

/// Usable configuration keys before the state NFT is created.
fn initialize(
  genesis: Data,
  genesis_abi: Int,
  state: AccountState,
  config_possession: List<Signature>,
  receipts: List<RewardReceipt>,
  account: Credential,
  tx: Transaction,
  deployment_domain: DeploymentDomain,
  state_validator_hash: ByteArray,
  core_checkpoint_hash: ByteArray,
  reward_sink: Address,
  network: Int,
) -> Bool {
  if !transaction_shape(tx) || genesis_abi != 1 || !normal_state(
    state,
    deployment_domain,
    state_validator_hash,
  ) || state.state_version != 0 || state.recovery_sequence != 0 || state.recovery_not_before != 0 || state.core_binding.checkpoint != core_checkpoint_hash || list.length(
    tx.withdrawals,
  ) != 1 || !shape(genesis, 2, 4) {
    False
  } else {
    let own = Script(state.auth_module.script_hash)
    let policy = state.account_id.policy
    if account != own || !account_lib.receipts(receipts, tx) || !account_lib.own_sink(
      own,
      reward_sink,
      receipts,
      tx,
    ) || asset_count(tx.mint) != 1 || quantity_of(tx.mint, policy, "") != 1 {
      False
    } else {
      let (valid, matches, _) =
        list.foldl(
          tx.outputs,
          (True, 0, 0),
          fn(output, acc) {
            let (valid, matches, position) = acc
            if quantity_of(output.value, policy, "") != 0 {
              (
                valid && state_output(output, state, state_validator_hash) && !receipt_at(
                  receipts,
                  position,
                ),
                matches + 1,
                position + 1,
              )
            } else {
              (valid, matches, position + 1)
            }
          },
        )
      if !valid || matches != 1 {
        False
      } else {
        expect config: PolicyConfig = state.auth_config
        if !policy.configuration(config) || list.length(config_possession) != list.length(
          config.roles.keys,
        ) {
          False
        } else {
          let preimage =
            GenesisProofEnvelope {
              tag: "KAVACH_GENESIS_POSSESSION_V1",
              deployment_domain,
              account_id: state.account_id,
              core_binding: state.core_binding,
              auth_module: state.auth_module,
              config_digest: crypto.blake2b_256(
                builtin.serialise_data(state.auth_config),
              ),
            }
          policy.signatures(
            config_possession,
            config,
            crypto.blake2b_256(builtin.serialise_data(preimage)),
            16,
            network,
            tx,
          )
        }
      }
    }
  }
}

/// The single admin-approved replacement by the fixed final module.
fn finish(
  invocation: Data,
  invocation_abi: Int,
  intent: IntentEnvelope,
  operation_proof: Option<Proof>,
  config_possession: List<Signature>,
  receipts: List<RewardReceipt>,
  account: Credential,
  tx: Transaction,
  deployment_domain: DeploymentDomain,
  state_validator_hash: ByteArray,
  core_checkpoint_hash: ByteArray,
  reward_sink: Address,
  final_module_hash: ByteArray,
  network: Int,
) -> Bool {
  if invocation_abi != 1 || !envelope(intent, tx) {
    False
  } else {
    let previous = state_transition.resolve(intent.domain, tx)
    if !state_transition.authenticate(
      previous,
      intent.domain,
      deployment_domain,
      state_validator_hash,
      tx,
    ) || previous.core_binding.checkpoint != core_checkpoint_hash {
      False
    } else {
      let own = Script(previous.auth_module.script_hash)
      let core_invocation: Data =
        CoreRedeemer { abi_version: 1, intent, receipts }
      let state_purpose: ScriptPurpose = Spend(intent.domain.state_ref)
      if !bound(invocation, intent, receipts, previous, own, account, tx, reward_sink) || pairs.get_first(
        tx.redeemers,
        state_purpose,
      ) != Some(core_invocation) {
        False
      } else {
        expect config: PolicyConfig = previous.auth_config
        if !policy.configuration(config) || config_possession != [] || !policy_lifecycle.approval(
          operation_proof,
          config,
          config.roles.admin,
          intent,
          network,
          tx,
        ) {
          False
        } else {
          when intent.action is {
            ReplaceModule { new_module, new_config } -> {
              let candidate = Script(new_module.script_hash)
              module_shape(new_module) && candidate != own && candidate != Script(
                core_checkpoint_hash,
              ) && new_module.script_hash == final_module_hash && new_config == previous.auth_config && list.length(
                tx.withdrawals,
              ) == 3 && pairs.has_key(tx.withdrawals, candidate)
            }
            _ -> False
          }
        }
      }
    }
  }
}

/// Exact purpose/envelope/receipt binding for reference-state and consumed-state operations.
fn bound(
  invocation: Data,
  intent: IntentEnvelope,
  receipts: List<RewardReceipt>,
  previous: AccountState,
  expected_own: Credential,
  account: Credential,
  tx: Transaction,
  reward_sink: Address,
) -> Bool {
  let own_purpose: ScriptPurpose = Withdraw(expected_own)
  let core_invocation = CoreRedeemer { abi_version: 1, intent, receipts }
  account == expected_own && state_transition.core_binding(
    core_invocation,
    previous,
    tx,
  ) && shape(invocation, 0, 5) && pairs.has_key(tx.withdrawals, expected_own) && pairs.get_first(
    tx.redeemers,
    own_purpose,
  ) == Some(invocation) && account_lib.receipts(receipts, tx) && account_lib.own_sink(
    expected_own,
    reward_sink,
    receipts,
    tx,
  )
}
