import { useCallback, useEffect, useState } from "react";
import { api, errText } from "../../api";
import type { InstallPlan, Kind, ModVersion, Provider } from "../../api/types";
import { useApp } from "../../app/store";
import { useAsync } from "../../components/hooks";
import { IDownload } from "../../components/Icons";
import { Confirm, Modal } from "../../components/overlays";
import { EmptyState, ErrorCard, Field, SkeletonRows } from "../../components/ui";
import { ago, label } from "../../lib/format";
import { dependencyQuestion, needsDependencyConfirm, resolveTarget } from "../../lib/install";
import { LOADER_NAMES } from "../../lib/versions";

export interface InstallItem {
  project_id: string;
  title: string;
  kind: Kind;
  icon_url: string | null;
  cover?: string | null;
  provider: Provider;
  /** Version to preselect in the dialog. */
  versionId?: string;
}

interface PendingDeps {
  item: InstallItem;
  profileId: string;
  versionId: string | null;
  world: string | null;
  plan: InstallPlan;
}

/** Install flow shared by the Content list and project pages: modal, Shift quick install, dependency prompt. */
export function useInstaller(onInstalled?: (profileId: string) => void) {
  const app = useApp();
  const [modal, setModal] = useState<InstallItem | null>(null);
  const [deps, setDeps] = useState<PendingDeps | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const notify = (n: Parameters<typeof app.pushNotification>[0]) => (app.prefs.notifyDownloads ? app.pushNotification(n) : null);

  const run = useCallback(
    async (item: InstallItem, profileId: string, versionId: string | null, world: string | null, withDeps: boolean) => {
      const profile = app.profiles.find((p) => p.id === profileId);
      const note = notify({ kind: "download", title: `Installing ${item.title}`, detail: `Into ${profile?.name ?? profileId}`, progress: 0.35 });
      setBusy(item.project_id);
      try {
        const added = await api.install(profileId, item.project_id, item.kind, { versionId, withDeps, world, provider: item.provider });
        const extra = added.length > 1 ? ` and ${added.length - 1} ${added.length === 2 ? "dependency" : "dependencies"}` : "";
        if (note)
          app.updateNotification(note, {
            title: `Installed ${item.title}${extra}`,
            detail: `In ${profile?.name ?? profileId}`,
            progress: undefined,
            action: { label: "Open profile", route: { page: "profiles", open: profileId } },
          });
        app.toast(`Installed ${item.title}${extra} into ${profile?.name ?? profileId}`, {
          tone: "success",
          action: { label: "Open profile", run: () => app.navigate({ page: "profiles", open: profileId }) },
        });
        onInstalled?.(profileId);
      } catch (e) {
        if (note) app.updateNotification(note, { kind: "crash", title: `Couldn't install ${item.title}`, detail: errText(e), progress: undefined });
        app.error(e);
      } finally {
        setBusy(null);
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [app, onInstalled],
  );

  /** Resolve dependencies first; ask before installing any the profile doesn't have. */
  const begin = useCallback(
    async (item: InstallItem, profileId: string, versionId: string | null, world: string | null) => {
      setBusy(item.project_id);
      try {
        const plan = await api.installPlan(profileId, item.project_id, versionId, item.kind, item.provider);
        if (needsDependencyConfirm(plan)) {
          setBusy(null);
          setDeps({ item, profileId, versionId, world, plan });
          return;
        }
      } catch (e) {
        setBusy(null);
        app.error(e);
        return;
      }
      await run(item, profileId, versionId, world, true);
    },
    [app, run],
  );

  const installModpack = useCallback(
    async (item: InstallItem, versionId: string | null) => {
      const note = notify({ kind: "download", title: `Downloading ${item.title}`, detail: "Creating a new profile", progress: 0.2 });
      setBusy(item.project_id);
      try {
        const p = await api.installModpack(item.project_id, versionId, item.provider);
        const cover = item.cover || item.icon_url;
        if (cover && !p.meta.cover) await api.updateProfile({ ...p, meta: { ...p.meta, cover } }).catch(() => {});
        await app.refreshProfiles();
        app.setActive(p.id);
        if (note)
          app.updateNotification(note, { title: `${p.name} is ready`, detail: `New profile, ${p.mc_version} ${LOADER_NAMES[p.loader]}`, progress: undefined, action: { label: "Open", route: { page: "profiles", open: p.id } } });
        app.toast(`Created the ${p.name} profile`, { tone: "success", action: { label: "Open", run: () => app.navigate({ page: "profiles", open: p.id }) } });
      } catch (e) {
        if (note) app.updateNotification(note, { kind: "crash", title: `Couldn't install ${item.title}`, detail: errText(e), progress: undefined });
        app.error(e);
      } finally {
        setBusy(null);
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [app],
  );

  /** Install button: opens the dialog, or with Shift installs the newest version into the remembered profile. */
  const start = useCallback(
    (item: InstallItem, quick = false) => {
      if (item.provider === "curseforge" && !app.cfg?.curseforge) {
        app.toast("CurseForge needs an API key. Add curseforge_api_key to kite.config.json (Settings > About).", { tone: "error" });
        return;
      }
      if (quick && item.kind === "modpack") return void installModpack(item, null);
      if (quick && item.kind !== "datapack") {
        const target = resolveTarget(app.prefs.installTarget, app.activeId, app.profiles);
        if (!target) return app.setNewProfileOpen(true);
        return void begin(item, target, null, null);
      }
      setModal(item);
    },
    [app, begin, installModpack],
  );

  const ui = (
    <>
      {modal && (
        <InstallModal
          item={modal}
          onClose={() => setModal(null)}
          onInstall={(profileId, versionId, world) => {
            setModal(null);
            if (modal.kind === "modpack") installModpack(modal, versionId);
            else begin(modal, profileId!, versionId, world);
          }}
        />
      )}
      <Confirm
        open={!!deps}
        title="Install dependencies?"
        text={
          deps && (
            <>
              <p>{dependencyQuestion(deps.item.title, deps.plan)}</p>
              <ul className="dep-list">
                {deps.plan.dependencies.map((d) => (
                  <li key={d.project_id}>
                    <strong>{d.title}</strong> <span className="muted small">{d.version}</span>
                  </li>
                ))}
              </ul>
            </>
          )
        }
        confirm="Install all"
        cancel="Cancel"
        extra={
          <button
            className="btn ghost"
            onClick={() => {
              const d = deps!;
              setDeps(null);
              run(d.item, d.profileId, d.versionId, d.world, false);
            }}
          >
            Only {deps?.item.title}
          </button>
        }
        onClose={() => setDeps(null)}
        onConfirm={() => {
          const d = deps!;
          setDeps(null);
          run(d.item, d.profileId, d.versionId, d.world, true);
        }}
      />
    </>
  );

  return { start, ui, busy };
}

function InstallModal({ item, onClose, onInstall }: { item: InstallItem; onClose: () => void; onInstall: (profileId: string | null, versionId: string | null, world: string | null) => void }) {
  const app = useApp();
  const pack = item.kind === "modpack";
  const [target, setTarget] = useState<string | null>(() => resolveTarget(app.prefs.installTarget, app.activeId, app.profiles));
  const [world, setWorld] = useState("");
  const versions = useAsync<ModVersion[]>(() => api.projectVersions(item.project_id, item.kind, pack ? null : target, item.provider), [item.project_id, target, pack]);
  const worlds = useAsync(() => (item.kind === "datapack" && target ? api.worlds(target) : Promise.resolve([])), [item.kind, target]);
  useEffect(() => {
    if (worlds.data && !worlds.data.some((w) => w.id === world)) setWorld(worlds.data[0]?.id ?? "");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [worlds.data]);
  const [picked, setPicked] = useState<string | null>(null);
  useEffect(() => {
    const list = versions.data ?? [];
    setPicked((list.find((v) => v.id === item.versionId) ?? list.find((v) => v.version_type === "release") ?? list[0])?.id ?? null);
  }, [versions.data, item.versionId]);

  const profile = app.profiles.find((p) => p.id === target);
  const canInstall = !!picked && (pack || (!!target && (item.kind !== "datapack" || world.trim())));

  return (
    <Modal
      open
      onClose={onClose}
      width={560}
      title={`Install ${item.title}`}
      footer={
        <>
          <span className="muted small grow">{pack ? "Creates a new profile with the modpack's cover." : "Tip: Shift-click Install to skip this dialog."}</span>
          <button className="btn ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={!canInstall} onClick={() => onInstall(target, picked, item.kind === "datapack" ? world.trim() : null)}>
            <IDownload size={16} /> {pack ? "Create profile" : "Install"}
          </button>
        </>
      }
    >
      <div className="stack">
        {!pack && (
          <Field label="Profile" hint="Remembered for your next installs.">
            {app.profiles.length === 0 ? (
              <button className="btn" onClick={() => (onClose(), app.setNewProfileOpen(true))}>
                Create a profile first
              </button>
            ) : (
              <select
                value={target ?? ""}
                onChange={(e) => {
                  setTarget(e.target.value);
                  app.setPrefs({ installTarget: e.target.value });
                }}
              >
                {app.profiles.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} ({p.mc_version} {LOADER_NAMES[p.loader]})
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
        {item.kind === "datapack" && (
          <Field label="World" hint="Datapacks belong to one world.">
            {!worlds.data ? (
              <SkeletonRows n={1} h={36} />
            ) : worlds.data.length === 0 ? (
              <p className="note warning">{profile?.name ?? "This profile"} has no worlds yet. Create one in game first, then install the datapack into it.</p>
            ) : (
              <select value={world} onChange={(e) => setWorld(e.target.value)} aria-label="World">
                {worlds.data.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.name}
                    {w.name !== w.id ? ` (${w.id})` : ""}
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
        <div className="version-pick">
          <div className="menu-heading">Version</div>
          {versions.error ? (
            <ErrorCard message={versions.error} retry={versions.reload} />
          ) : versions.loading ? (
            <SkeletonRows n={4} h={44} />
          ) : !versions.data?.length ? (
            <EmptyState
              title="No compatible version"
              text={profile ? `${item.title} has no version for ${profile.mc_version} with ${LOADER_NAMES[profile.loader]}. Pick another profile.` : "This project has no versions yet."}
            />
          ) : (
            <div className="version-list" role="radiogroup">
              {versions.data.slice(0, 40).map((v) => (
                <label key={v.id} className={picked === v.id ? "vrow on" : "vrow"}>
                  <input type="radio" name="version" checked={picked === v.id} onChange={() => setPicked(v.id)} />
                  <span className="vrow-main">
                    <strong>{v.version_number}</strong>
                    <span className="muted small">
                      {v.loaders.map(label).join(", ")} for {v.game_versions.slice(-3).join(", ")}
                      {v.game_versions.length > 3 ? " and more" : ""}
                    </span>
                  </span>
                  <span className={`vtype ${v.version_type}`}>{label(v.version_type)}</span>
                  <span className="muted small">{ago(v.date_published)}</span>
                </label>
              ))}
            </div>
          )}
        </div>
      </div>
    </Modal>
  );
}
