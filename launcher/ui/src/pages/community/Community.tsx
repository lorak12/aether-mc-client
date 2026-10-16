import { useEffect, useMemo, useRef, useState } from "react";
import { api } from "../../api";
import type { ChatMessage, Friend } from "../../api/types";
import { useApp } from "../../app/store";
import { isOnline, markThreadRead, refreshSocial, socialMe, useSocial } from "../../app/social";
import { useAsync } from "../../components/hooks";
import { IBlock, ICheck, IClose, ICommunity, IMessage, IMore, IPlay, ISend, ISmile, ITrash, IUserPlus } from "../../components/Icons";
import { Dropdown } from "../../components/Menu";
import { Confirm } from "../../components/overlays";
import { EmptyState, ErrorCard, presenceLabel, SkeletonRows, SkinHead, Spinner, Tabs } from "../../components/ui";
import { ago } from "../../lib/format";
import { SocialUnavailable, useJoinFriend } from "../../shell/RightSidebar";

type Tab = "messages" | "requests" | "blocked";

export function CommunityPage({ tab: initialTab, friend }: { tab?: Tab; friend?: string }) {
  const app = useApp();
  const s = useSocial();
  const tab: Tab = initialTab ?? "messages";
  const setTab = (t: Tab) => app.replace({ page: "community", tab: t, friend });
  const incoming = s.requests.filter((r) => r.direction === "incoming").length;

  return (
    <div className="page community-page">
      <header className="page-head">
        <h1>Community</h1>
      </header>
      {!s.enabled ? (
        <SocialUnavailable />
      ) : (
        <>
          <Tabs<Tab>
            value={tab}
            onChange={setTab}
            tabs={[
              { id: "messages", label: "Messages", count: Object.values(s.unread).reduce((a, b) => a + b, 0) },
              { id: "requests", label: "Friend requests", count: incoming },
              { id: "blocked", label: "Blocked" },
            ]}
          />
          {tab === "messages" && <Messages friendId={friend} />}
          {tab === "requests" && <Requests />}
          {tab === "blocked" && <Blocked />}
        </>
      )}
    </div>
  );
}

function Messages({ friendId }: { friendId?: string }) {
  const app = useApp();
  const s = useSocial();
  const threads = useAsync(() => api.threads(socialMe), []);
  const open = s.friends.find((f) => f.id === friendId) ?? null;

  // New messages arrive through the app's poll; refresh the list order when they do.
  useEffect(() => {
    const h = () => threads.reload();
    window.addEventListener("kite:messages", h);
    return () => window.removeEventListener("kite:messages", h);
  }, [threads.reload]);

  const list = useMemo(() => {
    const last = new Map((threads.data ?? []).map((t) => [t.with, t.last]));
    return s.friends
      .map((f) => ({ f, last: last.get(f.uuid) }))
      .filter((x) => x.last || x.f.id === friendId)
      .sort((a, b) => (b.last?.at ?? Date.now()) - (a.last?.at ?? Date.now()));
  }, [s.friends, threads.data, friendId]);

  if (!s.loaded) return <SkeletonRows n={4} h={60} />;
  if (s.error) return <ErrorCard message={s.error} retry={refreshSocial} />;
  if (!s.friends.length)
    return (
      <EmptyState
        icon={<ICommunity size={24} />}
        title="Add friends to start chatting"
        text="Send a friend request with their Minecraft name. They need to have used Kite Client once."
        action={
          <button className="btn primary" onClick={() => app.replace({ page: "community", tab: "requests" })}>
            <IUserPlus size={16} /> Add a friend
          </button>
        }
      />
    );

  return (
    <div className="chat-layout">
      <div className="thread-list">
        {list.length === 0 ? (
          <p className="rb-note">No conversations yet. Pick a friend on the right to message them.</p>
        ) : (
          list.map(({ f, last }) => {
            const unread = s.unread[f.uuid] ?? 0;
            return (
              <button key={f.id} className={f.id === friendId ? "thread on" : unread ? "thread unread" : "thread"} onClick={() => app.replace({ page: "community", tab: "messages", friend: f.id })}>
                <SkinHead name={f.username} uuid={f.uuid} size={34} presence={f.presence} />
                <span className="thread-text">
                  <span className="row between">
                    <strong>{f.username}</strong>
                    {last && <span className="muted small">{ago(last.at)}</span>}
                  </span>
                  <span className="row between">
                    <span className="muted small ellipsis">{last ? `${last.from === "me" ? "You: " : ""}${last.text}` : "New conversation"}</span>
                    {unread > 0 && <span className="dot-count inline">{unread}</span>}
                  </span>
                </span>
              </button>
            );
          })
        )}
      </div>
      {open ? (
        <Conversation key={open.id} friend={open} onSent={threads.reload} />
      ) : (
        <div className="conversation empty-conv">
          <EmptyState icon={<IMessage size={22} />} title="Pick a conversation" text="Or choose a friend from the list on the right." />
        </div>
      )}
    </div>
  );
}

const EMOJI = ["😀", "😂", "😎", "👍", "🔥", "❤️", "🎉", "😮", "😢", "⚔️", "🏹", "💎"];

function Conversation({ friend: f, onSent }: { friend: Friend; onSent: () => void }) {
  const app = useApp();
  const join = useJoinFriend();
  const msgs = useAsync(() => api.thread(f.uuid, socialMe), [f.uuid]);
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const [confirm, setConfirm] = useState<null | "remove" | "block">(null);
  const [emoji, setEmoji] = useState(false);
  const [older, setOlder] = useState<"idle" | "loading" | "done">("idle");
  const box = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const stick = useRef(true);

  useEffect(() => markThreadRead(f.uuid), [f.uuid, msgs.data?.length]);
  // Live: append messages from this friend as the app's poll sees them.
  useEffect(() => {
    const h = (e: Event) => {
      const got = (e as CustomEvent<{ id: number; from: string; text: string; at: number }[]>).detail.filter((m) => m.from === f.uuid);
      if (!got.length) return;
      msgs.setData((d) => {
        const have = new Set((d ?? []).map((m) => m.id));
        return [...(d ?? []), ...got.filter((m) => !have.has(m.id)).map((m): ChatMessage => ({ id: m.id, from: m.from, text: m.text, at: m.at, read: true }))];
      });
      markThreadRead(f.uuid);
    };
    window.addEventListener("kite:messages", h);
    return () => window.removeEventListener("kite:messages", h);
  }, [f.uuid, msgs.setData]);

  useEffect(() => {
    if (box.current && stick.current) box.current.scrollTop = box.current.scrollHeight;
  }, [msgs.data]);

  const loadOlder = async () => {
    const first = msgs.data?.[0];
    if (!first || older !== "idle") return;
    setOlder("loading");
    try {
      const prev = await api.thread(f.uuid, socialMe, first.id);
      stick.current = false;
      const h = box.current?.scrollHeight ?? 0;
      msgs.setData((d) => [...prev, ...(d ?? [])]);
      requestAnimationFrame(() => {
        if (box.current) box.current.scrollTop = box.current.scrollHeight - h;
        stick.current = true;
      });
      setOlder(prev.length < 60 ? "done" : "idle");
    } catch (e) {
      app.error(e);
      setOlder("idle");
    }
  };

  const send = async () => {
    const t = text.trim();
    if (!t) return;
    setSending(true);
    try {
      const m = await api.sendMessage(f.uuid, t, socialMe);
      msgs.setData((d) => [...(d ?? []), m]);
      setText("");
      onSent();
    } catch (e) {
      app.error(e);
    } finally {
      setSending(false);
      input.current?.focus();
    }
  };

  const online = isOnline(f);
  return (
    <div className="conversation">
      <header className="conv-head">
        <SkinHead name={f.username} uuid={f.uuid} size={38} presence={f.presence} />
        <div>
          <strong>{f.username}</strong>
          <span className="muted small">{online ? f.activity ?? presenceLabel(f.presence) : f.lastSeen ? `Last seen ${ago(f.lastSeen)}` : "Offline"}</span>
        </div>
        <span className="grow" />
        {online && f.server && (
          <button className="btn sm" disabled={!app.account} onClick={() => join(f)} title={`Launch and join ${f.server}`}>
            <IPlay size={13} /> Join
          </button>
        )}
        <Dropdown
          className="icon-btn"
          label="More"
          align="end"
          items={[
            { label: "Remove friend", icon: <ITrash size={16} />, onSelect: () => setConfirm("remove") },
            { label: "Block", icon: <IBlock size={16} />, danger: true, onSelect: () => setConfirm("block") },
          ]}
        >
          <IMore size={18} />
        </Dropdown>
      </header>
      <div className="conv-body" ref={box} onScroll={(e) => e.currentTarget.scrollTop < 40 && loadOlder()}>
        {msgs.error ? (
          <ErrorCard message={msgs.error} retry={msgs.reload} />
        ) : !msgs.data ? (
          <div className="center">
            <Spinner />
          </div>
        ) : msgs.data.length === 0 ? (
          <EmptyState title={`Say hi to ${f.username}`} text="Messages are only between the two of you." />
        ) : (
          <>
            {older === "loading" && (
              <div className="center">
                <Spinner size={14} />
              </div>
            )}
            {msgs.data.map((m, i) => {
              const prev = msgs.data![i - 1];
              const grouped = prev && prev.from === m.from && m.at - prev.at < 5 * 60_000;
              const gap = !prev || m.at - prev.at > 60 * 60_000;
              return (
                <div key={m.id}>
                  {gap && <div className="conv-time">{ago(m.at)}</div>}
                  <div className={`bubble-row ${m.from === "me" ? "me" : "them"}${grouped ? " grouped" : ""}`}>
                    {m.from !== "me" && !grouped && <SkinHead name={f.username} uuid={f.uuid} size={26} />}
                    <div className="bubble" title={new Date(m.at).toLocaleString("en-US")}>
                      {m.text}
                    </div>
                  </div>
                </div>
              );
            })}
          </>
        )}
      </div>
      <div className="composer">
        <button className={emoji ? "icon-btn on" : "icon-btn"} aria-label="Emoji" aria-expanded={emoji} onClick={() => setEmoji((x) => !x)}>
          <ISmile size={19} />
        </button>
        <textarea
          ref={input}
          rows={1}
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={`Message ${f.username}`}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              send();
            }
          }}
          maxLength={2000}
        />
        <button className="btn primary icon-only" aria-label="Send" disabled={!text.trim() || sending} onClick={send}>
          <ISend size={17} />
        </button>
      </div>
      {emoji && <EmojiBar onPick={(e) => (setText((t) => t + e), input.current?.focus())} />}
      <Confirm
        open={confirm === "remove"}
        title={`Remove ${f.username}?`}
        text="You won't see each other's status. You can add them again later."
        confirm="Remove friend"
        danger
        onClose={() => setConfirm(null)}
        onConfirm={() => {
          setConfirm(null);
          api.removeFriend(f.uuid).then(refreshSocial).then(() => app.replace({ page: "community", tab: "messages" })).catch(app.error);
        }}
      />
      <Confirm
        open={confirm === "block"}
        title={`Block ${f.username}?`}
        text="They're removed from your friends and can't message you or send requests."
        confirm="Block"
        danger
        onClose={() => setConfirm(null)}
        onConfirm={() => {
          setConfirm(null);
          api.block(f.uuid).then(refreshSocial).then(() => app.replace({ page: "community", tab: "messages" })).catch(app.error);
        }}
      />
    </div>
  );
}

function EmojiBar({ onPick }: { onPick: (e: string) => void }) {
  return (
    <div className="emoji-bar" aria-label="Quick emoji">
      {EMOJI.map((e) => (
        <button key={e} onClick={() => onPick(e)} aria-label={`Insert ${e}`}>
          {e}
        </button>
      ))}
    </div>
  );
}

function Requests() {
  const app = useApp();
  const s = useSocial();
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const incoming = s.requests.filter((r) => r.direction === "incoming");
  const outgoing = s.requests.filter((r) => r.direction === "outgoing");
  const valid = /^[A-Za-z0-9_]{1,16}$/.test(name.trim());

  const send = async () => {
    setBusy(true);
    try {
      const n = name.trim();
      const accepted = await api.sendFriendRequest(n);
      app.toast(accepted ? `You and ${n} are now friends` : `Friend request sent to ${n}`, { tone: "success" });
      setName("");
      await refreshSocial();
    } catch (e) {
      app.error(e);
    } finally {
      setBusy(false);
    }
  };
  const answer = (id: number, accept: boolean, who: string) =>
    api
      .answerRequest(id, accept)
      .then(refreshSocial)
      .then(() => app.toast(accept ? `You and ${who} are now friends` : `Declined ${who}`))
      .catch(app.error);

  return (
    <div className="stack">
      <section className="card add-friend">
        <h3>Add a friend</h3>
        <p className="muted small">Enter their Minecraft name. They need to have signed in to Kite Client at least once.</p>
        <div className="row">
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Minecraft name" maxLength={16} onKeyDown={(e) => e.key === "Enter" && valid && send()} aria-invalid={!!name && !valid} />
          <button className="btn primary" disabled={!valid || busy} onClick={send}>
            {busy ? <Spinner /> : <IUserPlus size={16} />} Send request
          </button>
        </div>
        {name && !valid && <p className="form-error">Names are 1–16 letters, numbers or underscores.</p>}
      </section>
      {!s.loaded ? (
        <SkeletonRows n={3} h={56} />
      ) : (
        <>
          <section className="stack">
            <h2 className="section-title">Received {incoming.length > 0 && <span className="muted">{incoming.length}</span>}</h2>
            {incoming.length === 0 ? (
              <p className="muted">No requests waiting.</p>
            ) : (
              <ul className="world-list">
                {incoming.map((r) => (
                  <li key={r.id}>
                    <SkinHead name={r.username} uuid={r.uuid} size={34} />
                    <div className="inst-text">
                      <strong>{r.username}</strong>
                      <span className="muted small">Sent {ago(r.at)}</span>
                    </div>
                    <button className="btn sm primary" onClick={() => answer(r.id, true, r.username)}>
                      <ICheck size={14} /> Accept
                    </button>
                    <button className="btn sm" onClick={() => answer(r.id, false, r.username)}>
                      Decline
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
          <section className="stack">
            <h2 className="section-title">Sent</h2>
            {outgoing.length === 0 ? (
              <p className="muted">You haven't sent any requests.</p>
            ) : (
              <ul className="world-list">
                {outgoing.map((r) => (
                  <li key={r.id}>
                    <SkinHead name={r.username} uuid={r.uuid} size={34} />
                    <div className="inst-text">
                      <strong>{r.username}</strong>
                      <span className="muted small">Pending, sent {ago(r.at)}</span>
                    </div>
                    <button className="btn sm" onClick={() => api.answerRequest(r.id, false).then(refreshSocial).catch(app.error)}>
                      <IClose size={14} /> Cancel
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}
    </div>
  );
}

function Blocked() {
  const app = useApp();
  const blocked = useAsync(api.blocked, []);
  if (blocked.error) return <ErrorCard message={blocked.error} retry={blocked.reload} />;
  if (!blocked.data) return <SkeletonRows n={2} h={56} />;
  if (!blocked.data.length) return <EmptyState icon={<IBlock size={22} />} title="Nobody is blocked" text="Blocked players can't message you or send friend requests." />;
  return (
    <ul className="world-list">
      {blocked.data.map((b) => (
        <li key={b.uuid}>
          <SkinHead name={b.username} uuid={b.uuid} size={34} />
          <div className="inst-text">
            <strong>{b.username}</strong>
          </div>
          <button className="btn sm" onClick={() => api.unblock(b.uuid).then(blocked.reload).then(() => app.toast(`Unblocked ${b.username}`)).catch(app.error)}>
            Unblock
          </button>
        </li>
      ))}
    </ul>
  );
}
