import { RefObject } from "react";
import type { NotificationKind } from "../api/types";
import { useApp } from "../app/store";
import { IBell, ICoin, IDownload, IPlay, IUserPlus, IWarning, IInfo } from "../components/Icons";
import { Popout } from "../components/Menu";
import { EmptyState } from "../components/ui";
import { ago } from "../lib/format";

const ICON: Record<NotificationKind, JSX.Element> = {
  download: <IDownload size={17} />,
  launch: <IPlay size={15} />,
  crash: <IWarning size={17} />,
  purchase: <ICoin size={17} />,
  friend: <IUserPlus size={17} />,
  info: <IInfo size={17} />,
};

export function NotificationCenter({ open, onClose, anchor }: { open: boolean; onClose: () => void; anchor: RefObject<HTMLElement> }) {
  const app = useApp();
  const list = app.notifications;
  return (
    <Popout open={open} onClose={onClose} anchor={anchor} align="end" className="notif-pop" width={380}>
      <header className="notif-head">
        <h3>Notifications</h3>
        <div className="row">
          <button className="link sm" disabled={!list.some((n) => !n.read)} onClick={app.markAllRead}>
            Mark all read
          </button>
          <button className="link sm" disabled={!list.length} onClick={app.clearNotifications}>
            Clear
          </button>
        </div>
      </header>
      <div className="notif-list">
        {list.length === 0 ? (
          <EmptyState icon={<IBell size={22} />} title="You're all caught up" text="Downloads, launches, purchases and friend requests show up here." />
        ) : (
          list.map((n) => (
            <div key={n.id} className={`notif ${n.kind}${n.read ? "" : " unread"}`} onMouseEnter={() => !n.read && n.progress === undefined && app.updateNotification(n.id, { read: true })}>
              <span className="notif-icon">{ICON[n.kind]}</span>
              <div className="notif-text">
                <strong>{n.title}</strong>
                <span>{n.detail}</span>
                {n.progress !== undefined && (
                  <div className="progress thin">
                    <div style={{ width: `${Math.max(4, n.progress * 100)}%` }} />
                  </div>
                )}
                <span className="notif-meta">
                  {ago(n.at)}
                  {n.action && (
                    <button
                      className="link sm"
                      onClick={() => {
                        onClose();
                        app.runAction(n.action!);
                      }}
                    >
                      {n.action.label}
                    </button>
                  )}
                </span>
              </div>
            </div>
          ))
        )}
      </div>
    </Popout>
  );
}
