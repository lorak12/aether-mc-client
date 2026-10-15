import type { GameSettings, Overrides, SettingKey } from "../api/types";

/** Shown until settings.json loads; matches the launcher's own defaults. */
export const DEFAULT_SETTINGS: GameSettings = {
  memoryMb: 4096,
  width: 1280,
  height: 720,
  fullscreen: false,
  jvmArgs: "",
  gc: "G1GC",
  preLaunch: "",
  wrapper: "",
  postExit: "",
  env: [],
  javaPath: "",
  shareOptions: true,
};

export const isOverridden = (ov: Overrides, key: SettingKey) => !!ov[key]?.on;

/** The value a profile launches with: its own where it overrides the global setting, the global value otherwise. */
export function effective(global: GameSettings, ov: Overrides): GameSettings {
  const out = { ...global } as Record<SettingKey, unknown>;
  for (const k of Object.keys(ov) as SettingKey[]) {
    const o = ov[k];
    if (o?.on && k in global) out[k] = o.value;
  }
  return out as GameSettings;
}

/** Toggle an override. Turning it on starts from the current global value so nothing changes until edited. */
export function setOverride<K extends SettingKey>(ov: Overrides, key: K, on: boolean, global: GameSettings): Overrides {
  const prev = ov[key];
  return { ...ov, [key]: { on, value: prev ? prev.value : global[key] } };
}

export function setOverrideValue<K extends SettingKey>(ov: Overrides, key: K, value: GameSettings[K]): Overrides {
  return { ...ov, [key]: { on: true, value } };
}
