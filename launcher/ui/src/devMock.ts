// Dev-only: lets the UI run in a plain browser (no Tauri) for design work and self-testing with `npm run dev`.
// Fakes the Tauri IPC (and the Kite backend behind `backend_call`) with in-memory state. Never part of a build.
type Args = Record<string, any>;

const now = Date.now();
const meta = (kind: string, extra: Args = {}) => ({ kind, description: "", cover: null, builtinOnly: false, lastPlayed: null, plays: 0, ...extra });
const prof = (id: string, name: string, mc: string, loader: string, kind: string, extra: Args = {}) => ({
  format: 2,
  id,
  name,
  mc_version: mc,
  loader,
  loader_version: null,
  created_unix: now / 1000 - 9e5,
  game_dir: null,
  overrides: {},
  meta: meta(kind, extra),
  dir: `C:\\Users\\dev\\AppData\\Roaming\\gg.kite.launcher\\profiles\\${id}`,
});
let profiles: any[] = [
  prof("skyblock-reborn", "Skyblock Reborn", "1.21.11", "fabric", "client", { lastPlayed: now - 3600_000, plays: 14 }),
  prof("vanilla", "Vanilla+", "1.21.4", "vanilla", "vanilla", { plays: 2, lastPlayed: now - 5 * 86400_000 }),
  prof("create-odyssey", "Create Odyssey", "1.20.1", "forge", "modpack", { description: "Tech and exploration pack." }),
  prof("kite-26", "Kite 26.3", "26.3", "quilt", "client"),
];
let settings: any = { memoryMb: 4096, width: 1280, height: 720, fullscreen: false, jvmArgs: "", gc: "G1GC", preLaunch: "", wrapper: "", postExit: "", env: [], javaPath: "", shareOptions: true };
const lock = (project_id: string, title: string, project_type: string, filename: string, explicit = true, enabled = true) => ({ project_id, version_id: "v-" + project_id, title, project_type, filename, sha1: "x", explicit, world: null, enabled, source: "modrinth" });
const installed: Record<string, any[]> = {
  "skyblock-reborn": [
    lock("AANobbMI", "Sodium", "mod", "sodium-fabric-0.6.5.jar"),
    lock("P7dR8mSH", "Fabric API", "mod", "fabric-api-0.110.jar", false),
    lock("gvQqBUqZ", "Lithium", "mod", "lithium-0.14.jar", true, false),
    lock("YL57xq9U", "Iris Shaders", "mod", "iris-1.8.jar"),
    lock("HVnmMxH1", "Complementary Reimagined", "shader", "ComplementaryReimagined_r5.3.zip"),
  ],
};
const localFiles: Record<string, any[]> = { "skyblock-reborn": [{ filename: "my-private-mod.jar", project_type: "mod", enabled: true, size_bytes: 482_113 }] };
let accounts = {
  accounts: [
    { uuid: "069a79f444e94726a5befca90e38aaf5", username: "Notch", kind: "microsoft" },
    { uuid: "0000000000003000a000000000000001", username: "Steve_Dev", kind: "offline" },
  ],
  active: "069a79f444e94726a5befca90e38aaf5" as string | null,
};
let running: string | null = null;
let killed = false;
let logLines: string[] = [];

const titles = ["Sodium", "Lithium", "Iris Shaders", "Mod Menu", "Fabric API", "Xaero's Minimap", "Entity Culling", "ferrite-core", "ImmediatelyFast", "Zoomify", "Continuity", "Indium", "Clumps", "Jade", "AppleSkin", "Debugify", "Krypton", "BadOptimizations", "Dynamic FPS", "More Culling"];
const descs = [
  "A modern rendering engine for Minecraft which greatly improves frame rates and fixes graphical issues.",
  "No-compromises game logic and server optimisation mod.",
  "A modern shaders mod for Minecraft intended to be compatible with existing OptiFine shader packs, and fast.",
  "Adds a mod menu to view the list of mods you have installed, with links, configs and more details about each of them.",
];
const hit = (t: string, i: number, kind: string) => ({
  project_id: "p" + i + kind,
  slug: t.toLowerCase().replace(/\W+/g, "-"),
  title: kind === "mod" ? t : `${t} ${kind === "modpack" ? "Pack" : kind === "shader" ? "Shaders" : "Textures"}`,
  description: descs[i % descs.length],
  icon_url: null,
  downloads: Math.round(80_000_000 / (i + 1) ** 1.3),
  follows: Math.round(30_000 / (i + 1)),
  author: ["jellysquid3", "CaffeineMC", "IMS", "Prospector", "modmuss50"][i % 5],
  categories: kind === "mod" ? ["fabric", "quilt", ["optimization", "utility", "decoration"][i % 3]] : ["optimization"],
  display_categories: kind === "mod" ? [["optimization", "utility", "decoration"][i % 3]] : ["optimization"],
  project_type: kind,
  date_modified: new Date(now - (i + 1) * 3 * 86400_000).toISOString(),
  versions: ["1.21.4", "1.21.11"],
});
const versions = (pid: string) =>
  Array.from({ length: 14 }, (_, i) => ({
    id: `${pid}-v${i}`,
    project_id: pid,
    name: `Release ${14 - i}`,
    version_number: `0.${6 - Math.floor(i / 4)}.${(14 - i) % 4}`,
    version_type: i % 5 === 2 ? "beta" : "release",
    game_versions: i < 6 ? ["1.21.4", "1.21.11"] : ["1.21.1", "1.21.4"],
    loaders: ["fabric", "quilt"],
    date_published: new Date(now - i * 23 * 86400_000).toISOString(),
    dependencies: i === 0 ? [{ project_id: "P7dR8mSH", version_id: null, dependency_type: "required" }, { project_id: "mOgUt4GM", version_id: null, dependency_type: "optional" }] : [],
    files: [{ filename: "file.jar", size: 1000, primary: true, url: "" }],
  }));
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const svg = (a: string, b: string) => `data:image/svg+xml;utf8,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" width="320" height="180"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${b}"/></linearGradient></defs><rect width="320" height="180" fill="url(#g)"/></svg>`)}`;

// ---------- a fake Kite backend ----------
const ME = "069a79f444e94726a5befca90e38aaf5";
const backend = {
  coins: 750,
  owned: ["cape-kite", "cape-midnight", "wings-fairy", "hat-tophat", "hat-bandana", "hat-bandana-blue", "ears-cat", "ears-bunny", "face-shades", "boots-frost", "back-explorer"],
  loadout: { cape: "cape-kite" } as Record<string, string>,
  friends: [
    { uuid: "853c80ef3c3749fdaa49938b674adae6", name: "jeb_", status: "online", activity: "Playing on play.example.net", server: "play.example.net", lastSeen: now },
    { uuid: "61699b2ed3274a019f1e0ea8c3f06bc6", name: "Dinnerbone", status: "away", activity: "In the launcher", server: null, lastSeen: now },
    { uuid: "f7c77d999f154a66a87dc4a51ef30d19", name: "hypixel", status: "offline", activity: null, server: null, lastSeen: now - 2 * 86400_000 },
  ],
  requests: [{ id: 1, uuid: "c06f89064c8a49119c29ea1dbd1aab82", name: "MHF_Steve", direction: "incoming", at: now - 3600_000 }],
  blocked: [] as { uuid: string; name: string }[],
  messages: [
    { id: 1, from: "853c80ef3c3749fdaa49938b674adae6", to: ME, text: "want to play later?", at: now - 40 * 60_000, read: false },
    { id: 2, from: ME, to: "853c80ef3c3749fdaa49938b674adae6", text: "sure, after dinner", at: now - 38 * 60_000, read: true },
  ],
  txns: [{ id: 1, at: now - 86400_000, label: "Welcome to Kite", coins: 750 }],
};
const STORE = [
  ["cape-kite", "cape", "Kite", "rare", null],
  ["cape-aurora", "cape", "Aurora", "epic", 600],
  ["cape-galaxy", "cape", "Galaxy", "legendary", 1200],
  ["wings-angel", "wings", "Angel Wings", "epic", 600],
  ["wings-dragon", "wings", "Dragon Wings", "legendary", 1200],
  ["wings-fairy", "wings", "Fairy Wings", "rare", null],
  ["hat-crown", "hat", "Crown", "epic", 600],
  ["hat-halo", "hat", "Halo", "legendary", 1200],
  ["hat-tophat", "hat", "Top Hat", "rare", null],
  ["ears-cat", "ears", "Cat Ears", "rare", null],
  ["boots-winged", "boots", "Winged Boots", "epic", 600],
].map(([id, slot, name, rarity, price]) => ({ id, slot, name, rarity, free: price === null, price, description: `${name}, from the starter set.`, icon: `/v1/cosmetics/${id}/icon` }));
let msgId = 10;
function backendCall(method: string, path: string, body: any): any {
  const [p, query] = path.split("?");
  const qs = new URLSearchParams(query ?? "");
  const thread = (u: string) => backend.messages.filter((m) => (m.from === u && m.to === ME) || (m.from === ME && m.to === u));
  if (p === "/v1/stats") return { online: 1284 };
  if (p === "/v1/news")
    return {
      news: [
        { id: "launch", tag: "Update", title: "The launcher is out of preview", excerpt: "Friends, chat, the store and CurseForge are live.", body: ["Everything that used to show sample data now works for real."], art: undefined, image: svg("#6C8CFF", "#B36CFF"), date: now - 86400_000, size: "lg" },
        { id: "forge", tag: "Update", title: "Forge and NeoForge profiles", excerpt: "Play Forge modpacks right from the launcher.", body: ["The launcher now runs the Forge installer for you."], image: svg("#FF9A4D", "#FF5D6C"), date: now - 3 * 86400_000, size: "wide" },
      ],
    };
  if (p === "/v1/banners") return { banners: [{ id: "welcome", variant: "info", text: "Welcome to the new launcher. Your profiles moved over automatically.", link: { label: "See what's new", page: "news" } }] };
  if (p === "/v1/servers") return { servers: [{ id: "ex", name: "Example Network", address: "play.example.net", description: "Survival, skyblock and minigames.", region: "EU", genres: ["Survival", "Minigames"], partner: true, color: "#6c8cff", version: "1.21.11" }] };
  if (p === "/v1/presence/heartbeat") return { ok: true };
  if (p === "/v1/friends") return { friends: backend.friends };
  if (p === "/v1/friends/requests" && method === "GET") return { requests: backend.requests };
  if (p === "/v1/friends/requests") return { accepted: false };
  if (p.startsWith("/v1/friends/requests/")) {
    const [, , , , id, action] = p.split("/");
    const r = backend.requests.find((x) => x.id === Number(id));
    backend.requests = backend.requests.filter((x) => x.id !== Number(id));
    if (r && action === "accept") backend.friends.push({ uuid: r.uuid, name: r.name, status: "online", activity: "In the launcher", server: null, lastSeen: Date.now() });
    return { ok: true };
  }
  if (p.startsWith("/v1/friends/")) return void (backend.friends = backend.friends.filter((f) => f.uuid !== p.split("/")[3]));
  if (p === "/v1/blocks" && method === "GET") return { blocked: backend.blocked };
  if (p === "/v1/blocks") {
    const f = backend.friends.find((x) => x.uuid === body.uuid);
    backend.friends = backend.friends.filter((x) => x.uuid !== body.uuid);
    if (f) backend.blocked.push({ uuid: f.uuid, name: f.name });
    return { ok: true };
  }
  if (p.startsWith("/v1/blocks/")) return void (backend.blocked = backend.blocked.filter((b) => b.uuid !== p.split("/")[3]));
  if (p === "/v1/messages") {
    const by = new Map<string, any>();
    for (const m of backend.messages) by.set(m.from === ME ? m.to : m.from, m);
    return { threads: [...by.entries()].map(([w, last]) => ({ with: w, last, unread: thread(w).filter((m) => m.to === ME && !m.read).length })) };
  }
  if (p.endsWith("/read")) return void backend.messages.forEach((m) => m.from === p.split("/")[3] && (m.read = true));
  if (p.startsWith("/v1/messages/") && method === "GET") return { messages: thread(p.split("/")[3]) };
  if (p.startsWith("/v1/messages/")) {
    const m = { id: ++msgId, from: ME, to: p.split("/")[3], text: body.text, at: Date.now(), read: true };
    backend.messages.push(m);
    return { message: m };
  }
  if (p === "/v1/social/updates") return { now: Date.now(), messages: [], requests: Number(qs.get("since")) < now ? [] : [] };
  if (p === "/v1/me") return { uuid: ME, name: "Notch", coins: backend.coins, owned: backend.owned, loadout: backend.loadout };
  if (p === "/v1/store") return { items: STORE, owned: backend.owned, loadout: backend.loadout, coins: backend.coins };
  if (p === "/v1/store/buy") {
    const it = STORE.find((x) => x.id === body.id)!;
    if (backend.coins < (it.price as number)) throw "You don't have enough coins for that.";
    backend.coins -= it.price as number;
    backend.owned.push(it.id as string);
    backend.txns.unshift({ id: Date.now(), at: Date.now(), label: it.name as string, coins: -(it.price as number) });
    return { coins: backend.coins, owned: backend.owned };
  }
  if (p === "/v1/me/loadout") {
    for (const [k, v] of Object.entries(body)) v === null ? delete backend.loadout[k] : (backend.loadout[k] = v as string);
    return { loadout: backend.loadout };
  }
  if (p === "/v1/me/transactions") return { transactions: backend.txns };
  if (p === "/v1/coins/bundles") return { enabled: true, bundles: [{ id: "1", coins: 500, bonus: 0, price: "€2.99" }, { id: "2", coins: 1200, bonus: 100, price: "€6.99", best: true }, { id: "3", coins: 2500, bonus: 400, price: "€13.99" }] };
  if (p === "/v1/coins/checkout") return { url: "https://checkout.tebex.io/example" };
  console.warn("[mock backend] unhandled", method, path);
  return null;
}

// ---------- event plumbing (tauri's listen/emit) ----------
const callbacks = new Map<number, (e: any) => void>();
const listeners = new Map<string, Set<number>>();
let cbSeq = 0;
function emit(event: string, payload: unknown) {
  for (const id of listeners.get(event) ?? []) callbacks.get(id)?.({ event, id, payload });
}

const SAMPLE_LOG = [
  "[main/INFO] (FabricLoader) Loading Minecraft 1.21.11 with Fabric Loader 0.17.2",
  "[main/INFO]: [Kite] client 0.1.0 starting, docs at https://github.com/lorak12/kite-mc-client.",
  "[Render thread/INFO]: Sound engine started",
  "[Render thread/WARN]: Missing sound for event: minecraft:item.goat_horn.play",
  "[Render thread/INFO]: [System] [CHAT] §6[Server] §aWelcome §lback§r, Notch! Vote at §bhttps://example.com/vote",
  "[Render thread/ERROR]: Failed to load texture: java.io.FileNotFoundException: kite:cosmetics/missing.png",
  "\tat net.minecraft.client.texture.ResourceTexture.load(ResourceTexture.java:58)",
  "\tat net.minecraft.client.texture.TextureManager.loadTexture(TextureManager.java:104)",
  "\t... 12 more",
];
/** Game-log-shaped sample: header lines get a timestamp, stack-trace lines stay bare. */
function sampleLogLine(n: number) {
  const m = SAMPLE_LOG[n % SAMPLE_LOG.length];
  return m.startsWith("\t") ? m : `[${new Date().toLocaleTimeString("en-GB")}] ${m}`;
}

async function launch(profileId: string) {
  if (running) throw "Minecraft is already running";
  running = profileId;
  killed = false;
  const stages: [string, number][] = [["account", 1], ["kite-client", 1], ["version", 1], ["java", 40], ["loader", 3], ["libraries", 120], ["assets", 900]];
  for (const [stage, total] of stages) {
    for (let d = 0; d <= total; d += Math.max(1, Math.round(total / 6))) {
      if (killed) {
        running = null;
        throw "Launch cancelled";
      }
      emit("launch-progress", { stage, done: Math.min(d, total), total });
      await sleep(110);
    }
  }
  emit("launch-progress", { stage: "running", done: 1, total: 1 });
  logLines = [];
  let n = 0;
  const t = setInterval(() => {
    logLines.push(sampleLogLine(n++));
    if (n > 60 || running !== profileId) clearInterval(t);
  }, 400);
  const exit = (code: number) => {
    if (running !== profileId) return;
    running = null;
    const dir = profiles.find((p) => p.id === profileId)?.dir ?? "";
    emit("game-exit", { profile_id: profileId, code, killed, log_tail: code ? logLines.slice(-12).join("\n") : null, log_path: dir + "\\logs\\launcher-output.log", crash_report: code ? dir + "\\crash-reports\\crash.txt" : null, relaunch: null });
  };
  setTimeout(() => exit((window as any).__MOCK_CRASH__ ? 1 : 0), 30_000);
  (window as any).__mockExit = exit;
  return accounts.accounts.find((a) => a.uuid === accounts.active)?.username ?? "Player";
}

const worlds: Record<string, any[]> = {
  "skyblock-reborn": [
    { id: "Skyblock", name: "Skyblock", mode: "survival", last_played: now - 3600_000, size_bytes: 52_400_000, version: "1.21.11", icon: null },
    { id: "Creative test", name: "Creative test", mode: "creative", last_played: now - 9 * 86400_000, size_bytes: 8_100_000, version: "1.21.11", icon: null },
  ],
};

const handlers: Record<string, (a: Args) => unknown> = {
  config_status: () => ({ login: true, curseforge: false, backend: true, backend_url: "http://localhost:8787", updates: true }),
  app_info: () => ({ version: "0.1.0", data_dir: "C:\\Users\\dev\\AppData\\Roaming\\gg.kite.launcher", config_file: "C:\\Users\\dev\\AppData\\Roaming\\gg.kite.launcher\\kite.config.json", os: "windows x86_64" }),
  list_profiles: () => profiles,
  create_profile: ({ name, mcVersion, loader }) => {
    const id = name.toLowerCase().replace(/\W+/g, "-") + "-" + Math.random().toString(36).slice(2, 5);
    const p = prof(id, name, mcVersion, loader, loader === "vanilla" ? "vanilla" : "client");
    profiles = [...profiles, p];
    return p;
  },
  delete_profile: ({ id }) => void (profiles = profiles.filter((p) => p.id !== id)),
  duplicate_profile: ({ id }) => {
    const src = profiles.find((p) => p.id === id);
    const p = { ...src, id: id + "-copy", name: src.name + " copy", meta: { ...src.meta, plays: 0, lastPlayed: null } };
    profiles = [...profiles, p];
    return p;
  },
  update_profile: ({ profile }) => {
    profiles = profiles.map((p) => (p.id === profile.id ? { ...p, ...profile, meta: { ...profile.meta, plays: p.meta.plays, lastPlayed: p.meta.lastPlayed } } : p));
    return profiles.find((p) => p.id === profile.id);
  },
  set_profile_cover: ({ id }) => profiles.find((p) => p.id === id),
  get_settings: () => settings,
  set_settings: ({ game }) => (settings = game),
  mc_versions: () => ["26.3", "26.2", "26.1", "1.21.11", "1.21.10", "1.21.8", "1.21.5", "1.21.4", "1.21.3", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.1", "1.19.4", "1.18.2", "1.16.5", "1.12.2", "1.8.9"].map((id) => ({ id, kind: "release" })),
  loader_versions: ({ loader }) => (loader === "forge" ? ["47.4.0", "47.3.0", "47.2.0"] : ["0.17.2", "0.16.14", "0.17.3-beta.1"]).map((v) => ({ version: v, stable: !v.includes("beta") })),
  kite_versions: () => ["26.3", "26.2", "26.1.2", "1.21.11", "1.21.10", "1.21.8", "1.21.5", "1.21.4", "1.21.3", "1.21.1", "1.20.6", "1.20.4", "1.20.1", "1.19.4", "1.18.2", "1.16.5", "1.12.2", "1.8.9", "1.7.10"],
  list_javas: async () => (await sleep(400), [{ path: "C:\\Program Files\\Eclipse Adoptium\\jdk-21\\bin\\javaw.exe", version: "21.0.5", major: 21, vendor: "Eclipse Adoptium", arch: "amd64" }]),
  probe_java: ({ path }) => ({ path, version: "21.0.5", major: 21, vendor: "Eclipse Adoptium", arch: "amd64" }),
  search_projects: async ({ q }) => {
    await sleep(350);
    const pool = titles.map((t, i) => hit(t, i, q.kind));
    const found = pool.filter((h) => !q.query || h.title.toLowerCase().includes(q.query.toLowerCase()));
    return { hits: found.slice(q.offset, q.offset + (q.limit || 20)), total_hits: q.query ? found.length : 4820 };
  },
  project_details: async ({ id }) => {
    await sleep(250);
    const h = hit(titles[Number(id.replace(/\D/g, "")) % titles.length] ?? "Sodium", 0, "mod");
    return {
      ...h,
      id,
      followers: 31200,
      body: "## Features\n\n- **Faster rendering**: a rewritten terrain renderer\n- Fewer stutters thanks to `async` chunk building\n\nInstall it next to Fabric API.",
      published: "2020-01-12T10:00:00Z",
      updated: new Date(now - 3 * 86400_000).toISOString(),
      loaders: ["fabric", "quilt"],
      game_versions: ["1.20.1", "1.20.4", "1.21", "1.21.1", "1.21.4", "1.21.11"],
      gallery: [],
      source_url: "https://github.com/CaffeineMC/sodium",
      issues_url: null,
      wiki_url: null,
      discord_url: null,
      versions: [],
    };
  },
  project_versions: async ({ projectId }) => (await sleep(250), versions(projectId)),
  install_plan: async ({ projectId, profileId }) => {
    await sleep(300);
    const have = (installed[profileId] ?? []).some((i) => i.project_id === "P7dR8mSH");
    return { version: "0.6.5", dependencies: have || projectId.startsWith("p1") ? [] : [{ project_id: "P7dR8mSH", title: "Fabric API", version: "0.110.0" }], missing: [] };
  },
  install_content: async ({ profileId, projectId, kind }) => {
    await sleep(900);
    const title = titles[Number(projectId.replace(/\D/g, "")) % titles.length];
    const added = [lock(projectId, title, kind, title + ".jar")];
    installed[profileId] = [...(installed[profileId] ?? []), ...added];
    return added;
  },
  install_modpack: async ({ projectId }) => {
    await sleep(1500);
    const p = prof("pack-" + projectId, "Fabulously Optimized", "1.21.4", "fabric", "modpack");
    profiles = [...profiles.filter((x) => x.id !== p.id), p];
    return p;
  },
  import_pack: () => {
    throw "Importing needs the desktop app";
  },
  list_installed: ({ profileId }) => installed[profileId] ?? [],
  list_local_files: ({ profileId }) => localFiles[profileId] ?? [],
  set_content_enabled: ({ profileId, id, enabled }) => {
    for (const i of installed[profileId] ?? []) if (i.project_id === id) i.enabled = enabled;
    for (const f of localFiles[profileId] ?? []) if (`file:${f.project_type}:${f.filename}` === id) f.enabled = enabled;
  },
  remove_local_file: ({ profileId, id }) => void (localFiles[profileId] = (localFiles[profileId] ?? []).filter((f) => `file:${f.project_type}:${f.filename}` !== id)),
  adopt_local_files: async () => (await sleep(600), 0),
  uninstall_content: ({ profileId, projectId }) => void (installed[profileId] = (installed[profileId] ?? []).filter((i) => i.project_id !== projectId)),
  check_updates: ({ profileId }) => ((installed[profileId] ?? []).some((i) => i.project_id === "gvQqBUqZ") ? [{ project_id: "gvQqBUqZ", title: "Lithium", current: "v3", latest: "0.15.0" }] : []),
  update_all: () => 1,
  update_content: () => true,
  export_candidates: () => ["config", "options.txt", "servers.dat", "saves"],
  export_mrpack: async ({ dest }) => (await sleep(800), { path: dest, downloads: 4, overrides: 12 }),
  list_worlds: ({ profileId }) => worlds[profileId] ?? [],
  delete_world: ({ profileId, world }) => void (worlds[profileId] = (worlds[profileId] ?? []).filter((w) => w.id !== world)),
  backup_world: async ({ world }) => (await sleep(700), { name: `${world}_2026-10-09_12-00-00.zip`, path: "C:\\backups\\x.zip", at: Date.now(), size_bytes: 21_000_000 }),
  list_backups: () => [],
  restore_backup: () => "Skyblock (2)",
  list_servers: ({ profileId }) => (profileId === "skyblock-reborn" ? [{ index: 0, name: "Example Network", address: "play.example.net", icon: null }] : []),
  add_server: () => null,
  remove_server: () => null,
  ping_server: async ({ address }) => {
    await sleep(300 + Math.random() * 500);
    if (address.includes("offline")) throw "The server didn't answer";
    return { online: 1532, max: 5000, version: "1.21.11", motd: "Survival, skyblock and minigames", favicon: null, latency_ms: 38 };
  },
  list_screenshots: ({ profileId }) => (profileId === "skyblock-reborn" ? [0, 1, 2].map((i) => ({ name: `2026-10-0${i + 1}_12.00.00.png`, path: svg(["#3DDC97", "#6C8CFF", "#FFC857"][i], "#0b0d12"), at: now - i * 86400_000, size_bytes: 2_000_000 })) : []),
  delete_screenshot: () => null,
  copy_screenshot: () => null,
  list_accounts: () => accounts,
  switch_account: ({ uuid }) => {
    accounts = { ...accounts, active: uuid };
    return accounts.accounts.find((a) => a.uuid === uuid);
  },
  remove_account: ({ uuid }) => void (accounts = { accounts: accounts.accounts.filter((a) => a.uuid !== uuid), active: accounts.active === uuid ? null : accounts.active }),
  offline_login: ({ name }) => {
    const a = { uuid: "off-" + name, username: name, kind: "offline" };
    accounts = { accounts: [...accounts.accounts.filter((x) => x.uuid !== a.uuid), a], active: a.uuid };
    return a;
  },
  start_login: () => ({ user_code: "AB12-CD34", verification_uri: "https://www.microsoft.com/link" }),
  finish_login: async () => {
    await sleep(4000);
    const a = { uuid: "853c80ef3c3749fdaa49938b674adae6", username: "jeb_", kind: "microsoft" };
    accounts = { accounts: [...accounts.accounts.filter((x) => x.uuid !== a.uuid), a], active: a.uuid };
    return a;
  },
  browser_login: async () => {
    await sleep(3000);
    return handlers.finish_login({});
  },
  cancel_login: () => null,
  logout: () => void (accounts = { accounts: accounts.accounts.filter((a) => a.uuid !== accounts.active), active: null }),
  log_line: () => null,
  account_name: () => accounts.accounts.find((a) => a.uuid === accounts.active)?.username ?? null,
  // jeb_'s real skin, so the mock shows a face drawn from a texture.
  skin_url: ({ uuid }) => (String(uuid).replace(/-/g, "") === "853c80ef3c3749fdaa49938b674adae6" ? "https://textures.minecraft.net/texture/7fd9ba42a7c81eeea22f1524271ae85a8e045ce0af5a6ae16c6406ae917e68b5" : null),
  launch_profile: ({ profileId }) => launch(profileId),
  kill_game: () => {
    killed = true;
    (window as any).__mockExit?.(1);
  },
  running_profile: () => running,
  read_game_log: ({ offset }) => ({ lines: logLines.slice(offset), offset: logLines.length, profile_id: running ?? "skyblock-reborn" }),
  upload_logs: async () => (await sleep(900), { launcher: "https://mclo.gs/AbCdEf1", game: "https://mclo.gs/XyZ9876", profile: "Skyblock Reborn" }),
  backend_call: async ({ method, path, body }) => (await sleep(120), backendCall(method, path, body)),
  backend_status: () => ({ configured: true, signed_in: !!accounts.active, uuid: ME, name: "Notch", error: null }),
  check_update: async () => (await sleep(800), { current: "0.1.0", latest: "0.2.0", available: true, notes: "Faster launches and Forge support.", installable: true }),
  install_update: async () => (await sleep(1500), null),
  open_path: ({ kind }) => console.info("[mock] open folder", kind),
  reveal_path: ({ path }) => console.info("[mock] reveal", path),
  open_url: ({ url }) => void window.open(url, "_blank"),
  restart_app: () => location.reload(),
  open_logs_window: () => void window.open(location.pathname + "?view=logs", "kite-logs", "width=920,height=580"),
  system_memory_mb: () => 32768,
};

// The logs window is a separate page: give it its own sample stream.
if (location.search.includes("view=logs")) {
  let i = 0;
  setInterval(() => logLines.push(sampleLogLine(i++)), 300);
}

const w = window as unknown as Record<string, unknown>;
if (!w.__TAURI_INTERNALS__) {
  w.__TAURI_INTERNALS__ = {
    transformCallback: (cb: (e: unknown) => void) => {
      const id = ++cbSeq;
      callbacks.set(id, cb);
      return id;
    },
    unregisterCallback: (id: number) => callbacks.delete(id),
    convertFileSrc: (path: string) => path,
    invoke: async (cmd: string, args: Args = {}) => {
      if (cmd === "plugin:event|listen") {
        const set = listeners.get(args.event) ?? new Set();
        set.add(args.handler);
        listeners.set(args.event, set);
        return args.handler;
      }
      if (cmd === "plugin:event|unlisten") {
        listeners.get(args.event)?.delete(args.eventId);
        return null;
      }
      if (cmd.startsWith("plugin:")) return null;
      const h = handlers[cmd];
      if (!h) {
        console.warn("[mock] unhandled command", cmd, args);
        return null;
      }
      return h(args);
    },
  };
  w.__TAURI_EVENT_PLUGIN_INTERNALS__ = { unregisterListener: () => {} };
}
export {};
