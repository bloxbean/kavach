import { ada, type AccountDeposits, type ReferenceView } from './api';

type Status = { tone: 'claimable' | 'locked' | 'held' | 'missing' | 'optional'; text: string };

/** Plain-language status for one reference copy; only verified vault outputs are claimable. */
export function referenceStatus(reference: ReferenceView): Status {
  if (!reference.available)
    return reference.activeRequired === false
      ? { tone: 'optional', text: 'Not needed after account creation' }
      : { tone: 'missing', text: 'Missing · account paused until republished' };
  if (reference.hosting === 'vault')
    return reference.reclaimable === true
      ? { tone: 'claimable', text: 'Claimable by the publisher wallet' }
      : { tone: 'held', text: 'Held in a vault for a different account' };
  if (reference.hosting === 'legacy') return { tone: 'locked', text: 'Locked permanently (older setup)' };
  return { tone: 'held', text: 'At the publisher’s wallet address (older setup)' };
}

const total = (values: (string | undefined)[]) => values.reduce((sum, value) => sum + BigInt(value || '0'), 0n);
const shortHash = (hash: string) => hash.length > 16 ? `${hash.slice(0, 8)}…${hash.slice(-6)}` : hash;
const neededFor = (reference: ReferenceView) =>
  reference.activeRequired === false ? 'Account creation only' : reference.requiredFor === 'state changes'
    ? 'Key, policy and recovery changes' : reference.requiredFor === 'transfers' ? 'Sending funds' : 'Sending funds and account changes';

/**
 * Explains every deposit held for an account: what the publisher wallet can claim back, when,
 * and what stays locked. The figures are display-only; each claim is rebuilt and re-verified by
 * the backend, which requires the vault's publisher signature.
 */
export function DepositsPanel({ references, deposits, busy, onRepair, onReclaim }: {
  references: ReferenceView[]; deposits?: AccountDeposits; busy: boolean;
  onRepair: (scriptHash: string) => void; onReclaim: (reference: ReferenceView) => void;
}) {
  const claimable = references.filter(r => r.available && r.hosting === 'vault' && r.reclaimable === true);
  const oldLocked = references.filter(r => r.available && r.hosting === 'legacy');
  const keyHeld = references.filter(r => r.available && r.hosting === 'publisher');
  const registration = deposits ? BigInt(deposits.registrationDeposit) * BigInt(deposits.registeredScripts) : 0n;
  const locked = total(oldLocked.map(r => r.capital)) + registration + BigInt(deposits?.stateReserve || '0');
  const publishers = [...new Set(claimable.map(r => r.publisherAddress).filter((a): a is string => !!a))];
  const paused = references.some(r => !r.available && r.activeRequired !== false);

  return <section className="deposits" aria-label="Deposits held for this account">
    <div className="section-heading"><h2>Deposits</h2><span>ADA set aside on the ledger for this account</span></div>
    <p className="deposits-intro">Running a Kavach account needs some ADA set aside on the ledger. It is not part of your
      spendable balance. Some of it can be claimed back later; the rest is locked for good.</p>
    {paused && <div className="notice" role="alert">A script this account needs is missing, so the account can’t make
      transactions until it is republished. Use Republish below; your address, funds and keys stay the same.</div>}

    <div className="deposit-tiles">
      <div className="deposit-tile claimable">
        <span>Claimable</span><strong>{ada(total(claimable.map(r => r.capital)).toString())} ADA</strong>
        <p>Reference script deposits. Only the publisher wallet can claim them back.</p>
      </div>
      <div className="deposit-tile locked">
        <span>Locked permanently</span><strong>{deposits ? `${ada(locked.toString())} ADA` : 'Unknown'}</strong>
        <p>Account state reserve and registration deposits{oldLocked.length ? ', plus older reference copies' : ''}. These can’t be withdrawn.</p>
      </div>
      {keyHeld.length > 0 && <div className="deposit-tile held">
        <span>At the publisher’s wallet address</span><strong>{ada(total(keyHeld.map(r => r.capital)).toString())} ADA</strong>
        <p>Older setup. The publisher can spend these with a wallet that supports reference-script outputs; this dashboard can’t claim them.</p>
      </div>}
    </div>

    <div className="deposit-explain">
      <div>
        <h3>Who can claim</h3>
        {publishers.length ? <>
          <p>Only the wallet that paid to publish these scripts{publishers.length > 1 ? ' (each deposit shows its own wallet)' : ''}:</p>
          {publishers.map(address => <code key={address} className="workflow-key">{address}</code>)}
        </> : <p>Only the wallet that paid to publish a script can claim its deposit. No claimable deposit was found for this account.</p>}
        <p>Your account keys, co-signers and recovery guardians can’t claim these deposits, and claiming never moves the ADA or
          tokens in your account. If the publisher wallet’s key is lost, its deposits can’t be claimed: recovering your Kavach
          account doesn’t restore them.</p>
      </div>
      <div>
        <h3>When</h3>
        <p>Any time. There is no waiting period.</p>
        <p>Each deposit keeps one script on the ledger that your account uses. Claim a script the account still needs and the
          account pauses until that script is published again. Anyone can republish it, and your address, funds and keys stay
          the same. The network fee for a claim is paid separately from the publisher wallet.</p>
      </div>
    </div>

    <h3>Reference scripts</h3>
    <div className="deposit-table-wrap">
      <table className="deposit-table">
        <thead><tr><th>Script</th><th>Needed for</th><th>Deposit</th><th>Status</th><th><span className="visually-hidden">Action</span></th></tr></thead>
        <tbody>{references.map(reference => {
          const status = referenceStatus(reference);
          return <tr key={`${reference.scriptHash}:${reference.transactionHash || 'missing'}:${reference.outputIndex ?? ''}`}>
            <td><strong>{reference.label || 'Reference script'}</strong><br />
              <code className="deposit-hash" title={reference.scriptHash}>{shortHash(reference.scriptHash)}</code></td>
            <td data-label="Needed for">{neededFor(reference)}</td>
            <td data-label="Deposit">{reference.capital ? `${ada(reference.capital)} ADA` : '—'}</td>
            <td data-label="Status"><span className={`deposit-status ${status.tone}`}>{status.text}</span>
              {reference.activeRequired === false && <small>Keep a backup of its exact script bytes; claiming it won’t affect the account.</small>}
              {reference.hosting === 'vault' && reference.transactionHash && <small title={reference.hostingAddress}>
                Output {shortHash(reference.transactionHash)}#{reference.outputIndex}</small>}</td>
            <td className="deposit-action">{status.tone === 'claimable'
              ? <button className="button" disabled={busy} onClick={() => onReclaim(reference)}>Claim</button>
              : status.tone === 'missing'
                ? <button className="button" disabled={busy} onClick={() => onRepair(reference.scriptHash)}>Republish</button>
                : null}</td>
          </tr>;
        })}</tbody>
      </table>
    </div>

    <h3>What’s locked permanently</h3>
    {deposits ? <dl className="economics-breakdown deposit-locked">
      <dt><strong>Account state reserve</strong><br />The minimum ADA kept with the account’s state. Kavach can’t close an account
        yet, so this reserve can’t be withdrawn. It can grow when the account’s data grows.</dt>
      <dd>{ada(deposits.stateReserve)} ADA</dd>
      <dt><strong>Stake registration deposits</strong><br />{deposits.registeredScripts} × {ada(deposits.registrationDeposit)} ADA for the
        authorization checkpoint and signing module. Kavach has no way to deregister them, so they can’t be withdrawn. Signing
        modules replaced earlier keep their own deposit too.</dt>
      <dd>{ada(registration.toString())} ADA</dd>
      {oldLocked.length > 0 && <><dt><strong>Older reference copies</strong><br />Published before deposits became claimable, to an
        address nothing can spend from.</dt><dd>{ada(total(oldLocked.map(r => r.capital)).toString())} ADA</dd></>}
    </dl> : <p>Refresh the account to load its locked reserve amounts.</p>}
  </section>;
}
