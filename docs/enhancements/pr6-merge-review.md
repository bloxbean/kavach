# PR #6 integration with main

Integrated main commit `cc1b782bd1baaec6803a37c82915fdf345a62305` using a merge commit,
preserving both histories and leaving untracked `contracts-aiken/` work untouched.

## Resolution decisions

- Preserve publisher-protected vault publication, exact output reclaim, full returned value,
  separate fee/collateral funding, retained artifacts, state minimums and eight-input admission.
- Integrate upstream exact-outpoint `.ref` hints with live script hash, network and unspent
  checks, retaining key-enterprise and historical state-address fallback. A query failure is
  not treated as a missing reference: only a genuine 404 can indicate absence.
- Preserve upstream optional immutable core/module reward sinks and bulk republishing of
  missing operational references. New mixed setup hints retain the module sink across restart.
  Earlier plain records remain supported for default-sink setups; incorrect historical
  custom-sink records fail the committed-module check rather than substitute another module.
- Disable the prior unqualified bulk key-address reclaim route: its selected-input ownership
  and consumed-reference fee handling do not meet the new custody checks. Vault users retain
  exact-output reclaim; legacy key holders need a separately qualified reference-aware tool.
- Keep main's ADR-011/012 identities. Renumber the PR's enhancements/vault ADRs to
  [ADR-013](../../adr/adr-013-economics-extensibility-and-wallet-safety.md) and
  [ADR-014](../../adr/adr-014-publisher-protected-reference-vault.md), with updated links.
  Historical evidence directory names retain their original branch identifiers. Shared-core
  tiers remain declined under main's ADR-012; research targets do not reverse that decision.
- Preserve upstream UI changes and custom sink controls with explicit vault reclaim review;
  retain legacy hosting distinctions and avoid presenting unsafe bulk reclaim as available.

## Validation

`./gradlew check dashboardWebBuild` passes: 572 root tests, 11 backend tests and one toolchain
acceptance gate, zero failures/skips. Frontend tests pass 26 cases. Added merge regressions
cover malformed/overflow outpoint hints, provider errors versus 404, wrong script/network,
custom reward-sink editing and maintenance action routing. Ten account-template CBOR hashes
still match the PR's recorded baseline. Local documentation links and staged whitespace checks pass.

Both focused external DevKit workflows passed: distinct reward sinks across mixed
setup/restarts and transfers, and vault reclaim/restart/independent repair through legacy `.ref`
manifest discovery. No ledger reset, parameter change or active recovery/reward worker restart.

[Validation counts](evidence/pr6-merge/validation.json) and [live test results](evidence/pr6-merge/backend-live.xml)
retain the exact scope. These merge checks do not change the existing production/audit gates.
