import { convertFileSrc } from "@tauri-apps/api/core";
import type { Profile } from "../api/types";

/** A soft two-colour gradient, for covers and news art without a picture. */
export const art = (a: string, b: string, c = "#0b0d12", angle = 135) =>
  `radial-gradient(120% 90% at 85% 10%, ${a}cc, transparent 60%), radial-gradient(90% 80% at 10% 100%, ${b}bb, transparent 60%), linear-gradient(${angle}deg, ${c}, #07090f)`;

export const COVERS = [art("#6C8CFF", "#B36CFF"), art("#3DDC97", "#6C8CFF"), art("#FFC857", "#FF5D6C"), art("#FF9A4D", "#B36CFF"), art("#8FB0FF", "#3DDC97"), art("#B36CFF", "#FF5D6C")];

/** A local file as a URL the webview can show (Tauri's asset protocol). Plain paths in a browser. */
export function fileUrl(path: string): string {
  try {
    return convertFileSrc(path);
  } catch {
    return path;
  }
}

/** The cover to draw for a profile: `file:<name>` resolves inside the profile folder. */
export function coverOf(p: Profile): string | null {
  const c = p.meta.cover;
  if (!c) return null;
  if (c.startsWith("file:")) {
    const sep = p.dir.includes("\\") ? "\\" : "/";
    return fileUrl(`${p.dir}${sep}${c.slice(5)}`);
  }
  return c;
}

/** News picture or gradient as a CSS background. */
export const newsBackground = (n: { image?: string; art?: string }) =>
  n.image ? `linear-gradient(180deg, transparent 30%, rgba(7,9,15,.85)), url("${n.image}") center/cover` : n.art || art("#6C8CFF", "#B36CFF");
