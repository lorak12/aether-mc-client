import { ReactNode } from "react";
import type { Page } from "../api/types";
import { useApp } from "../app/store";
import { ICommunity, IContent, IExplore, IHome, INews, IPlus, IProfiles, ISettings, ISidebar, IStore } from "../components/Icons";

export const NAV: { page: Page; label: string; icon: ReactNode; also?: Page[] }[] = [
  { page: "home", label: "Home", icon: <IHome /> },
  { page: "profiles", label: "Profiles", icon: <IProfiles /> },
  { page: "content", label: "Content", icon: <IContent />, also: ["project"] },
  { page: "explore", label: "Explore", icon: <IExplore /> },
  { page: "community", label: "Community", icon: <ICommunity /> },
  { page: "news", label: "News", icon: <INews />, also: ["article"] },
  { page: "store", label: "Store", icon: <IStore />, also: ["coins"] },
];

export function Sidebar() {
  const app = useApp();
  const collapsed = app.prefs.sidebarCollapsed;
  const pending = app.notifications.some((n) => n.kind === "friend" && !n.read);
  return (
    <aside className={collapsed ? "sidebar collapsed" : "sidebar"} aria-label="Main navigation">
      <nav className="side-nav">
        {NAV.map((n) => {
          const on = app.route.page === n.page || !!n.also?.includes(app.route.page);
          return (
            <button key={n.page} className={on ? "side-item on" : "side-item"} aria-current={on ? "page" : undefined} onClick={() => app.navigate({ page: n.page } as never)} title={collapsed ? n.label : undefined}>
              {n.icon}
              <span className="side-label">{n.label}</span>
              {n.page === "community" && pending && <span className="side-dot" aria-label="New friend request" />}
            </button>
          );
        })}
      </nav>
      <div className="side-bottom">
        <button className="side-square accent" onClick={() => app.setNewProfileOpen(true)} aria-label="New profile" title="New profile">
          <IPlus size={20} />
        </button>
        <button
          className={app.route.page === "settings" ? "side-square on" : "side-square"}
          onClick={() => app.navigate({ page: "settings" })}
          aria-label="Settings"
          title="Settings"
        >
          <ISettings size={20} />
        </button>
        <button className="side-square ghost" onClick={() => app.setPrefs({ sidebarCollapsed: !collapsed })} aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"} title={collapsed ? "Expand sidebar" : "Collapse sidebar"}>
          <ISidebar size={18} />
        </button>
      </div>
    </aside>
  );
}
