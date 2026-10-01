# Deposits UI evidence (2026-10-01)

Headless Chrome captures of the dashboard's **Security → Deposits** section and the
**Claim back a deposit** dialog. The account is a real Yaci DevKit account created by
`DemoServiceDevkitTest`; the page was restored from a locator built from its on-chain state
datum, and every figure comes from the backend's live account view:

- five publisher-vault reference deposits totalling 240.89452 ADA, all claimable;
- a 3.72815 ADA account state reserve and 2 × 2 ADA registration deposits, locked permanently.

`deposits-mobile.png` is a crop of the 400 px-wide layout, where reference rows stack as cards
and the page has no horizontal overflow. No console errors were recorded. No claim was
submitted from this capture: the claim itself is exercised on DevKit by
`publisherReferenceRemovalRestartAndIndependentSponsorRepair`.
