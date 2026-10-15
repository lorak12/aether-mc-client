import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { NAME, UUID, type Ctx } from "../app.js";
import { normUuid } from "../mojang.js";
import type { PresenceRow } from "../store.js";

export type Shown = "online" | "away" | "busy" | "offline";

/** How a player looks to their friends, from their launcher and game heartbeats (within the ttl). */
export function presenceView(rows: PresenceRow[]): { status: Shown; activity: string | null; server: string | null } {
  const launcher = rows.find((r) => r.source === "launcher");
  const game = rows.find((r) => r.source === "game");
  if (!launcher && !game) return { status: "offline", activity: null, server: null };
  const status = launcher?.status ?? "online";
  if (status === "invisible") return { status: "offline", activity: null, server: null };
  if (game) return { status, activity: game.server ? `Playing on ${game.server}` : "Playing Minecraft", server: game.server };
  return { status, activity: launcher?.activity || "In the launcher", server: null };
}

export function registerSocial(app: FastifyInstance, ctx: Ctx) {
  const { store, now, cfg } = ctx;

  async function friendList(uuid: string) {
    const list = await store.friends(uuid);
    const ids = list.map((f) => f.uuid);
    const [users, rows, seen] = await Promise.all([store.users(ids), store.presence(ids, now(), cfg.presenceTtlMs), store.presence(ids, now(), Number.MAX_SAFE_INTEGER)]);
    return list.map((f) => {
      const p = presenceView(rows.filter((r) => r.uuid === f.uuid));
      // Invisible players don't leak when they were last around either.
      const mine = seen.filter((r) => r.uuid === f.uuid);
      const hidden = mine.some((r) => r.source === "launcher" && r.status === "invisible");
      const lastSeen = hidden ? null : Math.max(0, ...mine.map((r) => r.at)) || null;
      return { uuid: f.uuid, name: users.get(f.uuid)?.name ?? "Unknown", since: f.since, ...p, lastSeen };
    });
  }

  app.get("/v1/friends", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    return { friends: await friendList(uuid) };
  });

  async function requestList(uuid: string, since = 0) {
    const reqs = (await store.requests(uuid)).filter((r) => r.at > since);
    const users = await store.users(reqs.flatMap((r) => [r.from, r.to]));
    return reqs.map((r) => {
      const incoming = r.to === uuid;
      const other = incoming ? r.from : r.to;
      return { id: r.id, uuid: other, name: users.get(other)?.name ?? "Unknown", direction: incoming ? "incoming" : "outgoing", at: r.at };
    });
  }

  app.get("/v1/friends/requests", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    return { requests: await requestList(uuid) };
  });

  // Send a request by player name. If they already asked us, this accepts it.
  app.post("/v1/friends/requests", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    const body = z.object({ name: NAME }).safeParse(req.body);
    if (!body.success) return reply.code(400).send({ error: "bad_name" });
    const target = await store.userByName(body.data.name);
    if (!target) return reply.code(404).send({ error: "unknown_user" });
    if (target.uuid === uuid) return reply.code(400).send({ error: "self" });
    if (await store.isBlocked(uuid, target.uuid)) return reply.code(403).send({ error: "blocked" });
    if (await store.areFriends(uuid, target.uuid)) return reply.code(409).send({ error: "already_friends" });
    if (await store.requestBetween(uuid, target.uuid)) return reply.code(409).send({ error: "request_exists" });
    if (await store.requestBetween(target.uuid, uuid)) {
      await store.addFriends(uuid, target.uuid, now());
      return { accepted: true, friend: { uuid: target.uuid, name: target.name } };
    }
    const pending = (await store.requests(uuid)).filter((r) => r.from === uuid).length;
    if (pending >= 100) return reply.code(429).send({ error: "too_many_requests" });
    const r = await store.addRequest(uuid, target.uuid, now());
    return { accepted: false, request: { id: r.id, uuid: target.uuid, name: target.name, direction: "outgoing", at: r.at } };
  });

  app.post("/v1/friends/requests/:id/:action", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    const p = z.object({ id: z.coerce.number().int(), action: z.enum(["accept", "decline"]) }).safeParse(req.params);
    if (!p.success) return reply.code(400).send({ error: "bad_request" });
    const r = await store.request(p.data.id);
    if (!r || (r.to !== uuid && r.from !== uuid)) return reply.code(404).send({ error: "not_found" });
    if (p.data.action === "accept") {
      if (r.to !== uuid) return reply.code(403).send({ error: "not_yours" });
      await store.addFriends(r.from, r.to, now());
    } else {
      await store.deleteRequest(r.id); // decline (recipient) or cancel (sender)
    }
    return { ok: true };
  });

  app.delete("/v1/friends/:uuid", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    const p = UUID.safeParse((req.params as { uuid: string }).uuid);
    if (!p.success) return reply.code(400).send({ error: "bad_request" });
    await store.removeFriends(uuid, normUuid(p.data));
    return { ok: true };
  });

  app.get("/v1/blocks", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    const ids = await store.blocks(uuid);
    const users = await store.users(ids);
    return { blocked: ids.map((id) => ({ uuid: id, name: users.get(id)?.name ?? "Unknown" })) };
  });

  app.post("/v1/blocks", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    const b = z.object({ uuid: UUID }).safeParse(req.body);
    if (!b.success) return reply.code(400).send({ error: "bad_request" });
    const other = normUuid(b.data.uuid);
    if (other === uuid) return reply.code(400).send({ error: "self" });
    await store.block(uuid, other);
    return { ok: true };
  });

  app.delete("/v1/blocks/:uuid", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    const p = UUID.safeParse((req.params as { uuid: string }).uuid);
    if (!p.success) return reply.code(400).send({ error: "bad_request" });
    await store.unblock(uuid, normUuid(p.data));
    return { ok: true };
  });

  // ---- messages ----
  app.get("/v1/messages", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    return { threads: await store.threads(uuid) };
  });

  app.get("/v1/messages/:uuid", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    const p = UUID.safeParse((req.params as { uuid: string }).uuid);
    const q = z.object({ before: z.coerce.number().int().optional(), limit: z.coerce.number().int().min(1).max(200).default(50) }).safeParse(req.query);
    if (!p.success || !q.success) return reply.code(400).send({ error: "bad_request" });
    return { messages: await store.thread(uuid, normUuid(p.data), q.data.before ?? null, q.data.limit) };
  });

  // Per sender: at most 30 messages a minute.
  const sent = new Map<string, number[]>();
  app.post("/v1/messages/:uuid", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    const p = UUID.safeParse((req.params as { uuid: string }).uuid);
    const b = z.object({ text: z.string().trim().min(1).max(2000) }).safeParse(req.body);
    if (!p.success || !b.success) return reply.code(400).send({ error: "bad_request" });
    const to = normUuid(p.data);
    if (!(await store.areFriends(uuid, to))) return reply.code(403).send({ error: "not_friends" });
    const t = now();
    const recent = (sent.get(uuid) ?? []).filter((x) => t - x < 60_000);
    if (recent.length >= 30) return reply.code(429).send({ error: "slow_down" });
    sent.set(uuid, [...recent, t]);
    return { message: await store.addMessage(uuid, to, b.data.text, t) };
  });

  app.post("/v1/messages/:uuid/read", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    const p = UUID.safeParse((req.params as { uuid: string }).uuid);
    if (!p.success) return reply.code(400).send({ error: "bad_request" });
    await store.markRead(uuid, normUuid(p.data));
    return { ok: true };
  });

  // What changed since the launcher last asked: new incoming messages and friend requests.
  app.get("/v1/social/updates", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    const q = z.object({ since: z.coerce.number().int().min(0).default(0) }).safeParse(req.query);
    if (!q.success) return reply.code(400).send({ error: "bad_request" });
    const t = now();
    const messages = await store.incoming(uuid, q.data.since);
    const names = await store.users(messages.map((m) => m.from));
    return {
      now: t,
      messages: messages.map((m) => ({ ...m, fromName: names.get(m.from)?.name ?? "Unknown" })),
      requests: (await requestList(uuid, q.data.since)).filter((r) => r.direction === "incoming"),
    };
  });
}
