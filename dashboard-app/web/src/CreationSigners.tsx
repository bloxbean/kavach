export type CreationSigner = { publicKey: string; source: "wallet" | "companion"; method: "1" | "2" };
export const emptyCreationSigners = (): CreationSigner[] => [0, 1, 2].map(() => ({ publicKey: "", source: "wallet", method: "2" }));

export function CreationSigners({ signers, onChange, onRemove, assigned }: { signers: CreationSigner[]; onChange: (signers: CreationSigner[]) => void; onRemove: (id: number) => void; assigned: number[] }) {
  const roles = ["Everyday signer", "Guardian", "Defensive backup"];
  const update = (id: number, patch: Partial<CreationSigner>) => onChange(signers.map((key, i) => i === id ? { ...key, ...patch } : key));
  return <section className="creation-signers" aria-labelledby="creation-signers-title">
    <div className="creation-section-heading"><span>02</span><div><h3 id="creation-signers-title">Choose your signers</h3><p>Account keys approve intents with COSE by default, using Yano or your iPhone. The fee payer signs the transaction separately.</p></div></div>
    {signers.map((key, id) => <fieldset className="creation-signer" key={id}>
      <legend>Key {id} · {roles[id] || "Additional signer"}</legend>
      <div className="creation-signer-options">
        <label>Key {id} device<select value={key.source} onChange={e => update(id, e.target.value === "companion" ? { source: "companion", method: "2" } : { source: "wallet" })}>
          <option value="wallet">Yano / Cardano wallet</option><option value="companion">Yano Companion · iPhone</option>
        </select></label>
        <details><summary>Advanced signing method · {key.method === "2" ? "COSE" : "Transaction witness"}</summary><label>Key {id} signing method<select value={key.method} disabled={key.source === "companion"} onChange={e => update(id, { method: e.target.value as "1" | "2" })}>
          <option value="2">Intent signature · COSE (default)</option><option value="1">Transaction witness · CIP-30 (advanced)</option>
        </select></label></details>
      </div>
      <label>Key {id} public key<input required pattern="[0-9a-fA-F]{64}" maxLength={64} autoComplete="off" spellCheck={false} value={key.publicKey} onChange={e => update(id, { publicKey: e.target.value.trim() })} placeholder="Paste the 64-character public key" /></label>
      <p className="field-note">{key.source === "companion" ? "The iPhone signs COSE approvals. Its key cannot sign Cardano transactions." : key.method === "1" ? "This wallet must provide a transaction signature for this key, including during creation." : "This wallet approves the exact intent using signData (COSE)."}</p>
      <button type="button" disabled={signers.length <= 3 || assigned.includes(id)} onClick={() => onRemove(id)}>Remove signer {id}</button>
      <p className="field-note">{assigned.includes(id) ? "To remove this signer, first clear its assignments in the authority policies below." : "This signer has no authority policy assignments."}</p>
    </fieldset>)}
    <button type="button" disabled={signers.length >= 8} onClick={() => onChange([...signers, { publicKey: "", source: "wallet", method: "2" }])}>Add signer</button>
    <p className="field-note">{signers.length} of 8 signer slots used. This setup requires at least three signers. All registered keys prove possession during setup. Each authority policy can contain up to eight keys. Transaction signatures, including the fee wallet, are limited to 16. Paste public keys only—never private keys or seed phrases.</p>
  </section>;
}
