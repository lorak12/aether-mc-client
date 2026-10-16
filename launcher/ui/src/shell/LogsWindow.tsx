import { useEffect, useMemo, useRef, useState } from "react";
import { api, errText } from "../api";
import { ICheck, ICopy, IFolder, ISearch, ITrash } from "../components/Icons";
import { Logo, Segmented, Toggle } from "../components/ui";
import { type Level, type Parsed, type Seg, parseLine, plainText } from "../lib/logline";
import { WindowControls } from "./WindowControls";

const MAX = 5000;

type Row = Parsed & { i: number; plain: string };

const segStyle = (s: Seg): React.CSSProperties | undefined => {
  const st = s.style;
  if (!st) return undefined;
  const deco = [st.underline && "underline", st.strike && "line-through"].filter(Boolean).join(" ");
  return { color: st.color, fontWeight: st.bold ? 700 : undefined, fontStyle: st.italic ? "italic" : undefined, textDecoration: deco || undefined };
};

function Segs({ segs, level }: { segs: Seg[]; level: Level }) {
  return (
    <>
      {segs.map((s, k) =>
        s.href ? (
          <a
            key={k}
            className="lg-link"
            href={s.href}
            style={segStyle(s)}
            title={`Open ${s.href}`}
            onClick={(e) => {
              e.preventDefault();
              api.openUrl(s.href!).catch(() => {});
            }}
          >
            {s.text}
          </a>
        ) : (
          <span key={k} className={s.kind ? (s.kind === "level" ? `lg-level ${level}` : `lg-${s.kind}`) : undefined} style={segStyle(s)}>
            {s.text}
          </span>
        ),
      )}
    </>
  );
}

/** Second window: the running game's output, streamed from the profile's launcher-output.log. */
export function LogsWindow() {
  const [lines, setLines] = useState<string[]>([]);
  const [profile, setProfile] = useState<string | null>(null);
  const [filter, setFilter] = useState<"all" | Level>("all");
  const [q, setQ] = useState("");
  const [follow, setFollow] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [names, setNames] = useState<Record<string, string>>({});
  useEffect(() => {
    api.listProfiles().then((l) => setNames(Object.fromEntries(l.map((p) => [p.id, p.name])))).catch(() => {});
  }, []);
  const offset = useRef(0);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let stop = false;
    const tick = async () => {
      try {
        const chunk = await api.readGameLog(offset.current, null);
        if (chunk.offset < offset.current) setLines([]); // a new launch recreated the log
        offset.current = chunk.offset;
        setProfile(chunk.profile_id);
        if (chunk.lines.length) setLines((l) => [...l, ...chunk.lines].slice(-MAX));
        setErr(null);
      } catch (e) {
        setErr(errText(e));
      }
      if (!stop) setTimeout(tick, 700);
    };
    tick();
    return () => {
      stop = true;
    };
  }, []);

  const rows = useMemo<Row[]>(() => {
    let prev: Level | undefined;
    return lines.map((raw, i) => {
      const p = parseLine(raw, prev);
      prev = p.level;
      return { ...p, i, plain: plainText(raw) };
    });
  }, [lines]);

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    const want = (l: Level) => filter === "all" || l === filter || (filter === "info" && l === "debug");
    return rows.filter((r) => want(r.level) && (!needle || r.plain.toLowerCase().includes(needle)));
  }, [rows, filter, q]);

  const [copied, setCopied] = useState<number | null>(null);
  const copiedTimer = useRef<ReturnType<typeof setTimeout>>();
  const copyLine = (r: Row) => {
    navigator.clipboard?.writeText(r.plain).then(() => {
      setCopied(r.i);
      clearTimeout(copiedTimer.current);
      copiedTimer.current = setTimeout(() => setCopied(null), 1200);
    });
  };

  useEffect(() => {
    if (follow && box.current) box.current.scrollTop = box.current.scrollHeight;
  }, [shown, follow]);

  const counts = useMemo(() => {
    const c = { warn: 0, error: 0 };
    for (const r of rows) if (r.level === "warn" || r.level === "error") c[r.level]++;
    return c;
  }, [rows]);

  return (
    <div className="logs-win">
      <header className="topbar" data-tauri-drag-region>
        <div className="tb-left" data-tauri-drag-region>
          <Logo size={22} />
          <strong className="brand-name">Live logs</strong>
          <span className="muted small">{profile ? names[profile] ?? profile : "No game running"}</span>
        </div>
        <div className="tb-drag" data-tauri-drag-region />
        <WindowControls />
      </header>
      <div className="logs-tools">
        <Segmented
          size="sm"
          value={filter}
          onChange={setFilter}
          options={[
            { id: "all", label: "All" },
            { id: "info", label: "Info" },
            { id: "warn", label: `Warnings ${counts.warn || ""}` },
            { id: "error", label: `Errors ${counts.error || ""}` },
          ]}
        />
        <label className="sb-input small-input">
          <ISearch size={15} />
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Filter lines" />
        </label>
        <span className="grow" />
        <label className="row small">
          <Toggle on={follow} onChange={setFollow} label="Follow output" /> Follow
        </label>
        <button className="icon-btn" title="Copy shown lines" aria-label="Copy shown lines" onClick={() => navigator.clipboard?.writeText(shown.map((r) => r.plain).join("\n"))}>
          <ICopy size={17} />
        </button>
        <button className="icon-btn" title="Open logs folder" aria-label="Open logs folder" disabled={!profile} onClick={() => api.openPath("client-logs", profile).catch(() => {})}>
          <IFolder size={17} />
        </button>
        <button className="icon-btn" title="Clear view" aria-label="Clear view" onClick={() => setLines([])}>
          <ITrash size={17} />
        </button>
      </div>
      {err && <div className="logs-err">{err}</div>}
      <div
        className="logs-box"
        ref={box}
        onScroll={(e) => {
          const el = e.currentTarget;
          const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 24;
          if (!atBottom && follow) setFollow(false);
        }}
      >
        {shown.length === 0 ? (
          <p className="logs-empty">{lines.length ? "No lines match the filter." : profile ? "Waiting for output…" : "Start a game from the launcher to see its output here."}</p>
        ) : (
          shown.map((r) => (
            <div key={r.i} className={`log-line ${r.level}${r.trace ? " trace" : ""}${copied === r.i ? " copied" : ""}`}>
              <button className="ln" title="Copy line" aria-label={`Copy line ${r.i + 1}`} onClick={() => copyLine(r)}>
                {r.i + 1}
              </button>
              <span className="lg-text">
                <Segs segs={r.segs} level={r.level} />
              </span>
              <button className="lg-copy" title="Copy line" aria-label={`Copy line ${r.i + 1}`} onClick={() => copyLine(r)}>
                {copied === r.i ? <ICheck size={13} /> : <ICopy size={13} />}
              </button>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
