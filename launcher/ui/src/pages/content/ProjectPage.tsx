import { useMemo, useState } from "react";
import { api } from "../../api";
import type { Kind, ModVersion, ProjectDetails, Provider } from "../../api/types";
import { useApp } from "../../app/store";
import { Async, useAsync } from "../../components/hooks";
import { IArrowLeft, IDownload, IExternal, IHeart, IClock, IGlobe } from "../../components/Icons";
import { Modal } from "../../components/overlays";
import { EmptyState, ErrorCard, Skeleton, Spinner, Tabs } from "../../components/ui";
import { ago, compact, date, label } from "../../lib/format";
import { compareVersions } from "../../lib/versions";
import { useInstaller } from "./install";
import { Markdown } from "./markdown";

type Tab = "description" | "gallery" | "versions" | "dependencies" | "insights";

export function ProjectPage({ id, kind, provider }: { id: string; kind: Kind; provider: Provider }) {
  const app = useApp();
  const [tab, setTab] = useState<Tab>("description");
  const [shot, setShot] = useState<string | null>(null);
  const details = useAsync(() => api.projectDetails(id, provider), [id, provider]);
  const versions = useAsync<ModVersion[]>(() => api.projectVersions(id, kind, null, provider), [id, provider]);
  const installer = useInstaller();
  const d = details.data;

  if (details.error)
    return (
      <div className="page">
        <ErrorCard message={details.error} retry={details.reload} />
      </div>
    );
  if (!d)
    return (
      <div className="page project-page">
        <div className="project-hero">
          <Skeleton w={96} h={96} r={22} />
          <div className="stack" style={{ flex: 1 }}>
            <Skeleton w="40%" h={30} />
            <Skeleton w="70%" h={16} />
            <Skeleton w="50%" h={16} />
          </div>
        </div>
        <Skeleton h={300} r={18} />
      </div>
    );

  const page = provider === "curseforge" ? d.page_url : `https://modrinth.com/${d.project_type}/${d.slug}`;
  const links = [
    [provider === "curseforge" ? "CurseForge" : "Modrinth", page],
    ["Source", d.source_url],
    ["Issues", d.issues_url],
    ["Wiki", d.wiki_url],
    ["Discord", d.discord_url],
  ].filter(([, u]) => !!u) as [string, string][];
  const projectKind = (["mod", "modpack", "resourcepack", "shader", "datapack"].includes(d.project_type) ? d.project_type : kind) as Kind;
  const item = { project_id: d.id, title: d.title, kind: projectKind, icon_url: d.icon_url, cover: d.gallery.find((g) => g.featured)?.url ?? d.gallery[0]?.url ?? null, provider };
  const banner = d.gallery.find((g) => g.featured)?.url ?? d.gallery[0]?.url;

  return (
    <div className="page project-page">
      <button className="link back-link" onClick={app.back}>
        <IArrowLeft size={15} /> Back
      </button>
      <section className="project-hero" style={banner ? { backgroundImage: `linear-gradient(90deg, rgba(11,13,18,.96) 35%, rgba(11,13,18,.7)), url("${banner}")` } : undefined}>
        <div className="project-icon">{d.icon_url ? <img src={d.icon_url} alt="" /> : <span>{d.title[0]}</span>}</div>
        <div className="project-head">
          <div className="row">
            <h1>{d.title}</h1>
            {provider === "curseforge" && <span className="badge">CurseForge</span>}
          </div>
          <p>{d.description}</p>
          <div className="hit-meta">
            <span>
              <IDownload size={14} /> {compact(d.downloads)} downloads
            </span>
            <span>
              <IHeart size={14} /> {compact(d.followers)} followers
            </span>
            <span>
              <IClock size={14} /> Updated {ago(d.updated)}
            </span>
            {d.loaders
              .filter((l) => l !== "minecraft" && l !== "datapack")
              .map((l) => (
                <span key={l} className="chip loader">
                  {label(l)}
                </span>
              ))}
          </div>
        </div>
        <div className="project-cta">
          <button className="btn primary lg" disabled={installer.busy === d.id} onClick={(e) => installer.start(item, e.shiftKey)}>
            {installer.busy === d.id ? <Spinner /> : <IDownload size={18} />} {projectKind === "modpack" ? "Install modpack" : "Install"}
          </button>
          {links.length > 0 && (
            <div className="row wrap">
              {links.map(([n, u]) => (
                <button key={n} className="btn sm ghost" onClick={() => api.openUrl(u).catch(app.error)}>
                  {n} <IExternal size={13} />
                </button>
              ))}
            </div>
          )}
        </div>
      </section>

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { id: "description", label: "Description" },
          { id: "gallery", label: "Gallery", count: d.gallery.length },
          { id: "versions", label: "Versions", count: versions.data?.length },
          { id: "dependencies", label: "Dependencies" },
          { id: "insights", label: "Insights" },
        ]}
      />

      <div className="project-body">
        {tab === "description" && <Markdown source={d.body || d.description} html={d.body_html && !!d.body} />}
        {tab === "gallery" &&
          (d.gallery.length === 0 ? (
            <EmptyState title="No images" text="This project hasn't added any gallery images." />
          ) : (
            <div className="gallery">
              {d.gallery.map((g) => (
                <button key={g.url} className="shot" style={{ backgroundImage: `url("${g.url}")` }} onClick={() => setShot(g.url)} aria-label={g.title ?? "Gallery image"}>
                  {g.title && <span>{g.title}</span>}
                </button>
              ))}
            </div>
          ))}
        {tab === "versions" && <VersionsTab versions={versions} onInstall={(v) => installer.start({ ...item, versionId: v.id }, false)} />}
        {tab === "dependencies" && <DependenciesTab versions={versions.data} loading={versions.loading} provider={provider} />}
        {tab === "insights" && <Insights d={d} versions={versions.data ?? []} loading={versions.loading} />}
      </div>

      <Modal open={!!shot} onClose={() => setShot(null)} width={960} className="lightbox">
        {shot && <img src={shot} alt="" className="lightbox-full" />}
      </Modal>
      {installer.ui}
    </div>
  );
}

function VersionsTab({ versions, onInstall }: { versions: Async<ModVersion[]>; onInstall: (v: ModVersion) => void }) {
  if (versions.error) return <ErrorCard message={versions.error} retry={versions.reload} />;
  if (!versions.data) return <Skeleton h={240} r={14} />;
  if (!versions.data.length) return <EmptyState title="No versions listed" />;
  return (
    <table className="vtable">
      <thead>
        <tr>
          <th>Version</th>
          <th>Type</th>
          <th>Game versions</th>
          <th>Loaders</th>
          <th>Published</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {versions.data.slice(0, 60).map((v) => (
          <tr key={v.id}>
            <td>
              <strong>{v.version_number}</strong>
              <div className="muted small">{v.name}</div>
            </td>
            <td>
              <span className={`vtype ${v.version_type}`}>{label(v.version_type)}</span>
            </td>
            <td className="small">{[...v.game_versions].sort(compareVersions).slice(-4).join(", ")}</td>
            <td className="small">{v.loaders.map(label).join(", ")}</td>
            <td className="small muted">{date(Date.parse(v.date_published))}</td>
            <td>
              <button className="btn sm" onClick={() => onInstall(v)}>
                <IDownload size={14} /> Install
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function DependenciesTab({ versions, loading, provider }: { versions?: ModVersion[]; loading: boolean; provider: Provider }) {
  const latest = versions?.[0];
  const ids = useMemo(() => (latest?.dependencies ?? []).filter((x) => x.project_id && x.dependency_type !== "embedded"), [latest]);
  const details = useAsync(() => Promise.all(ids.map((x) => api.projectDetails(x.project_id!, provider).catch(() => null))), [ids.map((x) => x.project_id).join(), provider]);
  const app = useApp();
  if (loading) return <Skeleton h={160} r={14} />;
  if (!latest || ids.length === 0) return <EmptyState title="No dependencies" text="The newest version works on its own." />;
  return (
    <div className="stack">
      <p className="muted">Dependencies of {latest.version_number}. Required ones install automatically, after you confirm.</p>
      <ul className="inst-list">
        {ids.map((dep, i) => {
          const p = details.data?.[i];
          return (
            <li key={dep.project_id}>
              <span className="inst-icon">{p?.icon_url ? <img src={p.icon_url} alt="" /> : <IGlobe size={18} />}</span>
              <div className="inst-text">
                <strong>{p?.title ?? dep.project_id}</strong>
                <span className="muted small">{p?.description}</span>
              </div>
              <span className={`vtype ${dep.dependency_type === "required" ? "release" : "beta"}`}>{label(dep.dependency_type)}</span>
              {p && (
                <button className="btn sm" onClick={() => app.navigate({ page: "project", id: p.id, kind: "mod", provider })}>
                  View
                </button>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** Real stats from the project and its version history: release cadence over the last 12 months. */
function Insights({ d, versions, loading }: { d: ProjectDetails; versions: ModVersion[]; loading: boolean }) {
  const [hover, setHover] = useState<number | null>(null);
  const [table, setTable] = useState(false);
  const months = useMemo(() => {
    const now = new Date();
    const list = Array.from({ length: 12 }, (_, i) => {
      const m = new Date(now.getFullYear(), now.getMonth() - 11 + i, 1);
      return { key: `${m.getFullYear()}-${m.getMonth()}`, label: m.toLocaleDateString("en-GB", { month: "short" }), full: m.toLocaleDateString("en-GB", { month: "long", year: "numeric" }), n: 0 };
    });
    for (const v of versions) {
      const t = new Date(v.date_published);
      const row = list.find((x) => x.key === `${t.getFullYear()}-${t.getMonth()}`);
      if (row) row.n++;
    }
    return list;
  }, [versions]);
  const max = Math.max(1, ...months.map((m) => m.n));
  const year = months.reduce((a, m) => a + m.n, 0);
  const W = 640;
  const H = 180;
  const bw = W / 12;

  return (
    <div className="insights">
      <div className="stat-row">
        <div className="stat">
          <span>Downloads</span>
          <strong>{compact(d.downloads)}</strong>
        </div>
        <div className="stat">
          <span>Followers</span>
          <strong>{compact(d.followers)}</strong>
        </div>
        <div className="stat">
          <span>Versions</span>
          <strong>{loading ? "…" : versions.length || d.versions.length}</strong>
        </div>
        <div className="stat">
          <span>First published</span>
          <strong>{d.published ? date(Date.parse(d.published)) : "Unknown"}</strong>
        </div>
      </div>
      <section className="chart-card">
        <header className="row between">
          <div>
            <h3>Releases per month</h3>
            <span className="muted small">{loading ? "Loading version history" : `${year} in the last 12 months`}</span>
          </div>
          <button className="link sm" onClick={() => setTable((t) => !t)}>
            {table ? "Show chart" : "Show as table"}
          </button>
        </header>
        {table ? (
          <table className="vtable compact">
            <tbody>
              {months.map((m) => (
                <tr key={m.key}>
                  <td>{m.full}</td>
                  <td>{m.n}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <div className="chart-wrap">
            <svg viewBox={`0 0 ${W} ${H + 24}`} className="bar-chart" role="img" aria-label={`Releases per month, ${year} in the last 12 months`}>
              {[0.5, 1].map((f) => (
                <line key={f} x1={0} x2={W} y1={H - H * f * 0.9} y2={H - H * f * 0.9} className="grid" />
              ))}
              <line x1={0} x2={W} y1={H} y2={H} className="axis" />
              {months.map((m, i) => {
                const h = (m.n / max) * H * 0.9;
                const w = Math.round(bw * 0.5);
                const x = i * bw + (bw - w) / 2;
                return (
                  <g key={m.key} onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}>
                    <rect x={i * bw} y={0} width={bw} height={H + 24} fill="transparent" />
                    {m.n > 0 && <path d={`M${x},${H} v${-(h - 4)} q0,-4 4,-4 h${w - 8} q4,0 4,4 v${h - 4} z`} className={hover === i ? "bar on" : "bar"} />}
                    <text x={i * bw + bw / 2} y={H + 17} className="tick">
                      {m.label}
                    </text>
                  </g>
                );
              })}
            </svg>
            {hover !== null && (
              <div className="chart-tip" style={{ left: `${((hover + 0.5) / 12) * 100}%` }}>
                <b>
                  {months[hover].n} {months[hover].n === 1 ? "release" : "releases"}
                </b>
                <span>{months[hover].full}</span>
              </div>
            )}
          </div>
        )}
      </section>
      <section className="chart-card">
        <h3>Supports</h3>
        <div className="row wrap">
          {[...d.game_versions]
            .sort(compareVersions)
            .slice(-14)
            .reverse()
            .map((v) => (
              <span key={v} className="chip">
                {v}
              </span>
            ))}
          {d.game_versions.length > 14 && <span className="muted small">and {d.game_versions.length - 14} older versions</span>}
        </div>
      </section>
    </div>
  );
}
