// UI preferences and UI-side data in localStorage. Storage can be missing or throw (private mode, cleared data),
// so every read falls back to the default and every write is best-effort.
const PREFIX = "kite.ui.";

export function load<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(PREFIX + key);
    return raw == null ? fallback : (JSON.parse(raw) as T);
  } catch {
    return fallback;
  }
}

export function save<T>(key: string, value: T) {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify(value));
  } catch {
    /* storage unavailable */
  }
}
