//// Measurement probes, not protocol code. They isolate the cost of Aiken's deep structural
//// `expect`, which the Java unchecked casts do not perform. The port has two: the typed
//// `CoreRedeemer` redeemer, and `resolve_from`'s `expect state: AccountState = inline`, which
//// stands in for the Java `(AccountState) (Object) inline.datum()`. Without these the size
//// comparison would charge Aiken for validation JuLC never does.

use cardano/transaction.{OutputReference, Transaction}
use kavach/types.{AccountState, CoreRedeemer}

validator probe_typed {
  spend(_d: Option<Data>, r: CoreRedeemer, _o: OutputReference, _tx: Transaction) {
    r.abi_version == 1
  }

  else(_) {
    fail
  }
}

validator probe_raw {
  spend(_d: Option<Data>, r: Data, _o: OutputReference, _tx: Transaction) {
    r == r
  }

  else(_) {
    fail
  }
}

validator probe_state_typed {
  spend(_d: Option<Data>, r: Data, _o: OutputReference, _tx: Transaction) {
    expect state: AccountState = r
    state.schema_version == 1
  }

  else(_) {
    fail
  }
}
