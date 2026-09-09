---
title: "Security is a set of enforced boundaries"
description: "Security is a set of enforced boundaries — a practical Kavach guide."
---

Kavach aims to make authority explicit and verifiable. Its current development implementation is **not independently audited or production-qualified**.

## What the protocol must enforce

- Authenticate state using the full state NFT identity and expected validator.
- Bind authorization to the account, operation, deployment domain and canonical intent.
- Enforce lifecycle, replay protection and value conservation in immutable core code.
- Authorize configuration changes under the old policy.
- Account for every native asset, recipient allocation, valid change and bounded account-paid fee.

Inputs from wallets, relayers, redeemers and modules are untrusted. A useful interface is not a substitute for ledger validation.

## Recovery has its own authority

Recovery uses a delay and a separate cancellation path. Spending keys do not implicitly control it. Freeze cannot guarantee it will beat a competing spend to the ledger.

Recovery implementation and short lifecycle tests exist. Full real-delay qualification remains an open gate; short tests do not prove a complete recovery after the immutable delay or restoration on a clean device.

## Device and privacy boundaries

Distinct keys derived from one seed share a common compromise risk. The companion's Ed25519 key uses device-only Keychain storage and user presence; it is not Secure Enclave Ed25519 signing. A device can be lost, and an approval display depends on the data it receives.

The ledger is public. Authenticated QR payloads are not encrypted payloads, and future relays need their own privacy design.

Read [the security model in the white paper](/kavach/whitepaper/#11-security-model) and [the current acceptance ledger](/kavach/reference/qualification/).
