import { z } from "zod";
import { CATALOG, SLOTS, byId, type Cosmetic } from "./catalog.js";
import type { Store } from "./store.js";

/**
 * Cosmetics admins publish from the in-game importer: metadata plus the files the client loads (cosmetic.json,
 * textures, model). Kept in the key/value table (`custom:index` and `custom:<id>`); clients download the bundle
 * (GET /v1/cosmetics/:id/bundle) and show them like the built-in set, so everyone sees them and can buy them.
 */
export interface CustomCosmetic extends Cosmetic {
  custom: true;
  /** Bumped on every publish so clients fetch the new files. */
  version: number;
  author: string;
  publishedAt: number;
}

interface Stored {
  meta: CustomCosmetic;
  /** File name -> base64. */
  files: Record<string, string>;
}

export const CUSTOM_ID = /^custom-[a-z0-9-]{1,40}$/;
const FILE = /^[A-Za-z0-9_.-]{1,64}$/;
/** Decoded size of one bundle. */
export const MAX_BUNDLE_BYTES = 6 * 1024 * 1024;

export const Publish = z.object({
  id: z.string().regex(CUSTOM_ID),
  slot: z.enum(SLOTS),
  name: z.string().trim().min(1).max(40),
  rarity: z.enum(["common", "rare", "epic", "legendary"]),
  /** Coins; 0 = free to claim. */
  price: z.number().int().min(0).max(1_000_000),
  description: z.string().max(200).default(""),
  files: z.record(z.string().regex(FILE), z.string().max(MAX_BUNDLE_BYTES * 2)),
});
export type Publish = z.infer<typeof Publish>;

export class Customs {
  private cache?: Map<string, Stored>;

  constructor(private store: Store) {}

  private async load(): Promise<Map<string, Stored>> {
    if (this.cache) return this.cache;
    const out = new Map<string, Stored>();
    const index = JSON.parse((await this.store.getKv("custom:index")) || "[]") as string[];
    for (const id of index) {
      const raw = await this.store.getKv(`custom:${id}`);
      if (raw) out.set(id, JSON.parse(raw) as Stored);
    }
    this.cache = out;
    return out;
  }

  async list(): Promise<CustomCosmetic[]> {
    return [...(await this.load()).values()].map((s) => s.meta);
  }

  async get(id: string): Promise<Stored | undefined> {
    return (await this.load()).get(id);
  }

  /** Add or replace (same id) a published cosmetic. */
  async publish(p: Publish, author: string, now: number): Promise<CustomCosmetic> {
    if (!p.files["cosmetic.json"]) throw new Error("missing_cosmetic_json");
    const bytes = Object.values(p.files).reduce((n, b) => n + Buffer.from(b, "base64").length, 0);
    if (bytes > MAX_BUNDLE_BYTES) throw new Error("too_large");
    const all = await this.load();
    const meta: CustomCosmetic = {
      id: p.id,
      slot: p.slot,
      name: p.name,
      rarity: p.rarity,
      free: false,
      price: p.price,
      description: p.description,
      asset: p.id,
      custom: true,
      version: (all.get(p.id)?.meta.version ?? 0) + 1,
      author,
      publishedAt: now,
    };
    const stored: Stored = { meta, files: p.files };
    await this.store.setKv(`custom:${p.id}`, JSON.stringify(stored));
    all.set(p.id, stored);
    await this.store.setKv("custom:index", JSON.stringify([...all.keys()]));
    return meta;
  }

  async remove(id: string): Promise<boolean> {
    const all = await this.load();
    if (!all.delete(id)) return false;
    await this.store.setKv("custom:index", JSON.stringify([...all.keys()]));
    await this.store.setKv(`custom:${id}`, "");
    return true;
  }

  /** Built-in and published cosmetics. */
  async all(): Promise<Cosmetic[]> {
    return [...CATALOG, ...(await this.list())];
  }

  async cosmetic(id: string): Promise<Cosmetic | undefined> {
    return byId.get(id) ?? (await this.get(id))?.meta;
  }
}
