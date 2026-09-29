import Fastify from "fastify";
import { SignJWT, jwtVerify } from "jose";
import { createPrivateKey, randomBytes, sign as edSign } from "node:crypto";
import { z } from "zod";
import { CATALOG, SLOTS, byId } from "./catalog.js";
import { Config } from "./config.js";
import { Verifier, normUuid } from "./mojang.js";
import { Store } from "./store.js";

const UUID = z.string().regex(/^[0-9a-fA-F-]{32,36}$/);

/** PKCS8 wrapper for a raw 32-byte Ed25519 seed. */
function edKey(seedHex: string) {
  const der = Buffer.concat([Buffer.from("302e020100300506032b657004220420", "hex"), Buffer.from(seedHex, "hex")]);
  return createPrivateKey({ key: der, format: "der", type: "pkcs8" });
}

export function buildApp(cfg: Config, store: Store, verify: Verifier, now: () => number = Date.now) {
  const app = Fastify({ logger: false, bodyLimit: 64 * 1024 });

  // Per-IP fixed window rate limit (in-memory; put Redis behind this for multi-instance deployments).
  const hits = new Map<string, { n: number; reset: number }>();
  app.addHook("onRequest", async (req, reply) => {
    const t = now();
    const h = hits.get(req.ip);
    if (!h || t > h.reset) hits.set(req.ip, { n: 1, reset: t + 60_000 });
    else if (++h.n > 300) return reply.code(429).send({ error: "rate_limited" });
  });

  // Nonce the client passes to Mojang's join call as serverId (prevents replaying someone else's join).
  const nonces = new Map<string, number>();

  app.get("/v1/health", async () => ({ ok: true }));

  app.post("/v1/auth/nonce", async () => {
    const n = randomBytes(16).toString("hex");
    nonces.set(n, now() + 60_000);
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
    const uuid = normUuid(id.uuid);
    const token = await new SignJWT({ name: id.name })
      .setProtectedHeader({ alg: "HS256" })
      .setSubject(uuid)
      .setIssuedAt(Math.floor(now() / 1000))
      .setExpirationTime(Math.floor(now() / 1000) + 3600)
      .sign(cfg.jwtSecret);
    return { token, uuid, name: id.name };
  });

  async function authed(req: { headers: Record<string, unknown> }): Promise<string | null> {
    const h = req.headers["authorization"];
    if (typeof h !== "string" || !h.startsWith("Bearer ")) return null;
    try {
      const { payload } = await jwtVerify(h.slice(7), cfg.jwtSecret, { algorithms: ["HS256"], currentDate: new Date(now()) });
      return payload.sub ?? null;
    } catch {
      return null;
    }
  }

  app.get("/v1/cosmetics", async () => ({ slots: SLOTS, cosmetics: CATALOG }));

  app.get("/v1/me", async (req, reply) => {
    const uuid = await authed(req);
    if (!uuid) return reply.code(401).send({ error: "unauthorized" });
    return { uuid, owned: [...(await store.owned(uuid))], loadout: await store.getLoadout(uuid) };
  });

  app.put("/v1/me/loadout", async (req, reply) => {
    const uuid = await authed(req);
    if (!uuid) return reply.code(401).send({ error: "unauthorized" });
    const parsed = z.record(z.enum(SLOTS), z.string().nullable()).safeParse(req.body);
    if (!parsed.success) return reply.code(400).send({ error: "bad_request" });
    const owned = await store.owned(uuid);
    const next = await store.getLoadout(uuid);
    for (const [slot, id] of Object.entries(parsed.data) as [(typeof SLOTS)[number], string | null][]) {
      if (id === null) {
        delete next[slot];
        continue;
      }
      const c = byId.get(id);
      if (!c || c.slot !== slot) return reply.code(400).send({ error: "wrong_slot", slot });
      if (!owned.has(id)) return reply.code(403).send({ error: "not_owned", id });
      next[slot] = id;
    }
    await store.setLoadout(uuid, next);
    return { loadout: next };
  });

  app.post("/v1/presence/heartbeat", async (req, reply) => {
    const uuid = await authed(req);
    if (!uuid) return reply.code(401).send({ error: "unauthorized" });
    const body = z.object({ server: z.string().max(255).optional() }).safeParse(req.body ?? {});
    if (!body.success) return reply.code(400).send({ error: "bad_request" });
    await store.touchPresence(uuid, body.data.server, now());
    return { ok: true, ttlMs: cfg.presenceTtlMs };
  });

  // Which of these players are client users, and what are they wearing? Non-users are omitted, so the badge
  // and cosmetics only ever show for people running the client.
  app.post("/v1/presence/lookup", async (req, reply) => {
    if (!(await authed(req))) return reply.code(401).send({ error: "unauthorized" });
    const body = z.object({ uuids: z.array(UUID).max(200) }).safeParse(req.body);
    if (!body.success) return reply.code(400).send({ error: "bad_request" });
    const ids = body.data.uuids.map(normUuid);
    const online = await store.online(ids, now(), cfg.presenceTtlMs);
    const users: Record<string, { badge: true; loadout: Record<string, string> }> = {};
    for (const u of online) users[u] = { badge: true, loadout: (await store.getLoadout(u)) as Record<string, string> };
    return { users };
  });

  const Channel = z.enum(["nightly", "beta", "stable"]);

  app.get("/v1/manifest/:channel", async (req, reply) => {
    const c = Channel.safeParse((req.params as { channel?: string }).channel);
    if (!c.success) return reply.code(400).send({ error: "bad_channel" });
    const m = await store.getManifest(c.data);
    if (!m) return reply.code(404).send({ error: "no_manifest" });
    return m;
  });

  // ---- admin ----
  const admin = (req: { headers: Record<string, unknown> }) => !!cfg.adminToken && req.headers["authorization"] === `Bearer ${cfg.adminToken}`;

  app.post("/v1/admin/grant", async (req, reply) => {
    if (!admin(req)) return reply.code(401).send({ error: "unauthorized" });
    const b = z.object({ uuid: UUID, cosmeticId: z.string(), revoke: z.boolean().optional() }).safeParse(req.body);
    if (!b.success || !byId.has(b.data.cosmeticId)) return reply.code(400).send({ error: "bad_request" });
    const u = normUuid(b.data.uuid);
    if (b.data.revoke) await store.revoke(u, b.data.cosmeticId);
    else await store.grant(u, b.data.cosmeticId);
    return { ok: true };
  });

  app.put("/v1/admin/manifest/:channel", async (req, reply) => {
    if (!admin(req)) return reply.code(401).send({ error: "unauthorized" });
    if (!cfg.manifestSeedHex) return reply.code(503).send({ error: "manifest_signing_not_configured" });
    const c = Channel.safeParse((req.params as { channel?: string }).channel);
    if (!c.success) return reply.code(400).send({ error: "bad_channel" });
    const payload = JSON.stringify(req.body);
    const signature = edSign(null, Buffer.from(payload), edKey(cfg.manifestSeedHex)).toString("hex");
    await store.setManifest(c.data, payload, signature);
    return { ok: true, signature };
  });

  return app;
}
