# Ordinary V1 initialization parameter graph

This is the normative dependency graph and creator-authorization construction. Production template bytes and their final hashes are Phase 1/3 artifacts. The [compiled identity-probe vector](../../conformance/v1/deployment-probe.json) establishes ordered parameter application, a seed-specific AccountId and a dependent script without a hash cycle. It deliberately uses a sealed holder and a reference reader; those are not production state/asset validators. The full twelve-field state encoding and genesis-possession domain have separate canonical vectors.

| Artifact | Ordered parameters / dependencies |
| --- | --- |
| State validator | Core version; deployment domain |
| Core checkpoint | Core version; deployment domain; state-validator hash; immutable full reward sink |
| Authorization module | Module version; ABI version; deployment domain; state-validator hash; core-checkpoint hash; immutable full reward sink |
| State NFT policy | Core version; deployment domain; creator-owned seed TxOutRef; creator key hash; state-validator hash |
| AccountId | State NFT policy hash; empty asset name |
| Asset validator | Core version; deployment domain; AccountId; state-validator hash; core-checkpoint hash |
| Initial AccountState | All derived identities/bindings; selected module/config; version and sequence zero; bounded delay/cooldown; recoveryNotBefore zero; Normal mode |

Every version is explicit and positive. Parameter values use the corresponding CDDL records, integers and byte strings; never substitute textual addresses/hashes or omit network/deployment fields. Each implementation must publish its template bytes, parameter application procedure, compiled parameterized bytes and resulting hashes. Language-independent conformance does not promise identical script bytes or addresses.

The mint policy's parameters must not contain the final state, AccountId, asset hash or a commitment transitively containing them. They are computed only after the policy hash exists. The creator-owned seed must be consumed and its creator included in required transaction signers. The creator's transaction-body signature authenticates the complete final inline state and its output address. The creation client derives the asset binding from the published template and parameters before requesting that signature; it must never accept a relayer-supplied binding without recomputation. This is the chosen explicit creator-authorized commitment construction, not an assumed recursive on-chain hash derivation.

Creation mints exactly one empty-name NFT and no other mint entries, to exactly one supported enterprise state address with the exact genesis inline datum, positive minimum ADA and no reference script or unrelated assets. Core binding fields are immutable thereafter. Unauthenticated NFT-less deposits at the shared state address have no sweep path under the selected state-validator rules.

The selected initial module must already be registered. The creation transaction invokes its reward credential with `genesis-module-redeemer`: ABI version, full initial state, all-key possession signatures and any reward receipt. The first module validates the entire initial configuration and recomputes `genesis-proof-envelope`; every registered key must prove possession. The mint policy binds the module invocation to exactly the same initial state and rewarding purpose. The trusted initial module is selected and pinned by the creator's initialization transaction, just as later module replacement requires old administration authority. A creator who intentionally installs a malicious module does not obtain a safe account.

A positive module reward balance must be fully withdrawn to its immutable receipt sink, disjoint from the NFT output and operational account outputs. Receipt top-ups come from the external sponsor. Registration and its deposit are separate from the NFT's permanent state minimum ADA; the first initialization transaction needs an available key-controlled collateral provider. This creation ABI requires implementation and adversarial ledger tests in Phase 1; the isolated Phase 0 mint probe uses the documented three-field experimental datum.
