import { ReactNode, useEffect, useMemo, useRef, useState } from "react";
import { open as openDialog, save as saveDialog } from "@tauri-apps/plugin-dialog";
import { api, errText, FolderKind } from "../../api";
import type { Backup, Installed, Kind, LocalFile, Overrides, Profile, Screenshot, World } from "../../api/types";
import { useApp } from "../../app/store";
import { Async, useAsync } from "../../components/hooks";
import {
  ICamera,
  IDatabase,
  IDownload,
  IExternal,
  IFolder,
  IMap,
  IMore,
  IPalette,
  IPlay,
  IPlus,
  IPuzzle,
  IRefresh,
  IServer,
  ISliders,
  ISun,
  ITrash,
  ICopy,
  IUpload,
  IClose,
  ISearch,
  IFile,
  ICheck,
} from "../../components/Icons";
import { Dropdown } from "../../components/Menu";
import { BigPopover, Confirm, Modal } from "../../components/overlays";
import { SearchBar } from "../../components/SearchBar";
import { Checkbox, Cover, EmptyState, ErrorCard, Field, SkeletonRows, Spinner, Toggle } from "../../components/ui";
import { COVERS, coverOf, fileUrl } from "../../lib/art";
import { ago, bytes, date } from "../../lib/format";
import { load, save } from "../../lib/prefs";
import { pingClass, usePings } from "../../lib/ping";
import { LOADER_NAMES, profileLabel } from "../../lib/versions";
import { GameSettingsForm } from "../settings/GameSettingsForm";

type Tab = "mod" | "resourcepack" | "shader" | "datapack" | "worlds" | "servers" | "gallery" | "general";
const TABS: { id: Tab; label: string; icon: ReactNode }[] = [
  { id: "mod", label: "Mods", icon: <IPuzzle size={18} /> },
  { id: "resourcepack", label: "Resource packs", icon: <IPalette size={18} /> },
  { id: "shader", label: "Shaders", icon: <ISun size={18} /> },
  { id: "datapack", label: "Datapacks", icon: <IDatabase size={18} /> },
  { id: "worlds", label: "Worlds", icon: <IMap size={18} /> },
  { id: "servers", label: "Servers", icon: <IServer size={18} /> },
  { id: "gallery", label: "Gallery", icon: <ICamera size={18} /> },
  { id: "general", label: "General", icon: <ISliders size={18} /> },
];
const FOLDER: Partial<Record<Kind, FolderKind>> = { mod: "mods", resourcepack: "resourcepacks", shader: "shaderpacks", datapack: "saves" };

/** True while this profile's game is starting or running (files are in use). */
function useRunning(p: Profile) {
  const app = useApp();
  return (app.launch.phase === "preparing" || app.launch.phase === "running") && app.launch.profileId === p.id;
}

export function ProfileSettings({ profile: p, onClose, onDeleted }: { profile: Profile; onClose: () => void; onDeleted: () => void }) {
  const [tab, setTab] = useState<Tab>(() => load<Tab>("profileSettingsTab", "mod"));
  useEffect(() => save("profileSettingsTab", tab), [tab]);
  const installed = useAsync(() => api.listInstalled(p.id), [p.id]);
  const local = useAsync(() => api.listLocalFiles(p.id), [p.id]);
  const counts = useMemo(() => {
    const c: Record<string, number> = {};
    for (const i of installed.data ?? []) if (i.explicit) c[i.project_type] = (c[i.project_type] ?? 0) + 1;
    for (const f of local.data ?? []) c[f.project_type] = (c[f.project_type] ?? 0) + 1;
    return c;
  }, [installed.data, local.data]);

  return (
    <BigPopover
      open
      onClose={onClose}
      header={
        <div className="ps-head">
          <Cover cover={coverOf(p)} seed={p.id} loader={p.loader} className="ps-thumb" />
          <div>
            <h2>{p.name}</h2>
            <span className="muted">{profileLabel(p)}</span>
          </div>
        </div>
      }
      rail={TABS.map((t) => (
        <button key={t.id} className={tab === t.id ? "on" : ""} onClick={() => setTab(t.id)}>
          {t.icon}
          <span>{t.label}</span>
          {counts[t.id] ? <span className="count">{counts[t.id]}</span> : null}
        </button>
      ))}
    >
      {(tab === "mod" || tab === "resourcepack" || tab === "shader" || tab === "datapack") && (
        <ContentList key={tab} profile={p} kind={tab} installed={installed} local={local} onAdd={onClose} />
      )}
      {tab === "worlds" && <Worlds profile={p} onPlay={onClose} />}
      {tab === "servers" && <Servers profile={p} onPlay={onClose} />}
      {tab === "gallery" && <Gallery profile={p} />}
      {tab === "general" && <General profile={p} onClose={onClose} onDeleted={onDeleted} />}
    </BigPopover>
  );
}

// ---------- installed content ----------

type Filter = "all" | "enabled" | "disabled" | "updates";
type Row = { id: string; title: string; filename: string; enabled: boolean; tracked?: Installed; file?: LocalFile };

function ContentList({ profile: p, kind, installed, local, onAdd }: { profile: Profile; kind: Kind; installed: Async<Installed[]>; local: Async<LocalFile[]>; onAdd: () => void }) {
  const app = useApp();
  const running = useRunning(p);
  const [q, setQ] = useState("");
  const [filter, setFilter] = useState<Filter>("all");
  const [busy, setBusy] = useState<string | null>(null);
  const updates = useAsync(() => api.checkUpdates(p.id), [p.id]);
  const label = TABS.find((t) => t.id === kind)!.label.toLowerCase();

  const rows: Row[] = [
    ...(installed.data ?? []).filter((i) => i.project_type === kind).sort((a, b) => Number(b.explicit) - Number(a.explicit) || a.title.localeCompare(b.title)).map((i) => ({ id: i.project_id, title: i.title, filename: i.filename, enabled: i.enabled, tracked: i })),
    ...(local.data ?? []).filter((f) => f.project_type === kind).map((f) => ({ id: `file:${kind}:${f.filename}`, title: f.filename.replace(/\.(jar|zip)$/i, ""), filename: f.filename, enabled: f.enabled, file: f })),
  ];
  const upd = new Map((updates.data ?? []).map((u) => [u.project_id, u]));
  const needle = q.trim().toLowerCase();
  const shown = rows.filter(
    (r) =>
      (!needle || r.title.toLowerCase().includes(needle) || r.filename.toLowerCase().includes(needle)) &&
      (filter === "all" || (filter === "enabled" && r.enabled) || (filter === "disabled" && !r.enabled) || (filter === "updates" && upd.has(r.id))),
  );
  const untracked = rows.filter((r) => r.file).length;

  const run = async (id: string, fn: () => Promise<unknown>, ok?: string) => {
    setBusy(id);
    try {
      await fn();
      if (ok) app.toast(ok, { tone: "success" });
      installed.reload();
      local.reload();
      updates.reload();
    } catch (e) {
      app.error(e);
    } finally {
      setBusy(null);
    }
  };
  const toggle = (r: Row, on: boolean) => {
    const set = (v: boolean) => (r.tracked ? installed : local).setData((d) => (d as (Installed | LocalFile)[] | undefined)?.map((x) => (("project_id" in x ? x.project_id : `file:${kind}:${x.filename}`) === r.id ? { ...x, enabled: v } : x)) as never);
    set(on);
    api.setContentEnabled(p.id, r.id, on).catch((e) => {
      set(!on);
      app.error(e);
    });
  };
  const browse = () => {
    app.setPrefs({ installTarget: p.id });
    onAdd();
    app.navigate({ page: "content", kind });
  };

  return (
    <div className="ps-pane">
      <SearchBar<string, Filter>
        query={q}
        onQuery={setQ}
        placeholder={`Search ${label}`}
        sort={{
          value: filter,
          onChange: setFilter,
          options: [
            { id: "all", label: "All" },
            { id: "enabled", label: "Enabled" },
            { id: "disabled", label: "Disabled" },
            { id: "updates", label: `Has an update${updates.data?.length ? ` (${updates.data.length})` : ""}` },
          ],
        }}
        trailing={
          <div className="row">
            <button className="btn" onClick={() => api.openPath(FOLDER[kind] ?? "profile", p.id).catch(app.error)} title={`Open the ${label} folder`}>
              <IFolder size={16} />
            </button>
            <button className="btn" disabled={!updates.data?.length || busy === "all" || running} onClick={() => run("all", () => api.updateAll(p.id), "Everything is up to date")}>
              {busy === "all" ? <Spinner /> : <IRefresh size={16} />} Update all
            </button>
            <button className="btn primary" onClick={browse}>
              <IPlus size={16} /> Add {label}
            </button>
          </div>
        }
      />
      {running && <p className="note">The game is running, so content can't be switched on or off until it closes.</p>}
      {installed.error ? (
        <ErrorCard message={installed.error} retry={installed.reload} />
      ) : !installed.data || !local.data ? (
        <SkeletonRows n={4} h={56} />
      ) : rows.length === 0 ? (
        <EmptyState
          icon={<IDownload size={22} />}
          title={`No ${label} yet`}
          text={kind === "datapack" ? `Datapacks install into a world. Browse datapacks on Content and pick one of ${p.name}'s worlds.` : `Browse ${label} on Content and install them into ${p.name}, or drop files into its folder.`}
          action={
            <button className="btn primary" onClick={browse}>
              Browse {label}
            </button>
          }
        />
      ) : shown.length === 0 ? (
        <EmptyState title="Nothing matches" text="Try another search or filter." />
      ) : (
        <ul className="inst-list">
          {shown.map((r) => {
            const u = upd.get(r.id);
            const i = r.tracked;
            return (
              <li key={r.id} className={r.enabled ? "" : "off"}>
                <span className="inst-icon">{TABS.find((t) => t.id === kind)?.icon}</span>
                <div className="inst-text">
                  <strong>
                    {r.title}
                    {i?.source === "curseforge" && <span className="chip tiny">CurseForge</span>}
                    {r.file && <span className="chip tiny">Added by hand</span>}
                  </strong>
                  <span className="muted small">
                    {r.filename}
                    {i && !i.explicit && " (dependency)"}
                    {i?.world && ` in ${i.world}`}
                    {r.file && `, ${bytes(r.file.size_bytes)}`}
                  </span>
                </div>
                {u && (
                  <button className="btn sm accent" disabled={busy === r.id || running} onClick={() => run(r.id, () => api.updateOne(p.id, r.id), `Updated ${r.title} to ${u.latest}`)}>
                    {busy === r.id ? <Spinner size={13} /> : <IRefresh size={14} />} {u.latest}
                  </button>
                )}
                {kind !== "datapack" && <Toggle on={r.enabled} disabled={running} onChange={(v) => toggle(r, v)} label={r.enabled ? `Disable ${r.title}` : `Enable ${r.title}`} />}
                <Dropdown
                  className="icon-btn"
                  label="More"
                  align="end"
                  items={[
                    ...(i ? [{ label: "Open page", icon: <IExternal size={16} />, onSelect: () => app.navigate({ page: "project", id: i.project_id, kind: i.project_type, provider: i.source }) }] : []),
                    {
                      label: "Remove",
                      icon: <ITrash size={16} />,
                      danger: true,
                      disabled: running,
                      onSelect: () => run(r.id, () => (i ? api.uninstall(p.id, r.id) : api.removeLocalFile(p.id, r.id)), `Removed ${r.title}`),
                    },
                  ]}
                >
                  <IMore size={18} />
                </Dropdown>
              </li>
            );
          })}
        </ul>
      )}
      {untracked > 0 && kind !== "datapack" && (
        <div className="hint row">
          <span className="grow">Files added by hand don't get updates. Kite can look them up on Modrinth.</span>
          <button className="btn sm" disabled={busy === "adopt"} onClick={() => run("adopt", async () => app.toast(`Recognised ${await api.adoptLocalFiles(p.id)} of ${untracked} files`))}>
            {busy === "adopt" ? <Spinner size={13} /> : <ISearch size={14} />} Recognise files
          </button>
        </div>
      )}
    </div>
  );
}

// ---------- worlds ----------

const MODE: Record<World["mode"], string> = { survival: "Survival", creative: "Creative", adventure: "Adventure", spectator: "Spectator", hardcore: "Hardcore" };

function Worlds({ profile: p, onPlay }: { profile: Profile; onPlay: () => void }) {
  const app = useApp();
  const running = useRunning(p);
  const worlds = useAsync(() => api.worlds(p.id), [p.id]);
  const backups = useAsync(() => api.backups(p.id), [p.id]);
  const [q, setQ] = useState("");
  const [del, setDel] = useState<World | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const list = (worlds.data ?? []).filter((w) => w.name.toLowerCase().includes(q.trim().toLowerCase()));

  const backup = async (w: World) => {
    setBusy(w.id);
    try {
      const b = await api.backupWorld(p.id, w.id);
      app.toast(`Backed up ${w.name} (${bytes(b.size_bytes)})`, { tone: "success", action: { label: "Show", run: () => api.revealPath(b.path).catch(app.error) } });
      backups.reload();
    } catch (e) {
      app.error(e);
    } finally {
      setBusy(null);
    }
  };
  const restore = async (b: Backup) => {
    setBusy(b.name);
    try {
      const folder = await api.restoreBackup(p.id, b.name);
      app.toast(`Restored as ${folder}`, { tone: "success" });
      worlds.reload();
    } catch (e) {
      app.error(e);
    } finally {
      setBusy(null);
    }
  };
  const play = (w: World) => {
    onPlay();
    app.navigate({ page: "home" });
    app.play(p.id, { world: w.id });
  };

  return (
    <div className="ps-pane">
      <SearchBar
        query={q}
        onQuery={setQ}
        placeholder="Search worlds"
        trailing={
          <button className="btn" onClick={() => api.openPath("saves", p.id).catch(app.error)}>
            <IFolder size={16} /> Open saves folder
          </button>
        }
      />
      {worlds.error ? (
        <ErrorCard message={worlds.error} retry={worlds.reload} />
      ) : !worlds.data ? (
        <SkeletonRows n={3} h={72} />
      ) : list.length === 0 ? (
        <EmptyState icon={<IMap size={22} />} title={q ? "No worlds match" : "No worlds yet"} text={q ? "Try another search." : "Worlds you create in this profile appear here."} />
      ) : (
        <ul className="world-list">
          {list.map((w) => (
            <li key={w.id}>
              {w.icon ? <img className="world-thumb" src={fileUrl(w.icon)} alt="" /> : <span className="world-thumb" />}
              <div className="inst-text">
                <strong>{w.name}</strong>
                <span className="muted small">
                  {MODE[w.mode]}
                  {w.version && `, ${w.version}`}, {bytes(w.size_bytes)}
                  {w.last_played ? `, played ${ago(w.last_played)}` : ""}
                </span>
              </div>
              <button className="btn sm primary" disabled={!app.account || app.launch.phase === "preparing" || app.launch.phase === "running"} onClick={() => play(w)} title="Launch straight into this world (1.20 and newer)">
                <IPlay size={13} /> Play
              </button>
              <button className="btn sm" disabled={busy === w.id} onClick={() => backup(w)}>
                {busy === w.id ? <Spinner size={13} /> : <IUpload size={14} />} Back up
              </button>
              <button className="icon-btn" aria-label={`Delete ${w.name}`} disabled={running} onClick={() => setDel(w)}>
                <ITrash size={16} />
              </button>
            </li>
          ))}
        </ul>
      )}
      {(backups.data?.length ?? 0) > 0 && (
        <section className="stack">
          <h4 className="gs-section">Backups</h4>
          <ul className="world-list compact">
            {backups.data!.map((b) => (
              <li key={b.name}>
                <span className="inst-icon">
                  <IFile size={16} />
                </span>
                <div className="inst-text">
                  <strong>{b.name}</strong>
                  <span className="muted small">
                    {date(b.at)}, {bytes(b.size_bytes)}
                  </span>
                </div>
                <button className="btn sm" disabled={busy === b.name} onClick={() => restore(b)}>
                  {busy === b.name ? <Spinner size={13} /> : <IRefresh size={14} />} Restore
                </button>
                <button className="icon-btn" aria-label={`Show ${b.name}`} onClick={() => api.revealPath(b.path).catch(app.error)}>
                  <IFolder size={16} />
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}
      <Confirm
        open={!!del}
        title={`Delete ${del?.name}?`}
        text="The world folder is deleted from this profile. Back it up first if you might want it again. This can't be undone."
        confirm="Delete world"
        danger
        typeToConfirm={del?.name}
        onClose={() => setDel(null)}
        onConfirm={() => {
          const w = del!;
          setDel(null);
          api
            .deleteWorld(p.id, w.id)
            .then(() => {
              worlds.setData((d) => d?.filter((x) => x.id !== w.id));
              app.toast(`Deleted ${w.name}`);
            })
            .catch(app.error);
        }}
      />
    </div>
  );
}

// ---------- servers ----------

function Servers({ profile: p, onPlay }: { profile: Profile; onPlay: () => void }) {
  const app = useApp();
  const running = useRunning(p);
  const servers = useAsync(() => api.profileServers(p.id), [p.id]);
  const [q, setQ] = useState("");
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState("");
  const [address, setAddress] = useState("");
  const list = (servers.data ?? []).filter((s) => (s.name + s.address).toLowerCase().includes(q.trim().toLowerCase()));
  const pings = usePings((servers.data ?? []).map((s) => s.address));
  const play = (address: string) => {
    onPlay();
    app.navigate({ page: "home" });
    app.play(p.id, { server: address });
  };
  const add = async () => {
    try {
      await api.addServer(p.id, name, address);
      setAdding(false);
      setName("");
      setAddress("");
      servers.reload();
    } catch (e) {
      app.error(e);
    }
  };
  return (
    <div className="ps-pane">
      <SearchBar
        query={q}
        onQuery={setQ}
        placeholder="Search servers"
        trailing={
          <button className="btn primary" disabled={running} onClick={() => setAdding(true)}>
            <IPlus size={16} /> Add server
          </button>
        }
      />
      {servers.error ? (
        <ErrorCard message={servers.error} retry={servers.reload} />
      ) : !servers.data ? (
        <SkeletonRows n={3} h={64} />
      ) : list.length === 0 ? (
        <EmptyState icon={<IServer size={22} />} title={q ? "No servers match" : "No servers"} text={q ? "Try another search." : "Servers you add here or in the game's multiplayer menu appear here."} />
      ) : (
        <ul className="world-list">
          {list.map((s) => {
            const r = pings[s.address];
            const info = r && "ping" in r ? r.ping : null;
            const icon = s.icon ?? info?.favicon;
            return (
              <li key={`${s.index}-${s.address}`}>
                {icon ? <img className="server-icon" src={icon} alt="" /> : <span className="server-icon">{s.name[0]}</span>}
                <div className="inst-text">
                  <strong>{s.name}</strong>
                  <span className="muted small ellipsis">
                    {s.address}
                    {info?.motd ? ` · ${info.motd}` : ""}
                  </span>
                </div>
                {info ? (
                  <>
                    <span className="muted small">
                      {info.online}/{info.max}
                    </span>
                    <span className={pingClass(info.latency_ms)}>{info.latency_ms} ms</span>
                  </>
                ) : r ? (
                  <span className="ping bad">Offline</span>
                ) : (
                  <Spinner size={12} />
                )}
                <button className="btn sm primary" disabled={!app.account || app.launch.phase === "preparing" || app.launch.phase === "running"} onClick={() => play(s.address)}>
                  <IPlay size={13} /> Play
                </button>
                <button
                  className="icon-btn"
                  aria-label={`Remove ${s.name}`}
                  disabled={running}
                  onClick={() =>
                    api
                      .removeServer(p.id, s.index, s.address)
                      .then(() => servers.reload())
                      .catch(app.error)
                  }
                >
                  <ITrash size={16} />
                </button>
              </li>
            );
          })}
        </ul>
      )}
      <Modal
        open={adding}
        onClose={() => setAdding(false)}
        title="Add a server"
        width={420}
        footer={
          <>
            <span className="grow" />
            <button className="btn ghost" onClick={() => setAdding(false)}>
              Cancel
            </button>
            <button className="btn primary" disabled={!address.trim()} onClick={add}>
              Add server
            </button>
          </>
        }
      >
        <div className="stack">
          <Field label="Name">
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Minecraft Server" maxLength={40} />
          </Field>
          <Field label="Address">
            <input value={address} onChange={(e) => setAddress(e.target.value)} placeholder="play.example.net" onKeyDown={(e) => e.key === "Enter" && address.trim() && add()} autoFocus />
          </Field>
        </div>
      </Modal>
    </div>
  );
}

// ---------- gallery ----------

function Gallery({ profile: p }: { profile: Profile }) {
  const app = useApp();
  const shots = useAsync(() => api.screenshots(p.id), [p.id]);
  const [view, setView] = useState<Screenshot | null>(null);
  const [del, setDel] = useState<Screenshot | null>(null);
  const list = shots.data ?? [];
  const idx = view ? list.findIndex((s) => s.name === view.name) : -1;
  const step = (d: number) => idx >= 0 && list.length && setView(list[(idx + d + list.length) % list.length]);

  useEffect(() => {
    if (!view) return;
    const h = (e: KeyboardEvent) => {
      if (e.key === "ArrowRight") step(1);
      if (e.key === "ArrowLeft") step(-1);
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  });

  return (
    <div className="ps-pane">
      <div className="row between">
        <span className="muted">{shots.data ? `${shots.data.length} screenshot${shots.data.length === 1 ? "" : "s"}` : " "}</span>
        <button className="btn" onClick={() => api.openPath("screenshots", p.id).catch(app.error)}>
          <IFolder size={16} /> Open screenshots folder
        </button>
      </div>
      {shots.error ? (
        <ErrorCard message={shots.error} retry={shots.reload} />
      ) : !shots.data ? (
        <SkeletonRows n={2} h={120} />
      ) : shots.data.length === 0 ? (
        <EmptyState icon={<ICamera size={22} />} title="No screenshots yet" text="Press F2 in game to take one." />
      ) : (
        <div className="gallery">
          {shots.data.map((s) => (
            <button key={s.name} className="shot" style={{ backgroundImage: `url("${fileUrl(s.path)}")` }} onClick={() => setView(s)} aria-label={s.name}>
              <span>{date(s.at)}</span>
            </button>
          ))}
        </div>
      )}
      <Modal open={!!view} onClose={() => setView(null)} width={960} className="lightbox">
        {view && (
          <>
            <img src={fileUrl(view.path)} alt={view.name} className="lightbox-full" />
            <div className="row between">
              <span>
                <strong>{view.name}</strong> <span className="muted small">{ago(view.at)}</span>
              </span>
              <div className="row">
                <button className="btn sm" onClick={() => step(-1)} aria-label="Previous screenshot" disabled={list.length < 2}>
                  ‹
                </button>
                <button className="btn sm" onClick={() => step(1)} aria-label="Next screenshot" disabled={list.length < 2}>
                  ›
                </button>
                <button className="btn sm" onClick={() => api.copyScreenshot(p.id, view.name).then(() => app.toast("Copied to clipboard")).catch(app.error)}>
                  <ICopy size={14} /> Copy
                </button>
                <button className="btn sm" onClick={() => api.revealPath(view.path).catch(app.error)}>
                  <IFolder size={14} /> Show
                </button>
                <button className="btn sm danger" onClick={() => setDel(view)}>
                  <ITrash size={14} /> Delete
                </button>
              </div>
            </div>
          </>
        )}
      </Modal>
      <Confirm
        open={!!del}
        title="Delete this screenshot?"
        text={`${del?.name} is deleted from the screenshots folder.`}
        confirm="Delete"
        danger
        onClose={() => setDel(null)}
        onConfirm={() => {
          const s = del!;
          setDel(null);
          api
            .deleteScreenshot(p.id, s.name)
            .then(() => {
              shots.setData((d) => d?.filter((x) => x.name !== s.name));
              setView(null);
            })
            .catch(app.error);
        }}
      />
    </div>
  );
}

// ---------- general ----------

function General({ profile: p, onClose, onDeleted }: { profile: Profile; onClose: () => void; onDeleted: () => void }) {
  const app = useApp();
  const m = p.meta;
  const [name, setName] = useState(p.name);
  const [desc, setDesc] = useState(m.description);
  const [ov, setOv] = useState<Overrides>(p.overrides);
  const [del, setDel] = useState(false);
  const [exporting, setExporting] = useState(false);
  const loaders = useAsync(() => (p.loader === "vanilla" ? Promise.resolve([]) : api.loaderVersions(p.loader, p.mc_version)), [p.loader, p.mc_version]);
  const [showUnstable, setShowUnstable] = useState(false);

  // Overrides save as you edit (debounced).
  const timer = useRef<ReturnType<typeof setTimeout>>();
  const latest = useRef(p);
  latest.current = p;
  const changeOv = (o: Overrides) => {
    setOv(o);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => app.saveProfile({ ...latest.current, overrides: o }), 400);
  };
  useEffect(() => () => clearTimeout(timer.current), []);

  const saveName = () => {
    const n = name.trim();
    if (!n || n === p.name) return setName(p.name);
    app.saveProfile({ ...p, name: n }).then((r) => r && app.toast("Name saved"));
  };
  const pickCover = async () => {
    try {
      const f = await openDialog({ filters: [{ name: "Images", extensions: ["png", "jpg", "jpeg", "webp", "gif"] }], title: "Choose a cover image" });
      if (typeof f !== "string") return;
      await api.setProfileCover(p.id, f);
      await app.refreshProfiles();
    } catch (e) {
      app.error(e);
    }
  };
  const pickFolder = async () => {
    try {
      const dir = await openDialog({ directory: true, title: "Choose the game folder for this profile" });
      if (typeof dir === "string") await app.saveProfile({ ...p, game_dir: dir });
    } catch (e) {
      app.error(e);
    }
  };
  const versions = (loaders.data ?? []).filter((v) => showUnstable || v.stable || v.version === p.loader_version);

  return (
    <div className="ps-pane general">
      <section className="ps-section">
        <h3>Profile</h3>
        <Field label="Name">
          <input value={name} onChange={(e) => setName(e.target.value)} onBlur={saveName} onKeyDown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()} maxLength={48} />
        </Field>
        <Field label="Description">
          <textarea value={desc} rows={3} onChange={(e) => setDesc(e.target.value)} onBlur={() => desc.trim() !== m.description && app.setMeta(p.id, { description: desc.trim() })} placeholder="What's this profile for?" />
        </Field>
        <Field label="Cover">
          <div className="cover-pick">
            <button className={!m.cover ? "on" : ""} onClick={() => app.setMeta(p.id, { cover: null })} aria-label="Generated cover">
              <Cover cover={null} seed={p.id} loader={p.loader} />
            </button>
            {COVERS.map((c, i) => (
              <button key={i} className={m.cover === c ? "on" : ""} onClick={() => app.setMeta(p.id, { cover: c })} aria-label={`Cover ${i + 1}`}>
                <Cover cover={c} seed="" />
              </button>
            ))}
            {m.cover && !COVERS.includes(m.cover) && (
              <button className="on" aria-label="Your cover image">
                <Cover cover={coverOf(p)} seed="" />
              </button>
            )}
            <button className="cover-add" onClick={pickCover} aria-label="Use your own image" title="Use your own image">
              <IUpload size={18} />
            </button>
          </div>
        </Field>
        <Field
          label="Loader version"
          hint={
            p.loader === "vanilla"
              ? "Vanilla profiles don't use a mod loader."
              : p.loader_version
                ? `Pinned to ${LOADER_NAMES[p.loader]} ${p.loader_version}.`
                : `The newest stable ${LOADER_NAMES[p.loader]} for ${p.mc_version} is installed at launch.`
          }
        >
          <div className="row">
            <select value={p.loader_version ?? ""} disabled={p.loader === "vanilla" || !loaders.data} onChange={(e) => app.saveProfile({ ...p, loader_version: e.target.value || null })}>
              <option value="">Latest stable</option>
              {versions.map((v) => (
                <option key={v.version} value={v.version}>
                  {v.version}
                  {v.stable ? "" : " (beta)"}
                </option>
              ))}
            </select>
            {loaders.loading && <Spinner size={14} />}
            {p.loader !== "vanilla" && (loaders.data ?? []).some((v) => !v.stable) && (
              <Checkbox checked={showUnstable} onChange={setShowUnstable}>
                Show betas
              </Checkbox>
            )}
          </div>
          {loaders.error && <p className="form-error">{loaders.error}</p>}
        </Field>
        <Field label="Game folder" hint={p.game_dir ? "Saves, mods and settings live in this folder instead of the profile's own." : "Saves, mods and settings live in the profile's own folder."}>
          <div className="input-with-btn">
            <input value={p.game_dir ?? "Profile folder (default)"} readOnly className="mono" />
            <button className="btn" onClick={pickFolder}>
              <IFolder size={16} /> Choose
            </button>
            {p.game_dir && (
              <button className="btn" onClick={() => app.saveProfile({ ...p, game_dir: null })}>
                Reset
              </button>
            )}
          </div>
        </Field>
      </section>

      <section className="ps-section">
        <h3>Java and game</h3>
        <p className="muted small">Unticked settings follow the defaults in Settings.</p>
        <GameSettingsForm global={app.globalSettings} overrides={ov} onOverrides={changeOv} />
      </section>

      <footer className="ps-danger">
        <button className="btn" onClick={() => setExporting(true)}>
          <IUpload size={16} /> Export .mrpack
        </button>
        <button
          className="btn"
          onClick={() =>
            api
              .duplicateProfile(p.id)
              .then((d) => app.refreshProfiles().then(() => app.toast(`Created ${d.name}`, { tone: "success" })))
              .catch(app.error)
          }
        >
          <ICopy size={16} /> Duplicate
        </button>
        <button className="btn" onClick={() => api.openPath("profile", p.id).catch(app.error)}>
          <IFolder size={16} /> Open folder
        </button>
        <span className="grow" />
        <button className="btn danger" onClick={() => setDel(true)}>
          <ITrash size={16} /> Delete profile
        </button>
      </footer>

      <Confirm
        open={del}
        title={`Delete ${p.name}?`}
        text={p.game_dir ? `Its settings are deleted. Its game folder (${p.game_dir}) is kept.` : "Its mods, worlds, screenshots and settings are deleted from this computer. This can't be undone."}
        confirm="Delete profile"
        danger
        typeToConfirm={p.name}
        onClose={() => setDel(false)}
        onConfirm={() =>
          api
            .deleteProfile(p.id)
            .then(() => {
              setDel(false);
              onClose();
              onDeleted();
              app.toast(`Deleted ${p.name}`);
              return app.refreshProfiles();
            })
            .catch(app.error)
        }
      />
      {exporting && <ExportModal profile={p} onClose={() => setExporting(false)} />}
    </div>
  );
}

function ExportModal({ profile: p, onClose }: { profile: Profile; onClose: () => void }) {
  const app = useApp();
  const candidates = useAsync(() => api.exportCandidates(p.id), [p.id]);
  const [name, setName] = useState(p.name);
  const [version, setVersion] = useState("1.0.0");
  const [include, setInclude] = useState<string[] | null>(null);
  const [state, setState] = useState<"form" | "busy" | { path: string; downloads: number; overrides: number }>("form");
  const [err, setErr] = useState<string | null>(null);
  const chosen = include ?? (candidates.data ?? []).filter((c) => c === "config" || c === "options.txt");

  const run = async () => {
    let dest: string | null = null;
    try {
      const r = await saveDialog({ title: "Export modpack", defaultPath: `${name.trim() || p.name}.mrpack`, filters: [{ name: "Modrinth modpack", extensions: ["mrpack"] }] });
      dest = typeof r === "string" ? r : null;
    } catch {
      setErr("Exporting needs the desktop app");
      return;
    }
    if (!dest) return;
    setErr(null);
    setState("busy");
    try {
      setState(await api.exportMrpack(p.id, dest, { name: name.trim() || p.name, version: version.trim() || "1.0.0", summary: p.meta.description, include: chosen }));
    } catch (e) {
      setErr(errText(e));
      setState("form");
    }
  };
  const describe: Record<string, string> = {
    config: "Mod settings",
    "options.txt": "Game options and keybinds",
    "servers.dat": "Server list",
    saves: "Worlds (can be large)",
    resourcepacks: "Resource packs added by hand",
    shaderpacks: "Shader packs added by hand",
    defaultconfigs: "Default configs",
    kubejs: "KubeJS scripts",
    schematics: "Schematics",
  };

  return (
    <Modal open onClose={onClose} title={`Export ${p.name}`} width={480}>
      {typeof state === "object" ? (
        <div className="stack">
          <p className="row">
            <span className="ok-dot">
              <ICheck size={14} />
            </span>
            Saved the pack: {state.downloads} files download from Modrinth, {state.overrides} are inside it.
          </p>
          <div className="row">
            <button className="btn" onClick={() => api.revealPath(state.path).catch(app.error)}>
              <IFolder size={15} /> Show file
            </button>
            <span className="grow" />
            <button className="btn primary" onClick={onClose}>
              <IClose size={15} /> Close
            </button>
          </div>
        </div>
      ) : (
        <div className="stack">
          <Field label="Pack name">
            <input value={name} onChange={(e) => setName(e.target.value)} maxLength={64} />
          </Field>
          <Field label="Version">
            <input value={version} onChange={(e) => setVersion(e.target.value)} maxLength={32} />
          </Field>
          <div className="stack tight">
            <span className="field-label">Also include</span>
            {!candidates.data ? (
              <Spinner />
            ) : candidates.data.length === 0 ? (
              <span className="muted small">Nothing else to include.</span>
            ) : (
              candidates.data.map((c) => (
                <Checkbox key={c} checked={chosen.includes(c)} onChange={(v) => setInclude(v ? [...chosen, c] : chosen.filter((x) => x !== c))}>
                  <span className="mono small">{c}</span> <span className="muted small">{describe[c] ?? ""}</span>
                </Checkbox>
              ))
            )}
          </div>
          <p className="hint">Mods from Modrinth are linked, not copied. Disabled content is left out. Files added by hand are copied into the pack.</p>
          {err && <p className="form-error">{err}</p>}
          <button className="btn primary" disabled={state === "busy"} onClick={run}>
            {state === "busy" ? <Spinner /> : <IDownload size={16} />} Choose where to save
          </button>
        </div>
      )}
    </Modal>
  );
}
