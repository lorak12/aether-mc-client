import { CSSProperties, useMemo, useState } from "react";
import { api } from "../../api";
import type { SavedServer, Server } from "../../api/types";
import { useApp } from "../../app/store";
import { useAsync } from "../../components/hooks";
import { ICopy, IPlay, IPlus, IServer, IStar, IUser } from "../../components/Icons";
import { FilterGroup, SearchBar } from "../../components/SearchBar";
import { Checkbox, EmptyState, ErrorCard, SkeletonRows, Spinner } from "../../components/ui";
import { compact } from "../../lib/format";
import { pingClass, usePings } from "../../lib/ping";

type Cat = "all" | "partner" | "mine";
type Sort = "players" | "ping" | "name";
const REGIONS: Server["region"][] = ["EU", "NA", "Asia", "OCE"];
const REGION_NAME: Record<string, string> = { NA: "North America", EU: "Europe", OCE: "Oceania", Asia: "Asia" };

/** A server from any profile's in-game list. */
interface Mine extends SavedServer {
  profileId: string;
  profileName: string;
}

export function ExplorePage() {
  const app = useApp();
  const backend = !!app.cfg?.backend;
  const curated = useAsync(() => (backend ? api.servers() : Promise.resolve([])), [backend]);
  const ids = app.profiles.map((p) => p.id).join();
  const mine = useAsync(async () => {
    const lists = await Promise.all(app.profiles.map((p) => api.profileServers(p.id).then((l) => l.map((s): Mine => ({ ...s, profileId: p.id, profileName: p.name }))).catch(() => [] as Mine[])));
    // One row per address; the most recently played profile wins.
    const seen = new Map<string, Mine>();
    const order = [...app.profiles].sort((a, b) => (b.meta.lastPlayed ?? 0) - (a.meta.lastPlayed ?? 0)).map((p) => p.id);
    for (const s of lists.flat().sort((a, b) => order.indexOf(a.profileId) - order.indexOf(b.profileId))) {
      const k = s.address.toLowerCase();
      if (!seen.has(k)) seen.set(k, s);
    }
    return [...seen.values()];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ids]);
  const [cat, setCat] = useState<Cat>("all");
  const [q, setQ] = useState("");
  const [sort, setSort] = useState<Sort>("players");
  const [regions, setRegions] = useState<string[]>([]);
  const [genres, setGenres] = useState<string[]>([]);
  const [adding, setAdding] = useState<string | null>(null);

  const pings = usePings([...(curated.data ?? []).map((s) => s.address), ...(mine.data ?? []).map((s) => s.address)]);
  const players = (a: string) => {
    const r = pings[a];
    return r && "ping" in r ? r.ping.online : -1;
  };
  const latency = (a: string) => {
    const r = pings[a];
    return r && "ping" in r ? r.ping.latency_ms : 99_999;
  };

  const allGenres = useMemo(() => [...new Set((curated.data ?? []).flatMap((s) => s.genres))].sort(), [curated.data]);
  const needle = q.trim().toLowerCase();
  const by = <T extends { name: string; address: string }>(a: T, b: T) =>
    sort === "players" ? players(b.address) - players(a.address) : sort === "ping" ? latency(a.address) - latency(b.address) : a.name.localeCompare(b.name);
  const partners = (curated.data ?? [])
    .filter((s) => cat !== "mine" && (cat === "all" || s.partner))
    .filter((s) => (!needle || `${s.name} ${s.address} ${s.description}`.toLowerCase().includes(needle)) && (!regions.length || regions.includes(s.region)) && (!genres.length || s.genres.some((g) => genres.includes(g))))
    .sort(by);
  const myList = (mine.data ?? []).filter((s) => cat !== "partner" && (!needle || `${s.name} ${s.address}`.toLowerCase().includes(needle))).sort(by);

  const play = (address: string, profileId?: string) => {
    const id = (profileId && app.profiles.some((p) => p.id === profileId) ? profileId : null) ?? app.activeId;
    if (!id) return app.setNewProfileOpen(true);
    app.navigate({ page: "home" });
    app.play(id, { server: address });
  };
  const copy = (address: string) => navigator.clipboard?.writeText(address).then(() => app.toast(`Copied ${address}`));
  const addToProfile = async (s: Server) => {
    if (!app.active) return app.setNewProfileOpen(true);
    setAdding(s.id);
    try {
      await api.addServer(app.active.id, s.name, s.address);
      app.toast(`Added ${s.name} to ${app.active.name}'s server list`, { tone: "success" });
      mine.reload();
    } catch (e) {
      app.error(e);
    } finally {
      setAdding(null);
    }
  };
  const busy = app.launch.phase === "preparing" || app.launch.phase === "running";
  const loading = (backend && !curated.data && !curated.error) || !mine.data;

  return (
    <div className="page">
      <header className="page-head">
        <h1>Explore</h1>
      </header>
      <SearchBar<Cat, Sort>
        category={{
          value: cat,
          onChange: setCat,
          options: [
            { id: "all", label: "All servers", icon: <IServer size={16} /> },
            { id: "partner", label: "Featured servers", icon: <IStar size={16} /> },
            { id: "mine", label: "My servers", icon: <IUser size={16} /> },
          ],
        }}
        query={q}
        onQuery={setQ}
        placeholder="Search servers by name or address"
        sort={{
          value: sort,
          onChange: setSort,
          options: [
            { id: "players", label: "Players online" },
            { id: "ping", label: "Ping" },
            { id: "name", label: "Name" },
          ],
        }}
      />
      <div className="content-layout">
        <div className="content-main">
          {curated.error && <ErrorCard message={curated.error} retry={curated.reload} />}
          {loading ? (
            <SkeletonRows n={4} h={120} />
          ) : partners.length === 0 && myList.length === 0 ? (
            <EmptyState
              icon={<IServer size={22} />}
              title={needle || regions.length || genres.length ? "No servers match" : "No servers yet"}
              text={needle || regions.length || genres.length ? "Try another search or remove some filters." : "Servers you add in the game's multiplayer menu show up here, from every profile."}
            />
          ) : (
            <>
              {partners.length > 0 && (
                <section className="stack">
                  <h2 className="section-title">Featured servers</h2>
                  <div className="partner-grid">
                    {partners.map((s) => {
                      const r = pings[s.address];
                      const p = r && "ping" in r ? r.ping : null;
                      return (
                        <article key={s.id} className="partner" style={{ "--srv": s.color ?? "#6c8cff" } as CSSProperties}>
                          <div className="partner-banner">
                            {p?.favicon ? (
                              <img className="server-icon lg" src={p.favicon} alt="" />
                            ) : (
                              <span className="server-icon lg" style={{ background: s.color ?? "#6c8cff" }}>
                                {s.name[0]}
                              </span>
                            )}
                          </div>
                          <div className="partner-body">
                            <div className="row between">
                              <strong className="partner-name">{s.name}</strong>
                              {p ? <span className={pingClass(p.latency_ms)}>{p.latency_ms} ms</span> : r ? <span className="ping bad">Offline</span> : <Spinner size={12} />}
                            </div>
                            <p className="muted small clamp">{s.description || p?.motd}</p>
                            <div className="row wrap">
                              {p && (
                                <span className="chip online">
                                  <span className="live-dot" /> {compact(p.online)} online
                                </span>
                              )}
                              {(s.version || p?.version) && <span className="chip">{s.version || p?.version}</span>}
                              <span className="chip">{REGION_NAME[s.region]}</span>
                              {s.genres.slice(0, 2).map((g) => (
                                <span key={g} className="chip">
                                  {g}
                                </span>
                              ))}
                            </div>
                            <div className="row">
                              <button className="btn primary grow" disabled={!app.account || busy} onClick={() => play(s.address)} title={`Launch ${app.active?.name ?? "your profile"} and join`}>
                                <IPlay size={14} /> Play
                              </button>
                              <button className="icon-btn" aria-label={`Add ${s.name} to ${app.active?.name ?? "a profile"}`} title="Add to the selected profile's server list" disabled={adding === s.id} onClick={() => addToProfile(s)}>
                                {adding === s.id ? <Spinner size={14} /> : <IPlus size={16} />}
                              </button>
                              <button className="icon-btn" aria-label={`Copy ${s.address}`} title={s.address} onClick={() => copy(s.address)}>
                                <ICopy size={16} />
                              </button>
                            </div>
                          </div>
                        </article>
                      );
                    })}
                  </div>
                </section>
              )}
              {myList.length > 0 && (
                <section className="stack">
                  <h2 className="section-title">My servers</h2>
                  <p className="muted small">From the server lists of all your profiles. Play launches the profile the server was added in.</p>
                  <ul className="world-list">
                    {myList.map((s) => {
                      const r = pings[s.address];
                      const p = r && "ping" in r ? r.ping : null;
                      return (
                        <li key={s.profileId + s.address}>
                          {s.icon || p?.favicon ? (
                            <img className="server-icon" src={s.icon ?? p!.favicon!} alt="" />
                          ) : (
                            <span className="server-icon">{s.name[0]}</span>
                          )}
                          <div className="inst-text">
                            <strong>{s.name}</strong>
                            <span className="muted small ellipsis">
                              {s.address} · {s.profileName}
                              {p?.motd ? ` · ${p.motd}` : ""}
                            </span>
                          </div>
                          {p ? (
                            <>
                              <span className="muted small">
                                {p.online}/{p.max}
                              </span>
                              <span className={pingClass(p.latency_ms)}>{p.latency_ms} ms</span>
                            </>
                          ) : r ? (
                            <span className="ping bad">Offline</span>
                          ) : (
                            <Spinner size={12} />
                          )}
                          <button className="icon-btn" aria-label={`Copy ${s.address}`} onClick={() => copy(s.address)}>
                            <ICopy size={16} />
                          </button>
                          <button className="btn sm primary" disabled={!app.account || busy} onClick={() => play(s.address, s.profileId)}>
                            <IPlay size={13} /> Play
                          </button>
                        </li>
                      );
                    })}
                  </ul>
                </section>
              )}
            </>
          )}
        </div>
        <aside className="filters" aria-label="Filters">
          <FilterGroup title="Region" active={regions.length > 0} onClear={() => setRegions([])}>
            {REGIONS.map((r) => (
              <Checkbox key={r} checked={regions.includes(r)} onChange={(v) => setRegions((s) => (v ? [...s, r] : s.filter((x) => x !== r)))}>
                {REGION_NAME[r]}
              </Checkbox>
            ))}
          </FilterGroup>
          {allGenres.length > 0 && (
            <FilterGroup title="Game mode" active={genres.length > 0} onClear={() => setGenres([])}>
              {allGenres.map((g) => (
                <Checkbox key={g} checked={genres.includes(g)} onChange={(v) => setGenres((s) => (v ? [...s, g] : s.filter((x) => x !== g)))}>
                  {g}
                </Checkbox>
              ))}
            </FilterGroup>
          )}
        </aside>
      </div>
    </div>
  );
}
