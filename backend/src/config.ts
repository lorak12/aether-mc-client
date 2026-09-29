import { randomBytes } from "node:crypto";

export interface Config {
  port: number;
  jwtSecret: Uint8Array;
  /** Hex-encoded 32-byte Ed25519 seed for signing runtime manifests. Optional. */
  manifestSeedHex?: string;
  /** Bearer token for /v1/admin/*. Admin routes are disabled when unset. */
  adminToken?: string;
  presenceTtlMs: number;
}

export function loadConfig(env = process.env): Config {
  const secret = env.AETHER_JWT_SECRET;
  if (!secret && env.NODE_ENV === "production") throw new Error("AETHER_JWT_SECRET is required in production");
  return {
    port: Number(env.PORT ?? 8787),
    // Dev fallback: random per process (tokens die on restart), never a guessable constant.
    jwtSecret: new TextEncoder().encode(secret ?? randomBytes(32).toString("hex")),
    manifestSeedHex: env.AETHER_MANIFEST_SEED,
    adminToken: env.AETHER_ADMIN_TOKEN,
    presenceTtlMs: 90_000,
  };
}
