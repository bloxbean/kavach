# ADR-004: Phase 2 immutable state-transition anchor

- Status: Proposed production design; development qualification in progress
- Related: [ADR-001](adr-001-kavach-programmable-smart-account-architecture.md), [ADR-003](adr-003-phase1-immutable-accounting-anchor.md)

Phase 2 distributes immutable enforcement between the checkpoint and the mandatory
consumed-state validator. This extends ADR-003's physical partition without transferring
lifecycle authority into the replaceable authentication module or changing the wire schema.

For every administration or recovery action, the checkpoint requires consumption of the
signed current state reference, authenticated by its complete state NFT and immutable
validator address. It requires the exact state-spending redeemer, including the intent and
reward receipts. The state validator independently authenticates custody, requires the
matching checkpoint invocation, computes the exact permitted successor and checks outputs.
It preserves account identity, core binding, recovery timings, NFT supply and state ADA.
It rejects unauthorized extra script inputs and unrelated token-bearing sponsor outputs.
The ledger must execute both immutable validators successfully.

The current module checks old-role authority and applicable possession proofs. Module
replacement additionally requires a distinct candidate invocation with the identical intent
and receipts, and candidate key possession. Candidate approval cannot replace old-authority
approval. Recovery completion uses the stored target and independent target-domain proofs;
its time, sequence, commitment and successor checks remain in immutable code.

This partition reduces repeated work and reference-script size. Ordered registry/policy/proof
merges and direct bounded output lookup reduce traversal cost while retaining the existing
16-key, eight-member policy and transaction bounds. Duplicate, descending and unknown
identifiers must still reject through compiled scripts. Constructed maximum-profile budget
tests are representative acceptance cases, not an exhaustive maximum-cost proof.

Phase 1's state script rejects all spending; it cannot acquire these transitions in place.
Phase 2 therefore needs a fresh development deployment with new immutable script hashes.
No production migration, CIP-113 compatibility or independent audit is established here.
See the [Phase 2 acceptance ledger](../docs/phase2/completion-checklist.md) for outstanding
real-delay, reward, competing-transaction and review requirements.
