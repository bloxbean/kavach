import { test } from "node:test";
import assert from "node:assert/strict";
import { savedAccounts, rememberAccount, forgetAccount, legacyCandidates } from "../src/accounts.ts";

class MemoryStorage {
  values = new Map();
  get length() { return this.values.size; }
  key(i) { return [...this.values.keys()][i] ?? null; }
  getItem(key) { return this.values.get(key) ?? null; }
  setItem(key, value) { this.values.set(key, String(value)); }
  removeItem(key) { this.values.delete(key); }
}
const a = { locator: "kavach-locator-v1:a", accountId: "a" };
const b = { locator: "kavach-locator-v1:b", accountId: "b" };

test("migrates the old active locator and retains both accounts across reload", () => {
  const storage = new MemoryStorage();
  storage.setItem("kavach.locator", a.locator);
  rememberAccount(storage, b);
  storage.setItem("kavach.locator", b.locator);
  assert.deepEqual(savedAccounts(storage).map(v => v.locator), [a.locator, b.locator]);
  rememberAccount(storage, a);
  rememberAccount(storage, a);
  assert.deepEqual(savedAccounts(storage), [a, b]);
});

test("forgetting inactive entries preserves selection and excludes legacy rediscovery", () => {
  const storage = new MemoryStorage();
  rememberAccount(storage, a);
  rememberAccount(storage, b);
  storage.setItem("kavach.locator", b.locator);
  storage.setItem("kavach.name." + a.locator, "First");
  forgetAccount(storage, a.locator);
  assert.equal(storage.getItem("kavach.locator"), b.locator);
  assert.deepEqual(savedAccounts(storage), [b]);
  assert.deepEqual(legacyCandidates(storage), []);
  // Explicit restoration allows adding a forgotten account again.
  rememberAccount(storage, a);
  assert.deepEqual(savedAccounts(storage), [b, a]);
});

test("forgetting selected and last accounts selects a remaining entry then clears selection", () => {
  const storage = new MemoryStorage();
  rememberAccount(storage, a);
  rememberAccount(storage, b);
  storage.setItem("kavach.locator", a.locator);
  forgetAccount(storage, a.locator);
  assert.equal(storage.getItem("kavach.locator"), b.locator);
  forgetAccount(storage, b.locator);
  assert.equal(storage.getItem("kavach.locator"), null);
  assert.deepEqual(savedAccounts(storage), []);
});

test("legacy candidates exclude known accounts and tolerate malformed registry storage", () => {
  const storage = new MemoryStorage();
  storage.setItem("kavach.accounts", "bad json");
  storage.setItem("kavach.name." + a.locator, "First");
  storage.setItem("kavach.name." + b.locator, "Second");
  assert.deepEqual(legacyCandidates(storage), [a.locator, b.locator]);
  rememberAccount(storage, a);
  assert.deepEqual(legacyCandidates(storage), [b.locator]);
  storage.setItem("kavach.accounts", JSON.stringify([null, 2, {locator: 5}, a, a]));
  assert.deepEqual(savedAccounts(storage), [a]);
});
