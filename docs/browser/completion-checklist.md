# Browser authentication and demo acceptance

Status: **development implementation available; qualification incomplete**. Preserve raw
Phase 2 evidence and its separate actual-delay worker. No production or hardware-wallet
approval follows from these results.

| Requirement | Evidence / status |
| --- | --- |
| Explicit transaction-witness / COSE modes; no fallback | Implemented, distinct applied script identities and scheme IDs |
| Genesis possession and six lifecycle roles | Compiled positive/negative cases; missing genesis key/witness, wrong proof domain and wrong/insufficient lifecycle authorities reject |
| Strict COSE response adapter | 12 cases, including pinned CCL ordinary/extended-key signing, optional matching kid, both networks and supported address types, truncations, duplicate fields, payload/key/network/signature and variant tampering |
| Full immutable composition | Compiled checks and three short DevKit workflows per mode pass; immutable sources retained |
| Key rotation and candidate module possession | Full live all-new-key rotation and same-mode replacement pass in each mode; demo additionally switches 1→2 and 2→1 |
| Full-registry transfer budget | 16 registry keys / eight approvals / one input fits with 5% headroom: mode 1 memory 5,200,673, CPU 2,565,793,068; candidate-2 mode 2 memory 5,249,022, CPU 3,006,534,197 (CCL kid proofs) |
| All maximum lifecycle/transaction shapes | **Pending**; the preceding measurement is not a full worst-case proof. The 16-required-signer bound excludes some simultaneous old/new-key combinations |
| Local verification | `check`: 497 root tests plus one backend enrollment test, zero failures/skips; JuLC artifact provenance, protocol baseline and Javadocs pass |
| Demo Java API | Both initial modes pass actual ledger creation, custom policies, selected threshold subset, ADA/token transfer, consolidation, full transfer, key/module replacement, freeze/unfreeze, start/cancel recovery |
| Witness safety | Demo tests reject premature submission, unexpected witness sections and valid signatures over a changed body before accepting correct witnesses |
| Network / setup safety | Read-only DevKit Shelley genesis magic 42 and one-second slot/indexer agreement checked; reserved identity seed excluded and final setup body checked |
| React / CF Connect UI | Built; wallet discovery shows installed Lace/Eternl. Desktop and 390px mobile layouts reviewed and corrected; no horizontal overflow observed; an archived disposable account restored through the UI with matching ledger roles/version |
| Actual wallet/version captures | **Pending**. No actual extension signing or hardware compatibility claim |
| Real-delay browser recovery completion | **Pending**; compiled completion passes, short ledger flows cover start/cancel only. No shortened delay |
| Positive-reward UI routing | **Pending**; this demo slice handles zero-reward checkpoints; receipt-aware contracts/SDK remain intact |
| Independent frontend CBOR review | **Pending**; canonical review is rendered by the trusted Java SDK, not independently decoded by the browser |
| Full adversarial candidate/new-key matrix, model testing and audit | **Pending**; retain the pre-production gate |

Reproducible commands and practical setup constraints are in the [demo guide](../../dashboard-app/README.md).
Public transaction IDs, actual paid fees and transaction sizes, initial browser workflow reports and focused compiled/parser test
reports are archived under [evidence](evidence/). These are disposable DevKit results, not
mainnet deployments. [ADR-005](../../adr/adr-005-browser-wallet-authentication-and-demo.md)
and the [bounded profile](../../protocol/browser/specification.md) describe the trust boundaries.

The latest custom-policy demo transfer samples paid approximately **1.18–1.23 ADA**.
Those include token-bearing/multi-input and whole-account cases and are not an apples-to-apples
replacement for the earlier plain-ADA fee benchmark. Creation/reference deposits are separately
disclosed in the setup guide.

Candidate 2 fixes the optional address-valued `kid` encoding used by CCL's CIP-30 signer.
The browser module hash changes; existing installed modules need an authorized replacement.
The public-key export API accepts the new form after the backend update. Yano's inspected
checkout delegates to CCL and pins pre4; regression tests use Kavach's required pre5.
This is library-format evidence, not a captured extension session. Candidate-2 reports are
archived separately under [candidate-2 evidence](evidence/candidate-2/), preserving the
original candidate's script and fee evidence.

Candidate-2 live regression: `coseIntegrationTest` passed all three workflows and
`:dashboard-app:backend:dashboardIntegrationTest` passed both modes (42 confirmed demo transactions).
COSE proofs in these runs come from the pinned CCL signer rather than handcrafted wrappers.
The original no-kid form remains covered by compiled positive tests and SDK parser tests.
Full `check` passes; immutable contract source hashes match the previous browser evidence.

### Offline companion transport increment (2026-09-08)

Implemented dashboard pairing and bounded request/approval exchange for mode-2 genesis
and ordinary single-recipient ADA Spend; see [ADR-006](../../adr/adr-006-offline-iphone-companion.md).
No contract/profile identity changes or heterogeneous signing-module claims.

- `dashboardWebBuild`, `:dashboard-app:backend:check` and frontend local-registry tests pass.
- Backend transport tests cover persistent identity, exact signed envelope bytes,
  compact QR round-trip and rejection of mismatched, expired and forged approvals.
- `:dashboard-app:backend:dashboardIntegrationTest` passes both modes, 42 confirmed transactions.
  Supported COSE operations use the new export/import endpoints with synthetic CCL
  signer responses, including wrong-ticket and duplicate-import rejection. This is
  ledger evidence for the transport path, **not physical iPhone ledger evidence**.
- Companion Swift tests pass (11), including genesis golden-vector equality and
  authenticated enrollment. Its generated COSE enrollment signature is accepted by
  the existing Java adapter. The updated native app builds and installs on the iPhone.
- Live dashboard pairing QR was visually checked in Chrome. A real-phone new-account
  genesis and subsequent payment remain to be exercised by the owner.

Public regression logs: [DevKit](evidence/companion/demo-devkit.log),
[build/check](evidence/companion/check.log). Raw scheme-0 and sealed phase evidence
are preserved. No DevKit reset or recovery-delay worker restart was performed.

### Approval response QR scanning (2026-09-08)

Added an explicit **Scan phone approval QR** action to the companion panel. The existing
plain-JSON response QR is decoded locally with pinned `jsqr` 1.4.0; matching framed data
uses the same backend signature/request verification as paste. Successful scanning adds
an approval only, never submits. Camera frames are not uploaded and audio is not requested.

`dashboardWebBuild` and all nine frontend tests pass. Tests decode a generated phone-sized
approval QR and check exact JSON preservation, malformed/wrong-key/request rejection,
and camera cleanup including late permission resolution. Physical camera scanning still
requires a hands-on check; no camera compatibility claim follows from the decoder test.

The owner has separately reported successful real-phone account creation and spending
using JSON import. The dashboard showed both requests confirmed for account policy
`be8ebeee6dc9fbc4035269a2337645e43cc1736e6d4c6c486fc4d34e` during this increment.
This is evidence for that local DevKit workflow, not general device or production qualification.

### Recipient also paying fees (2026-09-08)

Fixed the demo builder deducting fees from a signed recipient allocation when recipient
and sponsor addresses coincide. Separate sponsor funding and protected output balancing
preserve signed amounts; contracts and proof encodings are unchanged. Backend check and
live demo integration suite pass, with 44 confirmed transactions across both modes,
including exact 22 ADA sponsor-address payments. See the
[diagnosis and evidence](evidence/companion/sponsor-recipient-rejection.md).
