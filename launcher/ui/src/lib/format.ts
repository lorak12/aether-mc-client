/** 1234 → "1.2K", 3_400_000 → "3.4M". */
export function compact(n: number): string {
  if (n < 1000) return String(n);
  const units: [number, string][] = [
    [1e9, "B"],
    [1e6, "M"],
    [1e3, "K"],
  ];
  for (const [v, s] of units) {
    if (n >= v) {
      const x = n / v;
      return (x >= 100 ? Math.round(x) : Math.round(x * 10) / 10) + s;
    }
  }
  return String(n);
}

/** Relative time: "just now", "5 min ago", "3 h ago", "2 days ago", then a date. */
export function ago(at: number | string, now = Date.now()): string {
  const t = typeof at === "string" ? Date.parse(at) : at;
  if (!Number.isFinite(t)) return "";
  const s = Math.max(0, Math.round((now - t) / 1000));
  if (s < 45) return "just now";
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} h ago`;
  const d = Math.round(h / 24);
  if (d === 1) return "yesterday";
  if (d < 30) return `${d} days ago`;
  const mo = Math.round(d / 30);
  if (mo < 12) return `${mo} month${mo > 1 ? "s" : ""} ago`;
  return new Date(t).toLocaleDateString("en-GB", { year: "numeric", month: "short", day: "numeric" });
}

export function date(at: number): string {
  return new Date(at).toLocaleDateString("en-GB", { month: "short", day: "numeric", year: "numeric" });
}

export function megabytes(mb: number): string {
  return mb >= 1024 ? `${(mb / 1024).toFixed(mb % 1024 ? 1 : 0)} GB` : `${mb} MB`;
}

export const cap = (s: string) => (s ? s[0].toUpperCase() + s.slice(1) : s);
/** "map-information" → "Map information" */
const PROPER: Record<string, string> = { neoforge: "NeoForge", optifine: "OptiFine", pbr: "PBR", "path-tracing": "Path tracing" };
export const label = (s: string) => PROPER[s] ?? cap(s.replace(/[-_]/g, " "));

/** 1536 → "1.5 KB", 3_400_000 → "3.2 MB". */
export function bytes(n: number): string {
  if (n < 1024) return `${n} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v >= 100 ? Math.round(v) : Math.round(v * 10) / 10} ${units[i]}`;
}
