# Recipient / fee-payer collision — 2026-09-08

The user's failed plan `4f449d77-4466-42da-9fce-68d7e34719aa` approved a 22,000,000
lovelace recipient allocation, but its final transaction contained a 20,867,549
lovelace recipient output and a 1,132,451 lovelace fee. Its only ordinary input was
the smart-account input. The builder deducted the fee from the recipient because
that address was also configured as fee payer. Direct evaluation of the saved
transaction failed, independently of the QR scanner. The immutable accounting check
correctly rejected the unsigned allocation mismatch; signatures were not the cause.

The fix selects a separate sponsor UTxO, disables output merging for this case,
preserves the recipient/account-change allocations, and directs all fee deductions
to the dedicated sponsor-change output. The post-balance candidate must evaluate
successfully, receives the existing execution margin, and has fees and collateral
recomputed to a bounded fixed point. Reordered or changed output layouts/assets
fail closed. No contract, proof domain, account configuration or address changes.

These bounds intentionally require an ADA-only sponsor UTxO of at least 5 ADA and
at least 2 ADA remaining sponsor change. The signer count includes an allowance
for the sponsor input witness. Updated bodies require fresh wallet witnesses; expired
payment intents must be recreated and reapproved. Existing signatures are not edited
or silently applied to a different payment intent.

Validation: `./gradlew :demo:backend:check :demo:backend:demoIntegrationTest`
passed on 2026-09-08. The live suite confirmed 44 transactions across both browser
modes, including an explicit 22 ADA payment to the fee-paying address in each mode.
Assertions check the exact recipient allocation and separate ordinary fee input;
the smaller eligible fee UTxO is selected first to exercise the library's largest-output
fee-selection hazard. Unit coverage rejects changed output layouts. These tests use
synthetic CCL signatures and actual DevKit ledger validation, not a new physical-phone
qualification claim. See the [regression log](sponsor-recipient-devkit.log).

Submission also checks the transaction's upper validity bound against the latest ledger
slot and returns an actionable expired-request message before node submission.
