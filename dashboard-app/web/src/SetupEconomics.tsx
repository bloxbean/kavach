import { ada, type Plan } from './api';

/** Separates claimable deposits, permanently locked reserves, spent fees and collateral before wallet signatures. */
export function SetupEconomics({ plan }: { plan: Plan }) {
  const costs = plan.costs;
  return <>
    {costs && <section className="workflow-setup" aria-label="Complete setup funding estimate">
      <h3>What this setup will cost</h3>
      <p>{costs.setupTransactions} transactions · {costs.referenceCount} reference scripts</p>
      <dl className="economics-breakdown">
        <dt>Reference script deposits · claimable later by the publisher wallet</dt><dd>{ada(costs.referenceCapital)} ADA</dd>
        <dt>Account state reserve · permanently locked</dt><dd>{ada(costs.stateReserve)} ADA</dd>
        <dt>Stake registration deposits · permanently locked, withdrawal unsupported</dt><dd>{ada(costs.registrationReserve)} ADA</dd>
        <dt>Network fees · spent (estimate)</dt><dd>{ada(costs.feeAllowance)} ADA</dd>
        <dt>Collateral · stays in your wallet, not a successful transaction charge</dt><dd>{ada(costs.collateralReserve)} ADA</dd>
        <dt><strong>Total ADA needed in the wallet</strong></dt><dd><strong>{ada(costs.totalFundingEstimate)} ADA</strong></dd>
      </dl>
      <p><strong>Who can claim the reference deposits:</strong> only this publisher wallet, which pays for them. Kavach account
        keys, co-signers and recovery guardians can’t claim them:</p><code className="workflow-key">{costs.publisherAddress}</code>
      {costs.hostingAddress && <><p>The deposits are held at this separate vault address, so ordinary wallet spending can’t use them
        by accident. Claiming one needs the publisher wallet’s signature and never spends account funds:</p>
        <code className="workflow-key">{costs.hostingAddress}</code></>}
      <p><strong>When:</strong> any time after setup. Claiming a deposit removes a script the account uses, so the account pauses
        until that script is republished. If the publisher key is lost, its deposits are lost too, even if you recover your Kavach
        account. A claim pays its own network and reference-script fees. Historical locked references can’t be claimed.</p>
      {!costs.hostingAddress && <p>These deposits use the older key-address hosting. Claiming them needs a wallet or tool that
        supports reference outputs, and ordinary wallet coin selection can spend them by accident, which pauses the account.</p>}
      <p className="field-note">This estimate is for this setup and publisher; each transaction shows its actual fee before you
        sign. It excludes the reserved identity seed and includes the selected collateral. The wallet needs three separate plain
        ADA outputs: the identity seed, collateral of at least 5 ADA, and setup funding. Funding checks can’t reserve your wallet’s
        outputs or stop ledger parameters changing, so finish one setup at a time per wallet. Keep the backend running until the
        account is created: pre-genesis setup cannot yet resume after a restart, and already paid deposits and fees stay where
        they are. Save your locator once the account exists.</p>
    </section>}
    {plan.publication && <section className="workflow-setup" aria-label="Reference publication capital">
      <h3>This reference script deposit</h3>
      <p><strong>{ada(plan.publication.capital)} ADA</strong> deposit, separate from the network fee. It stays claimable by the
        publisher wallet below.</p>
      <p>Script:</p><code className="workflow-key">{plan.publication.scriptHash}</code>
      <p>Publisher wallet (the only wallet that can claim it):</p><code className="workflow-key">{plan.publication.publisherAddress}</code>
      {plan.publication.hosting === 'vault' && <><p>Held at this separate vault address, out of reach of ordinary wallet spending:</p>
        <code className="workflow-key">{plan.publication.hostingAddress}</code></>}
      <p>Publication grants no account authority. The publisher can later claim this deposit, which removes this copy of the
        script; keep a copy available or republish one before claiming. Network and reference-script fees still apply, and
        real-wallet compatibility is not established.</p>
    </section>}
    {plan.reclamation && <section className="workflow-setup" aria-label="Reference reclamation review">
      <h3>Claim back this deposit</h3>
      <dl className="economics-breakdown">
        <dt>You get back</dt><dd><strong>{ada(plan.reclamation.returnedCapital)} ADA</strong></dd>
      </dl>
      {!!plan.reclamation.returnedAssets?.length && <><p>Native assets also returned to the same publisher wallet:</p><ul>{plan.reclamation.returnedAssets.map(asset => <li key={asset.unit}><code className="workflow-key">{asset.unit}</code> · {asset.quantity} units</li>)}</ul></>}
      <p>Paid to the publisher wallet, which must sign this exact transaction:</p><code className="workflow-key">{plan.reclamation.publisherAddress}</code>
      <p>The network fee below is funded separately from the same wallet. This claim grants no account authority and does not
        return the permanently locked account state reserve.</p>
      <p>{plan.reclamation.activeRequired === false
        ? 'Optional genesis-only copy: claiming it won’t affect the account. Keep a backup of its exact script bytes.'
        : 'This removes an active reference. The account pauses until an identical script is republished. No replacement is created automatically.'}</p>
      <p className="field-note">Deposit output {plan.reclamation.transactionHash}#{plan.reclamation.outputIndex} · script{' '}
        {plan.reclamation.scriptHash} · vault {plan.reclamation.hostingAddress}</p>
    </section>}
    {plan.stateFunding && <section className="workflow-setup" aria-label="Permanent account state funding">
      <h3>Permanent account state funding</h3>
      <dl className="economics-breakdown">
        <dt>Current locked reserve</dt><dd>{ada(plan.stateFunding.previousReserve)} ADA</dd>
        <dt>New locked reserve</dt><dd>{ada(plan.stateFunding.nextReserve)} ADA</dd>
        <dt>Additional sponsor funding</dt><dd>{ada(plan.stateFunding.topUp)} ADA</dd>
      </dl>
      <p>The account’s data grew, so it needs a larger reserve. The fee-paying wallet supplies this top-up, separately from the
        network fee, and it becomes permanently locked: account closure and reserve withdrawal are unsupported. Review this
        amount before signing.</p>
    </section>}
    <section className="workflow-setup" aria-label="Transaction network fee">
      <strong>{plan.fee ? `This transaction’s network fee: ${ada(plan.fee)} ADA` : 'Network fee calculated after account approvals'}</strong>
      <p>{plan.fee ? 'Review this fee before signing. Deposits and reserves are separate from this charge.' : 'You will see the final fee before the fee-paying wallet signs. Account intent approval is separate from funding approval.'}</p>
    </section>
  </>;
}
