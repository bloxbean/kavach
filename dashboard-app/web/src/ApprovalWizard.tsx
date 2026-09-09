import { useEffect, useRef, useState } from 'react';
import { Check, Clock3, ArrowRight } from 'lucide-react';
import { type Plan, ada } from './api';
import { CompanionPanel } from './CompanionPanel';
import { approvalPurpose, workflowStage, setupPhase, isFeePayerOnly, transactionRole } from './approvalWorkflow';

type Device = 'wallet' | 'companion';
type Props = {
  plan: Plan; busy: boolean; connected: boolean; notice: string;
  devices: Record<string, Device>;
  onPlan: (plan: Plan) => void;
  onWallet: (authority: string, submitWhenReady: boolean) => Promise<void>;
  onSubmit: () => Promise<void>; onAdvance: () => Promise<void>;
  onRefresh: () => Promise<void>; onOpen: () => Promise<void>;
};
export function ApprovalWizard(p: Props) {
  const { plan, busy } = p;
  const [selected, setSelected] = useState('');
  const [routes, setRoutes] = useState<Record<string, Device>>(() => {
    try {
      const saved = JSON.parse(localStorage.getItem('kavach.signerDevices') || '{}');
      return Object.fromEntries(Object.entries(saved).filter(([key, value]) => /^[0-9a-f]{64}$/i.test(key) && (value === 'wallet' || value === 'companion'))) as Record<string, Device>;
    } catch { return {}; }
  });
  const chooseDevice = (key: string, value: Device) => {
    const next = { ...routes, [key]: value };
    setRoutes(next); localStorage.setItem('kavach.signerDevices', JSON.stringify(next));
  };
  const [autoNext, setAutoNext] = useState(() => localStorage.getItem('kavach.prepareNextStep') !== 'false');
  const advanced = useRef('');
  const nextHeading = useRef<HTMLHeadingElement>(null);
  const proofs = plan.payloads || [];
  const proof = proofs.find(v => `${v.purpose}:${v.id}` === selected) || proofs[0];
  const proofKey = proof ? `${proof.purpose}:${proof.id}` : '';
  const device = proof ? routes[proof.publicKey] || p.devices[proof.publicKey] : undefined;
  const stage = workflowStage(plan);
  const fundingOnly = isFeePayerOnly(plan);
  const authorities = plan.authorityApprovals || [];
  const approvedCount = authorities.filter(a => a.approved).length;
  const approvalGroups = [...new Set(authorities.map(a => a.purpose))];
  useEffect(() => { nextHeading.current?.focus(); }, [plan.id, stage, proofKey]);
  const signerLabel = (hash: string) => {
    const key = plan.signerKeys?.find(k => k.paymentKeyHash === hash);
    const role = transactionRole(plan, hash);
    return key ? `${role} · Key ${key.id}` : role;
  };
  const remaining = plan.requiredSigners.filter(k => !plan.approvals.includes(k));
  useEffect(() => {
    if (autoNext && plan.confirmed && plan.canAdvance && !busy && advanced.current !== plan.id) {
      advanced.current = plan.id;
      void p.onAdvance();
    }
  }, [autoNext, plan.id, plan.confirmed, plan.canAdvance, busy, p.onAdvance]);
  return <section className="approval-wizard" aria-label="Approval workflow">
    {plan.setupTotal && <section className="workflow-setup">
      <div className="workflow-phases">{['Prepare account', 'Enroll keys', 'Activate account'].map((name, i) => <span key={name} aria-current={setupPhase(plan.setupStep || 1) === i ? 'step' : undefined}>{i + 1}. {name}</span>)}</div>
      <p>Transaction {plan.setupStep} of {plan.setupTotal} · {plan.title}</p>
      <details><summary>Why multiple transactions?</summary><p>This development setup publishes six reference scripts, registers two authorization scripts, enrolls your keys and activates the final policy. Script size and deployment dependencies require separate transactions. Preparation needs the fee wallet; enrollment and activation also need your authority keys.</p></details>
      <label className="checkbox-field"><input type="checkbox" checked={autoNext} onChange={e => { setAutoNext(e.target.checked); localStorage.setItem('kavach.prepareNextStep', String(e.target.checked)); }} />Prepare the next step after confirmation</label>
      <p className="field-note">Each new transaction still waits for your approval.</p>
    </section>}
    <ol className="workflow-phases" aria-label="Transaction progress">{['Approve intent', fundingOnly ? 'Fee payer signing' : 'Wallet signatures', 'Confirm', 'Complete'].map((name, i) => <li key={name} aria-current={stage === i ? 'step' : undefined}>{i < stage ? '✓ ' : ''}{name}</li>)}</ol>
    {stage === 0 && <section className="workflow-card" aria-label="What you are approving">
      <h3>Approve → Fund → Confirm</h3>
      <p>Account keys approve this exact request. The fee wallet then signs the transaction, and the change takes effect after network confirmation.</p>
      {!fundingOnly && <p className="field-note">Transaction-based authority keys must also sign the transaction.</p>}
      {plan.title === 'Rotate keys' && <p className="field-note">Administrators approve the update; each key separately confirms its place in the new policy. Your account address stays the same.</p>}
    </section>}
    <details className="workflow-review" ><summary>Review request details · {plan.fee ? `${ada(plan.fee)} ADA fee` : 'fee calculated after approvals'}</summary><pre className="review-text">{plan.review}</pre></details>
    {authorities.length > 0 && <section className="approval-progress" aria-label="Account approval progress">
      <div className="approval-progress-heading">
        <div><h3>Account approvers</h3><p role="status">{approvedCount === authorities.length ? 'All account approvals collected' : `${authorities.length - approvedCount} approval${authorities.length - approvedCount === 1 ? '' : 's'} remaining`}</p></div>
        <span className="approval-count">{approvedCount}<span> / {authorities.length}</span></span>
      </div>
      <progress className="approval-meter" aria-label="Account approvals collected" value={approvedCount} max={authorities.length} />
      {approvalGroups.map(purpose => <div className="approval-group" key={purpose}>
        <h4>{approvalPurpose(purpose)}</h4>
        <ul className="approval-members">{authorities.filter(a => a.purpose === purpose).map(a => {
          const current = !a.approved && stage === 0 && proofKey === `${a.purpose}:${a.id}`;
          const StatusIcon = a.approved ? Check : current ? ArrowRight : Clock3;
          return <li key={`${a.purpose}:${a.id}`} className={`approval-member ${a.approved ? 'is-approved' : current ? 'is-current' : 'is-pending'}`} aria-current={current ? 'step' : undefined}>
            <span className="approval-key-icon" aria-hidden="true"><StatusIcon size={17} /></span>
            <div className="approval-member-label"><strong>Key {a.id}</strong><span>{a.method === 2 ? 'COSE intent' : 'Transaction witness'}</span></div>
            <span className="approval-status">{a.approved ? 'Approved' : current ? 'Sign now' : 'Pending'}</span>
          </li>;
        })}</ul>
      </div>)}
    </section>}
    {plan.feePayer && stage < 2 && <section className="workflow-setup" aria-label="Fee payer">
      <strong>Fee payer · {stage === 0 ? 'signs after account approvals' : 'transaction funding'}</strong>
      <code className="workflow-key">{plan.feePayer.address}</code>
      <p className="field-note">{fundingOnly ? 'Pays fees and provides collateral where required. This signature is separate from account approval.' : 'Funds the transaction. A transaction-based authority key may also serve as fee payer.'}</p>
      <p className="field-note">To use a different sponsor, connect that wallet before preparing a new request.</p>
    </section>}
    {stage === 0 && proof && <>
      <div className="workflow-heading"><h3 ref={nextHeading} tabIndex={-1}>Approve with Key {proof.id}</h3><span>{proofs.length} intent approval{proofs.length === 1 ? '' : 's'} pending</span></div>
      <div className="workflow-queue" aria-label="Pending approvals">{proofs.map(v => <button type="button" disabled={busy} className={proofKey === `${v.purpose}:${v.id}` ? 'selected' : ''} key={`${v.purpose}:${v.id}`} onClick={() => setSelected(`${v.purpose}:${v.id}`)}>Key {v.id} · {approvalPurpose(v.purpose)}</button>)}</div>
      <section className="workflow-card">
        <h4>{approvalPurpose(proof.purpose)}</h4>
        <p>{proof.purpose === 'operation' ? `Key ${proof.id} is required by your current approval rules to authorize this request.` : ['candidate', 'configuration', 'possession'].includes(proof.purpose) ? 'Confirm this key’s place in the new policy. This is separate from approving the update.' : 'Confirm that you control this key and agree to enroll it in this account. Review the request on your signing device before approving.'}</p>
        <code className="workflow-key">{proof.publicKey}</code>
        {device && <p className="workflow-next"><strong>Next: sign on {device === 'companion' ? 'your iPhone in Yano Companion' : 'the Cardano wallet that owns this key'}.</strong></p>}
        <div className="workflow-devices" aria-label="Signing device">
          <button type="button" className="button" disabled={busy} aria-pressed={device === 'wallet'} onClick={() => chooseDevice(proof.publicKey, 'wallet')}>Cardano wallet</button>
          {proof.companionSupported && <button type="button" className="button" disabled={busy} aria-pressed={device === 'companion'} onClick={() => chooseDevice(proof.publicKey, 'companion')}>iPhone Companion</button>}
        </div>
        {!device && <p>Choose the device that owns this public key.</p>}
        {device === 'wallet' && <><p>Select the owning account in Yano. Its address-index search will use this exact key.</p><button className="button primary" disabled={busy || !p.connected} onClick={() => void p.onWallet(proofKey, false)}>Approve Key {proof.id} with wallet</button></>}
        {device === 'companion' && <CompanionPanel key={`${plan.id}:${proofKey}`} plan={plan} onPlan={p.onPlan} disabled={busy} focusedKey={proofKey} />}
      </section>
    </>}
    {stage === 1 && <section className="workflow-card">
      <h3 ref={nextHeading} tabIndex={-1}>{plan.status === 'Ready' ? 'Ready to submit' : fundingOnly ? 'Fee payer: sign the transaction' : 'Collect remaining wallet signatures'}</h3>
      <p>{plan.status === 'Ready' ? 'Account authorization and all required transaction signatures have been verified.' : fundingOnly ? 'Account intent approvals are complete. Switch to the fee-paying wallet shown above, review its funding and fees, then sign.' : `${remaining.length} wallet signature${remaining.length === 1 ? '' : 's'} remaining. Select a wallet account that owns a pending key. Yano can sign multiple keys from that account in one approval.`}</p>
      {remaining.length > 0 && <div className="workflow-pending">{remaining.map(k => <p key={k}><strong>{signerLabel(k)}</strong> · Sign in your Cardano wallet<br /><code>{k}</code></p>)}</div>}
      <details><summary>All transaction signers</summary>{plan.requiredSigners.map(k => <p key={k}>{plan.approvals.includes(k) ? '✓ Signed' : 'Pending'} · <code>{k}</code></p>)}</details>
      {plan.status !== 'Ready' && <p>The button submits only when every required signature is present. Otherwise, switch wallet accounts and repeat.</p>}
      {plan.status === 'Ready' ? <button className="button primary" disabled={busy} onClick={() => void p.onSubmit()}>Submit transaction</button> : <button className="button primary" disabled={busy || !p.connected} onClick={() => void p.onWallet('', true)}>{fundingOnly ? 'Fee payer: sign & submit' : 'Sign & submit when complete'}</button>}
    </section>}
    {stage === 2 && <section className="workflow-card" role="status"><h3>Waiting for ledger confirmation</h3><p>Your transaction was submitted. No more signatures are needed for this step.</p></section>}
    {stage === 3 && <section className="workflow-card"><h3>{plan.canAdvance ? 'Transaction confirmed' : 'Request complete'}</h3>{plan.canAdvance ? <button className="button primary" disabled={busy} onClick={() => void p.onAdvance()}>{busy ? 'Preparing next step…' : 'Continue setup'}</button> : plan.locator && <button className="button primary" disabled={busy} onClick={() => void p.onOpen()}>Open account</button>}</section>}
    {!p.connected && stage < 2 && <p className="notice">Connect your Cardano wallet to provide transaction signatures. You can close this request and reopen it from Activity after connecting.</p>}
    {p.notice && <div className="notice" role="alert">{p.notice}</div>}
    {busy && <p role="status">Working…</p>}
    <details className="workflow-tools"><summary>Request tools</summary><p>Request ID: <code>{plan.id}</code></p>{plan.txHash && <p>Transaction: <code>{plan.txHash}</code></p>}<button className="text-button" disabled={busy} onClick={() => void p.onRefresh()}>Refresh status</button></details>
  </section>;
}
