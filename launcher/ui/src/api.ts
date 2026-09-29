import { invoke } from "@tauri-apps/api/core";

export type Loader = "vanilla" | "fabric" | "quilt" | "forge" | "neoforge";
export type Kind = "mod" | "resourcepack" | "shader" | "datapack" | "modpack";

export interface Profile {
  format: number;
  id: string;
  name: string;
  mc_version: string;
  loader: Loader;
  memory_mb: number;
  jvm_args: string[];
  created_unix: number;
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
}
export interface Search {
  hits: Hit[];
  total_hits: number;
}
export interface Installed {
  project_id: string;
  version_id: string;
  title: string;
  project_type: Kind;
  filename: string;
  explicit: boolean;
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

export const api = {
  configStatus: () => invoke<ConfigStatus>("config_status"),
  listProfiles: () => invoke<Profile[]>("list_profiles"),
  createProfile: (name: string, mcVersion: string, loader: Loader) =>
    invoke<Profile>("create_profile", { name, mcVersion, loader }),
  deleteProfile: (id: string) => invoke<void>("delete_profile", { id }),
  duplicateProfile: (id: string) => invoke<Profile>("duplicate_profile", { id }),
  updateProfile: (profile: Profile) => invoke<void>("update_profile", { profile }),
  mcVersions: () => invoke<{ id: string; kind: string }[]>("mc_versions"),
  search: (query: string, kind: Kind, profileId: string | null, offset = 0) =>
    invoke<Search>("search_content", { query, kind, profileId, offset }),
  install: (profileId: string, projectId: string, kind: Kind, world: string | null = null) =>
    invoke<Installed[]>("install_content", { profileId, projectId, kind, world }),
  listInstalled: (profileId: string) => invoke<Installed[]>("list_installed", { profileId }),
  uninstall: (profileId: string, projectId: string) =>
    invoke<void>("uninstall_content", { profileId, projectId }),
  checkUpdates: (profileId: string) => invoke<UpdateInfo[]>("check_updates", { profileId }),
  updateAll: (profileId: string) => invoke<number>("update_all", { profileId }),
  importMrpack: (path: string) => invoke<Profile>("import_mrpack", { path }),
  startLogin: () => invoke<DeviceCode>("start_login"),
  finishLogin: () => invoke<Account>("finish_login"),
  logout: () => invoke<void>("logout"),
  hasAccount: () => invoke<boolean>("has_account"),
  accountName: () => invoke<string | null>("account_name"),
  launch: (profileId: string) => invoke<string>("launch_profile", { profileId }),
  runningProfile: () => invoke<string | null>("running_profile"),
};

export interface LaunchProgress {
  stage: string;
  done: number;
  total: number;
}
export interface GameExit {
  code: number | null;
  log_tail: string | null;
}

export const errText = (e: unknown) => (typeof e === "string" ? e : e instanceof Error ? e.message : JSON.stringify(e));
