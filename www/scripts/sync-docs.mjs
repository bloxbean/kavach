import { readFile, writeFile, mkdir, cp } from "node:fs/promises";
import path from "node:path";
const root = path.resolve(import.meta.dirname, "../..");
const entries = [
  ["docs/whitepaper/README.md", "whitepaper", "White paper · v0.1"],
  ["protocol/v1/specification.md", "reference/core", "Core protocol"],
  ["protocol/v1/module-abi.md", "reference/modules", "Module ABI"],
  ["protocol/v1/deployment.md", "reference/deployment", "Deployment domains"],
  [
    "protocol/browser/specification.md",
    "reference/browser",
    "Browser authorization",
  ],
  ["protocol/policy/specification.md", "reference/policies", "Policy modules"],
  ["docs/phase2/account-locator.md", "reference/locators", "Account locators"],
  [
    "docs/phase2/completion-checklist.md",
    "reference/qualification",
    "Phase 2 qualification",
  ],
  [
    "docs/policy/completion-checklist.md",
    "reference/policy-status",
    "Policy qualification",
  ],
  [
    "adr/adr-010-intent-execution-and-fee-sponsorship.md",
    "reference/sponsorship",
    "Proposed sponsored execution",
  ],
];
const routes = new Map(
  entries.map(([source, slug]) => [source, `/kavach/${slug}/`]),
);
for (const [source, slug, title] of entries) {
  let body = await readFile(path.join(root, source), "utf8");
  body = body.replace(/^# .*\n/, "");
  body = body.replace(/\]\(([^\s)]+)([^)]*)\)/g, (all, href, rest) => {
    if (/^(https?:|mailto:|#)/.test(href)) return all;
    const [file, anchor] = href.split("#");
    const resolved = path.posix.normalize(
      path.posix.join(path.posix.dirname(source), file),
    );
    const target =
      routes.get(resolved) ??
      `https://github.com/bloxbean/kavach/blob/main/${resolved}`;
    return `](${target}${anchor ? "#" + anchor : ""}${rest})`;
  });
  if (slug === "whitepaper") {
    let diagram = 0;
    const descriptions = [
      "Dashboard, wallet and iPhone approvals meet in the backend and are validated on Cardano",
      "Account identity is separate from asset outputs and configurable authority",
      "Intent approval, transaction funding and ledger confirmation are distinct responsibilities",
      "Proposed execution providers fund approved intents without acquiring account authority",
      "Account setup proceeds through reference publication, genesis and policy activation",
      "An enabled shared budget serializes spending through an on-chain counter",
      "Account lifecycle separates active, frozen and delayed recovery states",
    ];
    body = body.replace(
      /```mermaid\n[\s\S]*?```/g,
      () =>
        `![${descriptions[diagram++]}](/kavach/figures/${String(diagram).padStart(2, "0")}.svg)`,
    );
  }
  const target = path.join(root, "www/src/content/docs", slug + ".md");
  await mkdir(path.dirname(target), { recursive: true });
  await writeFile(
    target,
    `---\ntitle: ${JSON.stringify(title)}\ndescription: ${JSON.stringify(title + " — Kavach development specification and evidence.")}\neditUrl: https://github.com/bloxbean/kavach/edit/main/${source}\n---\n\n${body}`,
  );
}
await cp(
  path.join(root, "docs/whitepaper/figures"),
  path.join(root, "www/public/figures"),
  { recursive: true },
);
await mkdir(path.join(root, "www/public/downloads"), { recursive: true });
await cp(
  path.join(root, "output/pdf/kavach-whitepaper-v0.1.pdf"),
  path.join(root, "www/public/downloads/kavach-whitepaper-v0.1.pdf"),
);
console.log(`Synced ${entries.length} canonical documents, diagrams and PDF.`);
