# Security boundary

Development candidate only. No production funds, hardware qualification or audit claim.

## Keys and consent

The iPhone generates an independent Ed25519 key using CryptoKit. It is not derived
from the desktop wallet's seed. Physical-device storage uses Keychain
`WhenPasscodeSetThisDeviceOnly`, `userPresence`, and no synchronization. User presence
may be satisfied by the device passcode as well as biometrics. The key is retrieved
only for the reviewed signing operation; no long-lived in-memory private-key cache
is used. Ed25519 signing occurs in application memory, **not inside the Secure Enclave**.
Resetting one Data buffer does not guarantee erasure of framework/internal copies.
Device compromise therefore remains a material threat.

No mnemonic/private-key export or automatic replacement is offered. Device loss,
passcode removal or inaccessible Keychain may lose access to this signing credential.
Use Kavach's separately authorized recovery setup before relying on a phone credential.
Do not reset/recreate a key silently when lookup fails.

The simulator deliberately uses a development key without user-presence access
control, labelled on-screen. Simulator test hooks are compile-time restricted to
DEBUG + targetEnvironment(simulator); they are absent from physical-device builds.

## Untrusted inputs

Pairing labels, QR content, desktop-supplied JSON, request CBOR and returned responses
are untrusted. Only pinned desktop keys may request signatures. Authentication is
followed by bounded decoding; no arbitrary-message or arbitrary-hash signing API is
exposed in the app. Unsupported operations fail closed. Enrollment and ledger-state
authentication remain explicit integration gates rather than inferred from a supplied
account identifier. Desktop compromise can still solicit a malicious payment; full
recipient/account review on the phone matters.

The app requires an affirmative review checkbox and then fresh Keychain user presence.
Expiry is checked again after authentication. No QR scan, sample view or pairing step
signs a payment. A generated signature is verified locally and its record persisted
before release. Prior responses can be exported again rather than signing the same
intent repeatedly. These local checks supplement the ledger's replay rules; they
cannot replace them and are not a global cross-device replay database.

## Transport and local data

The first version has no network listener/client or relay. Manual QR/file messages
are signed, not encrypted. Recipients, amounts and identifiers are visible to anyone
who obtains a message. Copy uses local-only clipboard items with a two-minute expiry;
system sharing can intentionally transfer the response elsewhere. No private key is
ever part of these messages.

Pinned peers and approval history are stored in the app's device-only Keychain.
At 500 records, expired records older than one day may be pruned; if still full,
new approval is refused. Unpairing removes future request trust; it neither deletes
the phone credential from Kavach nor revokes already issued signatures.

## Required before broader use

1. Physical iPhone tests: passcode/Face ID cancellation, enrollment changes, lock,
   background, reinstall, storage failures and recovery paths.
2. Device-team provisioning and signing configuration review, dependency/compiler
   provenance and independent audit of CBOR, BLAKE2b, address rendering and consent.
3. Real camera QR tests and accessibility/large-text review.
4. Authenticated on-chain account state and enrolled role verification, complete
   genesis/configuration possession review, then live DevKit transaction acceptance.
5. Dedicated Yano transaction profile and wallet adapter qualification.
6. New ADR and conformance evidence for any mixed authorization module.

The app does not claim passkey security, hardware-backed Ed25519 execution, automatic
recovery, a compromised-relay availability guarantee, or production readiness.
