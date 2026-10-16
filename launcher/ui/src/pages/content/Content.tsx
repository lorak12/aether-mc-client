import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, errText } from "../../api";
import type { Hit, Kind, Sort } from "../../api/types";
import { useApp } from "../../app/store";
import { useAsync, useDebounced } from "../../components/hooks";
import { ICheck, IDownload, IEye, IHeart, IClock, IPuzzle } from "../../components/Icons";
import { FilterGroup, SearchBar } from "../../components/SearchBar";
import { Checkbox, EmptyState, ErrorCard, SkeletonRows, Spinner } from "../../components/ui";
import { ago, compact, label } from "../../lib/format";
import { resolveTarget } from "../../lib/install";
import { load, save } from "../../lib/prefs";
import { useVersionGroups } from "../profiles/pickers";
import { InstallItem, useInstaller } from "./install";
import { CATEGORIES, isLoaderSlug, KINDS, LOADERS, loaderFor, SORTS } from "./meta";

const PAGE = 20;

export function ContentPage({ kind: initialKind, q: initialQ }: { kind?: Kind; q?: string }) {
  const app = useApp();
  const [kind, setKind] = useState<Kind>(initialKind ?? "mod");
  const [query, setQuery] = useState(initialQ ?? "");
  const [sort, setSort] = useState<Sort>("relevance");
  const provider = app.prefs.provider;
  const target = resolveTarget(app.prefs.installTarget, app.activeId, app.profiles);
  const targetProfile = app.profiles.find((p) => p.id === target) ?? null;

  useEffect(() => {
    if (initialKind) setKind(initialKind);
  }, [initialKind]);
  useEffect(() => {
    if (initialQ !== undefined) setQuery(initialQ);
  }, [initialQ]);

  // Filters start from the install target (its loader and game version) and reset when the kind changes.
  const [loaders, setLoaders] = useState<string[]>(() => loaderFor(kind, targetProfile?.loader));
  const [version, setVersion] = useState<string>(() => (kind === "mod" ? targetProfile?.mc_version ?? "" : ""));
  const [cats, setCats] = useState<string[]>([]);
  const [hideInstalled, setHideInstalled] = useState(false);
  const firstKind = useRef(true);
  useEffect(() => {
    if (firstKind.current) return void (firstKind.current = false);
    setLoaders(loaderFor(kind, targetProfile?.loader));
    setVersion(kind === "mod" ? targetProfile?.mc_version ?? "" : "");
    setCats([]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kind]);

  const q = useDebounced(query, 300);
  const [hits, setHits] = useState<Hit[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [more, setMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const seq = useRef(0);
  const [retry, setRetry] = useState(0);

  const search = useCallback(
    (offset: number) => {
      const cf = provider === "curseforge";
      const req = { query: q, kind, game_versions: version ? [version] : [], loaders: cf && kind === "shader" ? [] : loaders, categories: cf ? [] : cats, sort: q ? sort : sort === "relevance" ? "downloads" : sort, offset, limit: PAGE };
      return api.searchProjects(req, provider);
    },
    [q, kind, version, loaders, cats, sort, provider],
  );
  const cfMissing = provider === "curseforge" && app.cfg !== null && !app.cfg.curseforge;

  useEffect(() => {
    if (cfMissing) {
      setLoading(false);
      setHits([]);
      setTotal(0);
      return;
    }
    const n = ++seq.current;
    setLoading(true);
    setError(null);
    search(0)
      .then((r) => {
        if (n !== seq.current) return;
        setHits(r.hits);
        setTotal(r.total_hits);
        if (q.trim()) save("recentSearches", [q.trim(), ...load<string[]>("recentSearches", []).filter((x) => x !== q.trim())].slice(0, 20));
      })
      .catch((e) => n === seq.current && setError(errText(e)))
      .finally(() => n === seq.current && setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search, q, retry, cfMissing]);

  const loadMore = useCallback(() => {
    if (more || loading || hits.length >= total) return;
    const n = seq.current;
    setMore(true);
    search(hits.length)
      .then((r) => n === seq.current && setHits((h) => [...h, ...r.hits.filter((x) => !h.some((y) => y.project_id === x.project_id))]))
      .catch(app.error)
      .finally(() => setMore(false));
  }, [more, loading, hits.length, total, search, app.error]);

  // Infinite scroll.
  const sentinel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = sentinel.current;
    if (!el) return;
    const io = new IntersectionObserver((e) => e[0].isIntersecting && loadMore(), { rootMargin: "400px" });
    io.observe(el);
    return () => io.disconnect();
  }, [loadMore]);

  const installed = useAsync(() => (target ? api.listInstalled(target) : Promise.resolve([])), [target]);
  const installedIds = useMemo(() => new Set((installed.data ?? []).map((i) => i.project_id)), [installed.data]);
  const installer = useInstaller(() => installed.reload());
  const shown = hideInstalled ? hits.filter((h) => !installedIds.has(h.project_id)) : hits;
  const { groups } = useVersionGroups();

  const toInstall = (h: Hit): InstallItem => ({ project_id: h.project_id, title: h.title, kind, icon_url: h.icon_url, cover: h.featured_gallery ?? null, provider });
  const kindLabel = KINDS.find((k) => k.id === kind)!;

  return (
    <div className="page content-page">
      <header className="page-head">
        <h1>Content</h1>
        <div className="provider-switch" role="radiogroup" aria-label="Content provider">
          <button role="radio" aria-checked={provider === "modrinth"} className={provider === "modrinth" ? "on modrinth" : "modrinth"} onClick={() => app.setPrefs({ provider: "modrinth" })}>
            Modrinth
          </button>
          <button role="radio" aria-checked={provider === "curseforge"} className={provider === "curseforge" ? "on curseforge" : "curseforge"} onClick={() => app.setPrefs({ provider: "curseforge" })}>
            CurseForge
          </button>
          <span className={`provider-thumb ${provider}`} aria-hidden />
        </div>
      </header>

      <SearchBar<Kind, Sort>
        category={{ value: kind, onChange: setKind, options: KINDS.map((k) => ({ id: k.id, label: k.label })) }}
        query={query}
        onQuery={setQuery}
        placeholder={`Search ${kindLabel.label.toLowerCase()} on ${provider === "modrinth" ? "Modrinth" : "CurseForge"}`}
        sort={{ value: sort, onChange: setSort, options: SORTS }}
      />

      <div className="content-layout">
        <div className="content-main">
          <div className="result-meta">
            <span className="muted">
              {cfMissing ? " " : loading ? "Searching" : `${total.toLocaleString("en-US")} ${total === 1 ? kindLabel.singular : kindLabel.label.toLowerCase()}`}
            </span>
            {kind !== "modpack" && targetProfile && (
              <span className="muted small">
                Installing into <strong>{targetProfile.name}</strong>. Shift-click Install for one click.
              </span>
            )}
          </div>
          {cfMissing ? (
            <EmptyState
              icon={<IPuzzle size={22} />}
              title="CurseForge needs an API key"
              text="CurseForge only allows apps with a key. Get one at console.curseforge.com, put it in kite.config.json as curseforge_api_key and restart the launcher."
              action={
                <div className="row">
                  <button className="btn" onClick={() => api.openPath("config-file").catch(app.error)}>
                    Show the config file
                  </button>
                  <button className="btn primary" onClick={() => app.setPrefs({ provider: "modrinth" })}>
                    Use Modrinth
                  </button>
                </div>
              }
            />
          ) : error ? (
            <ErrorCard message={error} retry={() => setRetry((r) => r + 1)} />
          ) : loading ? (
            <SkeletonRows n={6} h={104} />
          ) : shown.length === 0 ? (
            <EmptyState
              icon={<IPuzzle size={22} />}
              title={`No ${kindLabel.label.toLowerCase()} found`}
              text={query ? `Nothing matches “${query}” with these filters.` : "Try removing some filters."}
              action={
                <button
                  className="btn"
                  onClick={() => {
                    setQuery("");
                    setCats([]);
                    setLoaders([]);
                    setVersion("");
                  }}
                >
                  Clear search and filters
                </button>
              }
            />
          ) : (
            <ul className="hit-list">
              {shown.map((h) => {
                const has = installedIds.has(h.project_id);
                const loadersOf = h.categories.filter(isLoaderSlug).filter((c) => c !== "minecraft" && c !== "datapack");
                const catsOf = (h.display_categories?.length ? h.display_categories : h.categories).filter((c) => !isLoaderSlug(c)).slice(0, 3);
                return (
                  <li key={h.project_id} className="hit">
                    <button className="hit-icon" onClick={() => app.navigate({ page: "project", id: h.project_id, kind, provider })} aria-label={`View ${h.title}`}>
                      {h.icon_url ? <img src={h.icon_url} alt="" loading="lazy" /> : <span>{h.title[0]}</span>}
                    </button>
                    <div className="hit-body">
                      <div className="hit-title">
                        <button className="hit-name" onClick={() => app.navigate({ page: "project", id: h.project_id, kind, provider })}>
                          {h.title}
                        </button>
                        <span className="muted small">by {h.author}</span>
                      </div>
                      <p className="clamp">{h.description}</p>
                      <div className="hit-meta">
                        <span title={`${h.downloads.toLocaleString("en-US")} downloads`}>
                          <IDownload size={14} /> {compact(h.downloads)}
                        </span>
                        {h.follows !== undefined && (
                          <span title={`${h.follows.toLocaleString("en-US")} followers`}>
                            <IHeart size={14} /> {compact(h.follows)}
                          </span>
                        )}
                        {h.date_modified && (
                          <span>
                            <IClock size={14} /> {ago(h.date_modified)}
                          </span>
                        )}
                        {loadersOf.map((l) => (
                          <span key={l} className="chip loader">
                            {label(l)}
                          </span>
                        ))}
                        {catsOf.map((c) => (
                          <span key={c} className="chip">
                            {label(c)}
                          </span>
                        ))}
                      </div>
                    </div>
                    <div className="hit-actions">
                      <button className="btn" onClick={() => app.navigate({ page: "project", id: h.project_id, kind, provider })}>
                        <IEye size={16} /> View
                      </button>
                      {has && kind !== "modpack" ? (
                        <button className="btn installed" disabled>
                          <ICheck size={16} /> Installed
                        </button>
                      ) : (
                        <button
                          className="btn primary"
                          disabled={installer.busy === h.project_id}
                          onClick={(e) => installer.start(toInstall(h), e.shiftKey)}
                          title={kind === "modpack" ? "Shift-click to create the profile right away" : "Shift-click to install the newest version right away"}
                        >
                          {installer.busy === h.project_id ? <Spinner /> : <IDownload size={16} />} Install
                        </button>
                      )}
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
          <div ref={sentinel} className="sentinel">
            {more && <Spinner />}
          </div>
        </div>

        <aside className="filters" aria-label="Filters">
          {LOADERS[kind].length > 0 && !(provider === "curseforge" && kind === "shader") && (
            <FilterGroup title="Loader" active={loaders.length > 0} onClear={() => setLoaders([])}>
              {LOADERS[kind].map((l) => (
                <Checkbox key={l} checked={loaders.includes(l)} onChange={(v) => setLoaders((s) => (v ? [...s, l] : s.filter((x) => x !== l)))}>
                  {label(l)}
                </Checkbox>
              ))}
            </FilterGroup>
          )}
          <FilterGroup title="Game version" active={!!version} onClear={() => setVersion("")}>
            <select value={version} onChange={(e) => setVersion(e.target.value)} aria-label="Game version">
              <option value="">Any version</option>
              {(groups ?? []).map((g) => (
                <optgroup key={g.major} label={g.major}>
                  {g.minors.map((m) => (
                    <option key={m} value={m}>
                      {m}
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
            {targetProfile && version !== targetProfile.mc_version && (
              <button className="link sm" onClick={() => setVersion(targetProfile.mc_version)}>
                Match {targetProfile.name} ({targetProfile.mc_version})
              </button>
            )}
          </FilterGroup>
          {provider === "modrinth" && (
            <FilterGroup title="Categories" active={cats.length > 0} onClear={() => setCats([])}>
              {CATEGORIES[kind].map((c) => (
                <Checkbox key={c} checked={cats.includes(c)} onChange={(v) => setCats((s) => (v ? [...s, c] : s.filter((x) => x !== c)))}>
                  {label(c)}
                </Checkbox>
              ))}
            </FilterGroup>
          )}
          {kind !== "modpack" && target && (
            <FilterGroup title="Installed">
              <Checkbox checked={hideInstalled} onChange={setHideInstalled}>
                Hide what's in {targetProfile?.name}
              </Checkbox>
            </FilterGroup>
          )}
        </aside>
      </div>
      {installer.ui}
    </div>
  );
}
