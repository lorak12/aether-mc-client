import { randomBytes } from "node:crypto";
import { z } from "zod";

/** A coin bundle sold through Tebex: `id` is the Tebex package id. */
export const Bundle = z.object({ id: z.string().min(1), coins: z.number().int().positive(), bonus: z.number().int().min(0).default(0), price: z.string().min(1), best: z.boolean().optional() });
export type Bundle = z.infer<typeof Bundle>;

export interface Tebex {
  /** Headless API public token (Tebex creator panel > Integrations > Headless API). */
  publicToken: string;
  /** Webhook secret (Integrations > Webhooks). */
  webhookSecret: string;
}

export interface Config {
  port: number;
  jwtSecret: Uint8Array;
  /** SQLite file. ":memory:" keeps everything in RAM. */
  dbPath: string;
  /** Hex-encoded 32-byte Ed25519 seed for signing runtime manifests. Optional. */
  manifestSeedHex?: string;
  /** Bearer token for /v1/admin/*. Admin routes are disabled when unset. */
  adminToken?: string;
  /** Extra names with unlimited coins on top of src/admins.ts (KITE_ADMINS, comma separated). */
  admins: string[];
  presenceTtlMs: number;
  /** Development: let offline (unverified) accounts sign in. Never in production. */
  allowOffline: boolean;
  /** Coins a new account starts with. */
  welcomeCoins: number;
  tebex?: Tebex;
  bundles: Bundle[];
  /** Public base URL of this server, for checkout return pages. */
  publicUrl: string;
  /** Folder with the client's cosmetic assets (icons for the store). */
  cosmeticsDir: string;
}

export function loadConfig(env = process.env): Config {
  const secret = env.KITE_JWT_SECRET;
  const production = env.NODE_ENV === "production";
  if (!secret && production) throw new Error("KITE_JWT_SECRET is required in production");
  const allowOffline = env.KITE_ALLOW_OFFLINE === "1";
  if (allowOffline && production) throw new Error("KITE_ALLOW_OFFLINE must not be set in production");
  const port = Number(env.PORT ?? 8787);
  let bundles: Bundle[] = [];
  if (env.KITE_COIN_BUNDLES) bundles = z.array(Bundle).parse(JSON.parse(env.KITE_COIN_BUNDLES));
  const tebex = env.KITE_TEBEX_PUBLIC_TOKEN && env.KITE_TEBEX_WEBHOOK_SECRET ? { publicToken: env.KITE_TEBEX_PUBLIC_TOKEN, webhookSecret: env.KITE_TEBEX_WEBHOOK_SECRET } : undefined;
  return {
    port,
    // Dev fallback: random per process (tokens die on restart), never a guessable constant.
    jwtSecret: new TextEncoder().encode(secret ?? randomBytes(32).toString("hex")),
    dbPath: env.KITE_DB ?? "kite.db",
    manifestSeedHex: env.KITE_MANIFEST_SEED,
    adminToken: env.KITE_ADMIN_TOKEN,
    admins: (env.KITE_ADMINS ?? "").split(",").map((n) => n.trim()).filter(Boolean),
    presenceTtlMs: 90_000,
    allowOffline,
    welcomeCoins: Number(env.KITE_WELCOME_COINS ?? 0),
    tebex,
    bundles,
    publicUrl: (env.KITE_PUBLIC_URL ?? `http://localhost:${port}`).replace(/\/$/, ""),
    cosmeticsDir: env.KITE_COSMETICS_DIR ?? new URL("../../runtime/core/src/main/resources/kite/cosmetics/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1"),
  };
}
