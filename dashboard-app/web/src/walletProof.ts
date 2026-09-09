import type { Plan } from "./api.ts";

/** Address enumeration is a hint, not proof that an HD wallet cannot sign a key. */
export function walletProof(plan: Plan, addresses: string[], selected: string) {
  const pending = plan.payloads || [];
  const proof = selected
    ? pending.find(p => `${p.purpose}:${p.id}` === selected)
    : pending.find(p => addresses.some(a => a.slice(2, 58).toLowerCase() === p.paymentKeyHash));
  if (!proof) throw new Error("Choose the pending key under ‘Wallet authority’, then sign with the Yano account that owns it. The wallet will search its receive and change address indexes.");
  if (!/^[0-9a-f]{56}$/.test(proof.paymentKeyHash)) throw new Error("Invalid requested payment key hash");
  // Enterprise testnet address commits to the exact requested payment credential.
  // The wallet must resolve ownership and ask for consent; backend verifies evidence.
  return { proof, address: addresses.find(a => a.slice(2, 58).toLowerCase() === proof.paymentKeyHash) || `60${proof.paymentKeyHash}` };
}
