export const SLOTS = ["cape", "wings", "hat", "shield", "backpack"] as const;
export type Slot = (typeof SLOTS)[number];

export interface Cosmetic {
  id: string;
  slot: Slot;
  name: string;
  rarity: "common" | "rare" | "epic" | "legendary";
  /** Owned by everyone without a grant. */
  free: boolean;
  /** CDN path of the model/texture bundle (see the cosmetics asset pipeline). */
  asset: string;
}

export const CATALOG: Cosmetic[] = [
  { id: "cape-aether", slot: "cape", name: "Aether Cape", rarity: "common", free: true, asset: "capes/aether.png" },
  { id: "cape-founder", slot: "cape", name: "Founder Cape", rarity: "legendary", free: false, asset: "capes/founder.png" },
  { id: "wings-angel", slot: "wings", name: "Angel Wings", rarity: "epic", free: false, asset: "wings/angel.json" },
  { id: "wings-dragon", slot: "wings", name: "Dragon Wings", rarity: "legendary", free: false, asset: "wings/dragon.json" },
  { id: "hat-halo", slot: "hat", name: "Halo", rarity: "rare", free: false, asset: "hats/halo.json" },
  { id: "hat-crown", slot: "hat", name: "Crown", rarity: "epic", free: false, asset: "hats/crown.json" },
  { id: "shield-aether", slot: "shield", name: "Aether Shield", rarity: "rare", free: false, asset: "shields/aether.png" },
  { id: "backpack-explorer", slot: "backpack", name: "Explorer Pack", rarity: "common", free: true, asset: "backpacks/explorer.json" },
];

export const byId = new Map(CATALOG.map((c) => [c.id, c]));
