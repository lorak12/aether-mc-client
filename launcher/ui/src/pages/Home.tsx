import { CSSProperties } from "react";
import { ConfigStatus, LaunchProgress, Profile } from "../api";
import { IconPlay } from "../Icons";
import { PALETTES, Scene } from "../Scene";

export type LaunchState =
  | { phase: "idle" }
  | { phase: "preparing"; profileId: string; progress: LaunchProgress | null }
  | { phase: "running"; profileId: string }
  | { phase: "crashed"; code: number | null; log: string };

const STAGES: Record<string, string> = {
  account: "Signing in",
  version: "Fetching version info",
  java: "Downloading Java",
  libraries: "Downloading libraries",
  assets: "Downloading assets",
  running: "Starting Minecraft",
};

interface Props {
  profile: Profile | null;
  profiles: Profile[];
  select: (id: string) => void;
  cfg: ConfigStatus | null;
  account: string | null | undefined;
  launch: LaunchState;
  play: () => void;
  dismiss: () => void;
  go: (p: "profiles" | "settings") => void;
}

export function Home({ profile, profiles, select, cfg, account, launch, play, dismiss, go }: Props) {
  const signedIn = typeof account === "string";
  const busy = launch.phase === "preparing" || launch.phase === "running";
  const p = launch.phase === "preparing" ? launch.progress : null;
  const pct = p && p.total > 0 ? Math.round((p.done / p.total) * 100) : 0;
  const loader = profile?.loader ?? "fabric";
  const accent = (PALETTES[loader] ?? PALETTES.fabric).accent;

  let label = "Play";
  if (launch.phase === "preparing") label = "Preparing…";
  if (launch.phase === "running") label = "Running";

  const intensity = launch.phase === "preparing" ? "preparing" : launch.phase === "running" ? "running" : "idle";

  return (
    <div className="stage" style={{ "--hero": accent } as CSSProperties}>
      <Scene loader={loader} intensity={intensity} />
      <div className="stage-fade" />

      <div className="stage-copy">
        <span className="eyebrow">{account ? `Welcome back, ${account}` : "Welcome back"}</span>
        {profile ? (
          <>
            <h1 className="hero-title" key={profile.id}>
              {profile.name}
            </h1>
            <div className="chips">
              <span className="chip">Minecraft {profile.mc_version}</span>
              <span className="chip accent">{profile.loader}</span>
              <span className="chip">{(profile.memory_mb / 1024).toFixed(profile.memory_mb % 1024 ? 1 : 0)} GB RAM</span>
            </div>
            <div className="hero-actions">
              <button
                className={`play ${launch.phase}`}
                disabled={busy || !signedIn}
                title={signedIn ? "" : "Sign in with Microsoft in Settings first"}
                onClick={play}
              >
                {launch.phase === "idle" || launch.phase === "crashed" ? <IconPlay /> : <span className="spinner" />}
                <span>{label}</span>
              </button>
              <button className="ghost" onClick={() => go("profiles")}>
                Manage
              </button>
            </div>
            {launch.phase === "preparing" && (
              <div className="launch-progress">
                <div className="row between small">
                  <span>{STAGES[p?.stage ?? "account"] ?? p?.stage}</span>
                  {p && p.total > 1 && (
                    <span className="muted">
                      {p.done} / {p.total}
                    </span>
                  )}
                </div>
                <div className="progress">
                  <div style={{ width: `${launch.progress ? Math.max(pct, 4) : 4}%` }} />
                </div>
                <span className="muted small">First launch of a version downloads Java and game files; later launches are quick.</span>
              </div>
            )}
            {launch.phase === "running" && <span className="muted">Minecraft is running — close the game to launch again.</span>}
          </>
        ) : (
          <>
            <h1 className="hero-title">Build your first world</h1>
            <p className="muted lead">Pick a Minecraft version and start adding mods, shaders and packs.</p>
            <div className="hero-actions">
              <button className="play" onClick={() => go("profiles")}>
                <IconPlay /> <span>New profile</span>
              </button>
            </div>
          </>
        )}
      </div>

      <div className="stage-alerts">
        {launch.phase === "crashed" && (
          <div className="card">
            <div className="row between">
              <strong>Minecraft exited with code {launch.code ?? "unknown"}</strong>
              <button onClick={dismiss}>Dismiss</button>
            </div>
            <p className="muted small">Full output is in the profile's logs/launcher-output.log.</p>
            <pre className="log">{launch.log}</pre>
          </div>
        )}
        {cfg && !cfg.login && (
          <div className="card notice">
            <span>Microsoft sign-in isn't configured yet.</span>
            <button className="link" onClick={() => go("settings")}>
              See what to add
            </button>
          </div>
        )}
        {cfg?.login && account === null && (
          <div className="card notice">
            <span>Sign in with the account that owns Minecraft to play.</span>
            <button className="link" onClick={() => go("settings")}>
              Sign in
            </button>
          </div>
        )}
      </div>

      {profiles.length > 0 && (
        <div className="dock">
          <span className="dock-label">Worlds</span>
          <div className="dock-scroll">
            {profiles.map((pr) => (
              <button key={pr.id} className={pr.id === profile?.id ? "tile sel" : "tile"} onClick={() => select(pr.id)} disabled={busy}>
                <span className={`tile-art l-${pr.loader}`} />
                <span className="tile-meta">
                  <span className="tile-name">{pr.name}</span>
                  <span className="muted small">
                    {pr.mc_version} · {pr.loader}
                  </span>
                </span>
              </button>
            ))}
            <button className="tile add" onClick={() => go("profiles")}>
              <span className="plus">+</span>
              <span className="tile-name">New</span>
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
