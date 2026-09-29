import { useCallback, useEffect, useState } from "react";
import { PageHead, IconSearch } from "../Ui";
import { api, errText, Hit, Installed, Kind, Profile, UpdateInfo } from "../api";

const KINDS: { id: Kind; label: string }[] = [
  { id: "mod", label: "Mods" },
  { id: "resourcepack", label: "Resource packs" },
  { id: "shader", label: "Shaders" },
  { id: "datapack", label: "Datapacks" },
];

export function Browse({ profile, notify, refresh }: { profile: Profile | null; notify: (m: string) => void; refresh: () => Promise<void> }) {
  const [kind, setKind] = useState<Kind>("mod");
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<Hit[]>([]);
  const [total, setTotal] = useState(0);
  const [installed, setInstalled] = useState<Installed[]>([]);
  const [updates, setUpdates] = useState<UpdateInfo[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [world, setWorld] = useState("");

  const pid = profile?.id ?? null;

  const loadInstalled = useCallback(async () => {
    if (!pid) return setInstalled([]);
    setInstalled(await api.listInstalled(pid));
  }, [pid]);

  useEffect(() => {
    loadInstalled().catch((e) => notify(errText(e)));
    setUpdates([]);
  }, [loadInstalled, notify]);

  useEffect(() => {
    if (!pid) return;
    const t = setTimeout(() => {
      api
        .search(query, kind, pid)
        .then((r) => {
          setHits(r.hits);
          setTotal(r.total_hits);
        })
        .catch((e) => notify(errText(e)));
    }, 250);
    return () => clearTimeout(t);
  }, [query, kind, pid, notify]);

  if (!profile) return <div className="page"><PageHead title="Content" sub="Create a profile first." /></div>;

  const has = (id: string) => installed.some((i) => i.project_id === id);

  const install = async (h: Hit) => {
    if (kind === "datapack" && !world.trim()) return notify("Enter the world folder name to install the datapack into");
    setBusy(h.project_id);
    try {
      const added = await api.install(profile.id, h.project_id, kind, kind === "datapack" ? world.trim() : null);
      notify(`Installed ${h.title}${added.length > 1 ? ` (+${added.length - 1} dependencies)` : ""}`);
      await loadInstalled();
    } catch (e) {
      notify(errText(e));
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="page">
      <PageHead title="Content" sub={<>Installing into <strong>{profile.name}</strong> · {profile.mc_version} · {profile.loader}</>} />
      <div className="tabs">
        {KINDS.map((k) => (
          <button key={k.id} className={kind === k.id ? "tab active" : "tab"} onClick={() => setKind(k.id)}>
            {k.label}
          </button>
        ))}
      </div>
      <div className="row searchbar">
        <span className="search-ico"><IconSearch /></span>
        <input placeholder={`Search ${KINDS.find((k) => k.id === kind)!.label.toLowerCase()} on Modrinth…`} value={query} onChange={(e) => setQuery(e.target.value)} />
        {kind === "datapack" && <input placeholder="World folder name" value={world} onChange={(e) => setWorld(e.target.value)} />}
      </div>
      <div className="grid2 wide">
        <section>
          <div className="muted small">{total.toLocaleString()} results</div>
          <ul className="list hits">
            {hits.map((h) => (
              <li key={h.project_id}>
                <div className="hit">
                  {h.icon_url ? <img src={h.icon_url} alt="" /> : <span className="icon-ph" />}
                  <div>
                    <strong>{h.title}</strong> <span className="muted small">by {h.author}</span>
                    <div className="muted small clamp">{h.description}</div>
                  </div>
                </div>
                <button className="primary" disabled={busy === h.project_id || has(h.project_id)} onClick={() => install(h)}>
                  {has(h.project_id) ? "Installed" : busy === h.project_id ? "…" : "Install"}
                </button>
              </li>
            ))}
          </ul>
        </section>
        <section className="card">
          <div className="row between">
            <h3>Installed ({installed.length})</h3>
            <div className="row">
              <button onClick={() => api.checkUpdates(profile.id).then((u) => (setUpdates(u), notify(u.length ? `${u.length} updates` : "Everything is up to date"))).catch((e) => notify(errText(e)))}>
                Check updates
              </button>
              {updates.length > 0 && (
                <button className="primary" onClick={() => api.updateAll(profile.id).then((n) => (notify(`Updated ${n}`), setUpdates([]), loadInstalled())).catch((e) => notify(errText(e)))}>
                  Update all
                </button>
              )}
            </div>
          </div>
          <ul className="list compact">
            {installed.map((i) => (
              <li key={i.project_id}>
                <div>
                  {i.title} <span className="muted small">{i.project_type}{i.explicit ? "" : " · dependency"}</span>
                  {updates.some((u) => u.project_id === i.project_id) && <span className="badge">update</span>}
                </div>
                <button className="danger" onClick={() => api.uninstall(profile.id, i.project_id).then(loadInstalled).then(refresh).catch((e) => notify(errText(e)))}>
                  Remove
                </button>
              </li>
            ))}
          </ul>
        </section>
      </div>
    </div>
  );
}
