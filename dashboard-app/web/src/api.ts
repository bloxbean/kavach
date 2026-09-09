export type AccountView = {
  locator: string;
  address: string;
  accountId: string;
  mode: string;
  version: string;
  balance: string;
  signingMode: number;
  budgetCore?: boolean;
  setupPending?: boolean;
  budget?: { enabled: boolean; period?: "daily" | "weekly"; limit?: string; spent?: string; remaining?: string; resetsAt?: string; counter?: string };
  keys: { id: number; publicKey: string; method?: number }[];
  smallPaymentLimit?: string;
  smallSpend?: { threshold: number; members: number[] };
  policies: { role: string; threshold: number; members: number[] }[];
  recovery?: {
    executeAfter: string;
    sequence: string;
    targetKeys: { id: number; publicKey: string }[];
  };
  assets: { unit: string; quantity: string }[];
};
export type Plan = {
  id: string;
  title: string;
  review: string;
  transaction?: string;
  fee?: string;
  requiredSigners: string[];
  feePayer?: { address: string; paymentKeyHash: string };
  transactionAuthoritySigners?: string[];
  authorityApprovals?: { id: number; purpose: string; publicKey: string; paymentKeyHash: string; method: number; approved: boolean }[];
  signerKeys?: { id: number; publicKey: string; paymentKeyHash: string }[];
  approvals: string[];
  payloads?: {
    id: number;
    payload: string;
    publicKey: string;
    paymentKeyHash: string;
    purpose: string;
    companionSupported?: boolean;
  }[];
  status: string;
  txHash?: string;
  confirmed?: boolean;
  canAdvance?: boolean;
  setupStep?: number;
  setupTotal?: number;
  locator?: string;
};
export async function api<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch("/api" + path, {
    method: body === undefined ? "GET" : "POST",
    headers: { "Content-Type": "application/json" },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const text = await response.text();
  let data;
  try { data = JSON.parse(text); }
  catch {
    throw new Error(response.status >= 500
      ? "The Kavach backend is unavailable. Your form entries are preserved. Check the backend and try again."
      : "The backend returned an unreadable response. Refresh request status before retrying a submission.");
  }
  if (!response.ok)
    throw new Error(data?.error || `Request failed (${response.status})`);
  return data;
}
export interface WalletApi {
  getNetworkId(): Promise<number>;
  getChangeAddress(): Promise<string>;
  getUsedAddresses(): Promise<string[]>;
  getUtxos(): Promise<string[] | null>;
  signTx(tx: string, partial: boolean): Promise<string>;
  signData(
    address: string,
    payload: string,
  ): Promise<{ signature: string; key: string }>;
}
declare global {
  interface Window {
    cardano?: Record<string, { enable(): Promise<WalletApi> }>;
  }
}
export const short = (value: string, size = 8) =>
  value.length > size * 2 + 3
    ? `${value.slice(0, size)}…${value.slice(-size)}`
    : value;
export const ada = (lovelace: string) => {
  const amount = BigInt(lovelace);
  return `${(amount / 1000000n).toLocaleString()}.${(amount % 1000000n).toString().padStart(6, "0").replace(/0+$/, "") || "00"}`;
};
