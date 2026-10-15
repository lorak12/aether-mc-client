import { ReactNode, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { IClose } from "./Icons";
import { useClickOutside, useLayer } from "./hooks";

const root = () => document.getElementById("overlay-root") ?? document.body;

/** Keep focus inside a dialog and return it to where it was on close. */
function useFocusTrap(open: boolean) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const prev = document.activeElement as HTMLElement | null;
    const el = ref.current;
    const first = el?.querySelector<HTMLElement>("[autofocus], input, select, textarea, button:not([data-close])");
    (first ?? el)?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Tab" || !el) return;
      const f = [...el.querySelectorAll<HTMLElement>("button, [href], input, select, textarea, [tabindex]:not([tabindex='-1'])")].filter((x) => !x.hasAttribute("disabled"));
      if (!f.length) return;
      const [a, z] = [f[0], f[f.length - 1]];
      if (e.shiftKey && document.activeElement === a) {
        e.preventDefault();
        z.focus();
      } else if (!e.shiftKey && document.activeElement === z) {
        e.preventDefault();
        a.focus();
      }
    };
    el?.addEventListener("keydown", onKey);
    return () => {
      el?.removeEventListener("keydown", onKey);
      prev?.focus?.();
    };
  }, [open]);
  return ref;
}

export function Modal({
  open,
  onClose,
  title,
  children,
  footer,
  width = 480,
  className,
}: {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  width?: number;
  className?: string;
}) {
  useLayer(open, onClose);
  const ref = useFocusTrap(open);
  if (!open) return null;
  return createPortal(
    <div className="scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={className ? `modal ${className}` : "modal"} style={{ width }} role="dialog" aria-modal="true" ref={ref} tabIndex={-1}>
        {title && (
          <header className="modal-head">
            <h2>{title}</h2>
            <button className="icon-btn" data-close onClick={onClose} aria-label="Close">
              <IClose size={18} />
            </button>
          </header>
        )}
        <div className="modal-body">{children}</div>
        {footer && <footer className="modal-foot">{footer}</footer>}
      </div>
    </div>,
    root(),
  );
}

/** Large near-full-size dialog with a left tab rail (profile settings). */
export function BigPopover({
  open,
  onClose,
  header,
  rail,
  children,
}: {
  open: boolean;
  onClose: () => void;
  header: ReactNode;
  rail: ReactNode;
  children: ReactNode;
}) {
  useLayer(open, onClose);
  const ref = useFocusTrap(open);
  if (!open) return null;
  return createPortal(
    <div className="scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="big-pop" role="dialog" aria-modal="true" ref={ref} tabIndex={-1}>
        <header className="big-pop-head">
          {header}
          <button className="icon-btn" data-close onClick={onClose} aria-label="Close">
            <IClose size={18} />
          </button>
        </header>
        <div className="big-pop-main">
          <nav className="big-pop-rail">{rail}</nav>
          <div className="big-pop-body">{children}</div>
        </div>
      </div>
    </div>,
    root(),
  );
}

/** Panel that slides in from the right over the page content. */
export function SidePanel({ open, onClose, children, width = 420 }: { open: boolean; onClose: () => void; children: ReactNode; width?: number }) {
  useLayer(open, onClose);
  const ref = useRef<HTMLDivElement>(null);
  useClickOutside([ref], onClose, open, "#overlay-root, #menu-root");
  const [render, setRender] = useState(open);
  useEffect(() => {
    if (open) setRender(true);
    else {
      const t = setTimeout(() => setRender(false), 200);
      return () => clearTimeout(t);
    }
  }, [open]);
  if (!render) return null;
  return createPortal(
    <aside className={open ? "side-panel in" : "side-panel"} style={{ width }} ref={ref} role="dialog" aria-modal="false">
      <button className="icon-btn side-panel-close" onClick={onClose} aria-label="Close panel">
        <IClose size={18} />
      </button>
      {children}
    </aside>,
    document.getElementById("panel-root") ?? root(),
  );
}

export function Confirm({
  open,
  title,
  text,
  confirm,
  cancel = "Cancel",
  danger,
  onConfirm,
  onClose,
  extra,
  typeToConfirm,
}: {
  open: boolean;
  title: string;
  text: ReactNode;
  confirm: string;
  cancel?: string;
  danger?: boolean;
  onConfirm: () => void;
  onClose: () => void;
  extra?: ReactNode;
  typeToConfirm?: string;
}) {
  const [typed, setTyped] = useState("");
  useEffect(() => setTyped(""), [open]);
  const ok = !typeToConfirm || typed === typeToConfirm;
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={title}
      width={440}
      footer={
        <>
          {extra}
          <span className="grow" />
          <button className="btn ghost" onClick={onClose}>
            {cancel}
          </button>
          <button className={danger ? "btn danger" : "btn primary"} disabled={!ok} onClick={onConfirm}>
            {confirm}
          </button>
        </>
      }
    >
      <div className="confirm-text">{text}</div>
      {typeToConfirm && (
        <label className="type-confirm">
          <span>
            Type <strong>{typeToConfirm}</strong> to confirm
          </span>
          <input value={typed} onChange={(e) => setTyped(e.target.value)} autoFocus onKeyDown={(e) => e.key === "Enter" && ok && onConfirm()} />
        </label>
      )}
    </Modal>
  );
}
