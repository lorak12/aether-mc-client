import type { Loader, McVersion, Profile } from "../api/types";

const isRelease = (v: string) => /^\d+(\.\d+)+$/.test(v);

/** Compare release ids numerically ("1.21.10" > "1.21.9"). Non-release ids sort after releases. */
export function compareVersions(a: string, b: string): number {
  if (isRelease(a) !== isRelease(b)) return isRelease(a) ? -1 : 1;
  const pa = a.split(".").map(Number);
  const pb = b.split(".").map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d) return d;
  }
  return 0;
}

/**
 * The major a version belongs to. Classic ids group by the first two parts ("1.21.4" → "1.21");
 * year-based ids (26.x) group by the year ("26.3" → "26"). Anything else (snapshots) is its own group.
 */
export function groupOf(v: string): string {
  if (!isRelease(v)) return v;
  const parts = v.split(".");
  return parts[0] === "1" ? parts.slice(0, 2).join(".") : parts[0];
}

export interface VersionGroup {
  major: string;
  /** Newest first. */
  minors: string[];
}

/** Group release versions by major, newest major first. */
export function groupVersions(list: McVersion[]): VersionGroup[] {
  const map = new Map<string, string[]>();
  for (const v of list) {
    if (v.kind !== "release" || !isRelease(v.id)) continue;
    const g = groupOf(v.id);
    map.set(g, [...(map.get(g) ?? []), v.id]);
  }
  return [...map.entries()]
    .map(([major, minors]) => ({ major, minors: [...new Set(minors)].sort((a, b) => compareVersions(b, a)) }))
    .sort((a, b) => compareVersions(b.minors[0], a.minors[0]));
}

export const LOADER_NAMES: Record<Loader, string> = {
  vanilla: "Vanilla",
  fabric: "Fabric",
  quilt: "Quilt",
  forge: "Forge",
  neoforge: "NeoForge",
};

/** Forge and NeoForge run plain mods only: the Kite client (and its mod suite) has no build for them. */
export const isForgeLike = (l: Loader) => l === "forge" || l === "neoforge";

/** Shown wherever Forge or NeoForge is picked for a profile. */
export const forgeNote = (l: Loader) => `${LOADER_NAMES[l]} profiles don't include the Kite mod suite. You get Minecraft with ${LOADER_NAMES[l]} and the mods you add yourself.`;

/**
 * The line under the Launch button:
 * client profiles read "Kite 1.21.4 with Fabric" (or just "Kite 1.21.4" when only built-in mods load),
 * modpacks read "<pack> 1.20.1", vanilla profiles read "Minecraft 1.21.4" (plus the loader if it has one).
 */
export function profileLabel(p: Profile): string {
  const m = p.meta;
  if (m.kind === "modpack") return `${p.name} ${p.mc_version}`;
  if (p.loader === "vanilla") return `Minecraft ${p.mc_version}`;
  if (isForgeLike(p.loader)) return `Minecraft ${p.mc_version} with ${LOADER_NAMES[p.loader]}`;
  if (m.kind === "vanilla") return `Minecraft ${p.mc_version} with ${LOADER_NAMES[p.loader]}`;
  if (m.builtinOnly) return `Kite ${p.mc_version}`;
  return `Kite ${p.mc_version} with ${LOADER_NAMES[p.loader]}`;
}

/** Releases before 1.14, where Fabric means Legacy Fabric (the launcher installs it automatically) and Quilt doesn't exist. */
export const isLegacyFabric = (v: string) => {
  const m = /^1\.(\d+)(\.|$)/.exec(v);
  return !!m && Number(m[1]) <= 13;
};

/** Whether the Kite in-game client runs on this version and loader. `supported` comes from the launcher. */
export const clientSupported = (v: string, loader: Loader, supported: string[]) =>
  supported.includes(v) && (loader === "fabric" || (loader === "quilt" && !isLegacyFabric(v)));

/** "1.21.11 and 26.3" */
export const listText = (xs: string[]) => (xs.length <= 1 ? xs.join("") : `${xs.slice(0, -1).join(", ")} and ${xs[xs.length - 1]}`);
