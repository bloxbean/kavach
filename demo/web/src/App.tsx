import { ApprovalWizard } from "./ApprovalWizard";
import { policiesAfterSignerRemoval } from "./signerPolicies";
import { CreationSigners, emptyCreationSigners } from "./CreationSigners";
import { walletProof } from "./walletProof";
import { CompanionPairing } from "./CompanionPanel";
import { useEffect, useRef, useState } from "react";
import { savedAccounts, rememberAccount, forgetAccount, legacyCandidates } from "./accounts";
import { useCardano } from "@cardano-foundation/cardano-connect-with-wallet";
import { NetworkType } from "@cardano-foundation/cardano-connect-with-wallet-core";
import {
  ArrowDownLeft,
  ArrowUpRight,
  ArrowRight,
  Check,
  ChevronDown,
  Copy,
  Fingerprint,
  KeyRound,
  Layers3,
  LayoutDashboard,
  LifeBuoy,
  LockKeyhole,
  Plus,
  RefreshCw,
  Settings2,
  Shield,
  ShieldCheck,
  Snowflake,
  Sparkles,
  Unplug,
  Wallet,
  X,
  LoaderCircle,
  ExternalLink,
  CheckCircle2,
  Clock3,
} from "lucide-react";
import {
  api,
  ada,
  short,
  type AccountView,
  type Plan,
  type WalletApi,
} from "./api";

type Page = "Overview" | "Activity" | "Security" | "Recovery";
type Action =
  | "Create account"
  | "Finish account setup"
  | "Restore account"
  | "Send assets"
  | "Receive"
  | "Rotate keys"
  | "Replace module"
  | "Freeze account"
  | "Unfreeze account"
  | "Start recovery"
  | "Cancel recovery"
  | "Complete recovery"
  | "Connect wallet";
const pages = [
  { label: "Overview" as Page, Icon: LayoutDashboard },
  { label: "Activity" as Page, Icon: Layers3 },
  { label: "Security" as Page, Icon: ShieldCheck },
  { label: "Recovery" as Page, Icon: LifeBuoy },
];

type Policy = { role: string; threshold: number; members: number[] };
const defaultPolicies: Policy[] = [
  { role: "Spend", threshold: 1, members: [0] },
  { role: "Admin", threshold: 2, members: [0, 1] },
  { role: "Freeze", threshold: 1, members: [1] },
  { role: "Unfreeze", threshold: 1, members: [2] },
  { role: "Recovery", threshold: 1, members: [1] },
  { role: "Cancel", threshold: 1, members: [2] },
];
function PolicyEditor({
  policies,
  onChange,
  signerCount = 3,
}: {
  signerCount?: number;
  policies: Policy[];
  onChange: (policies: Policy[]) => void;
}) {
  return (
    <details className="policy-editor">
      <summary>Customize the six authority policies</summary>
      <p className="field-note">
        Key numbers correspond to the registered signers above. Select up to eight per policy. Recovery and
        defensive policies must remain independent.
      </p>
      {policies.map((policy, index) => (
        <fieldset key={policy.role}>
          <legend>{policy.role}</legend>
          <div className="policy-members">
            {Array.from({ length: signerCount }, (_, id) => id).map((id) => (
              <label key={id}>
                <input
                  type="checkbox"
                  checked={policy.members.includes(id)}
                  disabled={!policy.members.includes(id) && policy.members.length >= 8}
                  onChange={(e) =>
                    onChange(
                      policies.map((p, i) =>
                        i === index
                          ? {
                              ...p,
                              members: e.target.checked
                                ? [...p.members, id].sort((a, b) => a - b)
                                : p.members.filter((m) => m !== id),
                            }
                          : p,
                      ),
                    )
                  }
                />
                Key {id}
              </label>
            ))}
          </div>
          <label>
            Required approvals
            <input
              type="number"
              min="1"
              max={Math.max(1, policy.members.length)}
              value={policy.threshold}
              onChange={(e) =>
                onChange(
                  policies.map((p, i) =>
                    i === index
                      ? { ...p, threshold: Number(e.target.value) }
                      : p,
                  ),
                )
              }
            />
          </label>
        </fieldset>
      ))}
    </details>
  );
}

export default function App() {
  // CF Connect defaults to Mainnet unless the network restriction is explicit.
  const connector = useCardano({ limitNetwork: NetworkType.TESTNET });
  const [creationSigners, setCreationSigners] = useState(emptyCreationSigners);
  const [policies, setPolicies] = useState<Policy[]>(defaultPolicies);
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const [page, setPage] = useState<Page>("Overview");
  useEffect(() => {
    window.scrollTo({ top: 0, behavior: "instant" });
  }, [page]);
  const [account, setAccount] = useState<AccountView | null>(null);
  const [savedLocator, setSavedLocator] = useState(
    () => localStorage.getItem("kavach.locator") || "",
  );
  const [accountError, setAccountError] = useState("");
  const [accounts, setAccounts] = useState(() => savedAccounts(localStorage));
  const [workspaceOpen, setWorkspaceOpen] = useState(false);
  const [pendingCreation, setPendingCreation] = useState(
    () => localStorage.getItem("kavach.pendingCreation") || "",
  );
  const [modal, setModal] = useState<Action | null>(null);
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [verifiedKey, setVerifiedKey] = useState<{
    publicKey: string;
    walletName: string;
  } | null>(null);
  const [status, setStatus] = useState<{
    online: boolean;
    network: string;
    signing: string[];
  } | null>(null);
  const [plans, setPlans] = useState<Plan[]>([]);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [form, setForm] = useState({
    locator: savedLocator,
    recipient: "",
    amount: "",
    asset: "",
    quantity: "",
    whole: false,
    consolidate: false,
    name: "My Kavach",
    mode: "1",
    keys: "",
    target: "",
    planId: "",
    approvers: "",
    smallPaymentAda: "10",
    smallThreshold: "1",
    smallMembers: "0",
    coseIds: "1",
    budgetCore: false,
    budgetEnabled: false,
    budgetPeriod: "daily",
    budgetAda: "100",
  });
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    let active = true;
    // Recover earlier confirmed creations recorded by the single-account UI.
    // A failed restoration leaves its public backup intact for a future retry.
    void (async () => {
      for (const locator of legacyCandidates(localStorage)) {
        if (!active) return;
        try {
          const result = await api<AccountView>("/accounts/restore", { locator });
          if (active && legacyCandidates(localStorage).includes(locator))
            setAccounts(rememberAccount(localStorage, result));
        } catch { /* An unfinished setup is not a saved active account. */ }
      }
    })();
    return () => { active = false; };
  }, []);
  useEffect(() => {
    if (!pendingCreation) return;
    let active = true;
    let pending = false;
    // Keep the public locator across reloads even if the submit response is lost
    // or the backend's in-memory request disappears. Only authenticated ledger
    // restoration promotes it to the active account; reference publication does not.
    const discover = async () => {
      if (pending) return;
      pending = true;
      try {
        const result = await api<AccountView>("/accounts/restore", { locator: pendingCreation });
        if (!active) return;
        setAccounts(rememberAccount(localStorage, result));
        setAccount(null);
        localStorage.setItem("kavach.locator", pendingCreation);
        localStorage.removeItem("kavach.pendingCreation");
        setSavedLocator(pendingCreation);
        setForm((f) => ({ ...f, locator: pendingCreation }));
        setPendingCreation("");
      } catch {
        // The genesis may still await submission/confirmation. Preserve the
        // candidate without replacing the previously saved account.
      } finally {
        pending = false;
      }
    };
    void discover();
    const timer = window.setInterval(discover, 15000);
    return () => { active = false; window.clearInterval(timer); };
  }, [pendingCreation]);
  useEffect(() => {
    if (!savedLocator) return;
    let active = true;
    let pending = false;
    // Persist only the public locator; always authenticate state and balances
    // through the backend, including deposits submitted outside this demo.
    const refresh = async () => {
      if (pending || document.visibilityState === "hidden") return;
      pending = true;
      try {
        const latest = await api<AccountView>("/accounts/restore", {
          locator: savedLocator,
        });
        if (active) {
          setAccounts(rememberAccount(localStorage, latest));
          setAccount(latest);
          setAccountError("");
        }
      } catch (e) {
        if (active)
          setAccountError(
            `Could not refresh your saved account. Its locator is still saved; retry with Restore account or Refresh account. ${e instanceof Error ? e.message : String(e)}`,
          );
      } finally {
        pending = false;
      }
    };
    void refresh();
    const timer = window.setInterval(refresh, 15000);
    window.addEventListener("focus", refresh);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      active = false;
      window.clearInterval(timer);
      window.removeEventListener("focus", refresh);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, [savedLocator]);
  useEffect(() => {
    api<typeof status>("/status")
      .then(setStatus)
      .catch(() => setStatus(null));
  }, []);
  useEffect(() => {
    if (modal || plan) dialog.current?.showModal();
    else dialog.current?.close();
  }, [modal, plan]);
  useEffect(() => {
    if (!plan?.txHash || plan.confirmed) return;
    let active = true;
    const timer = setInterval(async () => {
      try {
        const latest = await api<Plan>(`/plans/${plan.id}`);
        if (!active) return;
        setPlan(latest);
        setPlans((old) => old.map((p) => (p.id === latest.id ? latest : p)));
        if (latest.confirmed && account) {
          const refreshed = await api<AccountView>("/accounts/restore", {
            locator: account.locator,
          });
          if (active) setAccount(refreshed);
        }
      } catch (e) {
        if (active) setNotice(e instanceof Error ? e.message : String(e));
      }
    }, 2000);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [plan?.id, plan?.txHash, plan?.confirmed, account?.locator]);
  useEffect(() => {
    let ids: unknown;
    try {
      ids = JSON.parse(localStorage.getItem("kavach.requestIds") || "[]");
    } catch {
      return;
    }
    if (!Array.isArray(ids)) return;
    Promise.allSettled(
      ids
        .slice(0, 100)
        .filter((id) => typeof id === "string" && /^[0-9a-f-]{36}$/.test(id))
        .map((id) => api<Plan>(`/plans/${id}`)),
    ).then((results) =>
      setPlans(
        results.flatMap((r) => (r.status === "fulfilled" ? [r.value] : [])),
      ),
    );
  }, []);
  const accountName = account
    ? localStorage.getItem("kavach.name." + account.locator) || "My Kavach"
    : savedLocator ? "Saved account" : "Personal account";
  const pendingSetup = plans.find((p) =>
    !p.confirmed && (p.title.startsWith("Create account") ||
      p.title === "Register authorization checkpoints"),
  );
  const run = async (work: () => Promise<void>) => {
    setBusy(true);
    setNotice("");
    try {
      await work();
    } catch (e) {
      const walletError = e && typeof e === "object" ? e as { info?: unknown; message?: unknown } : null;
      setNotice(e instanceof Error ? e.message
        : typeof walletError?.info === "string" ? walletError.info
        : typeof walletError?.message === "string" ? walletError.message
        : walletError ? "The wallet could not approve this request. Check that the selected authority belongs to this wallet account; use the companion panel for an iPhone key."
        : String(e));
    } finally {
      setBusy(false);
    }
  };
  const wallet = async (): Promise<WalletApi> => {
    if (!connector.enabledWallet)
      throw new Error("Connect a browser wallet to continue.");
    const extension = window.cardano?.[connector.enabledWallet];
    if (!extension)
      throw new Error(
        "This demo currently requires an injected CIP-30 browser wallet.",
      );
    const connected = await extension.enable();
    if ((await connected.getNetworkId()) !== 0)
      throw new Error(
        "Switch your wallet to the configured test network. Mainnet is disabled.",
      );
    return connected;
  };
  const restore = async (locator = form.locator) => {
    const result = await api<AccountView>("/accounts/restore", { locator });
    setAccounts(rememberAccount(localStorage, result));
    setAccount(result);
    setForm((f) => ({ ...f, locator }));
    localStorage.setItem("kavach.locator", locator);
    setSavedLocator(locator);
    setAccountError("");
    setModal(null);
  };
  const selectAccount = (locator: string) => {
    localStorage.setItem("kavach.locator", locator);
    setAccount(null);
    setAccountError("");
    setSavedLocator(locator);
    setForm((f) => ({ ...f, locator }));
    setWorkspaceOpen(false);
  };
  const forgetSavedAccount = (locator: string) => {
    setAccounts(forgetAccount(localStorage, locator));
    if (locator === savedLocator) {
      const next = localStorage.getItem("kavach.locator") || "";
      setAccount(null);
      setSavedLocator(next);
      setForm((f) => ({ ...f, locator: next }));
      setAccountError("");
    }
    setNotice("Account forgotten in this browser. Its on-chain account and funds are unchanged; restore its locator to add it again.");
  };
  const savePlan = (value: Plan) => {
    setNotice("");
    if (value.locator && value.title.startsWith("Create account") &&
        !localStorage.getItem("kavach.name." + value.locator))
      localStorage.setItem(
        "kavach.name." + value.locator,
        form.name.trim() || "My Kavach",
      );
    setPlan(value);
    setPlans((old) => {
      const next = [value, ...old.filter((p) => p.id !== value.id)].slice(
        0,
        100,
      );
      localStorage.setItem(
        "kavach.requestIds",
        JSON.stringify(next.map((p) => p.id)),
      );
      return next;
    });
    setModal(null);
  };
  const prepare = async () => {
    if (modal === "Create account") {
      const unsupported = creationSigners.flatMap((key, id) => {
        const roles = policies.filter(p => ["Freeze", "Unfreeze", "Recovery", "Cancel"].includes(p.role) && p.members.includes(id)).map(p => p.role);
        return key.source === "companion" && roles.length ? [`iPhone Key ${id}: ${roles.join(", ")}`] : [];
      });
      if (unsupported.length) throw new Error(`Change these authority policies before continuing: ${unsupported.join("; ")}. Uncheck the iPhone key in those policies and select a wallet key instead. Companion currently supports spending, enrollment and admin policy changes.`);
    }
    const connected = await wallet();
    const sponsor = await connected.getChangeAddress();
    const value = await api<Plan>("/plans", {
      ...form,
      smallMembers: form.smallMembers.split(",").map(v => v.trim()).filter(Boolean).map(Number),
      coseIds: form.coseIds.split(",").map(v => v.trim()).filter(Boolean).map(Number),
      ...(modal === "Create account" ? {
        mode: "3",
        keys: creationSigners.map(k => k.publicKey).join("\n"),
        coseIds: creationSigners.flatMap((k, id) => k.method === "2" ? [id] : []),
      } : {}),
      policies,
      action: modal,
      locator: account?.locator || form.locator,
      sponsor,
    });
    savePlan(value);
  };
  const approve = async (authority = "", submitWhenReady = false) => {
    if (!plan) return;
    const connected = await wallet();
    if (plan.transaction) {
      // CIP-30 address lists need not expose every key the wallet can sign with.
      // Let the wallet discover its keys and obtain consent; the backend verifies
      // every returned witness against the unchanged body and required signer set.
      if (plan.status === "Ready")
        throw new Error("All required signatures are collected. Submit the transaction next.");
      const witnesses = await connected.signTx(plan.transaction, true);
      const updated = await api<Plan>(`/plans/${plan.id}/witnesses`, { witnesses });
      savePlan(updated);
      if (submitWhenReady && updated.status === "Ready") await submit(updated);
    } else {
      const addresses = [
        ...(await connected.getUsedAddresses()),
        await connected.getChangeAddress(),
      ];
      const { proof: payload, address } = walletProof(plan, addresses, authority);
      const signed = await connected.signData(address, payload.payload);
      savePlan(
        await api<Plan>(`/plans/${plan.id}/proofs`, {
          ...signed,
          credentialId: payload.id,
          purpose: payload.purpose,
        }),
      );
    }
  };
  const exportKey = async () => {
    setVerifiedKey(null);
    const walletName = connector.enabledWallet || "connected wallet";
    const connected = await wallet();
    const address =
      (await connected.getUsedAddresses())[0] ||
      (await connected.getChangeAddress());
    const digest = new Uint8Array(
      await crypto.subtle.digest(
        "SHA-256",
        new TextEncoder().encode("Kavach demo public key export v1"),
      ),
    );
    const signed = await connected.signData(
      address,
      Array.from(digest, (b) => b.toString(16).padStart(2, "0")).join(""),
    );
    const result = await api<{ publicKey: string }>(
      "/wallet/enrollment",
      signed,
    );
    // Wallet approval can leave the document unfocused. Display the verified result;
    // copying is a separate user gesture after returning to this window.
    setVerifiedKey({ publicKey: result.publicKey, walletName });
    setNotice("Public key verified. Copy it below when you return from your wallet.");
  };
  const submit = async (submission = plan) => {
    if (submission) {
      if (submission.title === "Create account · approve genesis" && submission.locator) {
        localStorage.setItem("kavach.pendingCreation", submission.locator);
        setPendingCreation(submission.locator);
      }
      const result = await api<Plan>(`/plans/${submission.id}/submit`, {});
      savePlan(result);
    }
  };
  const copy = async (value: string) => {
    await navigator.clipboard.writeText(value);
    setNotice("Copied to clipboard.");
  };
  const open = (action: Action) => {
    const role = (
      {
        "Send assets": "Spend",
        "Rotate keys": "Admin",
        "Replace module": "Admin",
        "Freeze account": "Freeze",
        "Unfreeze account": "Unfreeze",
        "Start recovery": "Recovery",
        "Cancel recovery": "Cancel",
      } as Record<string, string>
    )[action];
    const policy = account?.policies.find((p) => p.role === role);
    setForm((f) => ({
      ...f,
      approvers: action === "Send assets" && (account?.signingMode ?? 0) >= 3 ? "" : policy
        ? policy.members.slice(0, policy.threshold).join(", ")
        : "",
    }));
    if (action === "Create account") setPolicies(defaultPolicies);
    else if (account) {
      setPolicies(account.policies);
      setForm((f) => ({
        ...f,
        target: account.keys.map((k) => k.publicKey).join("\n"),
        mode: String(account.signingMode),
        budgetCore: !!account.budgetCore,
        budgetEnabled: !!account.budget?.enabled,
        budgetPeriod: account.budget?.period ?? "daily",
        budgetAda: account.budget?.limit && account.budget.limit !== "0" ? `${BigInt(account.budget.limit) / 1000000n}.${(BigInt(account.budget.limit) % 1000000n).toString().padStart(6, "0")}` : "100",
        smallPaymentAda: account.smallPaymentLimit ? `${BigInt(account.smallPaymentLimit) / 1000000n}.${(BigInt(account.smallPaymentLimit) % 1000000n).toString().padStart(6, "0")}` : "10",
        smallThreshold: String(account.smallSpend?.threshold ?? 1),
        smallMembers: (account.smallSpend?.members ?? [0]).join(", "),
        coseIds: account.keys.filter(k => (k.method ?? account.signingMode) === 2).map(k => k.id).join(", "),
      }));
    }
    setPlan(null);
    setNotice("");
    setModal(action);
  };
  const update = (key: keyof typeof form, value: string) =>
    setForm((previous) => ({ ...previous, [key]: value }));
  const isNormal = account?.mode === "Normal" && !account.setupPending;

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a
          className="brand"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            setPage("Overview");
          }}
        >
          <span className="brand-mark">
            <Shield size={23} strokeWidth={2.2} />
          </span>
          kavach<span className="brand-dot">.</span>
        </a>
        <div className="workspace-label">YOUR WORKSPACE</div>
        <button
          className="account-switch"
          aria-expanded={workspaceOpen}
          aria-controls="workspace-accounts"
          onClick={() => setWorkspaceOpen((value) => !value)}
        >
          <span className="account-avatar">K</span>
          <span>
            {accountName}
            <small>
              {account
                ? short(account.accountId, 6)
                : savedLocator ? "Open saved account" : "Create your first account"}
            </small>
          </span>
          <ChevronDown size={14} />
        </button>
        {workspaceOpen && (
          <section id="workspace-accounts" className="workspace-accounts" aria-label="Saved accounts">
            <p>Accounts saved in this browser ({accounts.length})</p>
            {accounts.map((entry) => (
              <div className="workspace-account" key={entry.locator}>
                <button
                  className="workspace-select"
                  aria-current={entry.locator === savedLocator ? "true" : undefined}
                  onClick={() => entry.locator === savedLocator ? setWorkspaceOpen(false) : selectAccount(entry.locator)}
                >
                  <strong>{localStorage.getItem("kavach.name." + entry.locator) || "My Kavach"}</strong>
                  <small>{entry.accountId ? short(entry.accountId, 6) : "Saved account"}</small>
                  {entry.locator === savedLocator && <small>Selected</small>}
                </button>
                <div className="workspace-account-actions">
                  <button title="Copy locator backup" onClick={() => run(() => copy(entry.locator))}><Copy size={13} /><span>Locator</span></button>
                  <button onClick={() => forgetSavedAccount(entry.locator)}>Forget</button>
                </div>
              </div>
            ))}
            <p>Keep a locator backup before forgetting. This removes only the browser entry.</p>
            <button className="text-button" onClick={() => { setWorkspaceOpen(false); open("Create account"); }}>Create account</button>
            <button className="text-button" onClick={() => { setWorkspaceOpen(false); open("Restore account"); }}>Restore account</button>
          </section>
        )}
        <nav aria-label="Main navigation">
          {pages.map(({ label, Icon }) => (
            <button
              key={label}
              className={page === label ? "nav-item active" : "nav-item"}
              onClick={() => setPage(label)}
            >
              <Icon size={19} />
              {label}
              {label === "Recovery" && account?.recovery && (
                <span className="nav-dot" />
              )}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="network-label">
            <span className={status?.online ? "dot green" : "dot amber"} />
            {status?.network || "Backend offline"}
            <span className="tag">TESTNET</span>
          </div>
          <div className="demo-note">
            <ShieldCheck size={16} />
            <span>
              Development preview
              <br />
              <small>Use test assets only</small>
            </span>
          </div>
          <a
            href="https://github.com/bloxbean/kavach"
            target="_blank"
            rel="noreferrer"
          >
            <LifeBuoy size={16} /> Documentation <ArrowUpRight size={14} />
          </a>
        </div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <div className="breadcrumb">
            Workspace <span>/</span> <strong>{page}</strong>
          </div>
          <div className="topbar-right">
            <span className="connection-status">
              <span className={status?.online ? "dot green" : "dot amber"} />
              {status?.online ? "Connected to ledger" : "Waiting for backend"}
            </span>
            <button
              className="button wallet-button"
              onClick={() => open("Connect wallet")}
            >
              <Wallet size={17} />
              {connector.isConnected
                ? short(connector.enabledWallet || "Connected", 10)
                : "Connect wallet"}
              <ChevronDown size={13} />
            </button>
          </div>
        </header>
        <main>
          {account?.setupPending && <section className="creation-summary" role="status"><h2>Finish setting up this account</h2><p>Your keys are enrolled, but spending is blocked until the selected signing policy is activated. Resume the remaining setup steps.</p><button className="button primary" onClick={() => open("Finish account setup")}>Finish account setup</button></section>}
          <div className="page-heading">
            <div className="eyebrow">YOUR ACCOUNT. YOUR RULES.</div>
            <div className="heading-row">
              <div>
                <h1>
                  {page === "Overview"
                    ? "A little more peace of mind."
                    : page === "Security"
                      ? "Protection, on your terms."
                      : page === "Recovery"
                        ? "A way back, built in."
                        : "Your requests, in one place."}
                </h1>
                <p>
                  {page === "Overview"
                    ? "Your assets, protected by an account that can grow with you."
                    : page === "Security"
                      ? "Decide who can act, what they can do, and how many approvals it takes."
                      : page === "Recovery"
                        ? "Independent guardians help you regain access without changing your account."
                        : "Review requests, collect approvals, and follow confirmations on the ledger."}
                </p>
              </div>
              {account && (
                <button
                  className="icon-button"
                  title="Refresh account"
                  onClick={() => run(() => restore(account.locator))}
                >
                  <RefreshCw size={18} />
                </button>
              )}
            </div>
          </div>
          {(notice || accountError) && (
            <div className="notice" role="status">
              {notice || accountError}
              <button
                aria-label="Dismiss notification"
                onClick={() => { setNotice(""); setAccountError(""); }}
              >
                <X size={15} />
              </button>
            </div>
          )}
          {page === "Overview" && (
            <>
              <div className="overview-grid">
                <section className="balance-card">
                  <div className="balance-top">
                    <span>
                      <span className="dot pale" />
                      {account
                        ? account.mode === "Normal"
                          ? account.setupPending ? "Activation pending" : "Account active"
                          : account.mode
                        : savedLocator ? accountError ? "Saved account unavailable" : "Reopening saved account" : "Your smart account"}
                    </span>
                    <ShieldCheck size={25} />
                  </div>
                  <div className="balance-label">Available balance</div>
                  <div className="balance">
                    {account ? ada(account.balance) : "—"} <span>ADA</span>
                  </div>
                  <div className="address-line">
                    {account ? (
                      <>
                        <span>{short(account.address, 16)}</span>
                        <button
                          title="Copy account address"
                          onClick={() => run(() => copy(account.address))}
                        >
                          <Copy size={13} />
                        </button>
                      </>
                    ) : (
                      savedLocator
                        ? "Your public locator is saved on this device."
                        : pendingSetup ? "Account setup is unfinished. Resume your saved request below."
                        : "No account saved for this site address. Restore an existing account or create one."
                    )}
                  </div>
                  <div className="balance-actions">
                    {account ? (
                      <>
                        <button
                          className="button light"
                          disabled={!isNormal}
                          onClick={() => open("Send assets")}
                        >
                          <ArrowUpRight size={17} />
                          Send
                        </button>
                        <button
                          className="button ghost"
                          onClick={() => open("Receive")}
                        >
                          <ArrowDownLeft size={17} />
                          Receive
                        </button>
                      </>
                    ) : (
                      <button
                        className="button light"
                        onClick={() => open(savedLocator ? "Restore account" : "Create account")}
                      >
                        <Plus size={17} />
                        {savedLocator ? "Open saved account" : "Create account"}
                        <ArrowRight size={16} />
                      </button>
                    )}
                  </div>
                  {(pendingSetup || pendingCreation) && (
                    <p className="balance-refresh-note">
                      {pendingSetup
                        ? "New account setup is pending; it is not an active account yet. "
                        : "Waiting for the new account to appear on the ledger. Its locator is saved. "}
                      {pendingSetup && (
                        <button className="text-button" onClick={() => savePlan(pendingSetup)}>
                          Resume account setup
                        </button>
                      )}
                    </p>
                  )}
                  {account && (
                    <p className="balance-refresh-note">
                      {accountError ? "Balance refresh failed. Use Refresh account to retry." : "Balance refreshes every 15 seconds while this page is visible."}
                    </p>
                  )}
                  <div className="balance-orbit orbit-one" />
                  <div className="balance-orbit orbit-two" />
                </section>
                <section className="protection-card">
                  <div className="card-header">
                    <h2>Protection at a glance</h2>
                    <span className="mini-icon">
                      <Shield size={17} />
                    </span>
                  </div>
                  <div className="protection-status">
                    <span className="protection-shield">
                      <ShieldCheck size={26} />
                    </span>
                    <div>
                      <strong>
                        {account
                          ? "Your rules are on-chain"
                          : "Ready when you are"}
                      </strong>
                      <p>
                        {account
                          ? "Enforced by your account’s validators"
                          : "Built for everyday use. Designed for recovery."}
                      </p>
                    </div>
                  </div>
                  <div className="protection-row">
                    <span>
                      <Fingerprint size={16} />
                      Signing method
                    </span>
                    <strong>
                      {account
                        ? account.signingMode === 1
                          ? "Wallet transaction"
                          : account.signingMode >= 3 ? "Mixed signatures + amount tiers" : account.signingMode === 2
                            ? "CIP-8 / COSE"
                            : "Raw Ed25519"
                        : "Your choice"}
                    </strong>
                  </div>
                  <div className="protection-row">
                    <span>
                      <KeyRound size={16} />
                      Registered keys
                    </span>
                    <strong>
                      {account
                        ? `${account.keys.length} authorities`
                        : "Independent roles"}
                    </strong>
                  </div>
                  <div className="protection-row">
                    <span>
                      <Clock3 size={16} />
                      Recovery delay
                    </span>
                    <strong>At least 24 hours</strong>
                  </div>
                  <button
                    className="text-button"
                    onClick={() => setPage("Security")}
                  >
                    Explore account security <ArrowRight size={15} />
                  </button>
                </section>
              </div>
              {!account && (
                <section className="setup-strip">
                  <div className="setup-icon">
                    <Sparkles size={21} />
                  </div>
                  <div>
                    <strong>Same account. Even when life changes.</strong>
                    <p>
                      Replace a key, switch your signer, or recover access. Keep
                      your account.
                    </p>
                  </div>
                  <button
                    className="text-button"
                    onClick={() => open("Restore account")}
                  >
                    Already have an account? <ArrowRight size={16} />
                  </button>
                </section>
              )}
              <div className="section-heading">
                <h2>Make it yours</h2>
                <span>Simple controls. Strong foundations.</span>
              </div>
              <div className="quick-grid">
                {[
                  {
                    Icon: KeyRound,
                    title: "Manage your keys",
                    text: "New device? Update who can sign without moving your assets.",
                    action: () => setPage("Security"),
                    label: "Manage security",
                    color: "sand",
                  },
                  {
                    Icon: LifeBuoy,
                    title: "Keep a way back",
                    text: "Set up independent recovery and keep your account locator safe.",
                    action: () => setPage("Recovery"),
                    label: "Explore recovery",
                    color: "mint",
                  },
                  {
                    Icon: Snowflake,
                    title: "Take a moment",
                    text: "Freeze spending when something feels wrong. Unfreeze with a separate authority.",
                    action: () =>
                      account
                        ? open(isNormal ? "Freeze account" : "Unfreeze account")
                        : setPage("Security"),
                    label: "Account controls",
                    color: "lilac",
                  },
                ].map((item) => (
                  <button
                    key={item.title}
                    className="quick-card"
                    onClick={item.action}
                  >
                    <span className={`quick-icon ${item.color}`}>
                      <item.Icon size={23} />
                    </span>
                    <h3>{item.title}</h3>
                    <p>{item.text}</p>
                    <span className="quick-link">
                      {item.label}
                      <ArrowUpRight size={16} />
                    </span>
                  </button>
                ))}
              </div>
              <section className="activity-card">
                <div className="card-header">
                  <h2>Requests on this device</h2>
                  <button
                    className="text-button"
                    onClick={() => setPage("Activity")}
                  >
                    View all <ArrowRight size={15} />
                  </button>
                </div>
                {plans.length ? (
                  plans.slice(0, 3).map((item) => (
                    <button
                      className="activity-row"
                      key={item.id}
                      onClick={() => savePlan(item)}
                    >
                      <span className="mini-icon">
                        <Layers3 size={18} />
                      </span>
                      <span>
                        <strong>{item.title}</strong>
                        <small>{short(item.id)}</small>
                      </span>
                      <span className="status-pill">
                        {item.confirmed ? "Confirmed" : item.status}
                      </span>
                    </button>
                  ))
                ) : (
                  <div className="empty-activity">
                    <span className="empty-icon">
                      <Layers3 size={23} />
                    </span>
                    <div>
                      <strong>
                        {account
                          ? "No saved requests on this device."
                          : "A fresh start."}
                      </strong>
                      <p>
                        Requests created here will appear in this list. This is
                        not a complete on-chain history.
                      </p>
                    </div>
                  </div>
                )}
              </section>
            </>
          )}
          {page === "Security" && (
            <>
              <div className="section-heading">
                <h2>Who can do what</h2>
                <span>Authority is always explicit</span>
              </div>
              <div className="policy-grid">
                {(
                  account?.policies ||
                  [
                    "Spend",
                    "Admin",
                    "Freeze",
                    "Unfreeze",
                    "Recovery",
                    "Cancel",
                  ].map((role) => ({
                    role,
                    threshold: 0,
                    members: [] as number[],
                  }))
                ).map((policy) => (
                  <section className="policy-card" key={policy.role}>
                    <div className="card-header">
                      <span className="mini-icon">
                        <KeyRound size={18} />
                      </span>
                      <span className="status-pill">
                        {account
                          ? `${policy.threshold} of ${policy.members.length}`
                          : "Not configured"}
                      </span>
                    </div>
                    <h3>{policy.role}</h3>
                    <p>
                      {account
                        ? `Authorized keys: ${policy.members.join(", ")}`
                        : "Set up an account to define this authority."}
                    </p>
                  </section>
                ))}
              </div>
              {(account?.signingMode ?? 0) >= 3 && account?.smallSpend && (
                <section className="action-panel"><div>
                  <h2>Amount-based mixed approval</h2>
                  <p>Up to {ada(account.smallPaymentLimit || "0")} ADA including the maximum account fee: {account.smallSpend.threshold} of keys {account.smallSpend.members.join(", ")}. Larger payments, native tokens and whole transfers use Spend above.</p>
                  <p>{account.keys.map(k => `Key ${k.id}: ${k.method === 2 ? "COSE" : "transaction witness"}`).join(" · ")}</p>
                </div></section>
              )}
              {account && !account.budgetCore && <p className="field-note">Mixed approval can be installed on this account. Shared daily/weekly budgets require a new account created with budget support.</p>}
              {account?.budgetCore && <section className="action-panel"><div>
                <h2>Shared periodic ADA budget</h2>
                {account.budget?.enabled ? <><p>{ada(account.budget.remaining || "0")} ADA remaining of {ada(account.budget.limit || "0")} ADA {account.budget.period}.</p><p>Resets {new Date(Number(account.budget.resetsAt)).toISOString().replace("T", " ").replace(".000Z", " UTC")}. Budgeted spends share one counter.</p></> : <p>Disabled. Enable it through the budget-aware module; ordinary spending remains concurrent.</p>}
              </div><button className="button" disabled={!isNormal} onClick={() => { open(account.signingMode === 4 ? "Rotate keys" : "Replace module"); update("mode", "4"); }}>Configure budget</button></section>}
              <section className="action-panel">
                <div>
                  <h2>Change your keys. Keep your account.</h2>
                  <p>
                    Configuration changes require your existing administration
                    policy and {account && account.signingMode >= 3 ? "possession proofs from every destination key." : "new-key possession."}
                  </p>
                </div>
                <button
                  className="button primary"
                  disabled={!isNormal}
                  onClick={() => open("Rotate keys")}
                >
                  Rotate keys <ArrowUpRight size={16} />
                </button>
                <button
                  className="button"
                  disabled={!isNormal}
                  onClick={() => open("Replace module")}
                >
                  Replace module
                </button>
              </section>
              <section className="action-panel">
                <div>
                  <h2>Emergency controls</h2>
                  <p>
                    Freezing stops future spending once confirmed. It cannot
                    undo a confirmed transfer.
                  </p>
                </div>
                <button
                  className="button"
                  disabled={!account || account.mode === "RecoveryPending"}
                  onClick={() =>
                    open(isNormal ? "Freeze account" : "Unfreeze account")
                  }
                >
                  <Snowflake size={16} />
                  {isNormal ? "Freeze account" : "Unfreeze account"}
                </button>
              </section>
            </>
          )}
          {page === "Recovery" && (
            <>
              <section className="recovery-hero">
                <span className="quick-icon mint">
                  <LifeBuoy size={30} />
                </span>
                <div>
                  <h2>
                    {account?.recovery
                      ? "Recovery is in progress."
                      : "Losing a key doesn’t have to mean losing access."}
                  </h2>
                  <p>
                    {account?.recovery
                      ? `Earliest completion: ${new Date(Number(account.recovery.executeAfter)).toLocaleString()}. Completion still requires target-key approval.`
                      : "Your guardians initiate recovery. A delay gives your independent defensive authority time to cancel an unwanted request."}
                  </p>
                </div>
              </section>
              {account?.recovery && (
                <section className="action-panel">
                  <div>
                    <h2>Committed recovery keys</h2>
                    <p>
                      These are the target keys recorded on-chain. Completion
                      cannot substitute another configuration.
                    </p>
                    {account.recovery.targetKeys.map((key) => (
                      <div className="proof-request" key={key.id}>
                        <strong>Key {key.id}</strong>
                        <code>{key.publicKey}</code>
                      </div>
                    ))}
                  </div>
                </section>
              )}
              <div className="recovery-steps">
                {[
                  "Keep your locator",
                  "Ask your guardians",
                  "Wait for the delay",
                  "Approve with new keys",
                ].map((text, i) => (
                  <div key={text}>
                    <span>{String(i + 1).padStart(2, "0")}</span>
                    <h3>{text}</h3>
                  </div>
                ))}
              </div>
              <section className="action-panel">
                <div>
                  <h2>Account locator</h2>
                  <p>
                    A public backup that identifies your account. Keep it
                    somewhere you can find after device loss.
                  </p>
                </div>
                <button
                  className="button"
                  disabled={!account}
                  onClick={() => account && run(() => copy(account.locator))}
                >
                  <Copy size={16} />
                  Copy locator
                </button>
                <button
                  className="button"
                  onClick={() => open("Restore account")}
                >
                  Restore account
                </button>
              </section>
              <section className="action-panel">
                <div>
                  <h2>Recovery actions</h2>
                  <p>
                    Recovery preserves your address and assets. The existing
                    module stays installed.
                  </p>
                </div>
                {account?.recovery ? (
                  <>
                    <button
                      className="button"
                      onClick={() => open("Cancel recovery")}
                    >
                      Cancel recovery
                    </button>
                    <button
                      className="button primary"
                      disabled={now < Number(account.recovery.executeAfter)}
                      onClick={() => open("Complete recovery")}
                    >
                      Complete recovery
                    </button>
                  </>
                ) : (
                  <button
                    className="button primary"
                    disabled={!account}
                    onClick={() => open("Start recovery")}
                  >
                    Start recovery <ArrowRight size={16} />
                  </button>
                )}
              </section>
            </>
          )}
          {page === "Activity" && (
            <>
              <section className="action-panel">
                <div>
                  <h2>Bring your approval</h2>
                  <p>
                    Open a shared request to review it and add a signature from
                    another authority.
                  </p>
                </div>
                <input
                  aria-label="Shared request ID"
                  placeholder="Request ID"
                  value={form.planId}
                  onChange={(e) => update("planId", e.target.value)}
                />
                <button
                  className="button"
                  onClick={() =>
                    run(async () =>
                      savePlan(
                        await api<Plan>(
                          `/plans/${encodeURIComponent(form.planId)}`,
                        ),
                      ),
                    )
                  }
                >
                  Open request
                </button>
              </section>
              <section className="activity-card">
                {plans.length ? (
                  plans.map((item) => (
                    <button
                      className="activity-row"
                      key={item.id}
                      onClick={() => savePlan(item)}
                    >
                      <span className="mini-icon">
                        <Layers3 size={18} />
                      </span>
                      <span>
                        <strong>{item.title}</strong>
                        <small>{short(item.id)}</small>
                      </span>
                      <span className="status-pill">
                        {item.confirmed ? "Confirmed" : item.status}
                      </span>
                    </button>
                  ))
                ) : (
                  <div className="empty-activity">
                    <Layers3 size={28} />
                    <div>
                      <strong>No requests yet</strong>
                      <p>Create or restore an account to get started.</p>
                    </div>
                  </div>
                )}
              </section>
            </>
          )}
          <footer>
            <span>
              <Shield size={14} />
              Kavach · Programmable protection on Cardano
            </span>
            <span>Development demo · No production qualification</span>
          </footer>
        </main>
      </div>
      <dialog
        ref={dialog}
        className={modal === "Create account" || plan ? "creation-dialog" : undefined}
        onCancel={() => {
          setModal(null);
          setPlan(null);
        }}
      >
        <div className="dialog-top">
          <span className="eyebrow">
            {plan ? "REVIEW & APPROVE" : "YOUR ACCOUNT. YOUR RULES."}
          </span>
          <button
            className="icon-button"
            aria-label="Close dialog"
            disabled={busy}
            onClick={() => {
              setModal(null);
              setPlan(null);
            }}
          >
            <X size={20} />
          </button>
        </div>
        <h2>{plan?.title || (modal === "Rotate keys" && ["3", "4"].includes(form.mode) ? "Update keys and policies" : modal)}</h2>
        {notice && (modal === "Connect wallet" || modal === "Receive") && (
          <div className="notice" role="alert">
            {notice}
          </div>
        )}
        {modal === "Connect wallet" ? (
          <>
            <p>
              Connect your Cardano wallet to sign requests. Your keys stay in
              your wallet.
            </p>
            <div className="wallet-options">
              {connector.installedExtensions.map((name) => (
                <button
                  className="button"
                  key={name}
                  onClick={() =>
                    run(async () => {
                      setVerifiedKey(null);
                      await connector.connect(
                        name,
                        () => setModal(null),
                        (error) => setNotice(error.message),
                      );
                    })
                  }
                >
                  <Wallet size={19} />
                  {name}
                  <ArrowRight size={16} />
                </button>
              ))}
            </div>
            {!connector.installedExtensions.length && (
              <div className="inline-help">
                No browser wallet detected. Install a CIP-30 wallet and
                configure it for this demo’s network.
              </div>
            )}
            {connector.isConnected && (
              <button
                className="text-button"
                onClick={() => {
                  setVerifiedKey(null);
                  connector.disconnect();
                  setModal(null);
                }}
              >
                <Unplug size={16} />
                Disconnect wallet
              </button>
            )}
            <button
              className="button"
              disabled={busy || !connector.isConnected}
              onClick={() => run(exportKey)}
            >
              <KeyRound size={16} />
              Verify & show public key
            </button>
            {verifiedKey && (
              <section className="verified-key" aria-label="Verified public key export">
                <label htmlFor="verified-public-key">Verified payment public key</label>
                <p className="field-note">
                  Last verified export from {verifiedKey.walletName}. Verify again after
                  switching wallet accounts.
                </p>
                <textarea
                  id="verified-public-key"
                  value={verifiedKey.publicKey}
                  readOnly
                  rows={2}
                  spellCheck={false}
                  onFocus={(event) => event.currentTarget.select()}
                  aria-describedby="verified-key-help"
                />
                <button
                  className="button"
                  type="button"
                  onClick={() => run(async () => {
                    try {
                      await navigator.clipboard.writeText(verifiedKey.publicKey);
                      setNotice("Verified public key copied.");
                    } catch {
                      setNotice("Clipboard unavailable. Select the public key below and use Copy or Ctrl/Cmd+C.");
                    }
                  })}
                >
                  <Copy size={16} />
                  Copy public key
                </button>
                <p id="verified-key-help" className="field-note">
                  This is a public key, not a private key or seed phrase. Copying it
                  does not grant signing authority. Sharing it can link your accounts.
                  You can also select the field and copy it manually.
                </p>
              </section>
            )}
            <p className="field-note">
              Public-key export requires wallet signData support.
              Transaction-only wallets can supply their payment public key from
              their own trusted export.
            </p>
            <div className="connector-credit">
              Connection powered by Cardano Foundation · CF Connect
            </div>
          </>
        ) : plan ? (
          <ApprovalWizard key={plan.id} plan={plan} busy={busy} connected={connector.isConnected} notice={notice}
            devices={Object.fromEntries(creationSigners.filter(k => k.publicKey).map(k => [k.publicKey, k.source]))}
            onPlan={savePlan}
            onWallet={(authority, submitWhenReady) => run(() => approve(authority, submitWhenReady))}
            onSubmit={() => run(() => submit())}
            onAdvance={() => run(async () => savePlan(await api<Plan>(`/plans/${plan.id}/advance`, {})))}
            onRefresh={() => run(async () => savePlan(await api<Plan>(`/plans/${plan.id}`)))}
            onOpen={() => run(async () => { if (plan.locator) await restore(plan.locator); setPlan(null); })}
          />
        ) : modal === "Receive" ? (
          <>
            <p>
              Send ADA or ordinary Cardano native tokens to your account
              address. After ledger confirmation, the balance refreshes automatically
              while the dashboard is visible. You can also use Refresh account.
            </p>
            <code className="address-box">{account?.address}</code>
            <button
              className="button primary"
              onClick={() => account && run(() => copy(account.address))}
            >
              <Copy size={16} />
              Copy address
            </button>
          </>
        ) : (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              run(modal === "Restore account" ? () => restore() : prepare);
            }}
          >
            {modal === "Restore account" ? (
              <>
                <p>
                  Paste the public locator saved when your account was created.
                  You don’t need the original device.
                </p>
                <p className="field-note">
                  This browser saves accounts separately for localhost and 127.0.0.1,
                  and for each port. After changing the site address, restore your
                  locator once here. Your on-chain account and funds are unchanged.
                </p>
                <div className="dialog-actions">
                  <button
                    type="button"
                    className="text-button"
                    onClick={() => open("Create account")}
                  >
                    Create a new account
                  </button>
                  {form.locator && (
                    <button
                      type="button"
                      className="text-button"
                      onClick={() => {
                        forgetSavedAccount(form.locator);
                        setModal(null);
                      }}
                    >
                      Forget this device’s locator
                    </button>
                  )}
                </div>
                <label>
                  Account locator
                  <textarea
                    required
                    value={form.locator}
                    onChange={(e) => update("locator", e.target.value)}
                    placeholder="kavach-locator-v1:…"
                  />
                </label>
              </>
            ) : modal === "Create account" ? (
              <>
                <p className="creation-intro">Build your account around the devices you trust. Choose how each key signs; the account is ready when every setup step is confirmed.</p>
                <div className="creation-section-heading"><span>01</span><h3>Name your account</h3></div>
                <label>Account name<input value={form.name} onChange={e => update("name", e.target.value)} /></label>
                <CreationSigners signers={creationSigners} onChange={setCreationSigners}
                  assigned={policies.flatMap(p => p.members)}
                  onRemove={id => {
                    if (creationSigners.length <= 3 || policies.some(p => p.members.includes(id))) return;
                    setCreationSigners(creationSigners.filter((_, i) => i !== id));
                    setPolicies(policiesAfterSignerRemoval(policies, id));
                  }} />
                {creationSigners.some(k => k.source === "companion") && <>
                  <CompanionPairing />
                  <p className="field-note">Use the updated Companion app with mixed-key enrollment support. Keep recovery and emergency roles on wallet keys; the phone does not yet support those actions.</p>
                </>}
                <div className="creation-section-heading"><span>03</span><h3>Choose optional protection</h3></div>
                <label className="checkbox-field"><input type="checkbox" checked={form.budgetCore} onChange={e => setForm(f => ({ ...f, budgetCore: e.target.checked }))} /><span>Support optional daily/weekly budgets</span></label>
                <p className="field-note">Choose this now to support a shared spending budget. Enable its limit later in Security. Without an enabled budget, spending remains concurrent.</p>
                <section className="creation-summary" aria-label="Creation summary">
                  <h3>Your signing setup</h3>
                  {creationSigners.map((key, id) => <p key={id}>Key {id}: {key.source === "companion" ? "iPhone Companion" : "Cardano wallet"} · {key.method === "2" ? "COSE" : "Transaction signature"}</p>)}
                  <p>All keys prove possession and your admin signers approve activation. COSE keys authorize intents; a separate fee-paying wallet signs each transaction. Advanced transaction-witness keys also authorize through that transaction.</p>
                  <p>DevKit setup locks 480 ADA in six reference scripts, plus 12 ADA in account state, registration deposits and fees. Spending remains blocked until activation; an unfinished confirmed account can resume setup after refresh or backend restart.</p>
                </section>
              </>
            ) : modal === "Send assets" ? (
              <>
                <p>
                  The recipient and amount are part of your authenticated
                  account request.
                </p>
                <div className="transfer-options">
                  <label>
                    <input
                      type="checkbox"
                      checked={form.whole}
                      onChange={(e) =>
                        setForm((f) => ({
                          ...f,
                          whole: e.target.checked,
                          consolidate: false,
                        }))
                      }
                    />
                    Send all assets
                  </label>
                  <label>
                    <input
                      type="checkbox"
                      checked={form.consolidate}
                      onChange={(e) =>
                        setForm((f) => ({
                          ...f,
                          consolidate: e.target.checked,
                          whole: false,
                        }))
                      }
                    />
                    Consolidate into one account output
                  </label>
                </div>
                {!form.consolidate && (
                  <label>
                    Recipient address
                    <input
                      required
                      value={form.recipient}
                      onChange={(e) => update("recipient", e.target.value)}
                      placeholder="addr_test1…"
                    />
                  </label>
                )}
                {!form.whole && !form.consolidate && (
                  <>
                    <label>
                      Amount in ADA
                      <input
                        required
                        inputMode="decimal"
                        value={form.amount}
                        onChange={(e) => update("amount", e.target.value)}
                        placeholder="0.00"
                      />
                    </label>
                    <label>
                      Native asset (optional)
                      <select
                        value={form.asset}
                        onChange={(e) => update("asset", e.target.value)}
                      >
                        <option value="">ADA only</option>
                        {account?.assets.map((asset) => (
                          <option key={asset.unit} value={asset.unit}>
                            {short(asset.unit, 12)} · {asset.quantity} available
                          </option>
                        ))}
                      </select>
                    </label>
                    {form.asset && (
                      <label>
                        Token quantity
                        <input
                          required
                          inputMode="numeric"
                          value={form.quantity}
                          onChange={(e) => update("quantity", e.target.value)}
                          placeholder="Whole units"
                        />
                      </label>
                    )}
                  </>
                )}
                <p className="field-note">
                  Network fees come from your connected wallet. Every remaining
                  asset stays in Kavach. The demo handles up to 16 ordinary
                  account outputs.
                </p>
              </>
            ) : (
              <>
                <p>
                  {modal === "Freeze account"
                    ? "Once confirmed, ordinary spending is disabled. Your independent unfreeze authority can restore access."
                    : modal === "Cancel recovery"
                      ? "Cancellation leaves the account frozen. Unfreezing requires its independent authority."
                      : "The current policy determines which authorities must approve this action."}
                </p>
                {["Rotate keys", "Start recovery"].includes(modal || "") && (
                  <label>
                    Target authority public keys
                    <textarea
                      required
                      value={form.target}
                      onChange={(e) => update("target", e.target.value)}
                      placeholder="One public key per line: everyday, guardian, defensive backup"
                    />
                  </label>
                )}
                {modal === "Replace module" && (
                  <label>
                    Candidate signing method
                    <select
                      value={form.mode}
                      onChange={(e) => update("mode", e.target.value)}
                    >
                      <option value="1">Wallet transaction · CIP-30</option>
                      <option value="2">Intent signature · CIP-8 / COSE</option>
                      <option value="3">Mixed signatures + amount tiers</option>
                      {account?.budgetCore && <option value="4">Mixed signatures + optional periodic budget</option>}
                    </select>
                  </label>
                )}
              </>
            )}
            {["Create account", "Rotate keys", "Start recovery", "Replace module"].includes(modal || "") && (
              <PolicyEditor policies={policies} onChange={setPolicies} signerCount={modal === "Create account" ? creationSigners.length : form.target.trim() ? form.target.trim().split(/[\s,]+/).length : 3} />
            )}
            {["3", "4"].includes(form.mode) && ["Replace module", "Rotate keys", "Start recovery"].includes(modal || "") && (
              <>
                <p className="field-note">Spend above is the strong approval policy. Small ADA payments use the policy below; native-token and whole-UTxO transfers always use strong approval. Admin must include authority outside the strong spending keys.</p>
                <label>Small-payment threshold (ADA, inclusive)
                  <input type="number" min="0" step="0.000001" required value={form.smallPaymentAda} onChange={e => update("smallPaymentAda", e.target.value)} />
                </label>
                <p className="field-note">Includes recipient ADA plus the signed maximum account fee. This selects required approvals; it is not a cumulative spending cap.</p>
                <label>Small-payment key IDs (comma separated)
                  <input required value={form.smallMembers} onChange={e => update("smallMembers", e.target.value)} />
                </label>
                <label>Small-payment signatures required
                  <input type="number" min="1" max="8" required value={form.smallThreshold} onChange={e => update("smallThreshold", e.target.value)} />
                </label>
                <p className="field-note">Recommended: all account keys sign COSE intents. The fee-paying wallet then signs the transaction separately. Updating this setting requires current-admin approval and possession proofs.</p>
                <button type="button" className="button" onClick={() => update("coseIds", (form.target.trim() ? form.target.trim().split(/[\s,]+/).map((_, id) => id) : account?.keys.map(k => k.id) || []).join(", "))}>Use COSE for all account keys</button>
                <label>COSE key IDs (comma separated; blank means all transaction witnesses)
                  <input value={form.coseIds} onChange={e => update("coseIds", e.target.value)} />
                </label>
                <p className="field-note">Other registered keys sign the Cardano transaction. Every destination key must prove possession when this configuration is installed or changed.</p>
              </>
            )}
            {form.mode === "4" && ["Replace module", "Rotate keys", "Start recovery"].includes(modal || "") && (
              <>
                <label className="checkbox-field"><input type="checkbox" checked={form.budgetEnabled} onChange={e => setForm(f => ({ ...f, budgetEnabled: e.target.checked }))} /><span>Enable shared periodic ADA budget</span></label>
                <label>Budget period<select value={form.budgetPeriod} onChange={e => update("budgetPeriod", e.target.value)}><option value="daily">Daily · midnight UTC</option><option value="weekly">Weekly · Monday midnight UTC</option></select></label>
                {form.budgetEnabled && <label>ADA per period<input type="number" min="0.000001" step="0.000001" required value={form.budgetAda} onChange={e => update("budgetAda", e.target.value)} /></label>}
                <p className="field-note">This is a hard cumulative ADA cap, including actual account-paid fees. All budgeted spends share one on-chain counter and may conflict. Native tokens have no ADA price assigned.</p>
                <p className="field-note">Changing the limit preserves usage. Changing daily ↔ weekly starts a fresh period counter on the next spend. Disabling retains the counter; re-enabling the same period preserves that period’s recorded usage. Creating a counter locks a 3 ADA development deposit.</p>
              </>
            )}
            {modal === "Replace module" && account?.budget?.enabled && form.mode !== "4" && <p className="field-note">Installing this module removes the active periodic budget. Your existing admin authority must approve that removal.</p>}
            {account &&
              [
                "Send assets",
                "Rotate keys",
                "Replace module",
                "Freeze account",
                "Unfreeze account",
                "Start recovery",
                "Cancel recovery",
              ].includes(modal || "") && (
                <label>
                  Approving key IDs
                  <input
                    value={form.approvers}
                    onChange={(e) => update("approvers", e.target.value)}
                    placeholder="For example: 0, 1"
                  />
                  <span className="field-note">
                    Leave blank for automatic selection, or choose a sufficient subset of the current policy. Each
                    selected key must approve this request.
                  </span>
                </label>
              )}
            {notice && <div className="notice" role="alert">{notice}</div>}
            {modal !== "Restore account" && !connector.isConnected && (
              <section className="inline-help" aria-label="Connect fee-paying wallet">
                <p>Connect a Cardano wallet to enable Review request. Entering signer public keys does not connect a wallet. This wallet pays the transaction fees and setup deposits.</p>
                <div className="wallet-options">
                  {connector.installedExtensions.map(name => (
                    <button type="button" className="button" key={name} disabled={busy}
                      onClick={() => run(async () => {
                        setVerifiedKey(null);
                        await connector.connect(name, () => setNotice("Wallet connected. Your form entries are preserved."), error => setNotice(error.message));
                      })}>
                      <Wallet size={19} /> Connect {name}
                    </button>
                  ))}
                </div>
                {!connector.installedExtensions.length && <p>No Cardano wallet detected in this browser profile. Enable Yano here and configure it for Yaci DevKit.</p>}
              </section>
            )}
            <div className="dialog-actions">
              <button
                type="button"
                className="button"
                onClick={() => setModal(null)}
              >
                Cancel
              </button>
              <button
                className="button primary"
                disabled={
                  busy ||
                  (modal !== "Restore account" && !connector.isConnected)
                }
              >
                {busy ? (
                  <LoaderCircle size={16} className="spin" />
                ) : modal === "Restore account" ? (
                  "Find my account"
                ) : (
                  "Review request"
                )}
                <ArrowRight size={16} />
              </button>
            </div>
          </form>
        )}
      </dialog>
    </div>
  );
}
