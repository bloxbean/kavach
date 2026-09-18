//// Port of the Java `AccountStateValidator`.
////
//// Immutable state custody and lifecycle enforcement. Consumes the authenticated current
//// NFT and requires exactly one fully determined successor without reducing its ADA. The
//// old core checkpoint must approve the same intent and invoke the configured module.
//// NFT-less deposits and unsupported actions have no spending path.
////
//// Immutable parameters, in order: `core_version`, `deployment_domain` — the same order as
//// the Java `@Param` declarations, so applied-parameter sizes stay comparable.
////
//// This exists to measure Aiken's compiled size against JuLC's for identical logic. It is a
//// spike, not a second protocol implementation, and is not deployed.

use cardano/transaction.{OutputReference, Transaction}
use kavach/lifecycle
use kavach/state_transition
use kavach/types.{CoreRedeemer, DeploymentDomain}

validator account_state(core_version: Int, deployment_domain: DeploymentDomain) {
  spend(
    _datum: Option<Data>,
    redeemer: CoreRedeemer,
    own_ref: OutputReference,
    tx: Transaction,
  ) {
    if core_version != 1 {
      False
    } else {
      let previous = state_transition.resolve(redeemer.intent.domain, tx)
      // The Java reads the spending TxOutRef out of ScriptInfo; Aiken supplies it directly.
      let own_input = own_ref == redeemer.intent.domain.state_ref
      if !own_input || !state_transition.authenticate(
        previous,
        redeemer.intent.domain,
        deployment_domain,
        previous.core_binding.state_validator,
        tx,
      ) || !state_transition.core_binding(redeemer, previous, tx) || !state_transition.sponsor_inputs(
        redeemer,
        tx,
      ) {
        False
      } else {
        let next_state = lifecycle.successor(previous, redeemer.intent, tx)
        state_transition.outputs(redeemer, previous, next_state, tx)
      }
    }
  }

  else(_) {
    fail @"only the spending purpose is supported"
  }
}
