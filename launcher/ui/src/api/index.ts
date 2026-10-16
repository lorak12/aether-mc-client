import { invoke as tauriInvoke } from "@tauri-apps/api/core";
import type {
  AccountList,
  Account,
  AppInfo,
  Backup,
  BackendStatus,
  Banner,
  Blocked,
  ChatMessage,
  CoinBundle,
  ConfigStatus,
  DeviceCode,
  ExportOptions,
  ExportReport,
  Friend,
  FriendRequest,
  GameSettings,
  InstallPlan,
  Installed,
  JavaInstall,
  Kind,
  LauncherUpdate,
  LocalFile,
  LoaderVersion,
  LogChunk,
  Loader,
  McVersion,
  ModVersion,
  NewsArticle,
  Ping,
  Presence,
  Profile,
  ProjectDetails,
  Provider,
  QuickPlay,
  SavedAccount,
  SavedServer,
  Screenshot,
  Search,
  SearchQuery,
  Server,
  Slot,
  StoreData,
  Thread,
  Transaction,
  UpdateInfo,
  UploadedLogs,
  World,
} from "./types";

export * from "./types";

/** Folders `openPath` can open. Profile folders need the profile id. */
export type FolderKind =
  | "app"
  | "launcher-logs"
  | "config-file"
  | "profile"
  | "client-logs"
  | "saves"
  | "screenshots"
  | "mods"
  | "resourcepacks"
  | "shaderpacks"
  | "config"
  | "crash-reports"
  | "backups";

/** Calls that run constantly (polling, pings, lookups): their failures are not logged. */
const QUIET = new Set(["read_game_log", "running_profile", "ping_server", "skin_url", "backend_status", "account_name", "log_line"]);

/** `invoke`, with failed calls also written to the launcher log (Upload logs shares it) so problems can be traced. */
function invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  return tauriInvoke<T>(cmd, args).catch((err: unknown) => {
    if (!QUIET.has(cmd)) {
      const what = cmd === "backend_call" && args ? `${cmd} ${String(args.method)} ${String(args.path)}` : cmd;
      void tauriInvoke("log_line", { line: `${what} failed: ${err instanceof Error ? err.message : String(err)}` }).catch(() => undefined);
    }
    throw err;
  });
}

// ---------- Kite backend (through the launcher, which holds the sign-in) ----------
function backend<T>(method: "GET" | "POST" | "PUT" | "DELETE", path: string, body?: unknown, auth = true): Promise<T> {
  return invoke<T>("backend_call", { method, path, body: body ?? null, auth });
}

interface RawFriend {
  uuid: string;
  name: string;
  status: Exclude<Presence, "invisible">;
  activity: string | null;
  server: string | null;
  lastSeen: number | null;
}
const toFriend = (f: RawFriend): Friend => ({ id: f.uuid, uuid: f.uuid, username: f.name, presence: f.status, activity: f.activity, server: f.server, lastSeen: f.lastSeen });
interface RawRequest {
  id: number;
  uuid: string;
  name: string;
  direction: "incoming" | "outgoing";
  at: number;
}
const toRequest = (r: RawRequest): FriendRequest => ({ id: r.id, uuid: r.uuid, username: r.name, direction: r.direction, at: r.at });
interface RawMessage {
  id: number;
  from: string;
  to: string;
  text: string;
  at: number;
  read: boolean;
}
const toMessage = (me: string | null) => (m: RawMessage): ChatMessage => ({ id: m.id, from: m.from === me ? "me" : m.from, text: m.text, at: m.at, read: m.read });

export interface SocialUpdates {
  now: number;
  messages: (RawMessage & { fromName: string })[];
  requests: FriendRequest[];
}

// ---------- the API ----------
export const api = {
  // Profiles, settings and versions
  configStatus: () => invoke<ConfigStatus & { backend_url: string | null; updates: boolean }>("config_status"),
  appInfo: () => invoke<AppInfo>("app_info"),
  listProfiles: () => invoke<Profile[]>("list_profiles"),
  createProfile: (name: string, mcVersion: string, loader: Loader) => invoke<Profile>("create_profile", { name, mcVersion, loader }),
  deleteProfile: (id: string) => invoke<void>("delete_profile", { id }),
  duplicateProfile: (id: string) => invoke<Profile>("duplicate_profile", { id }),
  updateProfile: (profile: Profile) => invoke<Profile>("update_profile", { profile }),
  setProfileCover: (id: string, path: string) => invoke<Profile>("set_profile_cover", { id, path }),
  getSettings: () => invoke<GameSettings>("get_settings"),
  setSettings: (game: GameSettings) => invoke<GameSettings>("set_settings", { game }),
  mcVersions: () => invoke<McVersion[]>("mc_versions"),
  loaderVersions: (loader: Loader, mcVersion: string) => invoke<LoaderVersion[]>("loader_versions", { loader, mcVersion }),
  kiteVersions: () => invoke<string[]>("kite_versions"),
  listJavas: () => invoke<JavaInstall[]>("list_javas"),
  probeJava: (path: string) => invoke<JavaInstall>("probe_java", { path }),

  // Content (Modrinth and CurseForge)
  searchProjects: (q: SearchQuery, provider: Provider = "modrinth") => invoke<Search>("search_projects", { q, provider }),
  projectDetails: (id: string, provider: Provider = "modrinth") => invoke<ProjectDetails>("project_details", { id, provider }),
  projectVersions: (projectId: string, kind: Kind, profileId: string | null, provider: Provider = "modrinth") =>
    invoke<ModVersion[]>("project_versions", { projectId, kind, profileId, provider }),
  installPlan: (profileId: string, projectId: string, versionId: string | null, kind: Kind, provider: Provider = "modrinth") =>
    invoke<InstallPlan>("install_plan", { profileId, projectId, versionId, kind, provider }),
  install: (profileId: string, projectId: string, kind: Kind, opts: { versionId?: string | null; withDeps?: boolean; world?: string | null; provider?: Provider } = {}) =>
    invoke<Installed[]>("install_content", {
      profileId,
      projectId,
      kind,
      world: opts.world ?? null,
      versionId: opts.versionId ?? null,
      withDeps: opts.withDeps ?? true,
      provider: opts.provider ?? "modrinth",
    }),
  installModpack: (projectId: string, versionId: string | null, provider: Provider = "modrinth") => invoke<Profile>("install_modpack", { projectId, versionId, provider }),
  importPack: (path: string) => invoke<Profile>("import_pack", { path }),
  listInstalled: (profileId: string) => invoke<Installed[]>("list_installed", { profileId }),
  listLocalFiles: (profileId: string) => invoke<LocalFile[]>("list_local_files", { profileId }),
  /** `id`: a project id, or `file:<kind>:<filename>` for an untracked file. */
  setContentEnabled: (profileId: string, id: string, enabled: boolean) => invoke<void>("set_content_enabled", { profileId, id, enabled }),
  removeLocalFile: (profileId: string, id: string) => invoke<void>("remove_local_file", { profileId, id }),
  adoptLocalFiles: (profileId: string) => invoke<number>("adopt_local_files", { profileId }),
  uninstall: (profileId: string, projectId: string) => invoke<void>("uninstall_content", { profileId, projectId }),
  checkUpdates: (profileId: string) => invoke<UpdateInfo[]>("check_updates", { profileId }),
  updateAll: (profileId: string) => invoke<number>("update_all", { profileId }),
  updateOne: (profileId: string, projectId: string) => invoke<boolean>("update_content", { profileId, projectId }),
  exportCandidates: (profileId: string) => invoke<string[]>("export_candidates", { profileId }),
  exportMrpack: (profileId: string, dest: string, options: ExportOptions) => invoke<ExportReport>("export_mrpack", { profileId, dest, options }),

  // Worlds, servers, screenshots
  worlds: (profileId: string) => invoke<World[]>("list_worlds", { profileId }),
  deleteWorld: (profileId: string, world: string) => invoke<void>("delete_world", { profileId, world }),
  backupWorld: (profileId: string, world: string) => invoke<Backup>("backup_world", { profileId, world }),
  backups: (profileId: string) => invoke<Backup[]>("list_backups", { profileId }),
  restoreBackup: (profileId: string, backup: string) => invoke<string>("restore_backup", { profileId, backup }),
  profileServers: (profileId: string) => invoke<SavedServer[]>("list_servers", { profileId }),
  addServer: (profileId: string, name: string, address: string) => invoke<void>("add_server", { profileId, name, address }),
  removeServer: (profileId: string, index: number, address: string) => invoke<void>("remove_server", { profileId, index, address }),
  ping: (address: string) => invoke<Ping>("ping_server", { address }),
  screenshots: (profileId: string) => invoke<Screenshot[]>("list_screenshots", { profileId }),
  deleteScreenshot: (profileId: string, name: string) => invoke<void>("delete_screenshot", { profileId, name }),
  copyScreenshot: (profileId: string, name: string) => invoke<void>("copy_screenshot", { profileId, name }),

  // Accounts
  startLogin: () => invoke<DeviceCode>("start_login"),
  finishLogin: () => invoke<Account>("finish_login"),
  browserLogin: () => invoke<Account>("browser_login"),
  cancelLogin: () => invoke<void>("cancel_login"),
  offlineLogin: (name: string) => invoke<Account>("offline_login", { name }),
  logout: () => invoke<void>("logout"),
  listAccounts: () => invoke<AccountList>("list_accounts"),
  switchAccount: (uuid: string) => invoke<SavedAccount>("switch_account", { uuid }),
  removeAccount: (uuid: string) => invoke<void>("remove_account", { uuid }),
  accountName: () => invoke<string | null>("account_name"),
  /** A player's skin texture URL (https), null for offline accounts and players without a Mojang skin. */
  skinUrl: (uuid: string) => invoke<string | null>("skin_url", { uuid }),

  // Game
  launch: (profileId: string, quickPlay: QuickPlay | null = null) => invoke<string>("launch_profile", { profileId, quickPlay }),
  killGame: () => invoke<void>("kill_game"),
  runningProfile: () => invoke<string | null>("running_profile"),
  readGameLog: (offset: number, profileId: string | null) => invoke<LogChunk>("read_game_log", { offset, profileId }),
  uploadLogs: (profileId: string | null) => invoke<UploadedLogs>("upload_logs", { profileId }),

  // Shell
  openPath: (kind: FolderKind, profileId: string | null = null) => invoke<void>("open_path", { kind, profileId }),
  revealPath: (path: string) => invoke<void>("reveal_path", { path }),
  openUrl: (url: string) => invoke<void>("open_url", { url }),
  restartApp: () => invoke<void>("restart_app"),
  openLogsWindow: () => invoke<void>("open_logs_window"),
  systemMemoryMb: () => invoke<number>("system_memory_mb"),
  checkUpdate: () => invoke<LauncherUpdate>("check_update", { channel: null }),
  installUpdate: () => invoke<void>("install_update", { channel: null }),

  // ---------- Kite backend ----------
  backendStatus: () => invoke<BackendStatus>("backend_status"),
  onlineCount: () => backend<{ online: number }>("GET", "/v1/stats", undefined, false).then((r) => r.online),
  news: () => backend<{ news: NewsArticle[] }>("GET", "/v1/news", undefined, false).then((r) => r.news),
  banners: () => backend<{ banners: Banner[] }>("GET", "/v1/banners", undefined, false).then((r) => r.banners),
  servers: () => backend<{ servers: Server[] }>("GET", "/v1/servers", undefined, false).then((r) => r.servers),

  heartbeat: (status: Presence, activity: string | null) => backend<void>("POST", "/v1/presence/heartbeat", { source: "launcher", status, activity }),
  friends: () => backend<{ friends: RawFriend[] }>("GET", "/v1/friends").then((r) => r.friends.map(toFriend)),
  friendRequests: () => backend<{ requests: RawRequest[] }>("GET", "/v1/friends/requests").then((r) => r.requests.map(toRequest)),
  /** Returns true when it accepted their pending request instead (you're friends now). */
  sendFriendRequest: (name: string) => backend<{ accepted: boolean }>("POST", "/v1/friends/requests", { name: name.trim() }).then((r) => r.accepted),
  answerRequest: (id: number, accept: boolean) => backend<void>("POST", `/v1/friends/requests/${id}/${accept ? "accept" : "decline"}`),
  removeFriend: (uuid: string) => backend<void>("DELETE", `/v1/friends/${uuid}`),
  blocked: () => backend<{ blocked: { uuid: string; name: string }[] }>("GET", "/v1/blocks").then((r) => r.blocked.map((b): Blocked => ({ uuid: b.uuid, username: b.name }))),
  block: (uuid: string) => backend<void>("POST", "/v1/blocks", { uuid }),
  unblock: (uuid: string) => backend<void>("DELETE", `/v1/blocks/${uuid}`),
  threads: (me: string | null) =>
    backend<{ threads: { with: string; last: RawMessage; unread: number }[] }>("GET", "/v1/messages").then((r) => r.threads.map((t): Thread => ({ with: t.with, last: toMessage(me)(t.last), unread: t.unread }))),
  thread: (uuid: string, me: string | null, before?: number) =>
    backend<{ messages: RawMessage[] }>("GET", `/v1/messages/${uuid}?limit=60${before ? `&before=${before}` : ""}`).then((r) => r.messages.map(toMessage(me))),
  sendMessage: (uuid: string, text: string, me: string | null) => backend<{ message: RawMessage }>("POST", `/v1/messages/${uuid}`, { text }).then((r) => toMessage(me)(r.message)),
  markRead: (uuid: string) => backend<void>("POST", `/v1/messages/${uuid}/read`),
  socialUpdates: (since: number) => backend<SocialUpdates & { requests: RawRequest[] }>("GET", `/v1/social/updates?since=${since}`).then((r) => ({ ...r, requests: r.requests.map(toRequest) })),

  me: () => backend<{ uuid: string; name: string | null; coins: number; unlimited?: boolean; owned: string[]; loadout: Partial<Record<Slot, string>> }>("GET", "/v1/me"),
  store: () => backend<StoreData>("GET", "/v1/store"),
  buy: (id: string) => backend<{ coins: number; unlimited?: boolean; owned: string[] }>("POST", "/v1/store/buy", { id }),
  setLoadout: (patch: Partial<Record<Slot, string | null>>) => backend<{ loadout: Partial<Record<Slot, string>> }>("PUT", "/v1/me/loadout", patch).then((r) => r.loadout),
  transactions: () => backend<{ transactions: Transaction[] }>("GET", "/v1/me/transactions").then((r) => r.transactions),
  coinBundles: () => backend<{ enabled: boolean; bundles: CoinBundle[] }>("GET", "/v1/coins/bundles", undefined, false),
  checkout: (bundle: string) => backend<{ url: string }>("POST", "/v1/coins/checkout", { bundle }).then((r) => r.url),
};

export const errText = (e: unknown) => (typeof e === "string" ? e : e instanceof Error ? e.message : JSON.stringify(e));

/** A backend balance; admins (backend/src/admins.ts) have unlimited coins, shown as ∞. */
export const balanceOf = (r: { coins: number | null; unlimited?: boolean }): number | null => (r.unlimited ? Infinity : r.coins);
