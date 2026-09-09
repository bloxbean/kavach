---
title: "Create your first account"
description: "Create your first account — a practical Kavach guide."
---

Connect your DevKit wallet in the dashboard, then choose **Create account**. Creation is a ledger workflow: the account is usable after its final policy is activated.

## 1. Choose your approvers

Add between **three and eight keys**. Choose a device and signing method for each public key. COSE is the default and works with supported browser wallets and the iPhone companion. Transaction-witness authorization is an advanced option for wallet keys.

Only enter public keys. Separate address indexes from one seed are different keys, but losing that seed compromises all of them.

## 2. Set the roles

Review who can spend, administer, freeze, unfreeze, recover and cancel recovery. Defensive roles have separation requirements. Each registered key must prove possession during setup.

If you want optional daily or weekly limits later, select the budget-capable core at creation. An ordinary core cannot gain that capability through a module-only update.

## 3. Review and approve

The default per-key flow currently contains **ten transactions**, grouped into setup phases. It publishes reference scripts, establishes the account and activates its final policy. Follow the approval progress to see which key is needed next. COSE intent approval and fee-wallet signing are separate steps.

Current reference publication permanently locks **480 test ADA** across six scripts. The state output holds 12 ADA; a budget counter adds 3 ADA when created. Registration deposits and network fees are additional. These are development costs, not a production price.

## 4. Keep your locator

After confirmation, the account appears in **Your workspace**. Export its public locator and keep a separate backup. Workspace entries belong to this browser and site origin: `localhost` and `127.0.0.1` have different storage.

**Forget** removes a browser entry, not the on-chain account or its funds. Restore from the locator on another browser or after changing the dashboard's hostname.
