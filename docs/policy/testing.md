# Test amount tiers, mixed signing and periodic budgets

Local development build: [dashboard](http://localhost:6670/), Yaci DevKit network magic 42.
Use disposable ADA. This candidate is not independently audited or production qualified.

## Choose an account

New dashboard accounts use per-key mixed signing by default. An existing account can install
**Mixed signatures + amount tiers** using its current admin
approval. Its address stays the same. For the optional shared budget, create a **new account**
and check **Support optional daily/weekly budgets** before creation. This chooses a new
immutable core/address profile; old accounts are not migrated. Choose each key’s device and signing method during creation. Finish all setup steps,
including policy activation, before configuring an optional budget.

For a convenient three-key test, use:

| Key ID | Example holder | Method in the mixed module |
| --- | --- | --- |
| 0 | Yano everyday payment key | Transaction witness |
| 1 | Separate Yano guardian payment key | COSE |
| 2 | Separate defensive/admin backup key | Transaction witness |

A transaction-witness credential must belong to a Cardano wallet that can sign the final
transaction. A phone key must be marked COSE. Yano can supply both methods; the companion
supplies COSE only. For meaningful separation use independently controlled keys; address
indexes of one mnemonic share a single underlying compromise/recovery boundary.

## Install the policy

On a newly created account, use **Security → Rotate keys** to edit amount tiers, or
**Configure budget** to install the optional budget module. For older single-method accounts,
use **Replace module** to install mixed approval. Select the applicable mixed signing method. Set these role policies:

| Role | Signatures required | Key IDs |
| --- | --- | --- |
| Spend (strong tier) | 2 | 0, 1 |
| Admin | 2 | 0, 2 |
| Freeze | 1 | 1 |
| Unfreeze | 1 | 2 |
| Recovery | 1 | 1 |
| Cancel recovery | 1 | 2 |

Set the small-payment threshold to **10 ADA**, small-payment key IDs to **0**, signatures
required to **1**, and COSE key IDs to **1**. For budgets, enable the checkbox, choose
**Daily**, and set **30 ADA**. Follow publication and approval steps until confirmed.
Counter initialization locks an additional **3 ADA** development deposit; disabling a
budget does not refund that deposit.

The existing admin approves replacement. Every destination key also proves possession in
its new method, even when the public keys are unchanged. For a phone credential use
**Approve with Yano Companion · iPhone**: select the requested proof, export/scan the request,
review both configurations, approve on the phone, then scan its approval QR with the Mac.
Repeat for each requested purpose. The phone needs the updated policy-review app build.
For a phone variant, select Yano Companion for **key 0** during creation (COSE is automatic), use that phone key as **key 0** and Yano keys as 1 and 2, and set COSE key IDs
to **0** instead of 1. Small payments then need the phone approval and the fee payer; strong
payments additionally need key 1's transaction signature. Keep Freeze and Recovery on the
Yano key 1: the current phone app does not support those operations. Its policy-change review
supports Admin and destination possession, so this variant can still install and edit policies.

Finish by collecting the requested Cardano transaction signatures, including the fee payer,
and submit. A COSE approval alone does not sign the fee-paying transaction.

## Exercise the behavior

Fund the confirmed account with at least 40 ADA and refresh its balance.

1. Send **5 ADA**: small approval requires key 0 plus the fee-paying wallet, if different.
2. Send **20 ADA**: strong approval requires key 0's transaction signature and key 1's COSE
   approval. Security should show **25 / 30 ADA** spent and **5 ADA** remaining.
3. Try sending **6 ADA**: preparation rejects the cumulative overrun, even with both signers.
4. Send **5 ADA**: usage reaches 30 ADA; further positive ADA debit is blocked this period.
5. In **Security → Configure budget**, raise the limit or select **Weekly**, collect old-admin
   and destination possession proofs, then submit. Raising the limit retains usage. Changing
   the period explicitly starts a fresh counter on the next spend.
6. Disable the checkbox through the same approved change. Spending is no longer serialized
   by this counter. Re-enabling the same period keeps its recorded usage in the current window;
   payments made while disabled are not retrospectively counted.
7. Refresh the page. Restore the saved account from **Your workspace** if necessary; usage
   comes from the on-chain counter, not browser storage.

The 10 ADA value selects approvals; it is not a hard per-transaction cap. Tier selection
includes signed maximum account fees. Native-token and whole-UTxO transfers always require
strong approval. The cumulative budget measures actual ADA debit including account-paid
fees; it does not assign a price to native tokens. The demo normally sponsors fees separately.

Days reset at midnight UTC; weeks reset Monday midnight UTC. The ledger validity interval
must fit within one window. There is no background reset job. Enabled budgets use a shared
UTxO: two prepared payments can conflict even with different account inputs. After the first
confirms, prepare a fresh request for the second and collect its required signatures.

## Automated checks and evidence

```sh
./gradlew check dashboardWebBuild :dashboard-app:backend:dashboardIntegrationTest
```

For the periodic lifecycle alone:

```sh
./gradlew :dashboard-app:backend:dashboardIntegrationTest --tests '*periodicBudgetFullFlow'
```

This test creates a disposable account and counter, installs mixed approval, exercises both
tiers, changes limits, disables/re-enables, changes daily to weekly, and checks conflicting
payments plus a successful rebuild. It consumes devnet deposits and leaves public evidence
under `build/policy/companion`. Synthetic CIP-30/COSE responses with actual ledger acceptance
are not physical-wallet compatibility evidence. Compiled contract tests cover real boundary
conditions using constructed intervals; no elapsed day/week observation is claimed.


## Default creation screen

The creation form has one card per key. Choose a device, signing method and public key in
each card. Wallet keys can use transaction signatures or COSE. Companion keys use COSE.
Keep the Guardian and Defensive backup roles on wallet keys until the phone supports the
corresponding recovery/emergency operations. The summary shows all selected methods.

Click Review request and complete the ten-step creation sequence. The selected mixed
policy is activated inside this flow; there is no separate manual module configuration
required afterward. Reference deposits total 480 ADA in this development flow. Spending
is blocked until final activation. If the backend restarts after genesis, restore the account
and choose Finish account setup; confirmed final publication/registration steps are skipped.

Creation initially uses the same Spend policy for all amounts. Configure distinct small/strong
approval tiers later in Security. The optional budget checkbox selects core capability only;
it does not activate a limit or initialize a counter during creation.

### Adding and removing creation signers

Create account starts with three signers. Add signer adds a public-key/device/method card,
up to eight in this dashboard. Assign additional keys in the authority policies below.
Remove signer is available for unassigned keys while more than three remain. Clear that
key's policy assignments first; removal preserves every other signer's memberships and
never changes approval thresholds. Each policy accepts at most eight members.

The protocol's sixteen-key registry is broader than this flow: a sixteen-COSE-key live
creation reached genesis but exceeded the companion's 4096-byte request bound during
activation. The dashboard deliberately caps setup at eight without changing transport
or contract bounds. Do not use that disposable unfinished test account as a ready account.

### Approval wizard

The next card identifies the key, public key and purpose. Choose the owning device when no
hint is available. iPhone requests export automatically; accepting one approval selects the
next pending request, including a separate candidate proof from the same key. Wallet intent
buttons explicitly select the displayed credential, enabling address-index discovery.

After proofs, review pending transaction signers and use “Sign & submit when complete”.
Only a fully witnessed response is submitted. Confirmation advances to preparation of the
next request unless the user disables that preference. Signing always needs a user action.
The existing running backend can serve this UI; optional `signerKeys` metadata adds key-ID
labels to transaction hashes after a backend reload. Do not restart a backend with pending
approvals solely to load this display metadata.

### COSE defaults and independent funding

New creation keys default to COSE, including keys added with Add signer. Advanced signing
method still permits transaction witnesses. For an existing mixed account, open Security →
Rotate keys (Update keys and policies), keep its public keys and policies, and choose
Use COSE for all account keys. Review the unchanged thresholds and memberships before
preparing. Current administrators approve the change; the new configuration requires key
possession proofs. Only confirmation activates the new methods at the same address.

The approval page explains what each signature does alongside the request details. After
COSE approvals, the separate Fee payer signing step shows the funding address. Connecting
a different sponsor requires preparing a fresh request; a funding signature does not replace
an account approval. Existing transaction-based accounts are explicitly labeled with their
remaining authority roles.

Run `./gradlew :dashboard-app:backend:dashboardIntegrationTest --tests '*allCoseMigrationWithIndependentFeePayer'`
for mixed creation, same-address all-COSE update and 15/35 ADA transfers with a 30 ADA tier.
The sponsor is outside the authority registry. Assertions require one/two COSE proofs,
reject premature submission, and verify independent funding metadata and ledger balances.
This uses synthetic signatures against actual DevKit ledger validation.
