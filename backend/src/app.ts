import Fastify, { FastifyReply, FastifyRequest } from "fastify";
import { SignJWT, jwtVerify } from "jose";
import { createHash, createPrivateKey, randomBytes, sign as edSign } from "node:crypto";
import { z } from "zod";
import { isAdmin, UNLIMITED_COINS } from "./admins.js";
import { SLOTS } from "./catalog.js";
import { Customs, Publish, CUSTOM_ID } from "./custom.js";
import { Config } from "./config.js";
import { Verifier, normUuid } from "./mojang.js";
import { registerFeeds } from "./routes/feeds.js";
import { registerSocial } from "./routes/social.js";
import { registerStore } from "./routes/store.js";
import { Store, StoreError } from "./store.js";
import type { Fetch } from "./tebex.js";

export const UUID = z.string().regex(/^[0-9a-fA-F-]{32,36}$/);
export const NAME = z.string().regex(/^[A-Za-z0-9_]{1,16}$/);

/** PKCS8 wrapper for a raw 32-byte Ed25519 seed. */
function edKey(seedHex: string) {
  const der = Buffer.concat([Buffer.from("302e020100300506032b657004220420", "hex"), Buffer.from(seedHex, "hex")]);
  return createPrivateKey({ key: der, format: "der", type: "pkcs8" });
}

/** The uuid the launcher gives an offline account (SHA-1 of "OfflinePlayer:<name>", version 3 bits). */
export function offlineUuid(name: string) {
  const b = createHash("sha1").update(`OfflinePlayer:${name}`).digest().subarray(0, 16);
  b[6] = (b[6] & 0x0f) | 0x30;
  b[8] = (b[8] & 0x3f) | 0x80;
  return b.toString("hex");
}

/** What route modules get. */
export interface Ctx {
  cfg: Config;
  store: Store;
  now: () => number;
  fetch: Fetch;
  /** The caller's uuid, or null (no/invalid token). */
  authed(req: FastifyRequest): Promise<string | null>;
  /** The caller's uuid; replies 401 and returns null when signed out. */
  user(req: FastifyRequest, reply: FastifyReply): Promise<string | null>;
  admin(req: FastifyRequest): boolean;
  /** The balance to show: admins (src/admins.ts) have unlimited coins. */
  wallet(uuid: string): Promise<{ coins: number; unlimited: boolean }>;
  /** Whether {@code uuid}'s account name is on the admin list. */
  unlimited(uuid: string): Promise<boolean>;
  /** Built-in plus published cosmetics. */
  customs: Customs;
}

declare module "fastify" {
  interface FastifyRequest {
    rawBody?: string;
  }
}

export function buildApp(cfg: Config, store: Store, verify: Verifier, now: () => number = Date.now, fetchImpl: Fetch = fetch) {
  const app = Fastify({ logger: false, bodyLimit: 64 * 1024, trustProxy: true });

  // Keep the raw body (webhook signatures) and accept empty JSON bodies as {}.
  app.addContentTypeParser("application/json", { parseAs: "string" }, (req, body, done) => {
    req.rawBody = body as string;
    if (!(body as string).trim()) return done(null, {});
    try {
      done(null, JSON.parse(body as string));
    } catch {
      const err = new Error("bad json") as Error & { statusCode: number };
      err.statusCode = 400;
      done(err, undefined);
    }
  });

  app.setErrorHandler((err, _req, reply) => {
    if (err instanceof StoreError) return reply.code(err.status).send({ error: err.code });
    const status = (err as { statusCode?: number }).statusCode ?? 500;
    if (status >= 500) console.error(err);
    return reply.code(status).send({ error: status >= 500 ? "server_error" : "bad_request" });
  });

  // Per-IP fixed window rate limit (in-memory; put Redis behind this for multi-instance deployments).
  const hits = new Map<string, { n: number; reset: number }>();
  app.addHook("onRequest", async (req, reply) => {
    const t = now();
    const h = hits.get(req.ip);
    if (!h || t > h.reset) hits.set(req.ip, { n: 1, reset: t + 60_000 });
    else if (++h.n > 600) return reply.code(429).send({ error: "rate_limited" });
  });

  // Nonce the client passes to Mojang's join call as serverId (prevents replaying someone else's join).
  const nonces = new Map<string, number>();

  async function issue(uuid: string, name: string) {
    const first = !(await store.user(uuid));
    await store.upsertUser(uuid, name, now());
    if (first && cfg.welcomeCoins > 0) await store.addCoins(uuid, cfg.welcomeCoins, "Welcome to Kite", now(), `welcome:${uuid}`);
    const token = await new SignJWT({ name })
      .setProtectedHeader({ alg: "HS256" })
      .setSubject(uuid)
      .setIssuedAt(Math.floor(now() / 1000))
      .setExpirationTime(Math.floor(now() / 1000) + 3600)
      .sign(cfg.jwtSecret);
    return { token, uuid, name };
  }

  async function authed(req: FastifyRequest): Promise<string | null> {
    const h = req.headers["authorization"];
    if (typeof h !== "string" || !h.startsWith("Bearer ")) return null;
    try {
      const { payload } = await jwtVerify(h.slice(7), cfg.jwtSecret, { algorithms: ["HS256"], currentDate: new Date(now()) });
      return payload.sub ?? null;
    } catch {
      return null;
    }
  }
  const customs = new Customs(store);
  const ctx: Ctx = {
    customs,
    cfg,
    store,
    now,
    fetch: fetchImpl,
    authed,
    async user(req, reply) {
      const u = await authed(req);
      if (!u) reply.code(401).send({ error: "unauthorized" });
      return u;
    },
    admin: (req) => !!cfg.adminToken && req.headers["authorization"] === `Bearer ${cfg.adminToken}`,
    async unlimited(uuid) {
      return isAdmin((await store.user(uuid))?.name, cfg.admins);
    },
    async wallet(uuid) {
      if (await ctx.unlimited(uuid)) return { coins: UNLIMITED_COINS, unlimited: true };
      return { coins: await store.balance(uuid), unlimited: false };
    },
  };

  app.get("/v1/health", async () => ({ ok: true }));

  app.post("/v1/auth/nonce", async () => {
    const n = randomBytes(16).toString("hex");
    nonces.set(n, now() + 60_000);
    for (const [k, exp] of nonces) if (exp < now()) nonces.delete(k);
    return { serverId: n };
  });

  app.post("/v1/auth/mojang", async (req, reply) => {
    const body = z.object({ username: z.string().min(1).max(16), serverId: z.string().length(32) }).safeParse(req.body);
    if (!body.success) return reply.code(400).send({ error: "bad_request" });
    const exp = nonces.get(body.data.serverId);
    nonces.delete(body.data.serverId); // single use
    if (!exp || exp < now()) return reply.code(401).send({ error: "bad_nonce" });
    const id = await verify(body.data.username, body.data.serverId);
    if (!id) return reply.code(401).send({ error: "not_verified" });
    return issue(normUuid(id.uuid), id.name);
  });

  // Development only: offline accounts can't prove ownership, so this is off unless KITE_ALLOW_OFFLINE=1.
  app.post("/v1/auth/offline", async (req, reply) => {
    if (!cfg.allowOffline) return reply.code(403).send({ error: "offline_disabled" });
    const body = z.object({ username: NAME }).safeParse(req.body);
    if (!body.success) return reply.code(400).send({ error: "bad_request" });
    return issue(offlineUuid(body.data.username), body.data.username);
  });

  app.get("/v1/cosmetics", async () => ({ slots: SLOTS, cosmetics: await customs.all() }));

  // The files of a published cosmetic, for clients to load it like a built-in one.
  app.get("/v1/cosmetics/:id/bundle", async (req, reply) => {
    const c = await customs.get((req.params as { id: string }).id);
    if (!c) return reply.code(404).send({ error: "not_found" });
    return reply.header("cache-control", "public, max-age=300").send({ id: c.meta.id, version: c.meta.version, files: c.files });
  });

  app.get("/v1/me", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    const u = await store.user(uuid);
    return { uuid, name: u?.name ?? null, ...(await ctx.wallet(uuid)), admin: await ctx.unlimited(uuid), owned: [...(await store.owned(uuid))], loadout: await store.getLoadout(uuid) };
  });

  app.put("/v1/me/loadout", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    const parsed = z.record(z.enum(SLOTS), z.string().nullable()).safeParse(req.body);
    if (!parsed.success) return reply.code(400).send({ error: "bad_request" });
    // The game sends its whole loadout with ?lenient=1: items it can't wear (not owned, unknown, wrong slot) are left
    // out and listed in `rejected` instead of failing the request, so the rest still syncs.
    const lenient = (req.query as { lenient?: string } | undefined)?.lenient === "1";
    const owned = await store.owned(uuid);
    const next = await store.getLoadout(uuid);
    const rejected: string[] = [];
    for (const [slot, id] of Object.entries(parsed.data) as [(typeof SLOTS)[number], string | null][]) {
      if (id === null) {
        delete next[slot];
        continue;
      }
      const c = await customs.cosmetic(id);
      if (!c || c.slot !== slot) {
        if (!lenient) return reply.code(400).send({ error: "wrong_slot", slot });
        rejected.push(id);
        continue;
      }
      if (!owned.has(id)) {
        if (!lenient) return reply.code(403).send({ error: "not_owned", id });
        rejected.push(id);
        continue;
      }
      next[slot] = id;
    }
    await store.setLoadout(uuid, next);
    return lenient ? { loadout: next, rejected } : { loadout: next };
  });

  // Heartbeats come from the game (`{server}`) and from the launcher (`{source: "launcher", status, activity}`).
  app.post("/v1/presence/heartbeat", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    const body = z
      .object({
        server: z.string().max(255).optional(),
        source: z.enum(["launcher", "game"]).optional(),
        status: z.enum(["online", "away", "busy", "invisible"]).optional(),
        activity: z.string().max(80).nullable().optional(),
      })
      .safeParse(req.body ?? {});
    if (!body.success) return reply.code(400).send({ error: "bad_request" });
    const b = body.data;
    await store.touchPresence(uuid, b.source ?? "game", { status: b.status, activity: b.activity ?? null, server: b.server?.trim() || null }, now());
    return { ok: true, ttlMs: cfg.presenceTtlMs };
  });

  // Which of these players are client users, and what are they wearing? Non-users are omitted, so the badge
  // and cosmetics only ever show for people running the client.
  app.post("/v1/presence/lookup", async (req, reply) => {
    if (!(await ctx.user(req, reply))) return;
    const body = z.object({ uuids: z.array(UUID).max(200) }).safeParse(req.body);
    if (!body.success) return reply.code(400).send({ error: "bad_request" });
    const ids = body.data.uuids.map(normUuid);
    const online = await store.online(ids, now(), cfg.presenceTtlMs);
    const users: Record<string, { badge: true; loadout: Record<string, string> }> = {};
    for (const u of online) users[u] = { badge: true, loadout: (await store.getLoadout(u)) as Record<string, string> };
    return { users };
  });

  app.get("/v1/stats", async () => ({ online: await store.countOnline(now(), cfg.presenceTtlMs) }));

  const Channel = z.enum(["nightly", "beta", "stable"]);

  app.get("/v1/manifest/:channel", async (req, reply) => {
    const c = Channel.safeParse((req.params as { channel?: string }).channel);
    if (!c.success) return reply.code(400).send({ error: "bad_channel" });
    const m = await store.getManifest(c.data);
    if (!m) return reply.code(404).send({ error: "no_manifest" });
    return m;
  });

  // ---- admin ----
  app.post("/v1/admin/grant", async (req, reply) => {
    if (!ctx.admin(req)) return reply.code(401).send({ error: "unauthorized" });
    const b = z.object({ uuid: UUID, cosmeticId: z.string(), revoke: z.boolean().optional() }).safeParse(req.body);
    if (!b.success || !(await customs.cosmetic(b.data.cosmeticId))) return reply.code(400).send({ error: "bad_request" });
    const u = normUuid(b.data.uuid);
    if (b.data.revoke) await store.revoke(u, b.data.cosmeticId);
    else await store.grant(u, b.data.cosmeticId);
    return { ok: true };
  });

  app.post("/v1/admin/coins", async (req, reply) => {
    if (!ctx.admin(req)) return reply.code(401).send({ error: "unauthorized" });
    const b = z.object({ uuid: UUID, amount: z.number().int(), label: z.string().min(1).max(80) }).safeParse(req.body);
    if (!b.success) return reply.code(400).send({ error: "bad_request" });
    const u = normUuid(b.data.uuid);
    await store.addCoins(u, b.data.amount, b.data.label, now());
    return { ok: true, coins: await store.balance(u) };
  });

  // Publishing cosmetics: the admin token, or a signed-in admin account (src/admins.ts) from the in-game importer.
  const canPublish = async (req: FastifyRequest) => ctx.admin(req) || (await authedAdmin(req));
  async function authedAdmin(req: FastifyRequest) {
    const uuid = await authed(req);
    return !!uuid && (await ctx.unlimited(uuid));
  }

  app.post("/v1/admin/cosmetics", { bodyLimit: 10 * 1024 * 1024 }, async (req, reply) => {
    if (!(await canPublish(req))) return reply.code(403).send({ error: "not_admin" });
    const b = Publish.safeParse(req.body);
    if (!b.success) return reply.code(400).send({ error: "bad_request", issues: b.error.issues.map((i) => i.path.join(".") + ": " + i.message) });
    const uuid = await authed(req);
    const author = uuid ? ((await store.user(uuid))?.name ?? uuid) : "admin";
    try {
      const meta = await customs.publish(b.data, author, now());
      return { ok: true, cosmetic: meta };
    } catch (e) {
      return reply.code(400).send({ error: (e as Error).message });
    }
  });

  app.delete("/v1/admin/cosmetics/:id", async (req, reply) => {
    if (!(await canPublish(req))) return reply.code(403).send({ error: "not_admin" });
    const id = (req.params as { id: string }).id;
    if (!CUSTOM_ID.test(id)) return reply.code(400).send({ error: "not_custom" });
    return (await customs.remove(id)) ? { ok: true } : reply.code(404).send({ error: "not_found" });
  });

  app.put("/v1/admin/manifest/:channel", async (req, reply) => {
    if (!ctx.admin(req)) return reply.code(401).send({ error: "unauthorized" });
    if (!cfg.manifestSeedHex) return reply.code(503).send({ error: "manifest_signing_not_configured" });
    const c = Channel.safeParse((req.params as { channel?: string }).channel);
    if (!c.success) return reply.code(400).send({ error: "bad_channel" });
    const payload = JSON.stringify(req.body);
    const signature = edSign(null, Buffer.from(payload), edKey(cfg.manifestSeedHex)).toString("hex");
    await store.setManifest(c.data, payload, signature);
    return { ok: true, signature };
  });

  registerSocial(app, ctx);
  registerStore(app, ctx);
  registerFeeds(app, ctx);
  return app;
}
