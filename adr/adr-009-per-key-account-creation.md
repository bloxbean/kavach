# ADR-009: Guided account creation with per-key signing

Status: Proposed development candidate. Qualification is recorded in the
[policy ledger](../docs/policy/completion-checklist.md); not production approval.

Related: [ADR-001](adr-001-kavach-programmable-smart-account-architecture.md),
[ADR-005](adr-005-browser-wallet-authentication-and-demo.md),
[ADR-007](adr-007-tiered-mixed-authorization.md).

## Decision

Make per-credential signing the default dashboard creation experience. Users provide 3–8
public keys, defaulting every key to COSE intent verification. Transaction-witness signing
remains an explicit advanced choice per key. Selecting the
Companion device fixes that key to COSE. The existing six authority policies and defensive
separation remain. Methods are part of the canonical signed mixed configuration, never
inferred from evidence or stored only in the browser. The unchanged scheme 3 PolicyModule
is the final authorization module. Existing scheme 1/2 creation APIs remain compatible.

A full lifecycle mixed module with genesis exceeds the qualified 16,384-byte transaction
publication bound. Do not change DevKit limits, rewrite emitted UPLC, weaken initialization
checks, or pretend an intermediate account is ready. Instead use a separately deployed
`MixedSetupModule` with the same mixed configuration and genesis proof domain. It verifies
every credential's possession in its selected method and preserves all BrowserModule genesis
state/NFT/output/custody/receipt checks.

The setup module's immutable parameters, in order, are moduleVersion=1, abiVersion=1,
deploymentDomain, stateValidatorHash, coreCheckpointHash, rewardSink and finalModuleHash.
Derive the final PolicyModule first, then parameterize the setup module with its hash.
The setup module supports registration, genesis and exactly one operation kind: ReplaceModule
to that precommitted final module, with an identical full configuration. The old admin policy
must approve the exact intent; the final candidate separately requires all-key possession.
The immutable state/core validators still execute. Spending, freezing, configuration edits,
recovery and arbitrary module replacements cannot bypass activation.

This is an explicitly reviewed creation sequence, not a silent upgrade. Both genesis and
activation require wallet/phone approval. Ten confirmed steps publish five initial references,
register checkpoints, initialize state, publish/register the final module and activate it.
The sixth reference adds its own deposit to the existing development reference deposits,
apart from account state, registration and transaction fees. Each reference deposit was
later reduced from a flat 80 ADA to the ledger minimum for that script's serialized size,
which lowers the five core references from 400 ADA to about 220 ADA; the arrangement and
its permanence are unchanged. The account's identity,
asset address, keys and methods stay unchanged across activation. Creation is complete only
after the final policy is confirmed. No automatic transaction signing or submission occurs.

## Resume and trust boundary

The local verified deployment registry records the original public reward-sink address for
this setup checkpoint. After confirmed genesis, the backend can restore the NFT state from
its locator, derive the exact final module, and recompute the complete setup script hash.
A mismatch rejects. This record contains no private keys or authority to change the contract's
precommitted destination. The same registry already qualifies module signing profiles.

Resume checks confirmed reference publication and actual stake-registration activity, rather
than treating a successful account-information response as evidence of registration. It skips
completed steps and prepares fresh requests for remaining approvals. A restored setup account
is labeled unfinished and has a Finish account setup action; spend preparation rejects until
activation. This survives backend restart without an in-memory Plan. Pre-genesis reference
publication is not made resumable by this increment. Loss of verified deployment records
still requires their recovery/reverification; a public locator is not a copy of those records.

## Phone boundary

The existing authenticated genesis transport now accepts the five-field mixed configuration,
shows each credential's method, and rejects a request assigning the phone a transaction
witness. Policy activation uses the separately reviewed configuration-possession and old-admin
proof domains. The app still does not support recovery/emergency-operation requests; the
creation UI requires those roles to remain on wallet keys when a Companion device is selected.
Cryptographic validity cannot identify the device behind an arbitrary pasted public key.

Qualification requires compiled positive/adversarial and maximum-registry genesis checks,
precommitted-target/unchanged-config/admin tests, live all-transaction/all-COSE/mixed creation,
restart after each confirmed post-genesis step, final transfers, phone/Java digest fixtures,
and browser verification. Existing immutable sources and deployed PolicyModule are preserved.

The creation form starts with three keys and offers Add signer / Remove signer controls.
Removal requires clearing that key from every policy first; later key IDs are remapped
together with policy membership, preserving signer identities and thresholds. Each policy
is limited to eight members. Existing canonical configuration-size and separation checks
remain authoritative; a registry of eight keys does not relax those checks.

The dashboard currently caps setup at eight signers. The protocol registry allows sixteen,
but a sixteen-COSE-key activation exceeded the companion transport request bound in live testing.

## Guided approval workflow

The dashboard groups setup into Prepare account, Enroll keys and Activate account while
retaining the actual transaction count. Pending approvals are presented individually with
credential ID, public key, proof purpose and device choice. Device hints are UI metadata;
they never determine on-chain authorization. Unknown ownership requires a device choice.
Operation and candidate approvals remain separate and cannot reuse one another's signatures.

An explicit “Sign & submit when complete” action obtains wallet consent for the current
transaction and submits only after the backend reports all required witnesses present.
Partial signatures leave the request pending. Ledger confirmation can automatically prepare
the next setup request, with a persistent opt-out; it never triggers a new wallet signature.
This reduces user interactions without changing transaction count or protocol checks.

## Separate account approval and transaction funding

COSE is the dashboard default for both wallet and Companion keys. Funding is a separate
transaction signature, even when the funding wallet also owns an account key. Existing
accounts retain their signed methods until their current administration policy authorizes
a configuration update. The all-COSE shortcut changes only method selection; public keys,
policy thresholds, memberships and account address are preserved. Candidate possession
proofs remain required by the existing update protocol.

The API reports fee-payer identity separately from transaction-based authority signers and
reports each requested account approval with its method and purpose. These are display
metadata, not new authorization inputs. Missing metadata must not imply funding-only status.
The wizard explains approval, funding and confirmation beside the exact request payload;
it identifies separate administrator and key-possession approvals when a key signs twice.
An independent funded Cardano wallet can pay fees without becoming an account authority.
This does not introduce a hosted sponsor service or change any contract or wire schema.
