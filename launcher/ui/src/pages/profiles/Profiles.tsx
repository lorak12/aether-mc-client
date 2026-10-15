import { useEffect, useMemo, useState } from "react";
import type { Profile, ProfileKind } from "../../api/types";
import { useApp } from "../../app/store";
import { IPlay, IPlus, IProfiles, ISparkle, IGlobe, IStop, IProfiles as IStack } from "../../components/Icons";
import { SearchBar } from "../../components/SearchBar";
import { Cover, EmptyState, SkeletonRows, Spinner } from "../../components/ui";
import { coverOf } from "../../lib/art";
import { ago } from "../../lib/format";
import { compareVersions, LOADER_NAMES } from "../../lib/versions";
import { ProfilePanel } from "./ProfilePanel";

type Cat = "all" | ProfileKind;
type Sort = "played" | "popular" | "name" | "created" | "version";

export function ProfilesPage({ open }: { open?: string }) {
  const app = useApp();
  const [cat, setCat] = useState<Cat>("all");
  const [q, setQ] = useState("");
  const [sort, setSort] = useState<Sort>("played");
  const [panel, setPanel] = useState<string | null>(open ?? null);
  useEffect(() => setPanel(open ?? null), [open]);

  const list = useMemo(() => {
    const needle = q.trim().toLowerCase();
    const rows = app.profiles.map((p) => ({ p, m: p.meta }));
    const filtered = rows.filter(({ p, m }) => (cat === "all" || m.kind === cat) && (!needle || `${p.name} ${p.mc_version} ${p.loader}`.toLowerCase().includes(needle)));
    const by: Record<Sort, (a: (typeof rows)[0], b: (typeof rows)[0]) => number> = {
      played: (a, b) => (b.m.lastPlayed ?? 0) - (a.m.lastPlayed ?? 0) || b.p.created_unix - a.p.created_unix,
      popular: (a, b) => b.m.plays - a.m.plays,
      name: (a, b) => a.p.name.localeCompare(b.p.name),
      created: (a, b) => b.p.created_unix - a.p.created_unix,
      version: (a, b) => compareVersions(b.p.mc_version, a.p.mc_version),
    };
    return filtered.sort(by[sort]);
  }, [app.profiles, cat, q, sort]);

  const selected = app.profiles.find((p) => p.id === panel) ?? null;

  const L = app.launch;
  const busy = L.phase === "preparing" || L.phase === "running";
  const runningId = busy && "profileId" in L ? L.profileId : null;
  const playFromCard = (p: Profile) => {
    if (runningId === p.id) return void app.stop();
    app.navigate({ page: "home" });
    app.play(p.id);
  };

  return (
    <div className="page">
      <header className="page-head">
        <h1>Profiles</h1>
      </header>
      <SearchBar<Cat, Sort>
        category={{
          value: cat,
          onChange: setCat,
          options: [
            { id: "all", label: "All profiles", icon: <IStack size={16} /> },
            { id: "client", label: "Client versions", icon: <ISparkle size={16} /> },
            { id: "vanilla", label: "Vanilla versions", icon: <IGlobe size={16} /> },
            { id: "modpack", label: "Modpacks", icon: <IProfiles size={16} /> },
          ],
        }}
        query={q}
        onQuery={setQ}
        placeholder="Search profiles"
        sort={{
          value: sort,
          onChange: setSort,
          options: [
            { id: "played", label: "Last played" },
            { id: "popular", label: "Most played" },
            { id: "name", label: "Name" },
            { id: "created", label: "Recently created" },
            { id: "version", label: "Game version" },
          ],
        }}
        trailing={
          <button className="btn primary" onClick={() => app.setNewProfileOpen(true)}>
            <IPlus size={17} /> New profile
          </button>
        }
      />

      {!app.profilesLoaded ? (
        <SkeletonRows n={3} h={180} />
      ) : app.profiles.length === 0 ? (
        <EmptyState
          icon={<IProfiles size={24} />}
          title="No profiles yet"
          text="A profile is one game setup: a Minecraft version, a loader and its own mods and worlds."
          action={
            <button className="btn primary" onClick={() => app.setNewProfileOpen(true)}>
              <IPlus size={17} /> Create your first profile
            </button>
          }
        />
      ) : list.length === 0 ? (
        <EmptyState title="No profiles match" text="Try another search or category." action={<button className="btn" onClick={() => (setQ(""), setCat("all"))}>Clear filters</button>} />
      ) : (
        <div className="profile-grid">
          {list.map(({ p, m }) => (
            <article key={p.id} className={p.id === app.activeId ? "pcard active" : "pcard"}>
              <button className="pcard-open" onClick={() => setPanel(p.id)} aria-label={`Open ${p.name}`}>
                <Cover cover={coverOf(p)} seed={p.id} loader={p.loader} className="pcard-cover">
                  {p.id === app.activeId && <span className="pcard-flag">Selected</span>}
                </Cover>
              </button>
              <div className="pcard-body">
                <button className="pcard-name" onClick={() => setPanel(p.id)}>
                  {p.name}
                </button>
                <div className="pcard-meta">
                  <span className="chip">{p.mc_version}</span>
                  <span className="chip">{m.builtinOnly ? "Vanilla" : LOADER_NAMES[p.loader]}</span>
                  {m.kind === "modpack" && <span className="chip accent">Modpack</span>}
                </div>
                <span className="muted small">{m.lastPlayed ? `Played ${ago(m.lastPlayed)}` : "Never played"}</span>
              </div>
              <button
                className={runningId === p.id ? "pcard-play live" : "pcard-play"}
                onClick={() => playFromCard(p)}
                disabled={(runningId !== p.id && busy) || !app.account}
                aria-label={runningId === p.id ? `Stop ${p.name}` : `Launch ${p.name}`}
                title={runningId === p.id ? "Stop the game" : !app.account ? "Add an account first" : busy ? "Another profile is running" : "Launch"}
              >
                {runningId === p.id ? L.phase === "preparing" ? <Spinner size={14} /> : <IStop size={14} /> : <IPlay size={16} />}
              </button>
            </article>
          ))}
        </div>
      )}

      <ProfilePanel profile={selected} onClose={() => setPanel(null)} />
    </div>
  );
}
