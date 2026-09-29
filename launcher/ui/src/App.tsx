import { ReactNode, useCallback, useEffect, useState } from "react";
import { WindowControls } from "./WindowControls";
import { IconContent, IconHome, IconProfiles, IconSettings } from "./Icons";
import { listen } from "@tauri-apps/api/event";
import { api, ConfigStatus, errText, GameExit, LaunchProgress, Profile } from "./api";
import { Profiles } from "./pages/Profiles";
import { Browse } from "./pages/Browse";
import { Settings } from "./pages/Settings";
import { Home, LaunchState } from "./pages/Home";

type Page = "home" | "profiles" | "browse" | "settings";

const NAV: { id: Page; label: string; icon: ReactNode }[] = [
  { id: "home", label: "Home", icon: <IconHome /> },
  { id: "profiles", label: "Profiles", icon: <IconProfiles /> },
  { id: "browse", label: "Content", icon: <IconContent /> },
  { id: "settings", label: "Settings", icon: <IconSettings /> },
];

export function App() {
  const [page, setPage] = useState<Page>("home");
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [cfg, setCfg] = useState<ConfigStatus | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const notify = useCallback((m: string) => {
    setToast(m);
    // Long messages (sign-in instructions) need time to read.
    setTimeout(() => setToast((t) => (t === m ? null : t)), Math.max(4000, m.length * 70));
  }, []);

  const refresh = useCallback(async () => {
    try {
      const list = await api.listProfiles();
      setProfiles(list);
      setSelected((s) => (s && list.some((p) => p.id === s) ? s : list[0]?.id ?? null));
    } catch (e) {
      notify(errText(e));
    }
  }, [notify]);

  useEffect(() => {
    refresh();
    api.configStatus().then(setCfg).catch(() => {});
  }, [refresh]);

  // undefined = still loading, null = signed out, string = signed in (may be "" until the name is cached)
  const [account, setAccount] = useState<string | null | undefined>(undefined);
  useEffect(() => {
    api.accountName().then(setAccount).catch(() => setAccount(null));
  }, [page]);

  const [launch, setLaunch] = useState<LaunchState>({ phase: "idle" });
  useEffect(() => {
    api.runningProfile().then((id) => id && setLaunch({ phase: "running", profileId: id })).catch(() => {});
    const unProgress = listen<LaunchProgress>("launch-progress", ({ payload }) =>
      setLaunch((l) => {
        if (l.phase !== "preparing") return l;
        return payload.stage === "running" ? { phase: "running", profileId: l.profileId } : { ...l, progress: payload };
      }),
    );
    const unExit = listen<GameExit>("game-exit", ({ payload }) =>
      setLaunch(payload.code === 0 ? { phase: "idle" } : { phase: "crashed", code: payload.code, log: payload.log_tail ?? "" }),
    );
    return () => {
      unProgress.then((f) => f());
      unExit.then((f) => f());
    };
  }, []);

  const play = async () => {
    if (!selected) return;
    setLaunch({ phase: "preparing", profileId: selected, progress: null });
    try {
      const name = await api.launch(selected);
      setAccount(name);
      // If the game already exited (instant crash), game-exit has set the final state.
      setLaunch((l) => (l.phase === "preparing" ? { phase: "running", profileId: l.profileId } : l));
    } catch (e) {
      setLaunch({ phase: "idle" });
      notify(errText(e));
    }
  };

  const current = profiles.find((p) => p.id === selected) ?? null;

  const status = launch.phase === "running" ? "Playing" : launch.phase === "preparing" ? "Preparing" : "Ready";

  return (
    <div className="shell">
      <div className="aurora" aria-hidden />
      <aside className="rail">
        <div className="logo" title="Aether" />
        <nav>
          {NAV.map((n) => (
            <button key={n.id} className={page === n.id ? "nav active" : "nav"} onClick={() => setPage(n.id)} aria-label={n.label}>
              {n.icon}
              <span className="nav-label">{n.label}</span>
            </button>
          ))}
        </nav>
        <div className={`status-dot ${launch.phase}`} title={status} />
      </aside>
      <div className="main">
        <header className="topbar" data-tauri-drag-region>
          <div className="crumb" data-tauri-drag-region>
            <span className="brand">Aether</span>
            <span className="sep">/</span>
            <span>{NAV.find((n) => n.id === page)!.label}</span>
          </div>
          <div className="topbar-right">
            <label className="picker">
              <span className="muted small">Profile</span>
              <select value={selected ?? ""} onChange={(e) => setSelected(e.target.value)}>
                {profiles.length === 0 && <option value="">No profiles</option>}
                {profiles.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </label>
            <button className="account-chip" onClick={() => setPage("settings")} title={typeof account === "string" ? "Account" : "Sign in"}>
              <span className={typeof account === "string" ? "avatar on" : "avatar"}>{typeof account === "string" && account ? account[0].toUpperCase() : "?"}</span>
              <span>{typeof account === "string" ? account || "Signed in" : account === null ? "Sign in" : "…"}</span>
            </button>
            <WindowControls />
          </div>
        </header>
        <main className={page === "home" ? "content flush" : "content"} key={page}>
          {page === "home" && (
          <Home
            profile={current}
            cfg={cfg}
            account={account}
            launch={launch}
            play={play}
            dismiss={() => setLaunch({ phase: "idle" })}
            go={setPage}
            profiles={profiles}
            select={setSelected}
          />
        )}
        {page === "profiles" && (
          <Profiles profiles={profiles} selected={selected} select={setSelected} refresh={refresh} notify={notify} />
        )}
        {page === "browse" && <Browse profile={current} notify={notify} refresh={refresh} />}
        {page === "settings" && <Settings cfg={cfg} notify={notify} />}
        </main>
      </div>
      {toast && <div className="toast">{toast}</div>}
    </div>
  );
}
