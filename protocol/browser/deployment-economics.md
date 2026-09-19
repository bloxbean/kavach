# Dashboard deployment economics and reference availability

Development profile for [ADR-011](../../adr/adr-011-economics-extensibility-and-wallet-safety.md)
and [ADR-012](../../adr/adr-012-publisher-protected-reference-vault.md).
This supplements browser/policy deployment tooling; it does not change V1 CBOR, signing
preimages, immutable scripts, authority roles or existing account addresses. Qualification
is recorded in [the enhancement report](../../docs/enhancements/adr-011-review.md).

## Capital and quotes

Reference publication MUST calculate minimum lovelace from the full final output and
current ledger parameters. New publisher-owned references are stored at a dedicated Plutus V3 vault address,
controlled by the publishing payment key and bound to the full AccountId and fixed purpose.
The [vault specification](../reference-vault/specification.md) defines its separate wire schema.
Earlier publisher key-address copies remain supported for reference discovery. They are not account assets, not spend-policy collateral and not recoverable
by Kavach credential recovery. Spending a reference frees its capital but removes that copy. Vault reclaim requires an explicit script-spending transaction, the publisher signature and
applicable execution/reference-script fees. The dashboard binds reclamation to one exact UTxO
and returns its full value to the connected publisher, with separately funded fees/collateral.
Ordinary wallet coin selection cannot spend it using only a payment-key witness. This does
not protect against deliberately signing a malicious transaction. Legacy key-address reclaim
still requires a reference-aware wallet/tool; ordinary wallet compatibility is not established
by these synthetic tests.

The account-state output contains the state NFT and exact inline state datum. Its initial
reserve MUST satisfy the complete output minimum. Every successor MUST preserve at least
its consumed reserve and satisfy its new datum's minimum, with sponsor-funded growth.
State closure/burn and state-reserve withdrawal are unsupported. Historic NFT-less deposits
at the state validator remain unspendable; a frontend update cannot refund them.

Setup estimates MUST include all selected-profile references, state reserve, registration
deposits, expected transaction count and an explicitly estimated fee allowance. Collateral
is a separate retained operational balance, exposed to failure loss rather than a routine
successful-transaction charge. Registration and budget-counter withdrawal are not exposed
by this profile. Quotes MUST distinguish exact output capital under captured parameters
from estimated future fees; they are not signed fixed-price offers.

Before the first publication, check available plain sponsor funds, reserved seed and separate
collateral, and reject unaffordable or unsupported funding shapes. Recheck live inputs,
parameters, output minimums and transaction budgets before signing each final body. A
previous estimate cannot authorize extra account debit, changed recipients or a new module.
Final transaction fees and any permanently locked state-reserve increase MUST be visible
before wallet transaction approval. The funding display MUST derive old reserve, final new
reserve and exact sponsor top-up from the same successor output; balancing must not silently
increase it. Detached authority
proof collection does not freeze the unsigned sponsor fee; the final witness signs its body.

## Reference distribution and repair

Keep exact applied Plutus V3 script bytes in durable public storage keyed by their computed
hash. Deployment and hosting metadata are discovery hints; they confer no spending authority.
Authenticate restored state with its trusted locator before using its script bindings.
Reject retained-byte/hash mismatches. Do not reconstruct old deployed scripts using a changed
compiler and assume compatibility. Backup public artifacts separately from private wallet keys.

Discover historical locked references, earlier key-address copies and publisher vaults.
Vault discovery MUST verify the saved version, full account/publisher binding, exact custody
script bytes/hash and holding address; hosted reference bytes remain separately authenticated.
Persist exact vault artifacts alongside hosted scripts. Changing vault versions requires
retaining old artifact/derivation support for reclamation. The NFT policy
reference is genesis-only under this no-burn/no-closure profile; after genesis its absence
MUST NOT be labeled a missing operational reference requiring republication. Retain its
exact artifact bytes for restoration. This classification does not authorize automatic reclaim. Validate live
UTxO identity, correct network, reference presence and expected script identity; choose among
valid copies deterministically. Ledger validation remains authoritative if an indexer lies
or a reference is concurrently consumed. Exclude all reference-bearing UTxOs from automatic
funding and collateral selection and check resolved final inputs. A vault-bearing consumed
input is permitted only when explicitly selected by the dedicated reclamation operation;
its exact outpoint, owner, holding address, script hash and returned value MUST be rechecked. Protect the reserved seed
until genesis. Merely filtering an initially chosen fee input is insufficient.

Missing references MUST NOT cause silent script/module substitution. An independently funded
publisher may republish exact retained bytes into its own account-bound vault after explicit
transaction review and wallet signature; original account authority or original publisher-key possession is not required
to host bytes. The repair changes reference location, not account identity. The SDK/UI must
rebuild affected transactions and collect new witnesses; changes to signed intent semantics
also require new authority proofs. Positive-reward routing and recovery authorization remain
unchanged. Reference repair is not an account-operation authorization bypass.

Availability depends on an accessible correct copy and a funded publisher. Retention does
not guarantee automatic recovery if every copy of public artifacts and provider script
history is lost. A locator locates/authenticates state; it is not an artifact archive or an
account signing backup. Publisher-owned hosting deliberately trades permanent capital lock
for a disclosed availability dependency.

## Supported account extensions

| Profile/change | Address behavior | Constraints |
| --- | --- | --- |
| Credential/role rotation in installed module | Same account asset address | Old authority and exact target proofs, state-version increment |
| Compatible scheme 0/1/2/3 module replacement | Same immutable asset address | Old admin, exact candidate ABI/hash/config, candidate possession; actual deployment qualification required |
| Bounded stateless predicates inside compatible auth module | May preserve asset address | Must fit existing ABI, purpose, configuration/evidence and execution limits; review full recovery semantics |
| Periodic budget on already budget-capable core | Same budget-capable address | Configured counter NFT and atomic usage; spends contend on counter |
| Add budget core to ordinary core | Different asset validator/address | New deployment/migration, not a module-only installation |
| Arbitrary external policy/script/dApp invocation | Unsupported in ordinary profile | New feasibility/ABI/core design and qualification |
| New core/compiler output | Usually new hashes/addresses | Explicit migration under old authorization; never silently substituted |
| CIP-113 custody, staking, new cryptography | Separate qualification/design | No automatic existing-address compatibility promise |

Ordinary Spend supports at most eight account inputs and eight recipients within the full
transaction limits. The dashboard's multi-input sweep uses ordinary Spend. It is distinct
from the SDK's one-input TransferWholeUtxo action for large native maps. An enterprise account
address must be used exactly; alternative stake components or state-address deposits are not
promised recoverable. A maximum-size valid configuration need not permit every all-new-key
rotation: the union of old/new transaction signers and sponsor must fit the existing cap.

Unknown module capabilities MUST fail closed in tooling. A reviewed capability description
is evidence for user selection, not an on-chain allowlist or proof that arbitrary code is safe.
The immutable core retains replay, lifecycle and full value accounting independently of
module decisions. A malicious installed module can nevertheless authorize unwanted spending
or prevent recovery. No plugin, audit checklist or stable address eliminates that trust boundary.
