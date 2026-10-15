import { useEffect, useSyncExternalStore } from "react";
import { api, errText } from "../api";
import type { Friend, FriendRequest } from "../api/types";

/** Friends, requests and unread messages, shared by the right sidebar, the Community page and the top bar. */
interface SocialState {
  friends: Friend[];
  requests: FriendRequest[];
  /** Unread messages per friend uuid. */
  unread: Record<string, number>;
  loaded: boolean;
  error: string | null;
  /** False when the launcher has no backend or isn't signed in to it. */
  enabled: boolean;
}

let state: SocialState = { friends: [], requests: [], unread: {}, loaded: false, error: null, enabled: false };
const subs = new Set<() => void>();
const emit = () => subs.forEach((f) => f());
const set = (patch: Partial<SocialState>) => {
  state = { ...state, ...patch };
  emit();
};
let inflight: Promise<void> | null = null;

/** Whose messages are "me". Set by the app when the backend sign-in changes. */
export let socialMe: string | null = null;

export function setSocialEnabled(enabled: boolean, me: string | null) {
  socialMe = me;
  if (!enabled) set({ enabled: false, friends: [], requests: [], unread: {}, loaded: true, error: null });
  else if (!state.enabled) {
    set({ enabled: true, loaded: false });
    refreshSocial();
  }
}

export function refreshSocial() {
  if (!state.enabled) return Promise.resolve();
  inflight ??= Promise.all([api.friends(), api.friendRequests(), api.threads(socialMe)])
    .then(([friends, requests, threads]) => {
      set({ friends, requests, unread: Object.fromEntries(threads.map((t) => [t.with, t.unread])), loaded: true, error: null });
    })
    .catch((e) => set({ loaded: true, error: errText(e) }))
    .finally(() => {
      inflight = null;
    });
  return inflight;
}

export function markThreadRead(uuid: string) {
  if (!state.unread[uuid]) return;
  set({ unread: { ...state.unread, [uuid]: 0 } });
  api.markRead(uuid).catch(() => {});
}

export function bumpUnread(uuid: string, n = 1) {
  set({ unread: { ...state.unread, [uuid]: (state.unread[uuid] ?? 0) + n } });
}

export function useSocial() {
  const s = useSyncExternalStore(
    (f) => {
      subs.add(f);
      return () => subs.delete(f);
    },
    () => state,
  );
  useEffect(() => {
    if (s.enabled && !s.loaded) refreshSocial();
  }, [s.enabled, s.loaded]);
  return s;
}

const ORDER = { online: 0, busy: 1, away: 2, invisible: 3, offline: 4 } as const;
export const byPresence = (a: Friend, b: Friend) => ORDER[a.presence] - ORDER[b.presence] || a.username.localeCompare(b.username);
export const isOnline = (f: Friend) => f.presence !== "offline" && f.presence !== "invisible";
export const totalUnread = (s: SocialState) => Object.values(s.unread).reduce((a, b) => a + b, 0);
