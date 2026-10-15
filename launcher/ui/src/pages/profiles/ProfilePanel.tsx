import { useState } from "react";
import type { Profile } from "../../api/types";
import { useApp } from "../../app/store";
import { IPlay, ISettings, IStop } from "../../components/Icons";
import { SidePanel } from "../../components/overlays";
import { Cover, Spinner } from "../../components/ui";
import { coverOf } from "../../lib/art";
import { ago, megabytes } from "../../lib/format";
import { effective } from "../../lib/overrides";
import { clientSupported, groupOf, listText, LOADER_NAMES, profileLabel } from "../../lib/versions";
import { LoaderChoice, LoaderPicker, MinorPicker } from "./pickers";
import { ProfileSettings } from "./ProfileSettings";

export function ProfilePanel({ profile, onClose }: { profile: Profile | null; onClose: () => void }) {
  const app = useApp();
  const [settings, setSettings] = useState(false);
  const [saving, setSaving] = useState(false);
  // Keep the last profile rendered while the panel slides out.
  const [last, setLast] = useState<Profile | null>(profile);
  if (profile && profile !== last) setLast(profile);
  const p = profile ?? last;
  if (!p) return null;
  const m = p.meta;
  const busy = app.launch.phase === "preparing" || app.launch.phase === "running";
  const thisRunning = busy && "profileId" in app.launch && app.launch.profileId === p.id;
  const memory = effective(app.globalSettings, p.overrides).memoryMb;
  const loaderValue: LoaderChoice = m.kind === "client" && m.builtinOnly ? "builtin" : p.loader;

  const update = async (patch: Partial<Profile>) => {
    setSaving(true);
    await app.saveProfile({ ...p, ...patch });
    setSaving(false);
  };

  const setLoader = (l: LoaderChoice) => {
    if (l === "builtin") update({ loader: p.loader === "fabric" || p.loader === "quilt" ? p.loader : "fabric", meta: { ...m, builtinOnly: true } });
    else update({ loader: l, meta: { ...m, builtinOnly: m.kind === "client" ? false : m.builtinOnly } });
  };

  const launch = () => {
    onClose();
    app.navigate({ page: "home" });
    app.play(p.id);
  };

  return (
    <>
      <SidePanel open={!!profile && !settings} onClose={onClose} width={440}>
        <Cover cover={coverOf(p)} seed={p.id} loader={p.loader} className="panel-cover">
          <div className="panel-cover-text">
            <h2>{p.name}</h2>
            <span>{profileLabel(p)}</span>
          </div>
        </Cover>
        <div className="panel-body">
          <p className={m.description ? "panel-desc" : "panel-desc muted"}>{m.description || "No description yet. Add one in the profile settings."}</p>
          <div className="panel-stats">
            <div>
              <span className="muted small">Last played</span>
              <strong>{m.lastPlayed ? ago(m.lastPlayed) : "Never"}</strong>
            </div>
            <div>
              <span className="muted small">Memory</span>
              <strong>{megabytes(memory)}</strong>
            </div>
            <div>
              <span className="muted small">Played</span>
              <strong>{m.plays === 1 ? "Once" : `${m.plays} times`}</strong>
            </div>
          </div>

          <section className="panel-section">
            <h4>
              Version <span className="muted">{groupOf(p.mc_version)}</span> {saving && <Spinner size={13} />}
            </h4>
            <MinorPicker value={p.mc_version} onChange={(v) => v !== p.mc_version && update({ mc_version: v })} disabled={busy || saving || m.kind === "modpack"} />
            {m.kind === "modpack" && <p className="hint">Modpacks are made for one version, so it can't be changed here.</p>}
          </section>

          <section className="panel-section">
            <h4>Loader</h4>
            {m.kind === "modpack" ? (
              <p>{LOADER_NAMES[p.loader]}</p>
            ) : (
              <LoaderPicker kind={m.kind} value={loaderValue} onChange={setLoader} disabled={busy || saving} />
            )}
            {m.kind === "client" && (
              <p className="hint">
                {loaderValue === "builtin" ? "Vanilla loads only Kite's built-in mods, not mods from other loaders." : `Kite's built-in mods plus the ${LOADER_NAMES[p.loader]} mods in this profile.`}
              </p>
            )}
            {m.kind === "client" && app.aetherVersions.length > 0 && !clientSupported(p.mc_version, p.loader, app.aetherVersions) && (
              <p className="note warning">The Kite client supports {listText(app.aetherVersions)} today. This version starts without built-in mods.</p>
            )}
          </section>
        </div>
        <footer className="panel-foot">
          <button className="btn" onClick={() => setSettings(true)}>
            <ISettings size={17} /> Settings
          </button>
          {thisRunning ? (
            <button className="btn danger grow" onClick={app.stop}>
              <IStop size={15} /> {app.launch.phase === "preparing" ? "Cancel launch" : "Stop the game"}
            </button>
          ) : (
            <button className="btn primary grow" disabled={busy || !app.account} onClick={launch} title={app.account ? (busy ? "Another profile is running" : undefined) : "Add an account first"}>
              <IPlay size={15} /> Launch
            </button>
          )}
        </footer>
      </SidePanel>
      {settings && <ProfileSettings profile={p} onClose={() => setSettings(false)} onDeleted={onClose} />}
    </>
  );
}
