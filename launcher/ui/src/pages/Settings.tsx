import { useEffect, useState } from "react";
import { PageHead } from "../Ui";
import { api, ConfigStatus, DeviceCode, errText } from "../api";

const ITEMS: { key: keyof ConfigStatus; name: string; env: string; how: string }[] = [
  { key: "login", name: "Microsoft sign-in", env: "AETHER_AZURE_CLIENT_ID", how: "Azure AD app (public client) approved for Minecraft services" },
  { key: "curseforge", name: "CurseForge", env: "AETHER_CURSEFORGE_API_KEY", how: "API key from console.curseforge.com" },
  { key: "backend", name: "Cosmetics backend", env: "AETHER_BACKEND_URL", how: "Base URL of the Aether API" },
];

export function Settings({ cfg, notify }: { cfg: ConfigStatus | null; notify: (m: string) => void }) {
  const [code, setCode] = useState<DeviceCode | null>(null);
  const [signedIn, setSignedIn] = useState(false);
  const [name, setName] = useState<string | null>(null);

  useEffect(() => {
    api
      .accountName()
      .then((n) => {
        setSignedIn(n !== null);
        setName(n || null);
      })
      .catch(() => {});
  }, []);

  const login = async () => {
    try {
      const c = await api.startLogin();
      setCode(c);
      const acc = await api.finishLogin();
      setName(acc.username);
      setSignedIn(true);
      setCode(null);
      notify(`Signed in as ${acc.username}`);
    } catch (e) {
      setCode(null);
      notify(errText(e));
    }
  };

  return (
    <div className="page">
      <PageHead title="Settings" sub="Account and integrations." />
      <section className="card">
        <h3>Account</h3>
        {signedIn ? (
          <div className="row between">
            <span className="row"><span className="avatar on big">{name ? name[0].toUpperCase() : "✓"}</span><span><strong>{name ?? "Signed in"}</strong><div className="muted small">Microsoft account connected</div></span></span>
            <button onClick={() => api.logout().then(() => (setSignedIn(false), setName(null)))}>Sign out</button>
          </div>
        ) : code ? (
          <div>
            <p>
              Go to <strong>{code.verification_uri}</strong> and enter code
            </p>
            <div className="code">{code.user_code}</div>
            <p className="muted small">Waiting for you to finish signing in…</p>
          </div>
        ) : (
          <button className="primary" disabled={!cfg?.login} onClick={login} title={cfg?.login ? "" : "Set AETHER_AZURE_CLIENT_ID first"}>
            Sign in with Microsoft
          </button>
        )}
      </section>
      <section className="card">
        <h3>Integrations</h3>
        <p className="muted small">
          Set these as environment variables or in <code>aether.config.json</code> in the app data folder. Restart the launcher after changing them.
        </p>
        <ul className="list compact">
          {ITEMS.map((i) => (
            <li key={i.key}>
              <div>
                <strong>{i.name}</strong>
                <div className="muted small">
                  <code>{i.env}</code> — {i.how}
                </div>
              </div>
              <span className={cfg?.[i.key] ? "badge ok" : "badge"}>{cfg?.[i.key] ? "configured" : "missing"}</span>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
