import { useEffect, useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import { api, errText, Loader, Profile } from "../api";
import { PageHead } from "../Ui";

const LOADERS: Loader[] = ["vanilla", "fabric", "quilt", "forge", "neoforge"];

interface Props {
  profiles: Profile[];
  selected: string | null;
  select: (id: string) => void;
  refresh: () => Promise<void>;
  notify: (m: string) => void;
}

export function Profiles({ profiles, selected, select, refresh, notify }: Props) {
  const [versions, setVersions] = useState<string[]>([]);
  const [name, setName] = useState("");
  const [ver, setVer] = useState("");
  const [loader, setLoader] = useState<Loader>("fabric");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api
      .mcVersions()
      .then((v) => {
        const ids = v.map((x) => x.id);
        setVersions(ids);
        setVer((cur) => cur || ids[0] || "");
      })
      .catch((e) => notify(errText(e)));
  }, [notify]);

  const run = async (fn: () => Promise<unknown>, ok?: string) => {
    setBusy(true);
    try {
      await fn();
      await refresh();
      if (ok) notify(ok);
    } catch (e) {
      notify(errText(e));
    } finally {
      setBusy(false);
    }
  };

  const current = profiles.find((p) => p.id === selected);
  const [mem, setMem] = useState(4096);
  useEffect(() => {
    if (current) setMem(current.memory_mb);
  }, [current?.id]);

  return (
    <div className="page">
      <PageHead title="Profiles" sub="Each profile is its own isolated game: version, loader, mods and memory." />
      <div className="grid2 wide">
        <section className="profile-grid">
          {profiles.length === 0 && <p className="muted">Nothing here yet — create your first profile.</p>}
          {profiles.map((p) => (
            <article key={p.id} className={p.id === selected ? "pcard sel" : "pcard"} onClick={() => select(p.id)}>
              <div className={`pcard-art l-${p.loader}`}>
                <span className="pcard-ver">{p.mc_version}</span>
              </div>
              <div className="pcard-body">
                <strong>{p.name}</strong>
                <div className="chips">
                  <span className="chip accent">{p.loader}</span>
                  <span className="chip">{(p.memory_mb / 1024).toFixed(p.memory_mb % 1024 ? 1 : 0)} GB</span>
                </div>
              </div>
              <div className="pcard-actions">
                <button disabled={busy} onClick={(e) => (e.stopPropagation(), run(() => api.duplicateProfile(p.id), "Duplicated"))}>
                  Copy
                </button>
                <button
                  className="danger"
                  disabled={busy}
                  onClick={(e) => {
                    e.stopPropagation();
                    if (confirm(`Delete "${p.name}" and all its files?`)) run(() => api.deleteProfile(p.id), "Deleted");
                  }}
                >
                  Delete
                </button>
              </div>
            </article>
          ))}
        </section>

        <div className="stack">
          <section className="card">
            <h3>New profile</h3>
            <input placeholder="Profile name" value={name} onChange={(e) => setName(e.target.value)} />
            <select value={ver} onChange={(e) => setVer(e.target.value)}>
              {versions.map((v) => (
                <option key={v}>{v}</option>
              ))}
            </select>
            <div className="seg">
              {LOADERS.map((l) => (
                <button key={l} className={l === loader ? "on" : ""} onClick={() => setLoader(l)}>
                  {l}
                </button>
              ))}
            </div>
            <div className="row">
              <button
                className="primary grow"
                disabled={busy || !name.trim() || !ver}
                onClick={() =>
                  run(async () => {
                    const p = await api.createProfile(name.trim(), ver, loader);
                    select(p.id);
                    setName("");
                  }, "Profile created")
                }
              >
                Create profile
              </button>
              <button
                disabled={busy}
                onClick={async () => {
                  const f = await open({ filters: [{ name: "Modrinth pack", extensions: ["mrpack"] }] });
                  if (typeof f === "string") run(async () => select((await api.importMrpack(f)).id), "Modpack imported");
                }}
              >
                Import .mrpack
              </button>
            </div>
          </section>

          {current && (
            <section className="card">
              <div className="row between">
                <h3>{current.name}</h3>
                <span className="mem-read">
                  {(mem / 1024).toFixed(mem % 1024 ? 1 : 0)}
                  <small> GB</small>
                </span>
              </div>
              <label className="small muted">Memory allocation</label>
              <input type="range" min={1024} max={16384} step={512} value={mem} onChange={(e) => setMem(+e.target.value)} />
              <div className="seg">
                {[2, 4, 6, 8, 12, 16].map((g) => (
                  <button key={g} className={mem === g * 1024 ? "on" : ""} onClick={() => setMem(g * 1024)}>
                    {g}G
                  </button>
                ))}
              </div>
              <button className="primary" disabled={busy || mem === current.memory_mb} onClick={() => run(() => api.updateProfile({ ...current, memory_mb: mem }), "Saved")}>
                Save changes
              </button>
            </section>
          )}
        </div>
      </div>
    </div>
  );
}
