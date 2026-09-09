---
title: "Approve the intent. Fund the transaction."
description: "Approve the intent. Fund the transaction. — a practical Kavach guide."
---

Kavach separates **permission to act** from **paying to execute that action**. The approval interface shows which role each signature serves.

## COSE intent approval

A supported wallet or the iPhone companion signs the exact Kavach intent using the bounded COSE profile. This expresses account authority: “I approve this specific action.” Wallets use their supported `signData` interface.

## Transaction-witness approval

A wallet signs the final Cardano transaction body through `signTx`. The configured payment-key witness can satisfy an account authorization requirement. Changing the body requires new transaction signatures.

CIP-30 is the browser-wallet API, not a cryptographic alternative to Ed25519. The dashboard's signing modes select how authorization reaches the ledger.

## A separate fee signature

In an all-COSE policy, account keys approve the intent first. A fee/collateral wallet then signs the final transaction. It may belong to the same person, but the signature serves a separate role.

A fee signature alone does not satisfy a configured COSE intent requirement. The account contract still checks its policy and the exact authorized action.

## Future: execution behind the scenes

A sponsor could provide the fee wallet, allowing the account owner to interact only with their familiar approval device. A decentralized intent-execution network is a **proposal**, not a service implemented by Kavach today. Providers must remain unable to change authorized recipients, amounts or policy effects.

Read [the sponsorship proposal](/kavach/reference/sponsorship/) and [browser authorization specification](/kavach/reference/browser/).
