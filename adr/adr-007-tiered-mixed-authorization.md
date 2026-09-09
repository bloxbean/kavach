# ADR-007: Amount-tiered mixed authorization

Status: Proposed; development candidate under implementation, not production qualified.
Related: [ADR-001](adr-001-kavach-programmable-smart-account-architecture.md),
[ADR-005](adr-005-browser-wallet-authentication-and-demo.md).

Introduce a separate module, profile/scheme 3, combining fixed per-credential COSE and
transaction-witness verification with two spend thresholds. Existing modules, core scripts,
scheme 0 and archived protocol fixtures remain unchanged. Installing this module requires
the old module's administration authorization and candidate possession; never auto-upgrade.

Configuration is constructor 0 with exactly five fields: schemaVersion=1, the existing
eight-field Ed25519 role configuration, strictly increasing COSE credential IDs, an unsigned
lovelace limit, and a small-payment threshold policy. Registered credentials absent from
the COSE list require a transaction witness. All public keys remain unique across modes.
The entire wrapper remains within the core's 1024-byte configuration bound.

The role configuration's spend policy is the strong policy. Small-policy members must be
a subset of strong-policy members. Every sufficient strong-policy subset must also satisfy
the small policy; both retain defensive role independence. Ordinary Spend uses the small
policy only when every recipient asset is ADA and the sum of all recipient allocations plus
the signed maximum account fee is at most the configured limit. Native-asset recipients and
TransferWholeUtxo always use the strong policy. Consolidation follows the same fee bound.
This module alone has no exchange-rate oracle, cumulative counter, recipient allowlist or
session authorization. Repeated small transactions remain possible. The separate new core
profile for optional daily/weekly budgets is specified in
[ADR-008](adr-008-optional-periodic-budget.md).

Operation proof scheme is 3. Its ordered unique signature entries keep the existing ABI:
COSE credentials contain bounded browser evidence, transaction credentials contain empty
bytes and must occur in ledger signatories. A fee sponsor counts only if explicitly selected
as a registered policy credential. No evidence-controlled mode switching or duplicate votes.

Candidate installation, configuration replacement and recovery completion require
possession of every destination credential in its configured mode. This is deliberately
stronger than the prior new-key-only rule and prevents retaining a key from bypassing proof
when its signing mode changes. Possession domains and complete configuration commitments
remain distinct from operation approval. All lifecycle/receipt/NFT/core bindings are retained.
The current candidate is installed through an authorized module replacement; it rejects
genesis invocations. Direct creation and script publication size remain qualification gates.

Qualification requires explicit encoding vectors, compiled positive/adversarial and budget
tests, full DevKit creation/transfer/upgrade validation, SDK and dashboard policy selection
agreement, and clear signer review. Physical phone compatibility is separate evidence.


The [per-key creation increment](adr-009-per-key-account-creation.md) adds a distinct restricted
setup checkpoint followed by explicit activation. It does not add genesis to PolicyModule
or change existing deployments. Per-key methods can therefore be chosen in the default
creation UI without a later manual module-selection step.
