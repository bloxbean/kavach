# Validation record — 2026-09-08

Environment: macOS arm64, Xcode 26.3 (17C529), Swift 6.2.4, iOS 26.2 SDK,
iPhone 15 Pro simulator with iOS 17.5 runtime. Device deployment target iOS 17.

## Completed

- `swift test`: eight tests, zero failures. Covers the published Kavach Spend digest
  and Ed25519 signature, rejection of unsupported actions, BLAKE2b-224/256 at empty/
  127/128/129/255/256/1000-byte boundaries against Python hashlib, every truncated
  prefix of the Spend fixture, noncanonical CBOR, depth/size bounds, network, expiry,
  fee limits, authenticated transport, both signature profiles, wrong signing key,
  unpaired sender, replay, signature tampering, unsupported Yano profile and pairing bounds.
- iOS Simulator Debug target: builds with ad-hoc simulator entitlements. Installed
  and launched successfully on iOS 17.5. App identity/Keychain entitlement issue in
  the initial unsigned simulator build was fixed using Xcode's simulated entitlements.
- iPhoneOS Release target: builds successfully with signing disabled. The physical
  `userPresence`/passcode-protected Keychain branch compiles. This is not a provisioned IPA.
- Explicit simulator integration check: Keychain identity creation/loading,
  authenticated request decode, typed intent review, Keychain-backed Ed25519 operation,
  COSE response construction and independent signature verification all passed.
  The test uses a synthetic fixture, an ephemeral desktop key and a simulator device
  key; it never submits a transaction. Hooks are absent from physical-device builds.
- Swift-generated **and simulator-generated** COSE responses accepted by existing
  Kavach `BrowserSignatures.fromCip30` and `BrowserSignatures.verify`, using the local
  compiled Kavach classes and Bouncy Castle 1.84. This checks adapter compatibility,
  not UPLC execution or ledger acceptance.
- Home and sample review screens rendered and visually inspected using simulator
  screenshots. Desktop manual UI control was blocked by the locked Mac; no claim
  of a complete manual QR/paste/approval interaction test is made.

## Reproduce

```sh
swift test
./tools/run-simulator.sh
```

For the simulator-only check, launch the installed Debug app with
`xcrun simctl launch <SIMULATOR_ID> com.bloxbean.yano.companion --simulator-check`.
Its app Documents directory receives `simulator-check.json` and a public
`simulator-response.json`. `--preview-review` opens the non-signing sample screen.

Set `COMPANION_EVIDENCE_DIR` to an existing folder when running `swift test` to emit
`swift-cose-response.json`. Only public keys/signatures/digests are exported.
`tools/VerifyKavachResponse.java` checks a corresponding Java properties file with
`publicKey`, `digest`, `signature` and `key`, using the Kavach compiled classpath.

Local build evidence is under `build/evidence/` and screenshots under
`build/screenshots/`; these are ignored development artifacts, not committed secrets.

## Pending

Physical iPhone provisioning and Face ID/passcode/cancellation/camera tests; complete
manual UI/accessibility interaction; live account enrollment; phone-side ledger-state
verification; Kavach dashboard transport adapter; ordinary Yano transaction decoder;
real ledger acceptance; production cryptographic review and audit.

Xcode's scheme runner currently reports that iOS 26.2 is not installed, because only
older simulator runtimes are present. The direct target script above succeeds. Legacy
PNG icon resources avoid the newer asset compiler's runtime requirement; a 1024px
asset source is included for future distribution packaging.

### Physical install follow-up

The app was installed on the user's iPhone 14 Pro Max. A dense 1509-byte request QR
was reported not decoding. Added compact authenticated-payload QR transport, 1080p
back-camera capture with continuous autofocus, and a Scan again control. Nine core
tests pass, including byte-preserving compression and oversized-expansion rejection;
the signed iPhoneOS Debug build succeeds. Physical camera decode needs user retest.

### First physical iPhone approval verified

The user successfully scanned the compact request, reviewed it on the iPhone, and
returned the approval JSON. The desktop CLI verified the request ID, credential ID,
profile, public key, canonical intent digest and Ed25519/COSE signature against the
original request while it was still valid. Kavach's existing Java COSE adapter also
accepted the physical phone's response. This validates the manual camera/request/
approval/response exchange for this synthetic Spend fixture. No ledger transaction
was submitted; live enrollment and lifecycle integration remain pending. The specific
local authentication method used on the phone was not independently observed.


## Dashboard integration increment — 2026-09-08

- Eleven Swift tests pass, including canonical genesis proof digest equality with
  Kavach's published `conformance/v1/genesis-possession.json`, incorrect credential,
  changed initial counters, overlapping defensive policy, expiry and duplicate rejection.
- Swift-generated enrollment COSE evidence is accepted by Kavach's existing Java
  `BrowserSignatures.fromCip30` and `verify`; public evidence is under `build/evidence`.
- Updated physical iPhone build succeeds. This adds enrollment review, not evidence
  that the owner has completed a new physical-phone genesis transaction on the ledger.
- Kavach adds local authenticated QR exports, pairing and strict approval import for
  genesis and bounded ADA Spend. Remaining configuration/recovery and ordinary Yano
  transaction-signing profiles still reject.
- Kavach's demo API regression passes both modes (42 confirmed disposable DevKit
  transactions). Supported COSE genesis/Spend proofs traverse the new companion
  export/import endpoints with synthetic CCL signer responses. Physical-phone live
  genesis and payment still require the owner's scan/review/approval session.
- Chrome displays the dashboard pairing QR and full fingerprint in Create account.

## Mixed-policy increment — 2026-09-08

Fourteen Swift tests pass, including six public Java-produced live policy request fixtures.
The phone's operation, candidate and configuration-possession digests match Kavach's Java
proof domains. Compressed QR authentication, purpose/state/method/budget rejection,
expiry/replay and fresh-device CryptoKit COSE signing are covered. The public fixtures are
`Tests/CompanionCoreTests/Fixtures/policy-requests.json`; they contain no private keys.
Kavach's periodic DevKit lifecycle passed through companion-format request/approval
endpoints using synthetic CCL COSE responses. The physical phone's new policy-review flow
still needs an interactive owner review and approval; this is not a physical signing claim.
The complete updated SwiftUI app also builds for iOS Simulator. The physical-device build
reaches codesigning but is waiting for the Mac owner's Keychain authorization. This
increment has not yet been installed or interactively tested on the phone.


## Per-key genesis increment — 2026-09-08

Fifteen Swift tests pass. Two additional public Java-generated mixed genesis fixtures match
phone-computed possession digests; removing the phone from the COSE list rejects. The
phone displays per-key methods and policies before enrollment. The updated signed iPhone
build succeeded and `devicectl` confirmed installation of `com.bloxbean.yano.companion` on
the paired iPhone. This supersedes the earlier waiting-for-Keychain installation note.
No physical-device mixed genesis signature or biometric event is claimed by automated tests.

## Kavach repository import — 2026-09-09

Moved the current source to `companion-apps/ios`, preserving the product and bundle
identifier. All 15 Swift tests passed from the new location, and the iOS simulator
target built with signing disabled. Xcode resolves the preserved local team through
the ignored local signing configuration. No phone installation or signing-profile
change was performed as part of the move.
