import { CATALOG, Slot } from "./catalog.js";

export type Loadout = Partial<Record<Slot, string>>;

/** Persistence boundary. Swap MemoryStore for a Postgres/Redis implementation without touching routes. */
export interface Store {
  owned(uuid: string): Promise<Set<string>>;
  grant(uuid: string, cosmeticId: string): Promise<void>;
  revoke(uuid: string, cosmeticId: string): Promise<void>;
  getLoadout(uuid: string): Promise<Loadout>;
  setLoadout(uuid: string, l: Loadout): Promise<void>;
  touchPresence(uuid: string, server: string | undefined, now: number): Promise<void>;
  online(uuids: string[], now: number, ttlMs: number): Promise<string[]>;
  setManifest(channel: string, payload: string, signature: string | null): Promise<void>;
  getManifest(channel: string): Promise<{ payload: string; signature: string | null } | undefined>;
}

export class MemoryStore implements Store {
  private grants = new Map<string, Set<string>>();
  private loadouts = new Map<string, Loadout>();
  private seen = new Map<string, { at: number; server?: string }>();
  private manifests = new Map<string, { payload: string; signature: string | null }>();
  private free = new Set(CATALOG.filter((c) => c.free).map((c) => c.id));

  async owned(uuid: string) {
    return new Set([...this.free, ...(this.grants.get(uuid) ?? [])]);
  }
  async grant(uuid: string, id: string) {
    (this.grants.get(uuid) ?? this.grants.set(uuid, new Set()).get(uuid)!).add(id);
  }
  async revoke(uuid: string, id: string) {
    this.grants.get(uuid)?.delete(id);
    // Unequip anything the user no longer owns.
    const l = this.loadouts.get(uuid);
    if (l) for (const [slot, cid] of Object.entries(l)) if (cid === id && !this.free.has(id)) delete l[slot as Slot];
  }
  async getLoadout(uuid: string) {
    return { ...(this.loadouts.get(uuid) ?? {}) };
  }
  async setLoadout(uuid: string, l: Loadout) {
    this.loadouts.set(uuid, { ...l });
  }
  async touchPresence(uuid: string, server: string | undefined, now: number) {
    this.seen.set(uuid, { at: now, server });
  }
  async online(uuids: string[], now: number, ttlMs: number) {
    return uuids.filter((u) => {
      const s = this.seen.get(u);
      return s && now - s.at <= ttlMs;
    });
  }
  async setManifest(channel: string, payload: string, signature: string | null) {
    this.manifests.set(channel, { payload, signature });
  }
  async getManifest(channel: string) {
    return this.manifests.get(channel);
  }
}
