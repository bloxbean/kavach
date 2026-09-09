# Kavach browser demo

A local development wallet UI backed by the actual Kavach SDK and Yaci DevKit. The backend
builds and evaluates transactions; browser wallets hold the signing keys. The interface uses
React, TypeScript and Cardano Foundation Connect with Wallet.

## Run

Use the repository's pinned Java 25 / JuLC toolchain and a running Yaci DevKit with network
magic **42**, Blockfrost-compatible API at `http://localhost:8080/api/v1/`, and faucet on port
10000. Do not reset the ledger while the separate delayed-recovery worker is running.

From the repository root, start the Java API:

```sh
./gradlew :demo:backend:run
```

In another terminal:

```sh
cd demo/web
npm ci
npm run dev
```

Open **http://127.0.0.1:6670/**. The API binds to loopback port 8095; Vite proxies `/api`.
Do not expose either development server publicly. A public deployment requires a separate
operational design, independently verified deployment manifests and wallet qualification.

## Connect and create

1. Configure a **disposable** CIP-30 wallet for this exact local DevKit. Choosing a generic
   “testnet” is insufficient: CIP-30 network ID 0 also covers preview and preprod. The backend
   verifies genesis magic 42 and resolves funding from that ledger. CF Connect is explicitly restricted to `NetworkType.TESTNET`; its default is Mainnet. It does not change
   the wallet's chain/provider configuration.
2. Prepare three independent payment public keys: everyday key 0, guardian key 1, defensive
   backup key 2. In Connect wallet, **Verify & show public key** verifies a domain-separated
   `signData` response before exporting the public key. A wallet without `signData` needs its
   own trusted public-key export. Never enter a private key or seed phrase.
3. Fund the connected wallet's **change address** on DevKit. The setup needs separate outputs
   for the identity seed, fees and collateral. The automated test uses faucet outputs of
   1,000 + 20 + 20 test ADA. These amounts are test funding, not transfer fees.
4. Create an account and choose a device, signing method and public key for each signer.
   Wallet keys support transaction signing or COSE; Companion keys require COSE. The
   initial role profile is editable; invalid defensive-role overlaps and unsafe admin
   thresholds reject. All registered keys prove possession at creation. Use Add signer for up to 8 keys; each policy allows up to eight members. Remove signer requires clearing its policy assignments first and keeps at least three keys.
5. Review and approve each setup transaction, including final policy activation. The default
   per-key flow publishes six reference scripts with **80 ADA each permanently locked**, creates a **12 ADA state output**, and
   pays stake-registration deposits and network fees. Reference publication is deliberately
   conservative and is not an optimized production onboarding cost. Continue only after
   ledger confirmation. Save the public account locator before proceeding.
6. Connect the appropriate authority wallet(s) and use the shared request ID to collect
   approvals. All signers must access the same running backend. Transaction witnesses apply
   to one final body; any rebuilt request requires fresh signatures.

Submitting the final genesis request saves a pending public locator before contacting the
backend. The dashboard checks the ledger every 15 seconds and automatically saves and opens
the account once authenticated restoration succeeds, including after a reload or backend
restart. A confirmed mixed account awaiting activation is labeled unfinished and offers
**Finish account setup**. It cannot spend until activation. Pre-genesis setup remains a request,
with a **Resume account setup** shortcut;
reference publication alone does not create an account.

Opening or restoring an account also saves its public locator in this browser profile for this
exact site origin. The dashboard reopens that account after a page reload and refreshes its
ledger state and balance every 15 seconds while visible, and when you return to the page.
**Refresh account** also fetches the latest balance. A failed refresh retains the locator
and displays an error. **Already have an account? → Find my account** can restore the saved
locator manually. **Your workspace** lists accounts created or restored in this browser,
with the last selected account reopened after refresh. Click an entry to switch accounts;
use **Locator** to copy its backup or **Forget** to remove only that browser entry.
Forgetting the selected account selects another saved entry, if available. It never changes
on-chain state or funds. Earlier locally named accounts are recovered into the list only
after authenticated ledger restoration; unconfirmed genesis candidates are excluded. Forgotten entries
are excluded from this automatic migration but may be explicitly restored again.
This is a local account list, not wallet-wide discovery;
another browser profile or origin needs the exported locator. Keep a separate locator backup
using **Recovery → Copy locator**.
In particular, `localhost` and `127.0.0.1` use different storage, as do ports 5173 and 6670.
Restore once on the new origin after a port/hostname change; the account remains on-chain.
Local registry regression tests run with `npm test` in `demo/web` (Node 22.12+).

The approval dialog labels ten steps for default per-key creation (legacy API creation
still has seven). Initial reference publication and checkpoint registration need the fee
wallet. Genesis requires every enrolled key in its own method. Final publication and
registration follow, then activation requires old-admin approval and every destination key's
possession. The final module and configuration are precommitted; activation cannot substitute
other keys or rules. After genesis, resume can skip confirmed publication/registration even
following a backend restart. Every transaction still needs its fee payer's signature.

Approval counts and the transaction signer checklist show what remains. Once all signatures
are collected, Submit transaction is the next action. Switch active wallet accounts as needed.
See [ADR-009](../adr/adr-009-per-key-account-creation.md) for the restricted setup checkpoint.

## Available flows

- Create and restore an account from its locator; inspect balance, assets, mode and six roles.
- Receive ADA/native assets; send ADA plus a native asset, transfer all ordinary account
  assets, or consolidate them. Fees come from the connected wallet, not the account.
- Replace all authority keys or edit their policies under existing administration approval.
  Choose a sufficient subset of current policy members before creating a request.
- Switch between the two browser authentication modules, proving candidate-key possession.
- Freeze, unfreeze, initiate recovery, cancel recovery and complete an eligible recovery.
  Cancellation leaves the account frozen. Completion uses the target committed at initiation.
- Review canonical SDK-rendered requests, collect COSE proofs or transaction witnesses, and
  distinguish submission from ledger confirmation.

## Scope and limitations

This is a development preview, not an audited wallet or a general dApp connector. The UI
starts with a three-key reference setup, allows up to 8 keys, and supports up to 16 ordinary account inputs per transfer,
with one recipient. The protocol/SDK expose broader shapes that need separate UI work and
budget qualification. Recipient support is currently key-payment enterprise/base addresses.
CIP-113 transfers, staking, governance and session keys are not implemented here.

COSE support is the exact [bounded profile](../protocol/browser/specification.md), including
payment-key address binding, unhashed 32-byte payloads, and two bounded protected-header forms (with or without an address-valued `kid`). Other
valid CIP-8 variants intentionally fail. No particular wallet or hardware-wallet version
has yet been qualified by actual extension signatures. Automated signers exercise the same
response shape and real ledger, but do not establish extension compatibility.

The backend and frontend form a trusted review client. The UI displays canonical SDK
rendering, but does not independently decode/re-render the complete CBOR transaction in a
separate trust domain. A wallet showing only a digest cannot detect a compromised client's
misleading human description. Inspect wallet transaction details and independently compare
request digests for meaningful signing assurance.

Existing raw-Ed25519 accounts are not automatically enabled for browser signing; an explicit, qualified module upgrade is required. This demo restores known browser-module deployments.

Only zero-reward checkpoint flows are exposed by this UI slice. Positive checkpoint/module
balances require the SDK's explicit, disjoint reward-receipt allocation flow; do not reset or
change checkpoints to work around such a balance. The contracts retain their receipt checks.

Approval plans live in backend memory (maximum 200), and mutations expire within five
minutes. Close and prepare a fresh request after expiry/state conflicts. Keep the backend
running while collecting approvals or completing setup. A restart loses pending plans, not
confirmed on-chain accounts. `demo/backend/data/profiles/` contains public trusted module
mode records and must be backed up with account locators for restoration by this demo.
Never infer a signing mode from an untrusted account datum or wallet name.

The real minimum recovery delay remains **24 hours**. The UI does not shorten it. Short
integration tests cover initiation/cancellation; completed recovery is tested in compiled
contexts. Actual-delay completion and real-wallet captures remain separate acceptance gates.

## Verify

```sh
./gradlew check
./gradlew transactionWitnessIntegrationTest coseIntegrationTest
./gradlew :demo:backend:demoIntegrationTest
cd demo/web
npm run build
```

The demo integration test creates fresh disposable accounts in both modes, submits every
transaction to DevKit, checks token accounting and whole-account transfers, rotates keys,
replaces the module in both directions, and exercises freeze/unfreeze and recovery
initiation/cancellation. It also rejects premature submission, unexpected witness sections
and signatures over changed transaction bodies. It never logs signing keys.

See the [acceptance ledger](../docs/browser/completion-checklist.md) for the distinction
between implementation, automated ledger evidence and real-browser qualification.

Yano/CCL key export: candidate 2 accepts CCL 0.8.0-pre5's optional `kid` headers.
New accounts use the updated browser module. Existing modules require an authorized
module replacement before accepting this COSE variant; restarting the demo does not
change deployed scripts. Library-generated signatures are tested; actual extension
end-to-end compatibility remains separately qualified.

After wallet approval, the verified public key stays visible in the connect dialog.
Use **Copy public key** after returning to the page, or select the field and copy manually
if clipboard access is unavailable. Only a verified public key is displayed; this export
does not grant signing authority, though sharing it can link accounts.

## iPhone companion (COSE development candidate)

Use a new account with **Intent signature · CIP-8 / COSE**. In Create account, open
**Set up your iPhone companion** and scan the dashboard pairing QR with the updated
Yano Companion app. Compare the entire fingerprint on both devices. The local backend
uses a different request identity from the earlier companion CLI test by default.

Paste the phone's exported public key as one of the three authorities. Keep independent
Yano/backup keys for the other roles and review all six policies. Steps 1–6 use the
original Yano fee wallet. At step 7, open **Approve with Yano Companion · iPhone**, select
the matching phone credential and show its request QR. Review keys/policies/account on
the phone, approve with device authentication, then paste its approval JSON into the
same dashboard panel and click **Verify and add phone approval**. Collect other COSE
proofs through Yano, then return to the fee wallet for the final transaction signature.

The same panel supports a single ADA recipient in ordinary Spend. It does not support
native assets, consolidation, lifecycle changes, script recipients, or transaction-witness
mode. Unsupported requests fail closed on the phone. Adding a phone public key is not
sufficient until the genesis possession proof is collected. Existing accounts do not
change automatically. See [ADR-006](../adr/adr-006-offline-iphone-companion.md).

The backend's ignored `demo/backend/data/companion-request-key.bin` is a 0600 request
identity, not an account spending key. Preserve it to retain pairing across restarts;
if it is lost, compare a fresh fingerprint and pair again. Pending plans remain in
memory only. QR exports expire within five minutes; refresh an enrollment QR and return
its saved Activity approval if necessary. Expired Spend intents need a fresh plan.

For Yano authorities at nonzero HD address indexes, select the pending key under
**Wallet authority** before **Sign intent with wallet**. Keep the owning HD account
selected in Yano. Updated Yano searches known paths and receive/change indexes 0–29,
with explicit consent to extend to 0–49, then shows the matched path before COSE
signing. Repeat for each pending wallet key. Address-list omission is not treated as
proof that the wallet cannot sign; backend key/digest verification remains mandatory.

### Return a phone approval by camera

After approving on the iPhone, leave its **Approval ready** QR visible. In Kavach's
companion panel choose **Scan phone approval QR**, allow camera access, and hold the
phone's screen in front of the computer camera. A decoded matching approval is sent
through the same backend verification as pasted JSON and added automatically. It
does not submit the transaction. Complete any remaining signatures and submit normally.

The camera starts only when requested, captures no audio, and processes frames locally
with pinned `jsqr` 1.4.0. It stops after a QR read, on cancellation, panel closure,
leaving the tab, or a 90-second timeout. Closing while a permission prompt is pending
also releases any subsequently granted stream. Wrong-key/request, malformed or oversized
payloads reject; the backend still verifies request ID, expiry and cryptographic proof.
Paste remains available if a camera is missing, busy or denied. The existing iPhone
app already displays the supported approval QR; no phone update is required.

### Paying the same address that sponsors fees

For a transfer whose recipient is the fee-paying address, the demo explicitly selects
an additional ADA-only sponsor UTxO of at least 5 ADA and retains a separate sponsor
change output. Fee balancing cannot reduce the signed recipient or account-change
allocation. The final candidate is re-evaluated and fee/collateral accounting is iterated
to stability before wallet transaction signatures are requested. If the sponsor lacks
a suitable UTxO or at least 2 ADA would not remain in its change, preparation fails
with an actionable message. Split/fund the fee wallet accordingly before retrying.

## Amount tiers, mixed signatures and optional budgets

The dashboard supports separately versioned mixed authorization modules and an optional
shared daily/weekly ADA counter on a new immutable core profile. See the
[step-by-step policy testing guide](../docs/policy/testing.md) and
[qualification ledger](../docs/policy/completion-checklist.md). Existing accounts can install
mixed signing but require a new account/address for budget support. Counter deposits remain
locked in this development candidate.

The dashboard currently caps setup at eight signers. The protocol registry allows sixteen,
but a sixteen-COSE-key activation exceeded the companion transport request bound in live testing.

Account creation defaults to COSE intent approval for every key. Transaction-witness
methods remain under Advanced signing method. Existing accounts can select **Use COSE for
all account keys** in their key/policy update form; the change needs the current admin
approvals and confirmation. The approval wizard separates account approvals from fee-payer
transaction signing and explains both beside the request details. An independent funded
wallet may pay fees; this demo does not operate a hosted sponsorship service.
