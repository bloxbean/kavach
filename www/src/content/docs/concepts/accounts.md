---
title: "An account beyond one key"
description: "An account beyond one key — a practical Kavach guide."
---

A conventional key-controlled address ties control directly to a key. Kavach puts a contract-enforced policy between the account and its approvers.

## Identity and assets

The complete state NFT asset identifier, expected validator, quantity and supported datum schema authenticate account state. The account's asset outputs are distinct from its configuration state.

Approvers can change through an authorized configuration update. Compatible policy updates preserve the account address. Changing the immutable core is a different operation and may require migration to a new address.

## Six separate responsibilities

| Role | Responsibility |
| --- | --- |
| Spend | Authorize outgoing assets |
| Admin | Change account configuration |
| Freeze | Enter the frozen lifecycle state |
| Unfreeze | Restore operation through its own authority |
| Recovery | Begin the recovery process |
| Cancel | Cancel a pending recovery |

Spending power does not automatically grant administration or recovery power. Configuration changes are authorized under the **old policy**; proposed keys cannot simply authorize themselves into control.

## Local convenience, ledger authority

The browser stores public account locators. Backend storage helps restore public profiles; pending approval plans are held in memory. The authoritative account state and budget usage live on the ledger. Wallets and the companion hold signing keys.

See the [locator reference](/kavach/reference/locators/) for authenticated restoration.
