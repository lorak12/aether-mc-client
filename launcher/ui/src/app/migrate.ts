import { api } from "../api";
import type { GameSettings, Overrides, Profile, ProfileMeta } from "../api/types";
import { load, save } from "../lib/prefs";

const PREFIX = "kite.ui.";
const DONE = "migrated.v2";

/** What launcher versions before profile format 2 kept per profile in the webview. */
interface OldMeta {
  kind?: ProfileMeta["kind"];
  description?: string;
  cover?: string | null;
  builtinOnly?: boolean;
  loaderVersion?: string;
  lastPlayed?: number | null;
  plays?: number;
  disabled?: string[];
  overrides?: Overrides & { gameDir?: unknown };
}

function keys(): string[] {
  try {
    return Object.keys(localStorage).filter((k) => k.startsWith(PREFIX));
  } catch {
    return [];
  }
}
function drop(key: string) {
  try {
    localStorage.removeItem(PREFIX + key);
  } catch {
    /* storage unavailable */
  }
}

/**
 * Move profile metadata and global settings that older versions stored in localStorage into profile.json and
 * settings.json, apply "disabled" content for real, and remove the old preview-data keys. Runs once.
 */
export async function migrateLocalData() {
  if (load<boolean>(DONE, false)) return;
  const all = keys().map((k) => k.slice(PREFIX.length));

  const oldGlobal = load<(Partial<GameSettings> & { gameDir?: string }) | null>("globalSettings", null);
  if (oldGlobal) {
    const current = await api.getSettings();
    const { gameDir: _unused, ...rest } = oldGlobal;
    await api.setSettings({ ...current, ...rest });
    drop("globalSettings");
  }

  const profiles: Profile[] = await api.listProfiles().catch(() => []);
  for (const p of profiles) {
    const m = load<OldMeta | null>(`profile.${p.id}`, null);
    if (!m) continue;
    const { gameDir: _gd, ...overrides } = m.overrides ?? {};
    const next: Profile = {
      ...p,
      loader_version: m.loaderVersion && m.loaderVersion !== "latest" ? m.loaderVersion : p.loader_version,
      overrides: { ...p.overrides, ...overrides },
      meta: {
        ...p.meta,
        kind: m.kind ?? p.meta.kind,
        description: m.description ?? p.meta.description,
        cover: m.cover ?? p.meta.cover,
        builtinOnly: m.builtinOnly ?? p.meta.builtinOnly,
        lastPlayed: m.lastPlayed ?? p.meta.lastPlayed,
        plays: m.plays ?? p.meta.plays,
      },
    };
    await api.updateProfile(next).catch((e) => console.warn("couldn't migrate", p.id, e));
    for (const id of m.disabled ?? []) await api.setContentEnabled(p.id, id, false).catch(() => {});
    drop(`profile.${p.id}`);
  }

  // Leftovers of the old preview data.
  for (const k of all) if (k.startsWith("mock.") || k.startsWith("profile.")) drop(k);
  drop("notifications");
  save(DONE, true);
}
