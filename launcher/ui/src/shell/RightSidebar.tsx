import { useState } from "react";
import type { Friend } from "../api/types";
import { useApp } from "../app/store";
import { byPresence, isOnline, refreshSocial, useSocial } from "../app/social";
import { IChevronDown, ICommunity, IMessage, IPlay, IUserPlus } from "../components/Icons";
import { EmptyState, ErrorCard, SkeletonRows, SkinHead } from "../components/ui";
import { ago } from "../lib/format";

/** Launch the selected (or a Java-edition-compatible) profile straight onto a friend's server. */
export function useJoinFriend() {
  const app = useApp();
  return (f: Friend) => {
    if (!f.server) return;
    const id = app.activeId ?? app.profiles[0]?.id;
    if (!id) return app.setNewProfileOpen(true);
    if (app.launch.phase !== "idle" && app.launch.phase !== "crashed") return app.toast("Minecraft is already running. Join from the game's multiplayer menu.", { tone: "error" });
    app.navigate({ page: "home" });
    app.play(id, { server: f.server });
  };
}

export function FriendRow({ f, selected, onOpen }: { f: Friend; selected?: boolean; onOpen?: () => void }) {
  const app = useApp();
  const s = useSocial();
  const join = useJoinFriend();
  const online = isOnline(f);
  const unread = s.unread[f.uuid] ?? 0;
  return (
    <div className={selected ? "friend on" : "friend"}>
      <button className="friend-main" onClick={onOpen ?? (() => app.navigate({ page: "community", tab: "messages", friend: f.id }))}>
        <SkinHead name={f.username} uuid={f.uuid} size={32} presence={f.presence} />
        <span className="friend-text">
          <strong>{f.username}</strong>
          <span className={online ? "friend-activity" : "friend-activity muted"}>{online ? f.activity ?? "Online" : f.lastSeen ? `Last seen ${ago(f.lastSeen)}` : "Offline"}</span>
        </span>
        {unread > 0 && <span className="dot-count inline">{unread > 9 ? "9+" : unread}</span>}
      </button>
      <span className="friend-actions">
        <button className="icon-btn sm" aria-label={`Message ${f.username}`} title="Message" onClick={() => app.navigate({ page: "community", tab: "messages", friend: f.id })}>
          <IMessage size={15} />
        </button>
        {online && f.server && (
          <button className="icon-btn sm" aria-label={`Join ${f.username} on ${f.server}`} title={`Join on ${f.server}`} disabled={!app.account} onClick={() => join(f)}>
            <IPlay size={13} />
          </button>
        )}
      </span>
    </div>
  );
}

/** Shown instead of social features when they can't work yet. */
export function SocialUnavailable({ compact = false }: { compact?: boolean }) {
  const app = useApp();
  if (!app.cfg?.backend)
    return (
      <EmptyState
        icon={<ICommunity size={22} />}
        title="Friends aren't set up"
        text={compact ? "Connect the launcher to the Aether service to add friends." : "Friends, chat and presence need the Aether service. Set backend_url in aether.config.json (Settings > About shows where it is)."}
        action={
          !compact && (
            <button className="btn sm" onClick={() => app.navigate({ page: "settings", tab: "about" })}>
              Open Settings
            </button>
          )
        }
      />
    );
  if (!app.account)
    return (
      <EmptyState
        icon={<ICommunity size={22} />}
        title="Sign in to see friends"
        text="Add your Minecraft account to chat and see who's playing."
        action={
          <button className="btn sm" onClick={() => app.setOverlay("account")}>
            Add an account
          </button>
        }
      />
    );
  return (
    <ErrorCard message={app.online?.error ?? "Couldn't sign in to the Aether service."} retry={app.refreshOnline} />
  );
}

export function FriendsPanel({ selected, onOpen }: { selected?: string; onOpen?: (f: Friend) => void }) {
  const [drawer, setDrawer] = useState(false);
  const app = useApp();
  const s = useSocial();
  const [offlineOpen, setOfflineOpen] = useState(false);
  const sorted = [...s.friends].sort(byPresence);
  const online = sorted.filter(isOnline);
  const offline = sorted.filter((f) => !isOnline(f));
  const incoming = s.requests.filter((r) => r.direction === "incoming").length;

  return (
    <>
      {/* Below 1280px the bar becomes a drawer opened from this tab. */}
      <button className="rb-tab" onClick={() => setDrawer((d) => !d)} aria-expanded={drawer} aria-label={drawer ? "Hide friends" : "Show friends"}>
        <ICommunity size={18} />
        {online.length > 0 && <span className="rb-tab-count">{online.length}</span>}
      </button>
      <aside className={drawer ? "right-bar drawer-open" : "right-bar"} aria-label="Friends">
        <header className="rb-head">
          <div>
            <h3>Friends</h3>
            <span className="muted small">{s.enabled && s.loaded && !s.error ? `${online.length} online` : " "}</span>
          </div>
          <button className="icon-btn" aria-label="Add friend" title="Add friend" disabled={!s.enabled} onClick={() => app.navigate({ page: "community", tab: "requests" })}>
            <IUserPlus size={18} />
            {incoming > 0 && <span className="dot-count">{incoming}</span>}
          </button>
        </header>
        <div className="rb-list">
          {!s.enabled ? (
            <SocialUnavailable compact />
          ) : !s.loaded ? (
            <SkeletonRows n={4} h={44} />
          ) : s.error ? (
            <ErrorCard message={s.error} retry={refreshSocial} />
          ) : s.friends.length === 0 ? (
            <EmptyState
              icon={<ICommunity size={22} />}
              title="No friends yet"
              text="Add someone by their Minecraft name to see when they're playing."
              action={
                <button className="btn sm" onClick={() => app.navigate({ page: "community", tab: "requests" })}>
                  Add a friend
                </button>
              }
            />
          ) : (
            <>
              {online.length === 0 ? (
                <p className="rb-note">Nobody's online right now.</p>
              ) : (
                online.map((f) => <FriendRow key={f.id} f={f} selected={selected === f.id} onOpen={onOpen && (() => onOpen(f))} />)
              )}
              {offline.length > 0 && (
                <>
                  <button className="rb-group" onClick={() => setOfflineOpen((o) => !o)} aria-expanded={offlineOpen}>
                    <IChevronDown size={14} className={offlineOpen ? "" : "rot-90"} /> Offline <span className="muted">{offline.length}</span>
                  </button>
                  {offlineOpen && offline.map((f) => <FriendRow key={f.id} f={f} selected={selected === f.id} onOpen={onOpen && (() => onOpen(f))} />)}
                </>
              )}
            </>
          )}
        </div>
      </aside>
    </>
  );
}
