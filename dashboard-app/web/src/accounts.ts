import type { AccountView } from "./api";

export type SavedAccount = { locator: string; accountId?: string };
const registryKey = "kavach.accounts";
const forgottenKey = "kavach.forgottenAccounts";

function readArray(storage: Storage, key: string): unknown[] {
  try {
    const value = JSON.parse(storage.getItem(key) || "[]");
    return Array.isArray(value) ? value : [];
  } catch { return []; }
}

export function savedAccounts(storage: Storage): SavedAccount[] {
  const entries = readArray(storage, registryKey).filter((value): value is SavedAccount =>
    !!value && typeof value === "object" && "locator" in value &&
    typeof value.locator === "string" && value.locator.startsWith("kavach-locator-v1:") &&
    (!("accountId" in value) || typeof value.accountId === "string"),
  );
  // Migrate the former single-account preference without losing its locator.
  const active = storage.getItem("kavach.locator");
  if (active && !entries.some((entry) => entry.locator === active))
    entries.unshift({ locator: active });
  return [...new Map(entries.map((entry) => [entry.locator, entry])).values()];
}

export function rememberAccount(storage: Storage, account: Pick<AccountView, "locator" | "accountId">): SavedAccount[] {
  const entries = savedAccounts(storage);
  const value = { locator: account.locator, accountId: account.accountId };
  const next = entries.some((entry) => entry.locator === account.locator)
    ? entries.map((entry) => entry.locator === account.locator ? value : entry)
    : [...entries, value];
  storage.setItem(registryKey, JSON.stringify(next));
  storage.setItem(forgottenKey, JSON.stringify(readArray(storage, forgottenKey).filter((v) => v !== account.locator)));
  return next;
}

export function forgetAccount(storage: Storage, locator: string): SavedAccount[] {
  const next = savedAccounts(storage).filter((entry) => entry.locator !== locator);
  storage.setItem(registryKey, JSON.stringify(next));
  storage.setItem(forgottenKey, JSON.stringify([...new Set([...readArray(storage, forgottenKey), locator])]));
  if (storage.getItem("kavach.locator") === locator) {
    if (next[0]) storage.setItem("kavach.locator", next[0].locator);
    else storage.removeItem("kavach.locator");
  }
  return next;
}

// Older versions retained names for creation locators, even when replacing the
// active account. Authenticate these candidates on-chain before listing them.
export function legacyCandidates(storage: Storage): string[] {
  const known = new Set(savedAccounts(storage).map((entry) => entry.locator));
  const forgotten = new Set(readArray(storage, forgottenKey));
  return Array.from({ length: storage.length }, (_, i) => storage.key(i))
    .filter((key): key is string => !!key && key.startsWith("kavach.name.kavach-locator-v1:"))
    .map((key) => key.slice("kavach.name.".length))
    .filter((locator) => !known.has(locator) && !forgotten.has(locator));
}
