import { createContext, ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import { api, balanceOf, errText } from "../api";
import type {
  AccountList,
  AppNotification,
  BackendStatus,
  ConfigStatus,
  GameExit,
  GameSettings,
  LaunchProgress,
  Presence,
  Profile,
  ProfileMeta,
  QuickPlay,
  Route,
  SavedAccount,
} from "../api/types";
import * as H from "../lib/history";
import { stageText } from "../lib/launch";
import { DEFAULT_SETTINGS } from "../lib/overrides";
import { load, save } from "../lib/prefs";
import { migrateLocalData } from "./migrate";
import { bumpUnread, refreshSocial, setSocialEnabled, socialMe } from "./social";

export type LaunchState =
  | { phase: "idle" }
  | { phase: "preparing"; profileId: string; progress: LaunchProgress | null }
  | { phase: "running"; profileId: string }
  | { phase: "crashed"; profileId: string | null; code: number | null; log: string; logPath: string | null; crashReport: string | null };

export interface Prefs {
  installTarget: string | null;
  provider: "modrinth" | "curseforge";
  dismissedBanners: string[];
  sidebarCollapsed: boolean;
  reduceMotion: boolean;
  /** What the launcher window does when the game starts. "close" hides it to the tray. */
  onLaunch: "keep" | "minimise" | "close";
  showFriendsOnHome: boolean;
  notifyDownloads: boolean;
  notifyFriends: boolean;
  /** The status friends see. */
  presence: Presence;
}
const DEFAULT_PREFS: Prefs = {
  installTarget: null,
  provider: "modrinth",
  dismissedBanners: [],
  sidebarCollapsed: false,
  reduceMotion: false,
  onLaunch: "keep",
  showFriendsOnHome: true,
  notifyDownloads: true,
  notifyFriends: true,
  presence: "online",
};

export interface Toast {
  id: string;
  text: string;
  tone?: "error" | "success";
  action?: { label: string; run: () => void };
}

type Overlay = null | "search" | "notifications" | "account" | "appmenu";
export type Cfg = ConfigStatus & { backend_url: string | null; updates: boolean };

interface Store {
  route: Route;
  canBack: boolean;
  canForward: boolean;
  navigate: (r: Route) => void;
  replace: (r: Route) => void;
  back: () => void;
  forward: () => void;

  overlay: Overlay;
  setOverlay: (o: Overlay) => void;
  newProfileOpen: boolean;
  setNewProfileOpen: (v: boolean) => void;

  cfg: Cfg | null;
  profiles: Profile[];
  profilesLoaded: boolean;
  refreshProfiles: () => Promise<Profile[]>;
  activeId: string | null;
  setActive: (id: string) => void;
  active: Profile | null;
  /** Save edits to a profile (name, version, loader, overrides, meta...). */
  saveProfile: (p: Profile) => Promise<Profile | null>;
  setMeta: (id: string, patch: Partial<ProfileMeta>) => Promise<void>;
  globalSettings: GameSettings;
  setGlobalSettings: (s: GameSettings) => void;
  /** Game versions the Kite client runs on. */
  aetherVersions: string[];

  accounts: AccountList | null;
  account: SavedAccount | null;
  accountsLoaded: boolean;
  refreshAccounts: () => Promise<void>;
  switchAccount: (uuid: string) => Promise<void>;
  presence: Presence;
  setPresence: (p: Presence) => void;
  /** Aether backend sign-in for the active account. */
  online: BackendStatus | null;
  refreshOnline: () => Promise<void>;

  launch: LaunchState;
  play: (profileId?: string, quickPlay?: QuickPlay) => Promise<void>;
  stop: () => Promise<void>;
  dismissCrash: () => void;

  notifications: AppNotification[];
  pushNotification: (n: Omit<AppNotification, "id" | "at" | "read">) => string;
  updateNotification: (id: string, patch: Partial<AppNotification>) => void;
  markAllRead: () => void;
  clearNotifications: () => void;
  runAction: (a: NonNullable<AppNotification["action"]>) => void;

  /** Coin balance; null when not signed in to the backend. */
  coins: number | null;
  setCoins: (n: number) => void;
  refreshCoins: () => void;

  toasts: Toast[];
  toast: (text: string, opts?: Omit<Toast, "id" | "text">) => void;
  error: (e: unknown) => void;
  dismissToast: (id: string) => void;

  prefs: Prefs;
  setPrefs: (patch: Partial<Prefs>) => void;
}

const Ctx = createContext<Store | null>(null);
export const useApp = () => {
  const s = useContext(Ctx);
  if (!s) throw new Error("useApp outside AppProvider");
  return s;
};

const uid = () => Math.random().toString(36).slice(2, 10);

function windowAction(kind: "minimise" | "close") {
  import("@tauri-apps/api/window")
    .then(({ getCurrentWindow }) => (kind === "minimise" ? getCurrentWindow().minimize() : getCurrentWindow().hide()))
    .catch(() => {});
}

export function AppProvider({ children }: { children: ReactNode }) {
  // ---------- navigation ----------
  const [hist, setHist] = useState(() => H.start<Route>({ page: "home" }));
  const navigate = useCallback((r: Route) => setHist((h) => H.push(h, r)), []);
  const replace = useCallback((r: Route) => setHist((h) => H.replace(h, r)), []);
  const back = useCallback(() => setHist((h) => H.back(h)), []);
  const forward = useCallback(() => setHist((h) => H.forward(h)), []);
  const [overlay, setOverlay] = useState<Overlay>(null);
  const [newProfileOpen, setNewProfileOpen] = useState(false);
  const routeRef = useRef<Route>(H.current(hist));
  routeRef.current = H.current(hist);

  // ---------- prefs ----------
  const [prefs, setPrefsState] = useState<Prefs>(() => ({ ...DEFAULT_PREFS, ...load<Partial<Prefs>>("prefs", {}) }));
  const setPrefs = useCallback((patch: Partial<Prefs>) => {
    setPrefsState((p) => {
      const next = { ...p, ...patch };
      save("prefs", next);
      return next;
    });
  }, []);
  const prefsRef = useRef(prefs);
  prefsRef.current = prefs;

  // ---------- toasts ----------
  const [toasts, setToasts] = useState<Toast[]>([]);
  const dismissToast = useCallback((id: string) => setToasts((t) => t.filter((x) => x.id !== id)), []);
  const toast = useCallback(
    (text: string, opts: Omit<Toast, "id" | "text"> = {}) => {
      const id = uid();
      setToasts((t) => [...t.slice(-3), { id, text, ...opts }]);
      // Long messages (sign-in instructions) need time to read.
      setTimeout(() => dismissToast(id), Math.max(4000, text.length * 70));
    },
    [dismissToast],
  );
  const error = useCallback((e: unknown) => toast(errText(e), { tone: "error" }), [toast]);

  // ---------- notifications ----------
  const [notifications, setNotifications] = useState<AppNotification[]>(() => load<AppNotification[]>("notifications", []));
  useEffect(() => save("notifications", notifications.filter((n) => n.progress === undefined).slice(0, 50)), [notifications]);
  const pushNotification = useCallback((n: Omit<AppNotification, "id" | "at" | "read">) => {
    const id = uid();
    setNotifications((list) => [{ ...n, id, at: Date.now(), read: false }, ...list]);
    return id;
  }, []);
  const updateNotification = useCallback(
    (id: string, patch: Partial<AppNotification>) => setNotifications((list) => list.map((n) => (n.id === id ? { ...n, ...patch } : n))),
    [],
  );
  const markAllRead = useCallback(() => setNotifications((l) => l.map((n) => ({ ...n, read: true }))), []);
  const clearNotifications = useCallback(() => setNotifications((l) => l.filter((n) => n.progress !== undefined && n.progress < 1)), []);
  const runAction = useCallback(
    (a: NonNullable<AppNotification["action"]>) => {
      if ("route" in a) navigate(a.route);
      else if ("reveal" in a) api.revealPath(a.reveal).catch(error);
      else api.openUrl(a.url).catch(error);
    },
    [navigate, error],
  );

  // ---------- profiles and settings ----------
  const [cfg, setCfg] = useState<Cfg | null>(null);
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [profilesLoaded, setProfilesLoaded] = useState(false);
  const [activeId, setActiveId] = useState<string | null>(() => load<string | null>("activeProfile", null));
  const [globalSettings, setGlobalState] = useState<GameSettings>(DEFAULT_SETTINGS);
  const [aetherVersions, setAetherVersions] = useState<string[]>([]);

  const setActive = useCallback((id: string) => {
    setActiveId(id);
    save("activeProfile", id);
  }, []);

  const refreshProfiles = useCallback(async () => {
    try {
      const list = await api.listProfiles();
      setProfiles(list);
      setActiveId((s) => (s && list.some((p) => p.id === s) ? s : list[0]?.id ?? null));
      return list;
    } catch (e) {
      error(e);
      return [];
    } finally {
      setProfilesLoaded(true);
    }
  }, [error]);

  useEffect(() => {
    (async () => {
      // Data older launcher versions kept in the webview moves into profile.json / settings.json once.
      await migrateLocalData().catch((e) => console.warn("migration", e));
      refreshProfiles();
    })();
    api.configStatus().then(setCfg).catch(() => {});
    api.getSettings().then(setGlobalState).catch(() => {});
    api.aetherVersions().then(setAetherVersions).catch(() => {});
  }, [refreshProfiles]);

  const saveProfile = useCallback(
    async (p: Profile) => {
      try {
        const saved = await api.updateProfile(p);
        setProfiles((list) => list.map((x) => (x.id === saved.id ? saved : x)));
        return saved;
      } catch (e) {
        error(e);
        return null;
      }
    },
    [error],
  );
  const profilesRef = useRef(profiles);
  profilesRef.current = profiles;
  const setMeta = useCallback(
    async (id: string, patch: Partial<ProfileMeta>) => {
      const p = profilesRef.current.find((x) => x.id === id);
      if (!p) return;
      // Show the change right away; the saved copy replaces it.
      setProfiles((list) => list.map((x) => (x.id === id ? { ...x, meta: { ...x.meta, ...patch } } : x)));
      await saveProfile({ ...p, meta: { ...p.meta, ...patch } });
    },
    [saveProfile],
  );

  const settingsTimer = useRef<ReturnType<typeof setTimeout>>();
  const setGlobalSettings = useCallback(
    (s: GameSettings) => {
      setGlobalState(s);
      clearTimeout(settingsTimer.current);
      settingsTimer.current = setTimeout(() => api.setSettings(s).then(setGlobalState).catch(error), 400);
    },
    [error],
  );

  // ---------- accounts and the backend ----------
  const [accounts, setAccounts] = useState<AccountList | null>(null);
  const [accountsLoaded, setAccountsLoaded] = useState(false);
  const [online, setOnline] = useState<BackendStatus | null>(null);
  const [coins, setCoins] = useState<number | null>(null);

  const refreshOnline = useCallback(async () => {
    try {
      const s = await api.backendStatus();
      setOnline(s);
      setSocialEnabled(s.signed_in, s.uuid);
      if (s.signed_in) api.me().then((m) => setCoins(balanceOf(m))).catch(() => {});
      else setCoins(null);
    } catch {
      setOnline(null);
      setSocialEnabled(false, null);
    }
  }, []);
  const refreshCoins = useCallback(() => {
    api
      .me()
      .then((m) => setCoins(balanceOf(m)))
      .catch(() => {});
  }, []);

  const refreshAccounts = useCallback(async () => {
    try {
      setAccounts(await api.listAccounts());
    } catch {
      setAccounts({ accounts: [], active: null });
    } finally {
      setAccountsLoaded(true);
    }
    refreshOnline();
  }, [refreshOnline]);
  useEffect(() => {
    refreshAccounts();
  }, [refreshAccounts]);
  const account = accounts?.accounts.find((a) => a.uuid === accounts.active) ?? null;
  const switchAccount = useCallback(
    async (uuid: string) => {
      try {
        const a = await api.switchAccount(uuid);
        await refreshAccounts();
        toast(`Playing as ${a.username}`);
      } catch (e) {
        error(e);
      }
    },
    [refreshAccounts, toast, error],
  );
  const switchAccountRef = useRef(switchAccount);
  switchAccountRef.current = switchAccount;
  const refreshAccountsRef = useRef(refreshAccounts);
  refreshAccountsRef.current = refreshAccounts;

  const setPresence = useCallback((p: Presence) => setPrefs({ presence: p }), [setPrefs]);

  // ---------- launch ----------
  const [launch, setLaunch] = useState<LaunchState>({ phase: "idle" });
  const launchNote = useRef<string | null>(null);
  const launchRef = useRef(launch);
  launchRef.current = launch;

  // Presence: a launcher heartbeat every minute (the game sends its own with the server it's on).
  useEffect(() => {
    if (!online?.signed_in) return;
    const beat = () => {
      const l = launchRef.current;
      const playing = l.phase === "running" ? profilesRef.current.find((p) => p.id === l.profileId)?.name : null;
      api.heartbeat(prefsRef.current.presence, playing ? `Playing ${playing}` : "In the launcher").catch(() => {});
    };
    beat();
    const t = setInterval(beat, 60_000);
    return () => clearInterval(t);
  }, [online?.signed_in, prefs.presence, launch.phase]);

  // Friends: presence every 30 s, new messages and requests every 8 s (as notifications).
  useEffect(() => {
    if (!online?.signed_in) return;
    let since = Date.now();
    const poll = async () => {
      try {
        const u = await api.socialUpdates(since);
        since = u.now;
        const r = routeRef.current;
        for (const m of u.messages) {
          const viewing = r.page === "community" && r.friend === m.from && document.hasFocus();
          if (viewing) continue;
          bumpUnread(m.from);
          if (prefsRef.current.notifyFriends)
            pushNotification({ kind: "friend", title: `Message from ${m.fromName}`, detail: m.text.length > 120 ? m.text.slice(0, 117) + "…" : m.text, action: { label: "Reply", route: { page: "community", tab: "messages", friend: m.from } } });
        }
        if (u.messages.length) window.dispatchEvent(new CustomEvent("aether:messages", { detail: u.messages }));
        for (const q of u.requests) {
          if (prefsRef.current.notifyFriends)
            pushNotification({ kind: "friend", title: `${q.username} sent you a friend request`, detail: "Accept it to see when they're playing.", action: { label: "View", route: { page: "community", tab: "requests" } } });
        }
        if (u.requests.length) refreshSocial();
      } catch {
        /* next round */
      }
    };
    const fast = setInterval(poll, 8_000);
    const slow = setInterval(refreshSocial, 30_000);
    return () => {
      clearInterval(fast);
      clearInterval(slow);
    };
  }, [online?.signed_in, pushNotification]);

  useEffect(() => {
    api.runningProfile().then((id) => id && setLaunch({ phase: "running", profileId: id })).catch(() => {});
    const unProgress = listen<LaunchProgress>("launch-progress", ({ payload }) => {
      setLaunch((l) => {
        if (l.phase !== "preparing") return l;
        return payload.stage === "running" ? { phase: "running", profileId: l.profileId } : { ...l, progress: payload };
      });
      const note = launchNote.current;
      if (note && payload.total > 0) {
        setNotifications((list) => list.map((n) => (n.id === note ? { ...n, detail: stageText(payload.stage), progress: Math.min(0.99, payload.done / payload.total) } : n)));
      }
    });
    const unExit = listen<GameExit>("game-exit", ({ payload }) => {
      const pid = payload.profile_id;
      if (payload.relaunch && pid) {
        // The player switched accounts in the game's main menu.
        const uuid = payload.relaunch;
        setLaunch({ phase: "idle" });
        switchAccountRef.current(uuid).then(() => playRef.current(pid));
        return;
      }
      refreshProfilesRef.current();
      if (payload.code === 0 || payload.killed) {
        setLaunch({ phase: "idle" });
        return;
      }
      const name = profilesRef.current.find((p) => p.id === pid)?.name ?? "Minecraft";
      setNotifications((list) => [
        {
          id: uid(),
          kind: "crash",
          title: `${name} crashed`,
          detail: `Exit code ${payload.code ?? "unknown"}.${payload.crash_report ? " The game wrote a crash report." : ""}`,
          at: Date.now(),
          read: false,
          action: { label: payload.crash_report ? "Show crash report" : "Show log", reveal: payload.crash_report ?? payload.log_path },
        },
        ...list,
      ]);
      setLaunch({ phase: "crashed", profileId: pid, code: payload.code, log: payload.log_tail ?? "", logPath: payload.log_path, crashReport: payload.crash_report });
    });
    // The player switched accounts live in the game's main menu; the launcher already made it the active one.
    const unSwitched = listen<string>("account-switched", () => {
      refreshAccountsRef.current();
    });
    return () => {
      unProgress.then((f) => f());
      unExit.then((f) => f());
      unSwitched.then((f) => f());
    };
  }, []);
  const refreshProfilesRef = useRef(refreshProfiles);
  refreshProfilesRef.current = refreshProfiles;

  const play = useCallback(
    async (profileId?: string, quickPlay?: QuickPlay) => {
      const id = profileId ?? activeId;
      const p = profilesRef.current.find((x) => x.id === id);
      if (!id || !p) return;
      setActive(id);
      setLaunch({ phase: "preparing", profileId: id, progress: null });
      const where = quickPlay ? ("server" in quickPlay ? ` and joining ${quickPlay.server}` : " into your world") : "";
      const note = pushNotification({ kind: "launch", title: `Launching ${p.name}${where}`, detail: "Preparing game files", progress: 0 });
      launchNote.current = note;
      try {
        await api.launch(id, quickPlay ?? null);
        setLaunch((l) => (l.phase === "preparing" ? { phase: "running", profileId: l.profileId } : l));
        setNotifications((list) => list.map((n) => (n.id === note ? { ...n, title: `${p.name} is running`, detail: "Minecraft started", progress: undefined } : n)));
        refreshProfiles();
        if (prefsRef.current.onLaunch !== "keep") windowAction(prefsRef.current.onLaunch === "minimise" ? "minimise" : "close");
      } catch (e) {
        setLaunch({ phase: "idle" });
        setNotifications((list) => list.map((n) => (n.id === note ? { ...n, kind: "crash", title: `Couldn't launch ${p.name}`, detail: errText(e), progress: undefined } : n)));
        error(e);
      } finally {
        launchNote.current = null;
      }
    },
    [activeId, setActive, pushNotification, refreshProfiles, error],
  );
  const playRef = useRef(play);
  playRef.current = play;

  const stop = useCallback(async () => {
    try {
      await api.killGame();
      toast("Stopping Minecraft");
    } catch (e) {
      error(e);
    }
  }, [toast, error]);

  const active = profiles.find((p) => p.id === activeId) ?? null;

  const value = useMemo<Store>(
    () => ({
      route: H.current(hist),
      canBack: H.canBack(hist),
      canForward: H.canForward(hist),
      navigate,
      replace,
      back,
      forward,
      overlay,
      setOverlay,
      newProfileOpen,
      setNewProfileOpen,
      cfg,
      profiles,
      profilesLoaded,
      refreshProfiles,
      activeId,
      setActive,
      active,
      saveProfile,
      setMeta,
      globalSettings,
      setGlobalSettings,
      aetherVersions,
      accounts,
      account,
      accountsLoaded,
      refreshAccounts,
      switchAccount,
      presence: prefs.presence,
      setPresence,
      online,
      refreshOnline,
      launch,
      play,
      stop,
      dismissCrash: () => setLaunch({ phase: "idle" }),
      notifications,
      pushNotification,
      updateNotification,
      markAllRead,
      clearNotifications,
      runAction,
      coins,
      setCoins,
      refreshCoins,
      toasts,
      toast,
      error,
      dismissToast,
      prefs,
      setPrefs,
    }),
    [hist, navigate, replace, back, forward, overlay, newProfileOpen, cfg, profiles, profilesLoaded, refreshProfiles, activeId, setActive, active, saveProfile, setMeta, globalSettings, setGlobalSettings, aetherVersions, accounts, account, accountsLoaded, refreshAccounts, switchAccount, setPresence, online, refreshOnline, launch, play, stop, notifications, pushNotification, updateNotification, markAllRead, clearNotifications, runAction, coins, refreshCoins, toasts, toast, error, dismissToast, prefs, setPrefs],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

/** Who "me" is in chat (the backend uuid of the active account). */
export const chatMe = () => socialMe;
