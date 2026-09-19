import { ada, type AccountView, type ReferenceView } from './api';

/** Missing genesis-only references do not require paid repair after account creation. */
export function ReferenceAvailability({ references, busy, onRepair, onReclaim }: {
  references: NonNullable<AccountView['references']>; busy: boolean; onRepair: (scriptHash: string) => void; onReclaim: (reference: ReferenceView) => void;
}) {
  return <section className="workflow-setup" aria-label="Reference script availability">
    <h2>Reference script availability</h2>
    <p>Active reference scripts make account operations available. Publishers control their hosted capital, independently of your account keys. Removing an active reference copy may require republication.</p>
    {references.map(reference => <div key={`${reference.scriptHash}:${reference.transactionHash || "missing"}:${reference.outputIndex ?? ""}`} >
      <p><code className="workflow-key">{reference.scriptHash}</code><br />{reference.activeRequired === false
        ? `Optional genesis-only copy · ${reference.available ? 'available' : 'not published'}. Keep its exact script bytes backed up; supported post-genesis operations do not require this reference.`
        : `${reference.available ? reference.hosting === 'legacy' ? 'Available · historical locked reference' : reference.hosting === 'vault' ? 'Available · publisher vault' : 'Available · historical key hosted' : 'Missing · publication required'} · ${reference.requiredFor || 'account operations'}`}</p>
      {reference.hosting === 'vault' && <>
        <p>Vault holding address:</p><code className="workflow-key">{reference.hostingAddress}</code>
        <p>Publisher wallet:</p><code className="workflow-key">{reference.publisherAddress}</code>
        <p>Output: <code className="workflow-key">{reference.transactionHash}#{reference.outputIndex}</code></p>
        {reference.capital && <p>Hosted capital: {ada(reference.capital)} ADA · separate script-address reserve</p>}
        {reference.available && reference.reclaimable === true && <button className="button" disabled={busy} onClick={() => onReclaim(reference)}>Reclaim reference</button>}
      </>}
      {reference.activeRequired !== false && !reference.available && <button className="button" disabled={busy} onClick={() => onRepair(reference.scriptHash)}>Repair reference</button>}
    </div>)}
  </section>;
}
