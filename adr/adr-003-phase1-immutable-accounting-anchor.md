# ADR-003: Phase 1 immutable accounting anchor

- Status: Proposed production design; Phase 1 development qualification complete
- Date: 2026-09-07
- Related: ADR-001 sections 5, 15–17, 24 and 29

The first composed JuLC core checkpoint exceeded the 16,384-byte transaction limit,
so even publishing it as a reference script was impossible. Keeping authorization,
identity and both transfer-accounting paths in a single checkpoint is not a viable
artifact partition with the current compiler. Removing security checks is unacceptable.

Value and exact-input accounting execute in the immutable account asset validator,
once at the first canonically signed account input (or the sole whole-transfer input).
The immutable checkpoint independently requires that exact anchor to be consumed at
the account's full enterprise address. Every consumed account input must carry the
same canonical intent digest. The anchor then checks the complete consumed account
input set, recipients, change, receipt disjointness and all assets/fee contribution.
Other account inputs forward to the same immutable checkpoint. No module owns or can
bypass these checks. Missing, key-locked, foreign-address or substituted anchors reject.

The checkpoint still authenticates state identity/schema/mode/domain, enforces validity,
operation/withdrawal shape, binds the installed module invocation and reward receipts,
and proves the anchor exists. The module independently authenticates state and signatures.
All three validators must succeed in a ledger transaction. A single component's success
is never transaction authorization.

Current compiled templates fit individually (11,887 bytes asset, 9,752 bytes
checkpoint and 15,808 bytes module, before applying deployment parameters). Reference scripts are needed for composed transactions.
Reference publication and ordinary/native transfer transactions have been confirmed on
DevKit. The combined eight-recipient positive-receipt fixture uses 15,267,552 memory units
and 8,377,552,336 CPU steps; the SDK's explicit 5% allowance fits both ledger limits.
These measurements do not prove an exhaustive worst case. Complete positive-reward
ledger qualification and implementation self-review passed on 2026-09-07; see the
[Phase 1 acceptance ledger](../docs/phase1/completion-checklist.md). Artifact bytes must come directly from
the pinned compiler; there is no UPLC rewriting.

The generic checkpoint/module entrypoints are opaque ABI dispatch boundaries. Each
branch immediately casts to a typed wire variant and checks canonical reconstruction,
including nested records at their owning validation boundary. This avoids duplicating
compiler-generated traversal of all future action variants. Malformed-data rejection
must be tested through emitted UPLC; Java type declarations alone are insufficient.

Phase 1 state spending rejects unconditionally. These development artifacts do not
support administration or recovery and will change hash when Phase 2 is implemented.
The full V1 schema is preserved; no real-value or production use is approved.
