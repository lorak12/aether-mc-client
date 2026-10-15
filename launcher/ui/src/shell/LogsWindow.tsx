import { useEffect, useMemo, useRef, useState } from "react";
import { api, errText } from "../api";
import { ICopy, IFolder, ISearch, ITrash } from "../components/Icons";
import { Logo, Segmented, Toggle } from "../components/ui";
import { WindowControls } from "./WindowControls";

type Level = "info" | "warn" | "error";
const levelOf = (l: string): Level => (/\b(ERROR|FATAL|Exception|Caused by)\b|\/ERROR\]/.test(l) ? "error" : /\bWARN(ING)?\b|\/WARN\]/.test(l) ? "warn" : "info");
const MAX = 5000;

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

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return lines.map((text, i) => ({ text, i, level: levelOf(text) })).filter((l) => (filter === "all" || l.level === filter) && (!needle || l.text.toLowerCase().includes(needle)));
  }, [lines, filter, q]);

  useEffect(() => {
    if (follow && box.current) box.current.scrollTop = box.current.scrollHeight;
  }, [shown, follow]);

  const counts = useMemo(() => {
    const c = { warn: 0, error: 0 };
    for (const l of lines) {
      const lv = levelOf(l);
      if (lv !== "info") c[lv]++;
    }
    return c;
  }, [lines]);

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
        <button className="icon-btn" title="Copy shown lines" aria-label="Copy shown lines" onClick={() => navigator.clipboard?.writeText(shown.map((l) => l.text).join("\n"))}>
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
          shown.map((l) => (
            <div key={l.i} className={`log-line ${l.level}`}>
              <span className="ln">{l.i + 1}</span>
              <span>{l.text}</span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
