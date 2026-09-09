# Policy development acceptance ledger

Status: implemented and qualified as a local DevKit development candidate (2026-09-08).
Not production approval or an independent security audit.

- [x] Separately versioned amount-tiered mixed module and budget-aware mixed module.
- [x] Existing immutable scripts and archived wire fixtures preserved.
- [x] Fixed credential methods, low/strong tier selection and old-admin upgrade boundaries.
- [x] Optional on-chain counter, fixed daily/weekly windows and retained disabled counter.
- [x] Backend and dashboard configuration, counter initialization, usage display and transfers.
- [x] Eight independent CBOR/digest vectors round-trip through JVM codecs.
- [x] Compiled mixed-proof rejection and daily/weekly counter/window checks.
- [x] Live mixed-module publication, installation, two amount tiers and policy replacement.
- [x] Complete live periodic-budget enable/change/disable/re-enable workflow.
- [x] Additional adversarial, whole/native-asset and contention coverage.
- [x] Full configured checks, documentation review and running-dashboard verification.

Live periodic tests passed on separate disposable DevKit accounts. They do not reset
DevKit or alter its timing. No existing user account is migrated. Day/week boundary tests
use compiled ledger contexts; they do not claim elapsed daily/weekly live observation.

See [the specification](../../protocol/policy/specification.md) for the exact profile and
[ADR-008](../../adr/adr-008-optional-periodic-budget.md) for architectural constraints.

## Evidence

- [Full checks, web build and all four live API profiles](evidence/check-and-devkit.log):
  538 root JVM/compiled-contract tests, Javadoc, pinned toolchain acceptance, historical
  baseline checks; transaction/COSE legacy flows plus new mixed and periodic flows.
- [Companion-integrated periodic lifecycle](evidence/companion-devkit.log): unit transport
  checks plus live install, change, disable/re-enable, daily/weekly change and contention.
  Synthetic COSE evidence uses authenticated phone-format exports/imports. The second
  conflicting transaction rejects; rebuilding against the current counter succeeds.
- [Swift tests](evidence/swift-tests.log): 14 tests; independent phone parsing matches Java
  operation/candidate/possession digests from six public live request fixtures. Tests reject
  wrong purpose, state, signing method, malformed periods, expiry and replay; a fresh
  CryptoKit key signs and locally verifies the policy COSE response.
- Compiled candidate coverage includes 10 mixed-module, 22 budget/core/accounting and
  8 counter-NFT tests. Maximum combined execution uses 16 registered keys, 8 signers and
  8 account asset inputs plus the counter. Native and whole transfers receive strong
  authorization and cumulative debit enforcement. Every asset invocation is included
  in the combined execution budget. These contexts are not live token/whole transactions.
- Running API on port 8095 advertises all four profiles through dashboard port 6670.
  A confirmed disposable account restores after backend restart with 24 / 30 ADA weekly
  usage and 6 ADA remaining. Browser Security and Configure budget show the same state,
  methods, thresholds, period and locked-deposit disclosure.

## Remaining qualification boundaries

- New physical-phone policy review/Face ID and live wallet signing require the owner's
  interactive test. Swift/Java and ledger tests do not replace that evidence.
- No real elapsed day/week boundary, production network, migration, audit, or counter-deposit
  refund qualification is claimed. The old recovery/reward qualification boundaries remain.
- The companion exports only bounded ADA Spend, genesis, configuration replacement and
  module replacement. Recovery requests and ordinary wallet transactions are unsupported
  on the phone; supported browser wallets can supply the required COSE evidence.

Use the [testing guide](testing.md) for the exact UI flow and a valid three-key example.

The final UI copy/transport pass also passed [delivery checks](evidence/delivery-check.log)
and [all nine browser registry/approval parser tests](evidence/web-tests.log). The updated
SwiftUI app builds for iOS Simulator. The physical-device build and installation completed in the per-key creation increment below;
no new physical-phone policy signing is claimed.


## Per-key creation increment — 2026-09-08

- [x] Per-key device/method cards replace the global creation dropdown; selecting Companion
  fixes its method to COSE. Wallet keys retain either option. Three-key roles remain explicit.
- [x] Separately compiled MixedSetupModule preserves the complete genesis checks and requires
  all keys' configured possession. It only permits old-admin-approved activation of its
  precommitted final PolicyModule with the identical configuration. Spend attempts reject.
- [x] Fifteen compiled setup tests, including maximum registry, missing/wrong proofs,
  wrong NFT/custody, wrong final target, changed configuration and missing old-admin authority.
- [x] Restart-safe confirmed setup recovery checks public deployment records and confirmed
  reference/certificate history; account API balance presence is not registration evidence.
- [x] Phone genesis parser independently validates mixed methods; two public Java genesis
  fixtures match its computed proof domains. All 15 Swift tests pass.
- [x] Updated app builds, installs on the existing iPhone bundle, and preserves the installation
  rather than uninstalling it. Actual user review/biometric mixed creation remains manual evidence.

See [ADR-009](../../adr/adr-009-per-key-account-creation.md) for the initialization/activation
boundary and [the testing guide](testing.md) for the default UI flow. Ten-step mixed setup
costs six reference deposits (480 ADA), versus five for legacy API creation. Before genesis,
existing pending-plan persistence limitations still apply. Confirmed unfinished setup can
resume, but lost deployment registry records must be recovered or independently reverified.
The registration-history query fails closed beyond 10,000 indexed registrations in this demo.


Per-key creation qualification passed:

- [All three live creation profiles](evidence/per-key-creation-devkit.log): all transaction,
  mixed, and all COSE keys; 10 confirmed setup steps each, deliberate service replacement
  after steps 7, 8 and 9, no duplicate publication/registration, then two confirmed payments.
- [Final configured checks and frontend build](evidence/per-key-creation-check.log).
- [Fifteen phone tests](evidence/per-key-phone-tests.log), including canonical legacy and
  mixed genesis and policy-change proof domains.
- Browser review verified separate signer cards, per-key methods, automatic COSE for Companion,
  the creation summary, and aligned budget checkbox. iPhone installation was confirmed;
  programmatic launch was blocked only because the phone was locked.

### Dynamic creation signers (2026-09-08)

- Creation supports 3–8 keys, with add/remove controls and per-key methods. Assigned keys
  cannot be removed; membership IDs are remapped without changing signer identities or thresholds.
- Frontend browser checks verified add/remove and bounds; all 11 frontend tests passed,
  including removal remapping and rejection. `check dashboardWebBuild` passed.
- Live eight-key all-COSE creation completed all ten steps, resumed across backend recreation,
  activated, and confirmed two transfers. Invalid registry counts reject before funding.
  See [live evidence](evidence/dynamic-signers-devkit.log) and [checks](evidence/dynamic-signers-check.log).
- Sixteen-key setup exceeded the existing companion request bound at activation; eight-key
  QR requests encoded with the dashboard's existing low-correction setting. No transport
  or contract bounds were expanded. Larger QR payloads retain the existing import fallback.
  Physical-phone scanning of eight-key requests was not tested in this increment.

### Guided approval UI (2026-09-09)

- Next-key cards label the public key, proof purpose and owning-device selection. Device
  preferences are local display hints, never authorization. Accepted proofs move to the
  next card; phone QR export is automatic for the selected phone request.
- Explicit combined sign/submit uses the backend's fully witnessed status. Confirmation
  can prepare the next setup request once, with a persistent user opt-out. Transaction and
  signature counts are unchanged.
- `check dashboardWebBuild` and 14 frontend tests passed. Isolated browser fixtures covered
  operation/candidate routing, two successive phone requests and import, partial wallet
  signatures, confirmation gating, single advancement and manual opt-out. Visual layout
  was inspected. Browser fixtures used mocked signing/backend responses, not a new live
  ledger or physical-phone qualification.
- The running backend was preserved because the user has a pending activation request.
  Additive `signerKeys` API metadata is built but takes effect on its next normal restart;
  this UI falls back to transaction key hashes when that metadata is absent.

## COSE default and funding-role separation (2026-09-09)

- [x] Creation defaults every key to COSE; transaction witnesses remain advanced.
- [x] Existing mixed configuration can change all key methods using its current admin
  authorization and destination possession proofs, preserving its address and policies.
- [x] Explicit fee-payer and transaction-authority metadata drives the approval wizard;
  funding identity is never inferred to be an authority.
- [x] Plain-language approval/funding/confirmation explanation appears alongside request
  details; separate administrator and possession signatures are explained.
- [x] `./gradlew check dashboardWebBuild` and all 15 frontend tests passed.
- [x] Live synthetic-wallet test confirmed all-COSE migration and separate-sponsor
  15/35 ADA transfers, requiring one/two intent approvals at a 30 ADA threshold.
  [Evidence](evidence/cose-independent-sponsor-devkit.log).
- [x] Multi-input transfer regression exposed by the live test was fixed by ordering
  resolved inputs identically to the canonical signed input list; strict SDK validation stays.
- [x] User completed the existing account update with real-device approvals; the dashboard
  showed all five proofs approved and the update confirmed. This is manual development
  evidence, not general wallet compatibility qualification.
- [x] Approval progress groups proofs by purpose with a count, progress bar and explicit
  Approved / Sign now / Pending states; completed presentation inspected in Chrome.

This does not qualify a hosted sponsor service, production deployment or audit readiness.
