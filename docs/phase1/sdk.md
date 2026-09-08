# Phase 1 SDK usage and boundaries

This is development support for the sealed-state Phase 1 protocol, using CCL
`0.8.0-pre5` and the pinned JuLC snapshot. It is not a production wallet SDK.

`AccountDeployment.derive` applies the script graph in the normative order. The creator
must derive it locally with the chosen deployment domain, creator seed and immutable
reward sinks. `AccountDeployment.genesis` builds the final state; creation still requires
the creator transaction signature and all-key genesis possession evidence. The live
`AccountDevkitTest` contains the complete executable initialization example.

`AccountCodec` explicitly encodes the typed CDDL records. `intentDigest` validates an
intent and returns its canonical raw-Ed25519 message. Present
`WireFormat.renderSigningRequest` using the same envelope and authenticated ledger state
before requesting that signature. CIP-8/COSE and hardware-wallet signing are not implemented.

`AccountTransfer.attach` prepares ordinary transfer invocations for a caller-owned CCL
`Tx`. It checks the actual supplied state NFT, quantity, custody address, inline datum,
script graph, signed state reference, complete input set and spend-role evidence. Its
whole-transfer path reconstructs the full canonical input Value independently of backend
amount ordering. It requires complete, distinct receipt entries for positive withdrawals.
The caller must obtain resolved UTxOs and reward balances from the intended ledger;
accepting arbitrary relayer JSON as ledger truth is not authentication.

For example, after preparing and authorizing the exact typed envelope, with one
`Account sponsor` whose payment key owns all fee/collateral inputs and is the only
transaction key witness:

```java
// Existing outputs must follow the signed indices, full addresses and complete amounts.
Tx transaction = new Tx()
        .payToAddress(recipientAddress, recipientAmounts)
        .payToAddress(accountAddress, accountChange)
        .readFrom(coreReference)
        .readFrom(moduleReference)
        .readFrom(assetReference);

AccountTransfer.attach(transaction, scripts, state, stateInput, accountInputs,
        intent, proof, receipts, coreRewardBalance, moduleRewardBalance);

var evaluator = new ExecutionBudgetMargin(
        (cbor, inputs) -> backend.getTransactionService().evaluateTx(cbor),
        currentProtocolParameters);

var unsigned = new QuickTxBuilder(backend).compose(transaction)
        .feePayer(sponsor.baseAddress())
        .collateralPayer(sponsor.baseAddress())
        .validFrom(lowerSlot)
        .validTo(upperSlot)
        .withReferenceScripts(scripts.asset(), scripts.checkpoint(), scripts.module())
        .preBalanceTx(DuplicateScriptWitnessChecker.removeDuplicateScriptWitnesses())
        .removeDuplicateScriptWitnesses(true)
        .withTxEvaluator(evaluator)
        .build();
var signed = sponsor.sign(unsigned);
```

CCL already counts the sponsor key from its resolved inputs. In this specific setup,
registering it again through `withSigner` overestimates one witness. Additional witnesses
that are not inferred from inputs must still be accounted for before balancing; the
unsigned-build example must not be copied to a different signer setup without doing so.

This fragment assumes the named inputs have already been resolved. Whole transfers have
one recipient output and no account-change output. Positive balances require separate
receipt outputs at the immutable sinks, even when both sinks are the same address. Their
positions must match the receipt table and remain distinct from account allocations.
The caller derives the slot/POSIX conversion from the target chain and keeps the actual
transaction interval within the signed interval.

`ExecutionBudgetMargin` checks the complete redeemer estimate set, adds 5% by default,
rounds each allocation upward and rejects a padded total above current ledger limits.
Callers can explicitly select another allowance; the wrapper never silently reduces it
or clips budgets. Refresh protocol parameters when the ledger configuration changes.
Rejected evaluation results remain rejected. A 5% allowance has passed the four short
DevKit transfer profiles and the measured combined stress scenario, but is not a universal
bound on estimator error. Re-evaluate the final transaction and require node acceptance.
An oversized transaction must be rebuilt into separately authorized operations; never
silently change an already signed input set, output allocation or fee cap.

The helper does not select UTxOs, create outputs, infer receipt sinks, fund collateral,
convert arbitrary network slot schedules, publish reference scripts, submit transactions,
or implement account discovery/recovery. These are explicit caller responsibilities.
Immutable validators enforce output semantics even when a caller assembles an invalid
transaction. Reference scripts must actually exist at the supplied references; naming
script bytes for fee calculation alone does not establish their availability.
