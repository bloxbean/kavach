import { useEffect, useState } from "react";
import { ApprovalScanner } from "./ApprovalScanner";
import { parsePhoneApproval } from "./approvalScan";
import QRCode from "qrcode";
import { api, type Plan } from "./api";

function QR({ text }: { text: string }) {
  const [image, setImage] = useState("");
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    setImage(""); setError("");
    QRCode.toDataURL(text, { width: 520, margin: 4, errorCorrectionLevel: "L" })
      .then(url => { if (active) setImage(url); })
      .catch(() => { if (active) setError("QR is too large. Copy the request and use Import on your phone."); });
    return () => { active = false; };
  }, [text]);
  return <>{image && <img className="companion-qr" src={image} alt="Scan with Yano Companion on your iPhone" />}{error && <p role="alert">{error}</p>}</>;
}

type Exported = { qr: string; expiresAt: number };
export function CompanionPanel({ plan, onPlan, disabled, focusedKey }: { plan: Plan; onPlan: (plan: Plan) => void; disabled: boolean; focusedKey?: string }) {
  const proofs = plan.payloads?.filter(p => p.companionSupported) || [];
  const [pairing, setPairing] = useState<{ qr: string; fingerprint: string }>();
  const [selected, setSelected] = useState(focusedKey || "");
  const [request, setRequest] = useState<Exported>();
  const [approval, setApproval] = useState("");
  const [scanning, setScanning] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const proof = proofs.find(p => `${p.purpose}:${p.id}` === selected);
  useEffect(() => { setScanning(false); }, [proof?.id, proof?.purpose, disabled]);
  useEffect(() => {
    if (!focusedKey || !proof) return;
    let active = true;
    setBusy(true);
    api<Exported>(`/plans/${plan.id}/companion-request`, { credentialId: proof.id, purpose: proof.purpose })
      .then(result => { if (active) setRequest(result); })
      .catch(e => { if (active) setError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (active) setBusy(false); });
    return () => { active = false; };
  }, [focusedKey, plan.id]);
  async function run(action: () => Promise<void>) {
    setBusy(true); setError("");
    try { await action(); } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }
  async function addApproval(text: string) {
    if (!proof || !request) throw new Error("Select a phone credential and export its request first.");
    const response = parsePhoneApproval(text, proof);
    const updated = await api<Plan>(`/plans/${plan.id}/companion-proof`, { credentialId: proof.id, purpose: proof.purpose, response });
    setRequest(undefined); setApproval(""); setSelected(""); onPlan(updated);
  }
  if (!proofs.length || plan.transaction) return null;
  return <details open={focusedKey ? true : undefined} className="companion-panel" onToggle={e => { if (!e.currentTarget.open) setScanning(false); }}>
    <summary>Approve with Yano Companion · iPhone</summary>
    <p>{focusedKey ? "Scan this request in Companion, approve on your phone, then scan its approval QR here." : "Use the phone whose public key is enrolled below. Pair this dashboard once, then scan a request, review it on your phone and show its approval QR to your computer’s camera."}</p>
    <details><summary>Pair a new phone</summary><button className="button" disabled={busy || disabled || scanning} onClick={() => run(async () => setPairing(await api("/companion/pairing")))}>Show dashboard pairing QR</button>
    {pairing && <section>
      <QR text={pairing.qr} />
      <strong>Compare this full fingerprint on both devices before pairing</strong>
      <code>{pairing.fingerprint}</code>
      <button className="text-button" onClick={() => setPairing(undefined)}>Hide pairing QR</button>
    </section>}</details>
    {!focusedKey && <label>Phone credential
      <select value={selected} disabled={busy || disabled || scanning} onChange={e => { setSelected(e.target.value); setRequest(undefined); setApproval(""); setError(""); }}>
        <option value="">Choose the key matching your phone</option>
        {proofs.map(p => <option key={`${p.purpose}:${p.id}`} value={`${p.purpose}:${p.id}`}>{p.purpose} · Key {p.id} · {p.publicKey.slice(0, 12)}…</option>)}
      </select>
    </label>}
    {proof && <>
      {!focusedKey && <code>{proof.publicKey}</code>}
      <button className="button" disabled={busy || disabled || scanning} onClick={() => run(async () => {
        const result = await api<Exported>(`/plans/${plan.id}/companion-request`, { credentialId: proof.id, purpose: proof.purpose });
        setRequest(result); setPairing(undefined);
      })}>{request ? "Refresh request QR" : "Show phone approval QR"}</button>
      {request && <section>
        {!scanning && <QR text={request.qr} />}
        <p>Expires {new Date(request.expiresAt).toLocaleTimeString()}. In Companion choose Scan a request. Check the displayed account and policies or payment before approving.</p>
        <div className="workflow-phone-actions"><button className="text-button" onClick={() => run(async () => navigator.clipboard.writeText(request.qr))}>Copy request for phone import</button>
        {!scanning && <button type="button" className="button primary" disabled={busy || disabled} onClick={() => { setError(""); setScanning(true); }}>Scan phone approval QR</button>}</div>
        {scanning && <ApprovalScanner onClose={() => setScanning(false)} onRead={text => {
          setScanning(false); void run(() => addApproval(text));
        }} />}
        {busy && <p role="status">Verifying phone approval…</p>}
        <details><summary>Paste approval JSON instead</summary><label>Approval JSON
          <textarea value={approval} maxLength={8192} onChange={e => setApproval(e.target.value)} placeholder='Paste the phone’s “Copy approval JSON” here' rows={5} spellCheck={false} />
        </label>
        <button className="button" disabled={busy || disabled || scanning || !approval.trim()} onClick={() => run(() => addApproval(approval))}>Verify and add pasted approval</button></details>
        <p>This adds a signature to this request. Submit the transaction after all remaining approvals are collected.</p>
      </section>}
    </>}
    {error && <p role="alert" className="error">{error}</p>}
  </details>;
}

export function CompanionPairing() {
  const [pair, setPair] = useState<{ qr: string; fingerprint: string }>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  return <details className="companion-panel">
    <summary>Set up your iPhone companion</summary>
    <p>Open Yano Companion on your phone. Pair this dashboard by scanning its QR and comparing the full fingerprint. Then copy the phone’s public key into one of the authority lines below and choose COSE.</p>
    <button type="button" className="button" disabled={busy} onClick={async () => {
      setBusy(true); setError("");
      try { setPair(await api("/companion/pairing")); } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
      finally { setBusy(false); }
    }}>Show pairing QR</button>
    {pair && <><QR text={pair.qr} /><strong>Compare fingerprint on both devices</strong><code>{pair.fingerprint}</code></>}
    {error && <p role="alert">{error}</p>}
  </details>;
}
