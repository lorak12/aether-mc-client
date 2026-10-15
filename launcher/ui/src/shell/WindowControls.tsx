import { getCurrentWindow } from "@tauri-apps/api/window";
import { IClose, IMaximise, IMinus } from "../components/Icons";

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
        <IMinus size={16} />
      </button>
      <button aria-label="Maximise" onClick={act((w) => w.toggleMaximize())}>
        <IMaximise size={14} />
      </button>
      <button aria-label="Close" className="close" onClick={act((w) => w.close())}>
        <IClose size={16} />
      </button>
    </div>
  );
}
