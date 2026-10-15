import { CSSProperties, KeyboardEvent, ReactNode, RefObject, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ICheck, IChevronRight } from "./Icons";
import { useClickOutside, useLayer } from "./hooks";

export type MenuItem =
  | { sep: true }
  | { heading: string }
  | {
      label: ReactNode;
      icon?: ReactNode;
      hint?: ReactNode;
      onSelect?: () => void;
      danger?: boolean;
      disabled?: boolean;
      checked?: boolean;
      items?: MenuItem[];
    };

/** Floating box anchored under an element, rendered at the document root so containers can't clip it. */
export function Popout({
  open,
  onClose,
  anchor,
  align = "start",
  children,
  className,
  width,
}: {
  open: boolean;
  onClose: () => void;
  anchor: RefObject<HTMLElement>;
  align?: "start" | "end";
  children: ReactNode;
  className?: string;
  width?: number;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<CSSProperties>({ visibility: "hidden" });
  useLayer(open, onClose);
  useClickOutside([ref, anchor as RefObject<HTMLElement>], onClose, open);
  useLayoutEffect(() => {
    if (!open || !anchor.current) return;
    const place = () => {
      const r = anchor.current!.getBoundingClientRect();
      const h = ref.current?.offsetHeight ?? 0;
      const below = r.bottom + 6;
      const top = below + h > window.innerHeight - 8 && r.top - h - 6 > 8 ? r.top - h - 6 : below;
      setPos(align === "end" ? { top, right: Math.max(8, window.innerWidth - r.right) } : { top, left: Math.max(8, r.left) });
    };
    place();
    window.addEventListener("resize", place);
    return () => window.removeEventListener("resize", place);
  }, [open, anchor, align]);
  if (!open) return null;
  return createPortal(
    <div ref={ref} className={className ? `popout ${className}` : "popout"} style={{ ...pos, width }}>
      {children}
    </div>,
    document.getElementById("menu-root") ?? document.body,
  );
}

type ActionItem = Extract<MenuItem, { label: ReactNode }>;
const isAction = (i: MenuItem): i is ActionItem => "label" in i;

/** Keyboard-navigable menu list; items with `items` open a submenu to the side. */
export function MenuList({ items, onDone, autoFocus = true }: { items: MenuItem[]; onDone: () => void; autoFocus?: boolean }) {
  const [sub, setSub] = useState<number | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    if (autoFocus) listRef.current?.querySelector<HTMLElement>("button:not(:disabled)")?.focus();
  }, [autoFocus]);

  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const btns = [...(listRef.current?.querySelectorAll<HTMLElement>(":scope > .menu-row > button:not(:disabled)") ?? [])];
    const i = btns.indexOf(document.activeElement as HTMLElement);
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      e.stopPropagation();
      const n = btns.length;
      btns[(i + (e.key === "ArrowDown" ? 1 : -1) + n) % n]?.focus();
    } else if (e.key === "ArrowRight") {
      const idx = Number((document.activeElement as HTMLElement)?.dataset.idx);
      const it = items[idx];
      if (it && isAction(it) && it.items) {
        e.preventDefault();
        e.stopPropagation();
        setSub(idx);
      }
    }
  };

  return (
    <div className="menu" role="menu" ref={listRef} onKeyDown={onKey}>
      {items.map((it, idx) => {
        if ("sep" in it) return <div key={idx} className="menu-sep" role="separator" />;
        if ("heading" in it) return <div key={idx} className="menu-heading">{it.heading}</div>;
        const hasSub = !!it.items;
        return (
          <div key={idx} className="menu-row" onMouseEnter={() => setSub(hasSub ? idx : null)}>
            <button
              role="menuitem"
              data-idx={idx}
              className={it.danger ? "menu-item danger" : "menu-item"}
              disabled={it.disabled}
              aria-haspopup={hasSub || undefined}
              aria-expanded={hasSub ? sub === idx : undefined}
              onClick={() => {
                if (hasSub) return setSub(idx);
                it.onSelect?.();
                onDone();
              }}
            >
              <span className="menu-icon">{it.checked ? <ICheck size={16} /> : it.icon}</span>
              <span className="menu-label">{it.label}</span>
              {it.hint && <span className="menu-hint">{it.hint}</span>}
              {hasSub && <IChevronRight size={15} />}
            </button>
            {hasSub && sub === idx && (
              <div
                className="submenu"
                onKeyDown={(e) => {
                  if (e.key === "ArrowLeft") {
                    e.stopPropagation();
                    setSub(null);
                    (listRef.current?.querySelector(`[data-idx="${idx}"]`) as HTMLElement | null)?.focus();
                  }
                }}
              >
                <MenuList items={it.items!} onDone={onDone} autoFocus={false} />
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

/** A button that opens a menu. */
export function Dropdown({
  items,
  children,
  className = "btn",
  align = "start",
  label,
  width,
}: {
  items: MenuItem[];
  children: ReactNode;
  className?: string;
  align?: "start" | "end";
  label?: string;
  width?: number;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLButtonElement>(null);
  return (
    <>
      <button ref={ref} className={open ? `${className} open` : className} aria-haspopup="menu" aria-expanded={open} aria-label={label} onClick={() => setOpen((o) => !o)}>
        {children}
      </button>
      <Popout open={open} onClose={() => setOpen(false)} anchor={ref} align={align} width={width}>
        <MenuList items={items} onDone={() => setOpen(false)} />
      </Popout>
    </>
  );
}
