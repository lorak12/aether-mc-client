// ---------- backed by the Rust launcher ----------
export type Loader = "vanilla" | "fabric" | "quilt" | "forge" | "neoforge";
export type Kind = "mod" | "resourcepack" | "shader" | "datapack" | "modpack";
export type Provider = "modrinth" | "curseforge";

export type ProfileKind = "client" | "vanilla" | "modpack";
export interface ProfileMeta {
  kind: ProfileKind;
  description: string;
  /** A CSS background (gradient), an image URL, or `file:<name>` for an image in the profile folder. */
  cover: string | null;
  /** Client profiles only: load just Kite's built-in mods. */
  builtinOnly: boolean;
  /** Unix milliseconds. */
  lastPlayed: number | null;
  plays: number;
}

export type GC = "G1GC" | "ZGC" | "Shenandoah" | "Parallel";
/** Global defaults (settings.json) and, per profile, the values it overrides. */
export interface GameSettings {
  memoryMb: number;
  width: number;
  height: number;
  fullscreen: boolean;
  jvmArgs: string;
  gc: GC;
  preLaunch: string;
  wrapper: string;
  postExit: string;
  env: [string, string][];
  /** Empty = Mojang's Java runtime, downloaded automatically. */
  javaPath: string;
  /** Use the shared options.txt (video settings, keybinds, sound...) so they're the same in every profile. */
  shareOptions: boolean;
}
export type SettingKey = keyof GameSettings;
export type Overrides = Partial<{ [K in SettingKey]: { on: boolean; value: GameSettings[K] } }>;

export interface Profile {
  format: number;
  id: string;
  name: string;
  mc_version: string;
  loader: Loader;
  /** Pinned loader version; null = newest stable at launch. */
  loader_version: string | null;
  created_unix: number;
  /** Folder the game runs in, when not the profile's own folder. */
  game_dir: string | null;
  overrides: Overrides;
  meta: ProfileMeta;
  /** The profile's own folder (where profile.json lives). */
  dir: string;
}

export interface Hit {
  project_id: string;
  slug: string;
  title: string;
  description: string;
  icon_url: string | null;
  downloads: number;
  author: string;
  categories: string[];
  project_type: string;
  display_categories?: string[];
  follows?: number;
  date_modified?: string;
  featured_gallery?: string | null;
  versions?: string[];
}
export interface Search {
  hits: Hit[];
  total_hits: number;
}
export type Sort = "relevance" | "downloads" | "follows" | "newest" | "updated";
export interface SearchQuery {
  query: string;
  kind: Kind;
  game_versions: string[];
  loaders: string[];
  categories: string[];
  sort: Sort;
  offset: number;
  limit?: number;
}
export interface Installed {
  project_id: string;
  version_id: string;
  title: string;
  project_type: Kind;
  filename: string;
  sha1: string;
  explicit: boolean;
  world?: string | null;
  enabled: boolean;
  source: Provider;
}
/** A file in mods / resourcepacks / shaderpacks that the launcher doesn't track. */
export interface LocalFile {
  filename: string;
  project_type: Kind;
  enabled: boolean;
  size_bytes: number;
}
export interface UpdateInfo {
  project_id: string;
  title: string;
  current: string;
  latest: string;
}
export interface ConfigStatus {
  login: boolean;
  curseforge: boolean;
  backend: boolean;
}
export interface DeviceCode {
  user_code: string;
  verification_uri: string;
}
export interface Account {
  username: string;
  uuid: string;
}
export interface SavedAccount {
  uuid: string;
  username: string;
  kind: "microsoft" | "offline";
}
export interface AccountList {
  accounts: SavedAccount[];
  active: string | null;
}
export interface ModVersion {
  id: string;
  project_id: string;
  name: string;
  version_number: string;
  version_type: "release" | "beta" | "alpha" | string;
  game_versions: string[];
  loaders: string[];
  date_published: string;
  dependencies: { project_id: string | null; version_id: string | null; dependency_type: string }[];
  files: { filename: string; size: number; primary: boolean; url: string }[];
}
export interface ProjectDetails {
  id: string;
  slug: string;
  title: string;
  description: string;
  project_type: string;
  icon_url: string | null;
  body: string;
  /** CurseForge descriptions are HTML. */
  body_html?: boolean;
  downloads: number;
  followers: number;
  published: string;
  updated: string;
  categories: string[];
  loaders: string[];
  game_versions: string[];
  versions: string[];
  gallery: { url: string; featured: boolean; title: string | null }[];
  source_url: string | null;
  issues_url: string | null;
  wiki_url: string | null;
  discord_url: string | null;
  page_url?: string | null;
}
export interface InstallPlan {
  version: string;
  dependencies: { project_id: string; title: string; version: string }[];
  missing: string[];
}
export interface LogChunk {
  lines: string[];
  offset: number;
  profile_id: string | null;
}
export interface LaunchProgress {
  stage: string;
  done: number;
  total: number;
}
export interface GameExit {
  profile_id: string;
  code: number | null;
  /** Stopped from the launcher, not a crash. */
  killed: boolean;
  log_tail: string | null;
  log_path: string;
  crash_report: string | null;
  /** Account picked in the in-game switcher: switch to it and play the same profile again. */
  relaunch?: string | null;
}
export interface McVersion {
  id: string;
  kind: string;
}
export interface LoaderVersion {
  version: string;
  stable: boolean;
}
export interface JavaInstall {
  path: string;
  version: string;
  major: number;
  vendor: string;
  arch: string;
}
export type QuickPlay = { server: string } | { world: string };

export interface World {
  /** Folder name in saves/. */
  id: string;
  name: string;
  mode: "survival" | "creative" | "adventure" | "spectator" | "hardcore";
  last_played: number | null;
  size_bytes: number;
  version: string | null;
  icon: string | null;
}
export interface Backup {
  name: string;
  path: string;
  at: number;
  size_bytes: number;
}
export interface SavedServer {
  index: number;
  name: string;
  address: string;
  icon: string | null;
}
export interface Ping {
  online: number;
  max: number;
  version: string;
  motd: string;
  favicon: string | null;
  latency_ms: number;
}
export interface Screenshot {
  name: string;
  path: string;
  at: number;
  size_bytes: number;
}
export interface ExportOptions {
  name: string;
  version: string;
  summary: string;
  include: string[];
}
export interface ExportReport {
  path: string;
  downloads: number;
  overrides: number;
}
export interface UploadedLogs {
  launcher: string | null;
  game: string | null;
  profile: string | null;
}
export interface LauncherUpdate {
  current: string;
  latest: string;
  available: boolean;
  notes: string;
  installable: boolean;
}
export interface AppInfo {
  version: string;
  data_dir: string;
  config_file: string;
  os: string;
}
export interface BackendStatus {
  configured: boolean;
  signed_in: boolean;
  uuid: string | null;
  name: string | null;
  error: string | null;
}

// ---------- Aether backend ----------
export type Presence = "online" | "away" | "busy" | "invisible" | "offline";
export interface Friend {
  /** The friend's uuid (also used in routes). */
  id: string;
  uuid: string;
  username: string;
  presence: Presence;
  activity: string | null;
  /** Server they're on, when in game: Join connects there. */
  server: string | null;
  lastSeen: number | null;
}
export interface FriendRequest {
  id: number;
  uuid: string;
  username: string;
  direction: "incoming" | "outgoing";
  at: number;
}
export interface Blocked {
  uuid: string;
  username: string;
}
export interface ChatMessage {
  id: number;
  /** "me" for my own messages, else the sender's uuid. */
  from: string;
  text: string;
  at: number;
  read: boolean;
}
export interface Thread {
  with: string;
  last: ChatMessage;
  unread: number;
}
export type BannerVariant = "info" | "success" | "warning" | "danger";
export interface Banner {
  id: string;
  variant: BannerVariant;
  text: string;
  link?: { label: string; page?: string; url?: string };
}
export type NewsTag = "Update" | "Event" | "Sale" | "Community";
export interface NewsArticle {
  id: string;
  tag: NewsTag;
  title: string;
  excerpt: string;
  body: string[];
  image?: string;
  art?: string;
  date: number;
  size: "lg" | "wide" | "sm";
  link?: string;
}
/** A curated server from the Aether service. */
export interface Server {
  id: string;
  name: string;
  address: string;
  description: string;
  region: "EU" | "NA" | "Asia" | "OCE";
  genres: string[];
  partner: boolean;
  color?: string;
  version?: string;
}
export type Slot = "cape" | "wings" | "hat" | "ears" | "face" | "boots" | "back";
export type Rarity = "common" | "rare" | "epic" | "legendary";
export interface StoreItem {
  id: string;
  slot: Slot;
  name: string;
  rarity: Rarity;
  free: boolean;
  /** Coins; null when it can't be bought. */
  price: number | null;
  description: string;
  /** Image URL (absolute). */
  icon: string;
}
export interface StoreData {
  items: StoreItem[];
  owned: string[];
  loadout: Partial<Record<Slot, string>>;
  coins: number | null;
  /** Admin account: unlimited coins. */
  unlimited?: boolean;
}
export interface CoinBundle {
  id: string;
  coins: number;
  bonus: number;
  price: string;
  best?: boolean;
}
export interface Transaction {
  id: number;
  at: number;
  label: string;
  coins: number;
}

// ---------- launcher UI ----------
export type NotificationKind = "download" | "launch" | "crash" | "purchase" | "friend" | "info";
export type NotificationAction = { label: string; route: Route } | { label: string; reveal: string } | { label: string; url: string };
export interface AppNotification {
  id: string;
  kind: NotificationKind;
  title: string;
  detail: string;
  at: number;
  read: boolean;
  /** 0..1 while in progress. */
  progress?: number;
  action?: NotificationAction;
}

// ---------- navigation ----------
export type Route =
  | { page: "home" }
  | { page: "profiles"; open?: string }
  | { page: "content"; kind?: Kind; q?: string }
  | { page: "project"; id: string; kind: Kind; provider: Provider }
  | { page: "explore" }
  | { page: "community"; tab?: "messages" | "requests" | "blocked"; friend?: string }
  | { page: "news" }
  | { page: "article"; id: string }
  | { page: "store"; tab?: string }
  | { page: "coins" }
  | { page: "settings"; tab?: string };
export type Page = Route["page"];
