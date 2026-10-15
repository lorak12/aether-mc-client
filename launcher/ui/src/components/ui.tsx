import { CSSProperties, ReactNode, useEffect, useId, useState } from "react";
import { IRefresh, IWarning } from "./Icons";
import type { Presence } from "../api/types";
import { api } from "../api";

/** The Kite mark: a diamond kite of four flat facets on a ribbon tail. */
export function Logo({ size = 28, muted = false }: { size?: number; muted?: boolean }) {
  return (
    <svg viewBox="0 0 32 32" width={size} height={size} className={muted ? "logo-mark muted-mark" : "logo-mark"} aria-hidden>
      <path d="M16 3 28 13 16 16Z" fill="#A78BFA" />
      <path d="M16 3 4 13 16 16Z" fill="#8B5CF6" />
      <path d="M4 13 16 16 16 29Z" fill="#7C3AED" />
      <path d="M28 13 16 16 16 29Z" fill="#6D28D9" />
      <path d="M16 29 13 31.5M16 29 19 31.5" stroke="#8B5CF6" strokeWidth="1.6" strokeLinecap="round" fill="none" />
    </svg>
  );
}

export function EmptyState({ icon, title, text, action }: { icon?: ReactNode; title: string; text?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      {icon && <div className="empty-icon">{icon}</div>}
      <strong>{title}</strong>
      {text && <p>{text}</p>}
      {action}
    </div>
  );
}

export function ErrorCard({ message, retry }: { message: string; retry?: () => void }) {
  return (
    <div className="error-card" role="alert">
      <IWarning size={18} />
      <span>{message}</span>
      {retry && (
        <button className="btn sm" onClick={retry}>
          <IRefresh size={15} /> Try again
        </button>
      )}
    </div>
  );
}

export function Skeleton({ h = 16, w = "100%", r = 8, style }: { h?: number; w?: number | string; r?: number; style?: CSSProperties }) {
  return <span className="skeleton" style={{ height: h, width: w, borderRadius: r, ...style }} />;
}

export function SkeletonRows({ n = 5, h = 72 }: { n?: number; h?: number }) {
  return (
    <div className="skeleton-rows">
      {Array.from({ length: n }, (_, i) => (
        <Skeleton key={i} h={h} r={14} style={{ opacity: 1 - i * 0.14 }} />
      ))}
    </div>
  );
}

export function Toggle({ on, onChange, label, disabled }: { on: boolean; onChange: (v: boolean) => void; label?: string; disabled?: boolean }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={label}
      disabled={disabled}
      className={on ? "toggle on" : "toggle"}
      onClick={(e) => {
        e.stopPropagation();
        onChange(!on);
      }}
    >
      <span />
    </button>
  );
}

export function Checkbox({ checked, onChange, children, disabled }: { checked: boolean; onChange: (v: boolean) => void; children?: ReactNode; disabled?: boolean }) {
  return (
    <label className={disabled ? "check disabled" : "check"}>
      <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      <span className="check-box" aria-hidden>
        <svg viewBox="0 0 16 16" width="12" height="12">
          <path d="m3.500 8.500 3 3 6-7" fill="none" stroke="currentColor" strokeWidth="2.200" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </span>
      {children && <span>{children}</span>}
    </label>
  );
}

export function Segmented<T extends string>({ value, options, onChange, size }: { value: T; options: { id: T; label: ReactNode }[]; onChange: (v: T) => void; size?: "sm" }) {
  return (
    <div className={size ? `segmented ${size}` : "segmented"} role="tablist">
      {options.map((o) => (
        <button key={o.id} role="tab" aria-selected={o.id === value} className={o.id === value ? "on" : ""} onClick={() => onChange(o.id)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Tabs<T extends string>({ value, tabs, onChange }: { value: T; tabs: { id: T; label: ReactNode; count?: number }[]; onChange: (v: T) => void }) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((t) => (
        <button key={t.id} role="tab" aria-selected={t.id === value} className={t.id === value ? "tab on" : "tab"} onClick={() => onChange(t.id)}>
          {t.label}
          {t.count !== undefined && t.count > 0 && <span className="count">{t.count}</span>}
        </button>
      ))}
    </div>
  );
}

/** Slider with a typed number beside it and optional marks (e.g. recommended, system max). */
export function Slider({
  value,
  min,
  max,
  step,
  onChange,
  format,
  marks = [],
  disabled,
  unit,
}: {
  value: number;
  min: number;
  max: number;
  step: number;
  onChange: (v: number) => void;
  format?: (v: number) => string;
  marks?: { at: number; label: string }[];
  disabled?: boolean;
  unit?: string;
}) {
  const pct = ((value - min) / (max - min)) * 100;
  const [text, setText] = useState(String(value));
  useEffect(() => setText(String(value)), [value]);
  const commit = () => {
    const n = Number(text);
    if (Number.isFinite(n)) onChange(Math.min(max, Math.max(min, Math.round(n / step) * step)));
    else setText(String(value));
  };
  return (
    <div className={disabled ? "slider disabled" : "slider"}>
      <div className="slider-track-wrap">
        <input
          type="range"
          min={min}
          max={max}
          step={step}
          value={value}
          disabled={disabled}
          onChange={(e) => onChange(Number(e.target.value))}
          style={{ "--pct": `${pct}%` } as CSSProperties}
          aria-valuetext={format?.(value)}
        />
        <div className="slider-marks">
          {marks.map((m) => (
            <span key={m.label} style={{ left: `${((m.at - min) / (max - min)) * 100}%` }}>
              {m.label}
            </span>
          ))}
        </div>
      </div>
      <label className="slider-num">
        <input value={text} disabled={disabled} onChange={(e) => setText(e.target.value)} onBlur={commit} onKeyDown={(e) => e.key === "Enter" && commit()} />
        {unit && <span>{unit}</span>}
      </label>
    </div>
  );
}

const PRESENCE_LABEL: Record<Presence, string> = { online: "Online", away: "Away", busy: "Do not disturb", invisible: "Invisible", offline: "Offline" };
export const presenceLabel = (p: Presence) => PRESENCE_LABEL[p];

/** Skin texture lookups by uuid, shared by every head on screen (null: no Mojang skin; undefined: lookup failed). */
const skinCache = new Map<string, Promise<string | null | undefined>>();
function skinFor(uuid: string): Promise<string | null | undefined> {
  const key = uuid.replace(/-/g, "").toLowerCase();
  let p = skinCache.get(key);
  if (!p) {
    p = api
      .skinUrl(key)
      .catch(() => {
        skinCache.delete(key); // try again next time
        return undefined;
      });
    skinCache.set(key, p);
  }
  return p;
}

/**
 * A player's 2D face. With a uuid, it is drawn from their real skin texture (face plus hat layer, like in game);
 * otherwise, or when that fails, from mc-heads.net; and finally an initial tile when offline.
 */
export function SkinHead({ name, uuid, size = 28, presence, badge }: { name: string; uuid?: string | null; size?: number; presence?: Presence; badge?: boolean }) {
  const key = uuid || name;
  const [skin, setSkin] = useState<string | null>(null);
  const [loading, setLoading] = useState(!!uuid);
  const [failed, setFailed] = useState(false);
  // Start over when the account / player changes, so a failed placeholder never sticks.
  useEffect(() => {
    setFailed(false);
    setSkin(null);
    setLoading(!!uuid);
    if (!uuid) return;
    let live = true;
    skinFor(uuid).then((url) => {
      if (!live) return;
      setSkin(url ?? null);
      setLoading(false);
    });
    return () => {
      live = false;
    };
  }, [key, uuid]);
  const hue = [...name].reduce((a, c) => a + c.charCodeAt(0), 0) % 360;
  // The skin is 64 px wide: the face is 8x8 at (8, 8), the hat layer at (40, 8).
  const layer = (x: number): CSSProperties => ({
    backgroundImage: `url("${skin}")`,
    backgroundSize: `${size * 8}px auto`,
    backgroundPosition: `${-x * (size / 8)}px ${-size}px`,
  });
  return (
    <span className="head" style={{ width: size, height: size }}>
      {skin && !failed ? (
        <span className="head-skin" role="img" aria-label={name}>
          <span style={layer(8)} />
          <span style={layer(40)} />
          {/* Notices a texture that fails to load (background images can't), then falls back to mc-heads. */}
          <img src={skin} alt="" hidden onError={() => setSkin(null)} />
        </span>
      ) : !failed && !loading && key ? (
        <img src={`https://mc-heads.net/avatar/${encodeURIComponent(key)}/${Math.max(32, size * 2)}`} alt="" width={size} height={size} onError={() => setFailed(true)} draggable={false} />
      ) : (
        <span className="head-fallback" style={{ background: `hsl(${hue} 45% 38%)`, fontSize: size * 0.45 }}>
          {name ? name[0].toUpperCase() : "?"}
        </span>
      )}
      {badge && (
        <span className="head-badge">
          <Logo size={Math.max(12, size * 0.42)} />
        </span>
      )}
      {presence && <span className={`presence ${presence}`} title={presenceLabel(presence)} />}
    </span>
  );
}

/** Profile cover: the stored image or gradient, else generated from the loader and name. */
export function Cover({ cover, seed, loader, className, children }: { cover: string | null; seed: string; loader?: string; className?: string; children?: ReactNode }) {
  const style: CSSProperties = {};
  if (cover && /^(https?:|data:|blob:|asset:)/.test(cover)) style.backgroundImage = `url("${cover}")`;
  else if (cover) style.background = cover;
  else {
    const h = [...seed].reduce((a, c) => (a * 31 + c.charCodeAt(0)) % 360, 7);
    const base: Record<string, number> = { fabric: 225, quilt: 275, forge: 25, neoforge: 15, vanilla: 140 };
    const b = base[loader ?? ""] ?? h;
    style.background = `radial-gradient(110% 90% at 80% 0%, hsl(${b} 80% 62% / .75), transparent 60%), radial-gradient(90% 80% at 0% 100%, hsl(${(h + b) % 360} 70% 55% / .55), transparent 60%), linear-gradient(160deg, hsl(${b} 30% 14%), #07090f)`;
  }
  return (
    <div className={className ? `cover ${className}` : "cover"} style={style}>
      {!cover && <span className="cover-blocks" aria-hidden />}
      {children}
    </div>
  );
}

export function Field({ label, hint, children, wide }: { label: ReactNode; hint?: ReactNode; children: ReactNode; wide?: boolean }) {
  return (
    <div className={wide ? "field wide" : "field"}>
      <div className="field-label">{label}</div>
      <div className="field-body">
        {children}
        {hint && <div className="hint">{hint}</div>}
      </div>
    </div>
  );
}

export function Spinner({ size = 16 }: { size?: number }) {
  return <span className="spinner" style={{ width: size, height: size }} />;
}
