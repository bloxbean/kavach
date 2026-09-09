---
title: "Spending rules that fit the situation"
description: "Spending rules that fit the situation — a practical Kavach guide."
---

Kavach offers two distinct controls: **which approvals a transaction needs** and an optional **shared limit on ADA spent during a period**.

## Choose approval strength by amount

For example, a small-transfer tier can require the iPhone key, while the stronger tier requires the phone and another key.

A 30 ADA threshold selects the approval tier. It is not a hard 30 ADA transaction cap. The small tier's calculation includes recipient ADA and the signed maximum account-paid fee. Native-asset and whole-account transfers use the stronger path.

## Add an optional shared limit

The budget-capable core can enforce a shared daily or weekly ADA debit limit. Usage is recorded on-chain in an authenticated budget-counter UTxO, not in a backend database. Actual account-paid fees count toward the debit.

Periods are fixed UTC days or Monday-based UTC weeks. They are not rolling windows or Cardano epochs. Transaction validity intervals enforce time; off-chain code converts the network's slots and timestamps.

## Understand the concurrency tradeoff

Every budgeted spend consumes and recreates the same counter. Competing spends must serialize around it. With the budget disabled, independent asset inputs can be spent concurrently under the ordinary rules.

An authorized administrator can change or disable the limit. Setting it to zero disables enforcement but retains recorded usage. Re-enabling in the same period retains that usage; spends while disabled are not retroactively counted.

See [the policy specification](/kavach/reference/policies/) for the exact rules and [qualification evidence](/kavach/reference/policy-status/) for tested boundaries.
