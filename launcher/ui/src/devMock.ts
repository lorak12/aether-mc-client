// Dev-only: lets the UI run in a plain browser (no Tauri) for design work.
const profiles = [
  { format: 1, id: "a", name: "Skyblock Reborn", mc_version: "1.21.4", loader: "fabric", memory_mb: 6144, jvm_args: [], created_unix: 0 },
  { format: 1, id: "b", name: "Vanilla+", mc_version: "1.21.1", loader: "vanilla", memory_mb: 4096, jvm_args: [], created_unix: 0 },
  { format: 1, id: "c", name: "Create Odyssey", mc_version: "1.20.1", loader: "forge", memory_mb: 8192, jvm_args: [], created_unix: 0 },
  { format: 1, id: "d", name: "PvP Lite", mc_version: "1.8.9", loader: "quilt", memory_mb: 2048, jvm_args: [], created_unix: 0 },
];
const hits = ["Sodium", "Lithium", "Iris Shaders", "Mod Menu", "Fabric API", "Xaero's Minimap"].map((t, i) => ({
  project_id: "p" + i, slug: t, title: t, description: "Performance and quality of life improvement for your game, tuned for modern hardware.",
  icon_url: null, downloads: 1000, author: "author" + i, categories: [], project_type: "mod",
}));
const handlers: Record<string, unknown> = {
  config_status: { login: true, curseforge: false, backend: false },
  list_profiles: profiles,
  account_name: "Steve_Dev",
  running_profile: null,
  mc_versions: [{ id: "1.21.4", kind: "release" }, { id: "1.21.1", kind: "release" }],
  search_content: { hits, total_hits: 4820 },
  list_installed: [{ project_id: "p0", version_id: "v", title: "Sodium", project_type: "mod", filename: "s.jar", explicit: true }],
};
const w = window as unknown as Record<string, unknown>;
if (!w.__TAURI_INTERNALS__) {
  let cb = 0;
  w.__TAURI_INTERNALS__ = {
    transformCallback: () => ++cb,
    unregisterCallback: () => {},
    invoke: async (cmd: string) => (cmd in handlers ? handlers[cmd] : cmd.startsWith("plugin:event") ? cb : null),
  };
  w.__TAURI_EVENT_PLUGIN_INTERNALS__ = { unregisterListener: () => {} };
}
export {};
