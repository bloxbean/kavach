import { readdir, readFile, access } from "node:fs/promises";
import path from "node:path";
const root = path.resolve(import.meta.dirname, "../dist");
async function walk(dir) {
  const entries = await readdir(dir, { withFileTypes: true });
  return (
    await Promise.all(
      entries.map((e) =>
        e.isDirectory() ? walk(path.join(dir, e.name)) : path.join(dir, e.name),
      ),
    )
  ).flat();
}
const files = await walk(root);
const errors = [];
let count = 0;
for (const file of files.filter((f) => f.endsWith(".html"))) {
  const html = await readFile(file, "utf8");
  for (const match of html.matchAll(/(?:href|src)="([^"]+)"/g)) {
    const href = match[1];
    if (/^(?:[a-z]+:|\/\/|#)/i.test(href)) continue;
    const clean = decodeURIComponent(href.split(/[?#]/)[0]);
    if (!clean) continue;
    if (clean.startsWith("/") && !clean.startsWith("/kavach/")) {
      errors.push(`${file}: wrong project base: ${href}`);
      continue;
    }
    let target = clean.startsWith("/")
      ? path.join(root, clean.slice("/kavach/".length))
      : path.resolve(path.dirname(file), clean);
    if (clean.endsWith("/")) target = path.join(target, "index.html");
    try {
      await access(target);
      count++;
    } catch {
      errors.push(`${path.relative(root, file)}: missing ${href}`);
    }
  }
}
if (errors.length) {
  console.error(errors.join("\n"));
  process.exit(1);
}
console.log(
  `Verified ${count} local links/assets in ${files.filter((f) => f.endsWith(".html")).length} HTML pages.`,
);
