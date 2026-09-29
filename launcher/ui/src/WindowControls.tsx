import { getCurrentWindow } from "@tauri-apps/api/window";

// Frameless window: we draw our own minimise / maximise / close.
const act = (fn: (w: ReturnType<typeof getCurrentWindow>) => Promise<unknown>) => () => {
  try {
    fn(getCurrentWindow()).catch(() => {});
  } catch {
    /* running in a plain browser */
  }
};

export function WindowControls() {
  return (
    <div className="winctl">
      <button aria-label="Minimise" onClick={act((w) => w.minimize())}>
        <svg width="12" height="12" viewBox="0 0 12 12"><path d="M2 6h8" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" /></svg>
      </button>
      <button aria-label="Maximise" onClick={act((w) => w.toggleMaximize())}>
        <svg width="12" height="12" viewBox="0 0 12 12"><rect x="2.500" y="2.500" width="7" height="7" rx="1.500" fill="none" stroke="currentColor" strokeWidth="1.200" /></svg>
      </button>
      <button aria-label="Close" className="close" onClick={act((w) => w.close())}>
        <svg width="12" height="12" viewBox="0 0 12 12"><path d="M2.500 2.500l7 7m0-7l-7 7" stroke="currentColor" strokeWidth="1.200" strokeLinecap="round" /></svg>
      </button>
    </div>
  );
}
