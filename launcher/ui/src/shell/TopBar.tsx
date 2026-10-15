import { useRef } from "react";
import { api } from "../api";
import { useApp } from "../app/store";
import { IArrowLeft, IArrowRight, IBell, IChevronDown, ICoin, ILogs, ISearch } from "../components/Icons";
import { SkinHead } from "../components/ui";
import { useHotkey } from "../components/hooks";
import { AccountMenu } from "./Accounts";
import { AppMenu } from "./AppMenu";
import { NotificationCenter } from "./Notifications";
import { WindowControls } from "./WindowControls";

export function TopBar() {
  const app = useApp();
  const bell = useRef<HTMLButtonElement>(null);
  const acct = useRef<HTMLButtonElement>(null);
  const unread = app.notifications.filter((n) => !n.read).length;
  const running = app.launch.phase === "running" || app.launch.phase === "preparing";

  useHotkey("ctrl+k", () => app.setOverlay(app.overlay === "search" ? null : "search"), true);
  useHotkey("alt+arrowleft", app.back);
  useHotkey("alt+arrowright", app.forward);
  useHotkey("ctrl+,", () => app.navigate({ page: "settings" }));

  const acc = app.account;
  return (
    <header className="topbar" data-tauri-drag-region>
      <div className="tb-left">
        <AppMenu />
        <div className="tb-nav">
          <button className="icon-btn" onClick={app.back} disabled={!app.canBack} aria-label="Back" title="Back (Alt+←)">
            <IArrowLeft size={18} />
          </button>
          <button className="icon-btn" onClick={app.forward} disabled={!app.canForward} aria-label="Forward" title="Forward (Alt+→)">
            <IArrowRight size={18} />
          </button>
        </div>
      </div>

      <div className="tb-drag" data-tauri-drag-region />

      <div className="tb-right">
        {app.coins !== null && (
          <button className="coins-chip" onClick={() => app.navigate({ page: "coins" })} title="Kite coins">
            <ICoin size={17} />
            <span>{app.coins.toLocaleString("en-US")}</span>
          </button>
        )}
        <button className="tb-search" onClick={() => app.setOverlay("search")} aria-label="Search">
          <ISearch size={16} />
          <span>Search</span>
          <kbd>Ctrl K</kbd>
        </button>
        <button
          ref={bell}
          className={app.overlay === "notifications" ? "icon-btn tb-bell open" : "icon-btn tb-bell"}
          onClick={() => app.setOverlay(app.overlay === "notifications" ? null : "notifications")}
          aria-label={`Notifications${unread ? `, ${unread} unread` : ""}`}
        >
          <IBell size={19} />
          {unread > 0 && <span className="dot-count">{unread > 9 ? "9+" : unread}</span>}
        </button>
        <button
          ref={acct}
          className={app.overlay === "account" ? "account-chip open" : "account-chip"}
          onClick={() => app.setOverlay(app.overlay === "account" ? null : "account")}
          aria-label="Account"
        >
          <SkinHead name={acc?.username ?? "?"} uuid={acc?.kind === "microsoft" ? acc.uuid : null} size={28} presence={acc ? app.presence : undefined} badge={!!acc} />
          <span className="acct-name">{acc ? acc.username || "Signed in" : app.accountsLoaded ? "Sign in" : "…"}</span>
          <IChevronDown size={15} />
        </button>
        <button
          className={running ? "icon-btn tb-logs live" : "icon-btn tb-logs"}
          disabled={!running}
          onClick={() => api.openLogsWindow().catch(app.error)}
          aria-label="Live game logs"
          title={running ? "Live game logs" : "Live logs are available while the game runs"}
        >
          <ILogs size={19} />
        </button>
        <span className="tb-divider" />
        <WindowControls />
      </div>

      <NotificationCenter open={app.overlay === "notifications"} onClose={() => app.setOverlay(null)} anchor={bell} />
      <AccountMenu open={app.overlay === "account"} onClose={() => app.setOverlay(null)} anchor={acct} />
    </header>
  );
}
