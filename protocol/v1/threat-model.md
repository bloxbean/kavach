# Phase 0 ordinary V1 threat-model baseline

This baseline accompanies ADR-001 section 27 and the versioned wire/semantic/ABI specifications. It defines implementation obligations, not a claim that the Phase 0 probes implement a secure wallet.

Assume an adversary can construct arbitrary transactions, control relayers and indexers, supply fake datums/NFT-looking identifiers, change unsigned outputs and reference hints, reorder or duplicate evidence, replay old signatures, send unsolicited native tokens or reward credits, and compromise a subset of user/guardian credentials. Public chain data and transaction races are observable. The ledger, selected cryptographic primitives and reviewed compiler/module implementations remain trust dependencies.

| Boundary | Required invariant |
| --- | --- |
| Account creation/discovery | Creator-authorized final state; acyclic script parameter graph; one-shot NFT; authenticate full policy/name, quantity, address and schema, not a datum claim |
| Immutable core versus module | Core retains lifecycle/replay/value/identity rules; exact Rewarding invocation binds the same state/domain/action/envelope/receipts; successor cannot authorize its own installation |
| Signed versus relayer-selected data | Signer decodes, validates, renders and hashes the same typed envelope, resolves its state/input values, and authenticates them independently; every input/allocation/fee bound is committed |
| Value allocation | Complete asset accounting, distinct recipient/change/state/reward allocations; no double satisfaction, mint subsidy or account-funded reward receipt; whole transfer preserves full input native map and ADA |
| Recovery authority | Distinct defensive keys, bounded initiation cooldown, conservative transaction-time deadlines, full stored target, target possession, monotonic version/sequence; no spend or admin while pending |
| Credential evidence | Unique registered public keys, bounded unique proofs, raw scheme 0 only; distinct genesis/configuration/target domains; new-key possession; everyday spend keys cannot meet admin threshold |
| Ledger purposes/availability | Explicit supported-purpose dispatch; narrow registration only, no deregistration/delegation; full positive withdrawals to immutable sinks; external collateral, redundant script references and retained bytes |
| Resource exhaustion | Count/byte/integer limits, checked arithmetic and measured compiled budgets; out-of-profile partial inputs use the separately qualified whole-UTxO action |

Residual risks are explicit. An authorized malicious module can approve theft or lock recovery. Colluding guardians can complete takeover if independent defenders do not cancel on-chain. Freeze can lose a race to an already authorized spend. A compromised signer/display can lie despite canonical rendering. Lost account-locator backups, unavailable collateral sponsorship, chain outages or missing script bytes can prevent access. Core, schema/compiler or stake-address changes may require migration; there is no burn/closure or generic emergency bypass in ordinary V1. State minimum ADA and registration/reference deposits are not generally refundable.

CIP-113 outcome B is selected: no ordinary-V1 interoperability or hash-preserving later adapter promise. Ledger-valid native token receipt alone does not establish CIP-113 custody compatibility. Arbitrary dApp execution, staking/voting, hardware/CIP-8 signing, sessions and core migration remain outside this baseline. Requalify assumptions, implementation hashes and budgets before deployment or changing the target ledger/toolchain.
