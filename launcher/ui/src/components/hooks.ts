import { DependencyList, RefObject, useCallback, useEffect, useRef, useState } from "react";
import { errText } from "../api";

export interface Async<T> {
  data: T | undefined;
  error: string | null;
  loading: boolean;
  reload: () => void;
  setData: (fn: (d: T | undefined) => T | undefined) => void;
}

/** Load data for a view; stale responses (from an earlier `deps`) are dropped. */
export function useAsync<T>(fn: () => Promise<T>, deps: DependencyList): Async<T> {
  const [data, setDataState] = useState<T | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  const seq = useRef(0);
  useEffect(() => {
    const n = ++seq.current;
    setLoading(true);
    setError(null);
    fn()
      .then((d) => n === seq.current && setDataState(d))
      .catch((e) => n === seq.current && setError(errText(e)))
      .finally(() => n === seq.current && setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);
  const reload = useCallback(() => setTick((t) => t + 1), []);
  const setData = useCallback((f: (d: T | undefined) => T | undefined) => setDataState(f), []);
  return { data, error, loading, reload, setData };
}

/** Call `onOutside` on a pointer press outside every given element. */
export function useClickOutside(refs: RefObject<HTMLElement>[], onOutside: () => void, active = true, ignore?: string) {
  const cb = useRef(onOutside);
  cb.current = onOutside;
  useEffect(() => {
    if (!active) return;
    const h = (e: PointerEvent) => {
      // e.g. dialogs opened from inside a side panel don't count as outside it.
      if (ignore && (e.target as Element).closest?.(ignore)) return;
      if (refs.every((r) => r.current && !r.current.contains(e.target as Node))) cb.current();
    };
    // Defer so the click that opened the element doesn't close it.
    const t = setTimeout(() => document.addEventListener("pointerdown", h), 0);
    return () => {
      clearTimeout(t);
      document.removeEventListener("pointerdown", h);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active]);
}

// ---------- Esc closes the topmost layer ----------
const layers: { id: number; close: () => void }[] = [];
let layerSeq = 0;
if (typeof window !== "undefined") {
  window.addEventListener("keydown", (e) => {
    if (e.key !== "Escape" || !layers.length) return;
    e.preventDefault();
    e.stopPropagation();
    layers[layers.length - 1].close();
  });
}

/** Register an open overlay so Esc closes it (the most recently opened first). */
export function useLayer(open: boolean, close: () => void) {
  const cb = useRef(close);
  cb.current = close;
  useEffect(() => {
    if (!open) return;
    const id = ++layerSeq;
    layers.push({ id, close: () => cb.current() });
    return () => {
      const i = layers.findIndex((l) => l.id === id);
      if (i >= 0) layers.splice(i, 1);
    };
  }, [open]);
}

/** Global keyboard shortcut. `combo` like "ctrl+k" or "alt+arrowleft". Ignored while typing unless `inInputs`. */
export function useHotkey(combo: string, fn: (e: KeyboardEvent) => void, inInputs = false) {
  const cb = useRef(fn);
  cb.current = fn;
  useEffect(() => {
    const parts = combo.toLowerCase().split("+");
    const key = parts[parts.length - 1];
    const h = (e: KeyboardEvent) => {
      if (e.key.toLowerCase() !== key) return;
      if (parts.includes("ctrl") !== (e.ctrlKey || e.metaKey)) return;
      if (parts.includes("alt") !== e.altKey) return;
      if (parts.includes("shift") !== e.shiftKey) return;
      const t = e.target as HTMLElement;
      if (!inInputs && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable)) return;
      e.preventDefault();
      cb.current(e);
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [combo, inInputs]);
}

export function useDebounced<T>(value: T, ms = 250): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}
