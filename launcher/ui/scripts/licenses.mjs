// Writes src/licenses.json: every third-party Rust crate and npm package the launcher ships, with its licence.
// Runs before `npm run build`; run it by hand with `node scripts/licenses.mjs` after changing dependencies.
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ui = join(dirname(fileURLToPath(import.meta.url)), "..");
const out = new Map();
const add = (name, version, license, source) => {
  const key = `${source}:${name}`;
  if (!out.has(key)) out.set(key, { name, version, license: license || "See the project", source });
};

// Rust: what the launcher binary links (normal dependencies reachable from the workspace members).
try {
  const meta = JSON.parse(execFileSync("cargo", ["metadata", "--format-version", "1", "--locked"], { cwd: join(ui, "..", "src-tauri"), maxBuffer: 256 * 1024 * 1024 }).toString());
  const members = new Set(meta.workspace_members);
  const nodes = new Map(meta.resolve.nodes.map((n) => [n.id, n]));
  const pkgs = new Map(meta.packages.map((p) => [p.id, p]));
  const seen = new Set();
  const stack = [...members];
  while (stack.length) {
    const id = stack.pop();
    if (seen.has(id)) continue;
    seen.add(id);
    for (const d of nodes.get(id)?.deps ?? []) {
      if (d.dep_kinds.some((k) => k.kind === null || k.kind === "build")) stack.push(d.pkg);
    }
  }
  for (const id of seen) {
    if (members.has(id)) continue;
    const p = pkgs.get(id);
    if (p) add(p.name, p.version, p.license, "crate");
  }
} catch (e) {
  console.warn("licenses: cargo metadata failed, Rust crates left out:", e.message);
}

// npm: production dependencies from the lockfile.
const lock = JSON.parse(readFileSync(join(ui, "package-lock.json"), "utf8"));
for (const [path, p] of Object.entries(lock.packages ?? {})) {
  if (!path || p.dev || p.devOptional) continue;
  const name = path.split("node_modules/").pop();
  let license = p.license;
  if (!license) {
    try {
      license = JSON.parse(readFileSync(join(ui, path, "package.json"), "utf8")).license;
    } catch {
      /* not installed */
    }
  }
  add(name, p.version, license, "npm");
}

const list = [...out.values()].sort((a, b) => a.name.localeCompare(b.name));
writeFileSync(join(ui, "src", "licenses.json"), JSON.stringify(list, null, 0) + "\n");
console.log(`licenses: ${list.length} packages`);
