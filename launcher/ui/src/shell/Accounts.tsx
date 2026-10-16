import { RefObject, useState } from "react";
import { api, errText } from "../api";
import type { DeviceCode, Presence } from "../api/types";
import { useApp } from "../app/store";
import { ICopy, IExternal, IPlus, ISettings, IPower, IClose, IUser } from "../components/Icons";
import { Popout } from "../components/Menu";
import { Modal } from "../components/overlays";
import { presenceLabel, SkinHead, Spinner, Segmented } from "../components/ui";

/** Sign in another account: Microsoft (browser, or a code on another device) or an offline name for development. */
export function AddAccountModal({ open, onClose, mode: initial = "microsoft" }: { open: boolean; onClose: () => void; mode?: "microsoft" | "offline" }) {
  const app = useApp();
  const [mode, setMode] = useState(initial);
  const [code, setCode] = useState<DeviceCode | null>(null);
  const [browser, setBrowser] = useState(false);
  const [busy, setBusy] = useState(false);
  const [name, setName] = useState("");
  const [err, setErr] = useState<string | null>(null);

  const close = () => {
    if (browser) api.cancelLogin().catch(() => {});
    setCode(null);
    setBrowser(false);
    setBusy(false);
    setErr(null);
    onClose();
  };
  const done = async (username: string) => {
    await app.refreshAccounts();
    app.toast(`Signed in as ${username}`, { tone: "success" });
    setBrowser(false);
    close();
  };

  /** Microsoft's own sign-in page in the browser (email and password, passkeys, two-step verification). */
  const withBrowser = async () => {
    setErr(null);
    setBusy(true);
    setBrowser(true);
    try {
      const acc = await api.browserLogin();
      await done(acc.username);
    } catch (e) {
      const msg = errText(e);
      if (msg !== "Sign-in cancelled") setErr(msg);
      setBrowser(false);
    } finally {
      setBusy(false);
    }
  };

  /** A code to enter at microsoft.com/link, from any device. */
  const withCode = async () => {
    setErr(null);
    setBusy(true);
    try {
      const c = await api.startLogin();
      setCode(c);
      const acc = await api.finishLogin();
      await done(acc.username);
    } catch (e) {
      setErr(errText(e));
      setCode(null);
    } finally {
      setBusy(false);
    }
  };

  const offline = async () => {
    setErr(null);
    setBusy(true);
    try {
      const acc = await api.offlineLogin(name.trim());
      await app.refreshAccounts();
      app.toast(`Playing offline as ${acc.username}`, { tone: "success" });
      close();
    } catch (e) {
      setErr(errText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open={open} onClose={close} title="Add an account" width={460}>
      <Segmented
        value={mode}
        onChange={(m) => {
          setMode(m);
          setErr(null);
        }}
        options={[
          { id: "microsoft", label: "Microsoft" },
          { id: "offline", label: "Offline (development)" },
        ]}
      />
      {mode === "microsoft" ? (
        code ? (
          <div className="device-code">
            <p>Open the Microsoft sign-in page and enter this code:</p>
            <div className="code-row">
              <span className="code">{code.user_code}</span>
              <button className="icon-btn" aria-label="Copy code" onClick={() => navigator.clipboard?.writeText(code.user_code).then(() => app.toast("Code copied"))}>
                <ICopy size={17} />
              </button>
            </div>
            <button className="btn" onClick={() => api.openUrl(code.verification_uri).catch(app.error)}>
              <IExternal size={16} /> Open {code.verification_uri.replace(/^https?:\/\//, "")}
            </button>
            <p className="muted small row">
              <Spinner size={14} /> Waiting for you to finish signing in
            </p>
          </div>
        ) : browser ? (
          <div className="device-code">
            <p>Finish signing in on the Microsoft page that opened in your browser.</p>
            <p className="muted small row">
              <Spinner size={14} /> Waiting for Microsoft
            </p>
            <button
              className="btn ghost"
              onClick={() => {
                api.cancelLogin().catch(() => {});
                setBrowser(false);
              }}
            >
              Cancel
            </button>
          </div>
        ) : (
          <div className="stack">
            <p className="muted">Sign in with the Microsoft account that owns Minecraft. You can add more than one and switch between them.</p>
            <button className="btn primary" disabled={busy || app.cfg?.login === false} onClick={withBrowser}>
              {busy ? <Spinner /> : <IUser size={17} />} Sign in with Microsoft
            </button>
            <button className="btn ghost" disabled={busy || app.cfg?.login === false} onClick={withCode}>
              Use a code instead
            </button>
            <p className="hint">The code option lets you sign in on your phone or another computer.</p>
            {app.cfg?.login === false && <p className="hint">Microsoft sign-in isn't configured. Add azure_client_id to kite.config.json and restart the launcher.</p>}
          </div>
        )
      ) : (
        <div className="stack">
          <p className="muted">Offline accounts can't join online-mode servers. Use them for testing.</p>
          <input
            value={name}
            maxLength={16}
            onChange={(e) => setName(e.target.value)}
            placeholder="Player name"
            autoFocus
            onKeyDown={(e) => e.key === "Enter" && name.trim() && offline()}
          />
          <button className="btn primary" disabled={busy || !name.trim()} onClick={offline}>
            Add offline account
          </button>
        </div>
      )}
      {err && <p className="form-error">{err}</p>}
    </Modal>
  );
}

const PRESENCES: Presence[] = ["online", "away", "busy", "invisible"];

/** Account switcher, opened from the top bar chip and the Home greeting. */
export function AccountMenu({ open, onClose, anchor, align = "end" }: { open: boolean; onClose: () => void; anchor: RefObject<HTMLElement>; align?: "start" | "end" }) {
  const app = useApp();
  const [adding, setAdding] = useState<null | "microsoft" | "offline">(null);
  const acc = app.account;
  const others = app.accounts?.accounts.filter((a) => a.uuid !== acc?.uuid) ?? [];

  const signOut = async () => {
    onClose();
    try {
      await api.logout();
      await app.refreshAccounts();
      app.toast("Signed out");
    } catch (e) {
      app.error(e);
    }
  };

  return (
    <>
      <Popout open={open} onClose={onClose} anchor={anchor} align={align} className="account-pop" width={300}>
        {acc ? (
          <div className="acct-head">
            <SkinHead name={acc.username} uuid={acc.kind === "microsoft" ? acc.uuid : null} size={44} presence={app.presence} badge />
            <div>
              <strong>{acc.username || "Signed in"}</strong>
              <span className="muted small">{acc.kind === "microsoft" ? "Microsoft account" : "Offline account"}</span>
            </div>
          </div>
        ) : (
          <div className="acct-head">
            <SkinHead name="?" size={44} />
            <div>
              <strong>Not signed in</strong>
              <span className="muted small">Add an account to play</span>
            </div>
          </div>
        )}
        {acc && (
          <div className="presence-pick" role="radiogroup" aria-label="Status">
            {PRESENCES.map((p) => (
              <button key={p} role="radio" aria-checked={app.presence === p} className={app.presence === p ? "on" : ""} onClick={() => app.setPresence(p)} title={presenceLabel(p)}>
                <span className={`presence ${p}`} />
                {presenceLabel(p).replace("Do not disturb", "Busy")}
              </button>
            ))}
          </div>
        )}
        {others.length > 0 && (
          <div className="acct-list">
            <div className="menu-heading">Switch account</div>
            {others.map((a) => (
              <div key={a.uuid} className="acct-row">
                <button
                  className="acct-switch"
                  onClick={() => {
                    onClose();
                    app.switchAccount(a.uuid);
                  }}
                >
                  <SkinHead name={a.username} uuid={a.kind === "microsoft" ? a.uuid : null} size={26} />
                  <span>{a.username}</span>
                  <span className="muted small">{a.kind === "offline" ? "Offline" : ""}</span>
                </button>
                <button
                  className="icon-btn sm"
                  aria-label={`Remove ${a.username}`}
                  title="Remove from this device"
                  onClick={() => api.removeAccount(a.uuid).then(app.refreshAccounts).catch(app.error)}
                >
                  <IClose size={14} />
                </button>
              </div>
            ))}
          </div>
        )}
        <div className="menu">
          <div className="menu-row">
            <button className="menu-item" onClick={() => (onClose(), setAdding("microsoft"))}>
              <span className="menu-icon">
                <IPlus size={16} />
              </span>
              <span className="menu-label">Add Microsoft account</span>
            </button>
          </div>
          <div className="menu-row">
            <button className="menu-item" onClick={() => (onClose(), setAdding("offline"))}>
              <span className="menu-icon">
                <IUser size={16} />
              </span>
              <span className="menu-label">Add offline account</span>
            </button>
          </div>
          <div className="menu-row">
            <button className="menu-item" onClick={() => (onClose(), app.navigate({ page: "settings", tab: "accounts" }))}>
              <span className="menu-icon">
                <ISettings size={16} />
              </span>
              <span className="menu-label">Manage accounts</span>
            </button>
          </div>
          {acc && (
            <>
              <div className="menu-sep" />
              <div className="menu-row">
                <button className="menu-item danger" onClick={signOut}>
                  <span className="menu-icon">
                    <IPower size={16} />
                  </span>
                  <span className="menu-label">Sign out of {acc.username || "this account"}</span>
                </button>
              </div>
            </>
          )}
        </div>
      </Popout>
      <AddAccountModal key={adding ?? "none"} open={!!adding} mode={adding ?? "microsoft"} onClose={() => setAdding(null)} />
    </>
  );
}
