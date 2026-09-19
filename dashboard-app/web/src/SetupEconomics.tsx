import { ada, type Plan } from './api';

/** Separates spent fees from hosted capital and collateral before wallet signatures. */
export function SetupEconomics({ plan }: { plan: Plan }) {
  const costs = plan.costs;
  return <>
    {costs && <section className="workflow-setup" aria-label="Complete setup funding estimate">
      <h3>Complete setup funding estimate</h3>
      <p>{costs.setupTransactions} transactions · {costs.referenceCount} reference scripts</p>
      <dl className="economics-breakdown">
        <dt>Publisher-owned reference capital</dt><dd>{ada(costs.referenceCapital)} ADA</dd>
        <dt>Permanently locked account state reserve</dt><dd>{ada(costs.stateReserve)} ADA</dd>
        <dt>Registration reserve · withdrawal unsupported</dt><dd>{ada(costs.registrationReserve)} ADA</dd>
        <dt>Estimated network fee allowance</dt><dd>{ada(costs.feeAllowance)} ADA</dd>
        <dt>Collateral reserve · not a successful transaction charge</dt><dd>{ada(costs.collateralReserve)} ADA</dd>
        <dt>Total funding estimate</dt><dd>{ada(costs.totalFundingEstimate)} ADA</dd>
      </dl>
      <p>These estimates apply to this setup and publisher. Each final transaction shows its actual fee before signing. The funding estimate excludes the reserved identity seed and includes the selected collateral output. Setup needs three separate plain ADA outputs: identity seed, collateral of at least 5 ADA, and setup funding. Funding checks cannot reserve your wallet’s outputs or prevent ledger parameters changing. Finish one setup at a time per fee wallet; concurrent requests can consume its reserved seed or collateral.</p>
      <p>Reference capital belongs to this publisher wallet, separately from Kavach authority and recovery:</p><code className="workflow-key">{costs.publisherAddress}</code>
      {costs.hostingAddress && <><p>Separate vault holding address:</p><code className="workflow-key">{costs.hostingAddress}</code><p>New hosted capital is a separate script-address reserve, unavailable to ordinary wallet coin selection. Explicit Kavach reclamation requires the publisher’s signature; it does not spend account assets.</p></>}
      <p>Removing reference outputs can interrupt account operations until matching scripts are republished. Losing the publisher key can lose this capital even if you recover the Kavach account. Reclamation pays applicable network and reference-script fees. Historical locked references cannot be reclaimed.</p>
      {!costs.hostingAddress && <p>Historical key-address hosting requires a wallet or tool that supports reference outputs. Ordinary wallet coin selection can remove those historical outputs and interrupt operations.</p>}
      <p>Keep the backend running until genesis confirms. Pre-genesis setup cannot yet resume after a backend restart; already published capital and paid fees remain. Save your locator after genesis.</p>
    </section>}
    {plan.publication && <section className="workflow-setup" aria-label="Reference publication capital">
      <h3>This reference publication</h3><p>{ada(plan.publication.capital)} ADA publisher-owned capital, separate from the network fee.</p>
      <code className="workflow-key">{plan.publication.scriptHash}</code>
      <p>Publisher wallet:</p><code className="workflow-key">{plan.publication.publisherAddress}</code>
      {plan.publication.hosting === 'vault' && <><p>Separate vault holding address:</p><code className="workflow-key">{plan.publication.hostingAddress}</code><p>Capital is isolated from ordinary wallet coin selection. Use explicit Kavach reclamation with the publisher wallet to remove this output.</p></>}
      <p>The publisher can intentionally remove this script copy. Publication grants no account authority. Keep an active reference available or publish an identical replacement before reclaiming capital. Applicable network and reference-script fees still apply; real-wallet compatibility is not established.</p>
    </section>}
    {plan.reclamation && <section className="workflow-setup" aria-label="Reference reclamation review">
      <h3>Reclaim this reference output</h3>
      <p>Selected output: <code className="workflow-key">{plan.reclamation.transactionHash}#{plan.reclamation.outputIndex}</code></p>
      <p>Hosted script: <code className="workflow-key">{plan.reclamation.scriptHash}</code></p>
      <p>Vault holding address:</p><code className="workflow-key">{plan.reclamation.hostingAddress}</code>
      <p>Return destination · publisher wallet:</p><code className="workflow-key">{plan.reclamation.publisherAddress}</code>
      <p><strong>Returned ADA capital: {ada(plan.reclamation.returnedCapital)} ADA</strong></p>
      {!!plan.reclamation.returnedAssets?.length && <><p>Native assets also returned to the same publisher:</p><ul>{plan.reclamation.returnedAssets.map(asset => <li key={asset.unit}><code className="workflow-key">{asset.unit}</code> · {asset.quantity} units</li>)}</ul></>}
      <p>The network fee below is funded separately. The publisher wallet must sign this exact transaction. Reclamation grants no account authority and does not return the permanently locked account state reserve.</p>
      <p>{plan.reclamation.activeRequired === false ? 'Optional genesis-only copy: keep exact script bytes backed up. Supported post-genesis operations do not require this reference.' : 'This removes an active reference. Account operations may stop until an identical script is republished. No replacement is created automatically.'}</p>
    </section>}
    {plan.stateFunding && <section className="workflow-setup" aria-label="Permanent account state funding">
      <h3>Permanent account state funding</h3>
      <dl className="economics-breakdown">
        <dt>Current locked reserve</dt><dd>{ada(plan.stateFunding.previousReserve)} ADA</dd>
        <dt>New locked reserve</dt><dd>{ada(plan.stateFunding.nextReserve)} ADA</dd>
        <dt>Additional sponsor funding</dt><dd>{ada(plan.stateFunding.topUp)} ADA</dd>
      </dl>
      <p>The fee-paying wallet supplies this top-up, separately from the network fee. Any increase becomes permanently locked in account state; account closure and reserve withdrawal are unsupported. Review this amount before signing.</p>
    </section>}
    <section className="workflow-setup" aria-label="Transaction network fee">
      <strong>{plan.fee ? `This transaction’s network fee: ${ada(plan.fee)} ADA` : 'Network fee calculated after account approvals'}</strong>
      <p>{plan.fee ? 'Review this fee before signing the final transaction. Capital and reserves are separate from this charge.' : 'You will see the final fee before the fee-paying wallet signs. Account intent approval is separate from funding approval.'}</p>
    </section>
  </>;
}
