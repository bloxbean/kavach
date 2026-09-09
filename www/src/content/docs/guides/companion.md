---
title: "Approve with your iPhone"
description: "Approve with your iPhone — a practical Kavach guide."
---

The Yano companion is a native iPhone approval device for supported Kavach COSE intents. It keeps the signing key on the phone and presents the requested action before approval.

## Pair a public key

Install the development app from `companion-apps/ios`, create its key, and add the exported **public key** as a COSE signer in Kavach. Keep recovery authority available through independent keys.

## Approve a request

In the dashboard, open the companion request for the required key. Use **Scan a request** on the phone, review the displayed details and approve with user presence. Follow the dashboard's handoff instructions to return the approval; the manual approval JSON is a fallback.

The fee wallet still signs the final transaction separately. Approving on the phone does not fund the transaction.

## Current scope

The companion supports bounded genesis, ADA-spend and policy configuration/module-proof requests. It is not a general transaction signer and does not yet cover arbitrary signatures, native-asset or whole-account transfers, or the full recovery workflow.

Its CryptoKit Ed25519 key is protected by device-only Keychain access and user presence. Signing is not performed by Secure Enclave Ed25519 hardware. The phone does not independently verify the full live ledger state.

See the [iOS application documentation](https://github.com/bloxbean/kavach/tree/main/companion-apps/ios) for installation and current transport details.
