import type { Kind, Loader, Sort } from "../../api/types";

export const KINDS: { id: Kind; label: string; singular: string }[] = [
  { id: "mod", label: "Mods", singular: "mod" },
  { id: "modpack", label: "Modpacks", singular: "modpack" },
  { id: "resourcepack", label: "Resource packs", singular: "resource pack" },
  { id: "shader", label: "Shader packs", singular: "shader pack" },
  { id: "datapack", label: "Datapacks", singular: "datapack" },
];

export const SORTS: { id: Sort; label: string }[] = [
  { id: "relevance", label: "Relevance" },
  { id: "downloads", label: "Popularity" },
  { id: "follows", label: "Follows" },
  { id: "updated", label: "Recently updated" },
  { id: "newest", label: "Newest" },
];

/** Loader filter values per content kind (Modrinth category slugs). */
export const LOADERS: Record<Kind, string[]> = {
  mod: ["fabric", "quilt", "forge", "neoforge"],
  modpack: ["fabric", "quilt", "forge", "neoforge"],
  shader: ["iris", "optifine", "canvas", "vanilla"],
  resourcepack: [],
  datapack: [],
};

/** Category filters per kind (Modrinth category slugs). */
export const CATEGORIES: Record<Kind, string[]> = {
  mod: ["adventure", "decoration", "economy", "equipment", "food", "game-mechanics", "library", "magic", "management", "minigame", "mobs", "optimization", "social", "storage", "technology", "transportation", "utility", "worldgen"],
  modpack: ["adventure", "challenging", "combat", "kitchen-sink", "lightweight", "magic", "multiplayer", "optimization", "quests", "technology"],
  resourcepack: ["combat", "cursed", "decoration", "modded", "realistic", "simplistic", "themed", "tweaks", "utility", "vanilla-like"],
  shader: ["cartoon", "fantasy", "realistic", "semi-realistic", "vanilla-like", "atmosphere", "bloom", "colored-lighting", "foliage", "path-tracing", "pbr", "reflections", "shadows"],
  datapack: ["adventure", "decoration", "economy", "equipment", "food", "game-mechanics", "magic", "minigame", "mobs", "optimization", "technology", "utility", "worldgen"],
};

const LOADER_SLUGS = new Set(["fabric", "quilt", "forge", "neoforge", "iris", "optifine", "canvas", "vanilla", "minecraft", "datapack", "liteloader", "rift", "modloader", "bukkit", "paper", "spigot", "velocity", "bungeecord", "folia", "purpur", "sponge", "waterfall"]);
export const isLoaderSlug = (c: string) => LOADER_SLUGS.has(c);

/** Default loader filter for a profile: its own loader when it has one that applies. */
export const loaderFor = (kind: Kind, loader: Loader | undefined) => (loader && LOADERS[kind].includes(loader) ? [loader] : []);
