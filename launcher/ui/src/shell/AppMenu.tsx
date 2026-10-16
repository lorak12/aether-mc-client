import { useEffect, useMemo, useState } from "react";
import { api, errText } from "../api";
import type { LauncherUpdate, UploadedLogs } from "../api/types";
import { useApp } from "../app/store";
import { ICheck, ICopy, IExternal, IFile, IFolder, IHelp, IInfo, ILogs, IPower, IRefresh, ISettings, IUpload, IChevronDown, IDownload, IWarning } from "../components/Icons";
import { Dropdown, MenuItem } from "../components/Menu";
import { Modal } from "../components/overlays";
import { Logo, Spinner } from "../components/ui";
import LICENSES from "../licenses.json";
import { LINKS } from "../lib/links";

declare const __APP_VERSION__: string;
export const APP_VERSION = typeof __APP_VERSION__ === "string" ? __APP_VERSION__ : "dev";

type Dialog = null | "about" | "updates" | "licenses" | "upload";

export function AppMenu() {
  const app = useApp();
  const [dialog, setDialog] = useState<Dialog>(null);
  const open = (url: string) => api.openUrl(url).catch(app.error);
  const runningId = app.launch.phase !== "idle" && "profileId" in app.launch ? app.launch.profileId : null;
  const folder = (kind: "app" | "launcher-logs" | "client-logs") => api.openPath(kind, kind === "client-logs" ? runningId ?? app.activeId : null).catch(app.error);

  const items: MenuItem[] = [
    { label: "About Kite Client", icon: <IInfo size={16} />, onSelect: () => setDialog("about") },
    ...(app.cfg?.updates ? [{ label: "Check for updates", icon: <IRefresh size={16} />, onSelect: () => setDialog("updates") }] : []),
    { label: "Licenses", icon: <IFile size={16} />, onSelect: () => setDialog("licenses") },
    { sep: true },
    { label: "Open app folder", icon: <IFolder size={16} />, onSelect: () => folder("app") },
    {
      label: "Open logs folder",
      icon: <ILogs size={16} />,
      items: [
        { label: "Launcher logs", icon: <IFolder size={16} />, onSelect: () => folder("launcher-logs") },
        { label: "Client logs", icon: <IFolder size={16} />, hint: app.active?.name, disabled: !app.activeId, onSelect: () => folder("client-logs") },
      ],
    },
    { label: "Share logs", icon: <IUpload size={16} />, onSelect: () => setDialog("upload") },
    { sep: true },
    { label: "Get help", icon: <IHelp size={16} />, hint: <IExternal size={13} />, onSelect: () => open(LINKS.support) },
    { label: "Source code", icon: <IFile size={16} />, hint: <IExternal size={13} />, onSelect: () => open(LINKS.source) },
    { sep: true },
    { label: "Settings", icon: <ISettings size={16} />, hint: "Ctrl+,", onSelect: () => app.navigate({ page: "settings" }) },
    { label: "Restart", icon: <IPower size={16} />, onSelect: () => api.restartApp().catch(() => location.reload()) },
  ];

  return (
    <>
      <Dropdown items={items} className="app-menu-btn" label="App menu" width={248}>
        <Logo size={26} />
        <span className="brand-name">Kite Client</span>
        <IChevronDown size={15} />
      </Dropdown>
      <AboutModal open={dialog === "about"} onClose={() => setDialog(null)} />
      <UpdatesModal open={dialog === "updates"} onClose={() => setDialog(null)} />
      <LicensesModal open={dialog === "licenses"} onClose={() => setDialog(null)} />
      <UploadLogsModal open={dialog === "upload"} onClose={() => setDialog(null)} profileId={runningId ?? app.activeId} />
    </>
  );
}

function AboutModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <Modal open={open} onClose={onClose} width={420} className="about">
      <div className="about-body">
        <Logo size={64} />
        <h2>Kite Client</h2>
        <p className="muted">Launcher {APP_VERSION}</p>
        <p>A Minecraft client with built-in mods, cosmetics and a launcher for every version and modpack.</p>
        <p className="muted small">Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.</p>
        <button className="btn" onClick={onClose}>
          Close
        </button>
      </div>
    </Modal>
  );
}

function UpdatesModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const app = useApp();
  const [info, setInfo] = useState<LauncherUpdate | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [installing, setInstalling] = useState(false);
  useEffect(() => {
    if (!open) return;
    setInfo(null);
    setErr(null);
    api.checkUpdate().then(setInfo).catch((e) => setErr(errText(e)));
  }, [open]);
  const install = async () => {
    setInstalling(true);
    try {
      await api.installUpdate();
    } catch (e) {
      app.error(e);
      setInstalling(false);
    }
  };
  return (
    <Modal open={open} onClose={onClose} title="Check for updates" width={440}>
      {err ? (
        <div className="status-line">
          <IWarning size={16} /> {err}
        </div>
      ) : !info ? (
        <div className="status-line">
          <Spinner /> Checking for a newer version
        </div>
      ) : !info.available ? (
        <div className="status-line">
          <span className="ok-dot">
            <ICheck size={14} />
          </span>
          You're up to date. Kite Client {info.current} is the newest version.
        </div>
      ) : (
        <div className="stack">
          <p>
            <strong>Kite {info.latest}</strong> is available. You have {info.current}.
          </p>
          {info.notes && <p className="muted pre-wrap">{info.notes}</p>}
          {info.installable ? (
            <button className="btn primary" disabled={installing} onClick={install}>
              {installing ? <Spinner /> : <IDownload size={16} />} Download and install
            </button>
          ) : (
            <>
              <p className="hint">There's no installer for your system in this release yet.</p>
              <button className="btn" onClick={() => api.openUrl(LINKS.releases).catch(app.error)}>
                Open the releases page <IExternal size={13} />
              </button>
            </>
          )}
          {installing && <p className="hint">The installer opens and Kite Client closes to finish the update.</p>}
        </div>
      )}
    </Modal>
  );
}

function LicensesModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [q, setQ] = useState("");
  const list = useMemo(() => (LICENSES as { name: string; version: string; license: string; source: string }[]).filter((l) => !q || l.name.toLowerCase().includes(q.toLowerCase())), [q]);
  return (
    <Modal open={open} onClose={onClose} title="Open-source licenses" width={520}>
      <p className="muted">Kite Client is built with {LICENSES.length} open-source packages.</p>
      <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search packages" aria-label="Search packages" />
      <ul className="license-list scroll">
        {list.map((l) => (
          <li key={`${l.source}:${l.name}`}>
            <strong>{l.name}</strong>
            <span className="muted small">
              {l.version} · {l.license}
            </span>
          </li>
        ))}
      </ul>
    </Modal>
  );
}

/** Upload the launcher log and a profile's latest game log to mclo.gs and show the links. */
export function UploadLogsModal({ open, onClose, profileId }: { open: boolean; onClose: () => void; profileId: string | null }) {
  const app = useApp();
  const [result, setResult] = useState<UploadedLogs | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!open) return;
    setResult(null);
    setErr(null);
  }, [open]);
  const upload = async () => {
    setBusy(true);
    setErr(null);
    try {
      setResult(await api.uploadLogs(profileId));
    } catch (e) {
      setErr(errText(e));
    } finally {
      setBusy(false);
    }
  };
  const profile = app.profiles.find((p) => p.id === profileId);
  const copy = (link: string) => navigator.clipboard?.writeText(link).then(() => app.toast("Link copied"));
  return (
    <Modal open={open} onClose={onClose} title="Share logs" width={480}>
      {!result ? (
        <div className="stack">
          <p>
            Uploads the launcher log{profile ? ` and ${profile.name}'s latest game log` : ""} to <strong>mclo.gs</strong> so you can share a link with support.
          </p>
          <p className="muted small">Sign-in tokens are removed before uploading, and mclo.gs hides IP addresses. Anyone with the link can read the logs.</p>
          {err && <p className="form-error">{err}</p>}
          <button className="btn primary" disabled={busy} onClick={upload}>
            {busy ? <Spinner /> : <IUpload size={16} />} Upload logs
          </button>
        </div>
      ) : (
        <div className="stack">
          <p>Share these links with whoever is helping you.</p>
          {[
            ["Launcher log", result.launcher],
            [result.profile ? `${result.profile} game log` : "Game log", result.game],
          ]
            .filter(([, l]) => !!l)
            .map(([label, link]) => (
              <div key={label} className="stack tight">
                <span className="muted small">{label}</span>
                <div className="code-row">
                  <span className="code small">{link}</span>
                  <button className="icon-btn" aria-label={`Copy ${label} link`} onClick={() => copy(link!)}>
                    <ICopy size={17} />
                  </button>
                  <button className="icon-btn" aria-label={`Open ${label}`} onClick={() => api.openUrl(link!).catch(app.error)}>
                    <IExternal size={17} />
                  </button>
                </div>
              </div>
            ))}
        </div>
      )}
    </Modal>
  );
}
