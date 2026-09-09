---
title: "Meet Kavach"
description: "Meet Kavach — a practical Kavach guide."
---

An account should outlast a phone, a wallet, or a single key. Kavach is a programmable smart-account protocol for Cardano that separates **the account** from **the people and devices allowed to control it**.

## A familiar action. A configurable account.

Imagine sending 15 ADA with an approval on your iPhone. A larger transfer could require your phone and another wallet key. A separate set of keys can govern policy changes and recovery.

Kavach gives those rules an on-chain home. A dashboard prepares the action, your devices approve it, and the contracts enforce the account's rules.

## Three ideas to start with

- **Identity:** a state NFT authenticates the account's configuration. Its funds live at the account's script address.
- **Authority:** role policies decide which keys can spend, administer, freeze or recover the account.
- **Execution:** a fee wallet funds and signs the final transaction. With COSE authorization, this is a separate responsibility from approving the intent.

## Where the project stands

Kavach is **experimental**, with a working local Yaci DevKit stack, Java SDK, dashboard and iPhone companion. It is not audited or production-qualified. Use test assets.

[Create an account](/kavach/guides/create-account/) or explore the [living white paper](/kavach/whitepaper/).
