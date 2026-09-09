# Yano Companion

A native iPhone approval app for the Yano ecosystem. SwiftUI, CryptoKit Ed25519,
device Keychain, and an offline QR/file exchange. No third-party runtime dependencies.

**Status: development prototype, DevKit only.** This directory builds an actual app,
not a web mockup. The narrow signing profile is tested against Kavach's public
conformance fixture and Java COSE adapter. It is not a production wallet or a
complete Yano transaction signer. The Kavach dashboard now has bounded COSE enrollment and ADA payment integration.

## What works

- Onboarding and independent Ed25519 device identity, with public-key export.
- Physical-device key storage requires a passcode and user presence; no iCloud sync.
- Desktop pairing pins an Ed25519 request-signing key after fingerprint comparison.
- QR scanning, pasted JSON and JSON file import; response QR, copy and share.
- Independent parsing of canonical Kavach intent CBOR, not desktop-supplied review text.
- DevKit network magic 42, ordinary Spend, one ADA recipient, key enterprise/base addresses,
  up to eight ordered account inputs, five-minute maximum validity, maximum 5 ADA fee.
- Raw scheme-0 intent signatures or candidate-2-compatible COSE evidence.
- Expiry checks before and after authentication, protected local approval history,
  duplicate-intent rejection, unpairing, and response re-export.
- A clearly marked read-only sample review. It cannot sign or submit anything.
- A desktop CLI for pairing, request generation and response verification.

- COSE genesis possession: canonical state/configuration review of all keys and six policies.
- Kavach dashboard pairing, QR request export and verified approval import.

## What is not connected yet

- Independent live state/NFT verification on the phone and configuration/target possession proofs.
- Transaction-witness mode, ordinary Yano `signTx`, native assets, script recipients,
  multiple recipients, recovery/configuration/module changes, mainnet or preprod.
- Push notifications, a relay, encrypted remote transport, background requests, or
  an automatic account restore/backup service. QR/file payloads are authenticated
  but not encrypted; use a private channel to exchange them.
- Hardware qualification, security audit, TestFlight packaging and App Store distribution.

Kavach's current mode-1 accounts cannot use this phone's COSE response without a
qualified, authorized module/configuration change. Mixed evidence remains a separate
Kavach protocol/module project. This integration adds dashboard/backend transport without changing contracts or ordinary Yano wallet signing.

## Repository location

The app lives in Kavach at `companion-apps/ios`. Its product name remains Yano Companion,
and it can support both Kavach and Yano integrations. The bundle identifier, Keychain
identity and signing profiles are unchanged by this move.

## Build and run

From the Kavach root, open `companion-apps/ios/YanoCompanion.xcodeproj` in Xcode.
Run the commands below from `companion-apps/ios`. The deployment target is iOS 17.0.
For an iPhone, select your development team in Signing & Capabilities, choose your
connected device, enable Developer Mode if requested, and Run. A device passcode
must be enabled. Use `Config/Local.xcconfig` with `DEVELOPMENT_TEAM = YOUR_TEAM_ID` to keep your team
setting local; this file is ignored. The shared configuration includes it when present.
No Apple team ID or provisioning credential is committed.

On this machine, Xcode 26.3 has the 26.2 SDK but only iOS 17.4/17.5 simulator runtimes.
The scheme destination picker requires the newer platform; the direct target build
below works with the installed iOS 17.5 simulator:

```sh
./tools/run-simulator.sh
```

The script uses **simulator-only** development entitlements to enable Keychain in the
simulator. Do not use those entitlements for an iPhone build. Simulator keys do not
have biometric protection, and the UI labels them accordingly. Never enroll one in
a funded account. Install the matching simulator runtime through Xcode Components
if you want to use the normal scheme Run button.

```sh
swift test
swift run companion-exchange pair tools/out/desktop > tools/out/pairing.json
```

`tools/out/desktop/desktop-key.bin` is an automatically generated **local desktop
pairing key**, stored with permissions 0600 and ignored by Git. It is not a Cardano
spending key. Keep it private. The CLI prints only its public fingerprint and pairing
message. Compare that fingerprint on the phone before trusting the desktop.

Paste `pairing.json` into the phone's **Devices → Paste pairing code** flow. Export
the phone public key from **Your device identity**, then generate a synthetic request:

```sh
swift run companion-exchange sample tools/out/desktop PHONE_PUBLIC_KEY App/sample-intent.json > tools/out/request.json
```

The sample uses a published fixture account/input that does not exist on your ledger.
Its time window is refreshed to less than five minutes. Paste/import the JSON, review,
approve, and return the response as `tools/out/approval.json`:

```sh
swift run companion-exchange verify tools/out/request.json tools/out/approval.json
```

For an integrator-produced canonical intent, use `request` instead of `sample`:

```sh
swift run companion-exchange request tools/out/desktop PHONE_PUBLIC_KEY intent.hex cose
```

This emits credential ID 0. Integrators must set the actual registered credential ID
and select the authenticated account's module mode when constructing their request.
The phone signs only after local review. The CLI **never submits transactions**.

## Project map

- `App/`: native views, QR camera, Keychain, protected local state and approval orchestration.
- `Sources/CompanionCore/`: bounded CBOR/intent parsing, BLAKE2b, Bech32, signed transport and COSE.
- `Sources/CompanionExchange/`: desktop exchange CLI.
- `Tests/CompanionCoreTests/`: golden vectors and adversarial core tests.
- `docs/protocol.md`: exact exchange and signing boundary.
- `docs/security.md`: threat model and qualification gates.
- `docs/validation.md`: checks actually performed, evidence and limitations.
- `tools/generate-project.py`: deterministic Xcode project generation after adding source files.
- `tools/draw-icon.swift`: source-drawn app icon. Legacy PNG resources support the older simulator;
  the 1024px asset catalog source is also present for future distribution packaging.

The repository has no seed phrase/private-key fixtures, analytics or network client.
Public conformance vectors originate from Kavach's `conformance/v1/intent-vectors.json`.


The mixed-policy increment adds `kavach-cose-policy-v1` for configuration and module
replacement. Update the iPhone build before installing or changing a mixed Kavach policy
that includes its key. The app reviews old/new authority policies, methods, amount tiers
and optional daily/weekly budgets. See [the protocol](docs/protocol.md) and
[validation record](docs/validation.md) for the exact supported boundary.
