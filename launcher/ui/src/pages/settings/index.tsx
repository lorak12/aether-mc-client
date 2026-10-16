import { useState } from "react";
import { api } from "../../api";
import { useApp } from "../../app/store";
import { useAsync } from "../../components/hooks";
import { IExternal, IPlus, IUser, ITrash, ICheck, IFolder, IFile } from "../../components/Icons";
import { Field, Logo, Segmented, SkinHead, Toggle } from "../../components/ui";
import { LINKS } from "../../lib/links";
import { save } from "../../lib/prefs";
import { AddAccountModal } from "../../shell/Accounts";
import { APP_VERSION } from "../../shell/AppMenu";
import { GameSettingsForm } from "./GameSettingsForm";

const TABS = [
  { id: "general", label: "General" },
  { id: "appearance", label: "Appearance" },
  { id: "accounts", label: "Accounts" },
  { id: "java", label: "Java and game" },
  { id: "content", label: "Content" },
  { id: "notifications", label: "Notifications" },
  { id: "about", label: "About" },
] as const;
type Tab = (typeof TABS)[number]["id"];

/** Every setting, for the Ctrl+K search. */
export const SETTINGS_INDEX: { id: string; title: string; section: string; tab: Tab; keywords?: string }[] = [
  { id: "onlaunch", title: "When the game starts", section: "General", tab: "general", keywords: "minimise close hide tray launcher window" },
  { id: "friendshome", title: "Show friends on Home", section: "General", tab: "general", keywords: "sidebar right" },
  { id: "sidebar", title: "Collapse sidebar", section: "Appearance", tab: "appearance", keywords: "navigation compact icons" },
  { id: "motion", title: "Reduce motion", section: "Appearance", tab: "appearance", keywords: "animation accessibility" },
  { id: "banners", title: "Show dismissed announcements again", section: "Appearance", tab: "appearance", keywords: "banner sale" },
  { id: "accounts", title: "Accounts", section: "Accounts", tab: "accounts", keywords: "microsoft sign in login offline switch remove" },
  { id: "java", title: "Java", section: "Java and game", tab: "java", keywords: "jdk jre runtime path version" },
  { id: "memory", title: "Memory", section: "Java and game", tab: "java", keywords: "ram heap allocation xmx" },
  { id: "jvm", title: "JVM arguments", section: "Java and game", tab: "java", keywords: "java flags" },
  { id: "gc", title: "Garbage collector", section: "Java and game", tab: "java", keywords: "g1 zgc shenandoah" },

  { id: "res", title: "Game resolution", section: "Java and game", tab: "java", keywords: "window size fullscreen" },
  { id: "options", title: "Same Minecraft settings in every profile", section: "Java and game", tab: "java", keywords: "options.txt keybinds controls video graphics sound share sync" },
  { id: "hooks", title: "Pre-launch, wrapper and post-exit commands", section: "Java and game", tab: "java", keywords: "script hook" },
  { id: "env", title: "Environment variables", section: "Java and game", tab: "java", keywords: "env" },
  { id: "provider", title: "Default content provider", section: "Content", tab: "content", keywords: "modrinth curseforge" },
  { id: "recent", title: "Clear recent searches", section: "Content", tab: "content" },
  { id: "notifdl", title: "Download notifications", section: "Notifications", tab: "notifications" },
  { id: "notiffr", title: "Friend notifications", section: "Notifications", tab: "notifications" },
  { id: "about", title: "About and integrations", section: "About", tab: "about", keywords: "version azure backend api key config folder" },
];

const ITEMS: { key: "login" | "curseforge" | "backend" | "updates"; name: string; field: string; how: string }[] = [
  { key: "login", name: "Microsoft sign-in", field: "azure_client_id", how: "Azure app (public client) approved for Minecraft services. Add http://localhost as a Mobile and desktop redirect for browser sign-in." },
  { key: "curseforge", name: "CurseForge", field: "curseforge_api_key", how: "API key from console.curseforge.com" },
  { key: "backend", name: "Kite service", field: "backend_url", how: "Friends, chat, store, news and cosmetics for other players" },
  { key: "updates", name: "Launcher updates", field: "manifest_public_key", how: "Public key that signs update manifests (needs the Kite service too)" },
];

export function SettingsPage({ tab: initial }: { tab?: string }) {
  const app = useApp();
  const tab = (TABS.some((t) => t.id === initial) ? initial : "general") as Tab;
  const setTab = (t: Tab) => app.replace({ page: "settings", tab: t });

  return (
    <div className="page settings-page">
      <header className="page-head">
        <h1>Settings</h1>
      </header>
      <div className="settings-layout">
        <nav className="settings-nav" aria-label="Settings sections">
          {TABS.map((t) => (
            <button key={t.id} className={t.id === tab ? "on" : ""} onClick={() => setTab(t.id)}>
              {t.label}
            </button>
          ))}
        </nav>
        <div className="settings-body" key={tab}>
          {tab === "general" && <General />}
          {tab === "appearance" && <Appearance />}
          {tab === "accounts" && <Accounts />}
          {tab === "java" && (
            <section className="card">
              <h3>Java and game defaults</h3>
              <p className="muted">Every profile uses these unless it overrides a setting in its own settings.</p>
              <GameSettingsForm global={app.globalSettings} onGlobal={app.setGlobalSettings} />
            </section>
          )}
          {tab === "content" && <ContentPrefs />}
          {tab === "notifications" && <NotificationPrefs />}
          {tab === "about" && <About />}
        </div>
      </div>
    </div>
  );
}

function General() {
  const app = useApp();
  return (
    <section className="card">
      <h3>General</h3>
      <Field label="When the game starts" hint="What the launcher window does once Minecraft is running.">
        <Segmented
          value={app.prefs.onLaunch}
          onChange={(v) => app.setPrefs({ onLaunch: v })}
          options={[
            { id: "keep", label: "Keep open" },
            { id: "minimise", label: "Minimise" },
            { id: "close", label: "Hide to tray" },
          ]}
        />
      </Field>
      {app.prefs.onLaunch === "close" && <p className="hint">The launcher comes back when the game closes, or from the tray icon.</p>}
      <Field label="Show friends on Home" hint="The friends list beside the launch screen.">
        <Toggle on={app.prefs.showFriendsOnHome} onChange={(v) => app.setPrefs({ showFriendsOnHome: v })} label="Show friends on Home" />
      </Field>
    </section>
  );
}

function Appearance() {
  const app = useApp();
  return (
    <section className="card">
      <h3>Appearance</h3>
      <Field label="Collapse sidebar" hint="Show only icons in the left sidebar.">
        <Toggle on={app.prefs.sidebarCollapsed} onChange={(v) => app.setPrefs({ sidebarCollapsed: v })} label="Collapse sidebar" />
      </Field>
      <Field label="Reduce motion" hint="Turns off the animated home scene and page transitions.">
        <Toggle on={app.prefs.reduceMotion} onChange={(v) => app.setPrefs({ reduceMotion: v })} label="Reduce motion" />
      </Field>
      <Field label="Announcements" hint={`${app.prefs.dismissedBanners.length} dismissed`}>
        <button className="btn sm" disabled={!app.prefs.dismissedBanners.length} onClick={() => app.setPrefs({ dismissedBanners: [] })}>
          Show dismissed announcements again
        </button>
      </Field>
    </section>
  );
}

function Accounts() {
  const app = useApp();
  const [adding, setAdding] = useState<null | "microsoft" | "offline">(null);
  const list = app.accounts?.accounts ?? [];
  return (
    <section className="card">
      <div className="row between">
        <h3>Accounts</h3>
        <div className="row">
          <button className="btn sm" onClick={() => setAdding("offline")}>
            <IUser size={15} /> Add offline
          </button>
          <button className="btn sm primary" onClick={() => setAdding("microsoft")}>
            <IPlus size={15} /> Add Microsoft account
          </button>
        </div>
      </div>
      {list.length === 0 ? (
        <p className="muted">No accounts yet. Add the Microsoft account that owns Minecraft to play.</p>
      ) : (
        <ul className="acct-manage">
          {list.map((a) => {
            const active = a.uuid === app.accounts?.active;
            return (
              <li key={a.uuid} className={active ? "on" : ""}>
                <SkinHead name={a.username} uuid={a.kind === "microsoft" ? a.uuid : null} size={36} />
                <div>
                  <strong>{a.username || "Microsoft account"}</strong>
                  <span className="muted small">{a.kind === "microsoft" ? "Microsoft" : "Offline"}</span>
                </div>
                <span className="grow" />
                {active ? (
                  <span className="badge ok">
                    <ICheck size={13} /> Playing as
                  </span>
                ) : (
                  <button className="btn sm" onClick={() => app.switchAccount(a.uuid)}>
                    Use this account
                  </button>
                )}
                <button className="icon-btn" aria-label={`Remove ${a.username}`} title="Remove from this device" onClick={() => api.removeAccount(a.uuid).then(app.refreshAccounts).catch(app.error)}>
                  <ITrash size={16} />
                </button>
              </li>
            );
          })}
        </ul>
      )}
      <AddAccountModal key={adding ?? "none"} open={!!adding} mode={adding ?? "microsoft"} onClose={() => setAdding(null)} />
    </section>
  );
}

function ContentPrefs() {
  const app = useApp();
  return (
    <section className="card">
      <h3>Content</h3>
      <Field label="Default provider" hint="Where the Content page searches first.">
        <Segmented
          value={app.prefs.provider}
          onChange={(v) => app.setPrefs({ provider: v })}
          options={[
            { id: "modrinth", label: "Modrinth" },
            { id: "curseforge", label: "CurseForge" },
          ]}
        />
      </Field>
      <Field label="CurseForge" hint={app.cfg?.curseforge ? "API key configured" : "No API key: CurseForge search and installs are off. Add curseforge_api_key to kite.config.json."}>
        <span className={app.cfg?.curseforge ? "badge ok" : "badge"}>{app.cfg?.curseforge ? "Configured" : "Not set up"}</span>
      </Field>
      <Field label="Install target" hint="Shift-click Install and the install dialog use this profile.">
        <span>{app.profiles.find((p) => p.id === app.prefs.installTarget)?.name ?? "The active profile"}</span>
      </Field>
      <Field label="Recent searches">
        <button className="btn sm" onClick={() => (save("recentSearches", []), app.toast("Recent searches cleared"))}>
          Clear recent searches
        </button>
      </Field>
    </section>
  );
}

function NotificationPrefs() {
  const app = useApp();
  return (
    <section className="card">
      <h3>Notifications</h3>
      <Field label="Downloads and installs" hint="Progress and completion of mods, modpacks and packs.">
        <Toggle on={app.prefs.notifyDownloads} onChange={(v) => app.setPrefs({ notifyDownloads: v })} label="Download notifications" />
      </Field>
      <Field label="Friends" hint="Friend requests and messages from friends.">
        <Toggle on={app.prefs.notifyFriends} onChange={(v) => app.setPrefs({ notifyFriends: v })} label="Friend notifications" />
      </Field>
    </section>
  );
}

function About() {
  const app = useApp();
  const info = useAsync(api.appInfo, []);
  return (
    <>
      <section className="card about-card">
        <Logo size={48} />
        <div>
          <h3>Kite Client launcher</h3>
          <p className="muted">
            Version {info.data?.version ?? APP_VERSION}
            {info.data && ` on ${info.data.os}`}
          </p>
        </div>
        <span className="grow" />
        <div className="row">
          <button className="btn sm" onClick={() => api.openUrl(LINKS.support).catch(app.error)}>
            Get help <IExternal size={13} />
          </button>
          <button className="btn sm" onClick={() => api.openUrl(LINKS.source).catch(app.error)}>
            Source code <IExternal size={13} />
          </button>
        </div>
      </section>
      <section className="card">
        <h3>Where things are</h3>
        <Field label="App folder" hint="Profiles, Java, libraries, logs and settings.">
          <div className="input-with-btn">
            <input readOnly value={info.data?.data_dir ?? ""} className="mono" />
            <button className="btn" onClick={() => api.openPath("app").catch(app.error)}>
              <IFolder size={16} /> Open
            </button>
          </div>
        </Field>
        <Field label="Config file" hint="Keys for the integrations below. Restart the launcher after editing it.">
          <div className="input-with-btn">
            <input readOnly value={info.data?.config_file ?? ""} className="mono" />
            <button className="btn" onClick={() => api.openPath("config-file").catch(app.error)}>
              <IFile size={16} /> Show
            </button>
          </div>
        </Field>
      </section>
      <section className="card">
        <h3>Integrations</h3>
        <p className="muted small">
          Set these in <code>kite.config.json</code> (or as <code>KITE_*</code> environment variables), then restart the launcher.
        </p>
        <ul className="integrations">
          {ITEMS.map((i) => (
            <li key={i.key}>
              <div>
                <strong>{i.name}</strong>
                <div className="muted small">
                  <code>{i.field}</code> {i.how}
                </div>
              </div>
              <span className={app.cfg?.[i.key] ? "badge ok" : "badge"}>{app.cfg?.[i.key] ? "Configured" : "Not set up"}</span>
            </li>
          ))}
        </ul>
        {app.cfg?.backend && (
          <p className={app.online?.signed_in ? "hint ok-text" : "hint"}>
            {app.online?.signed_in ? (
              <>
                <ICheck size={13} /> Signed in to the Kite service as {app.online.name}.
              </>
            ) : (
              app.online?.error ?? "Not signed in to the Kite service yet."
            )}
          </p>
        )}
      </section>
    </>
  );
}
