import { createHash, createHmac } from "node:crypto";
import { describe, expect, it } from "vitest";
import { buildApp, offlineUuid } from "../src/app.js";
import { loadConfig } from "../src/config.js";
import { presenceView } from "../src/routes/social.js";
import { MemoryStore } from "../src/store.js";
import { verifyWebhook } from "../src/tebex.js";

const A = "11111111111111111111111111111111";
const B = "22222222222222222222222222222222";
const C = "33333333333333333333333333333333";
const BUNDLES = JSON.stringify([{ id: "5001", coins: 1000, bonus: 100, price: "€4.99" }]);

function setup(extra: Record<string, string> = {}, fetchImpl?: typeof fetch) {
  let t = 1_000_000;
  const cfg = loadConfig({ KITE_JWT_SECRET: "x".repeat(32), KITE_ADMIN_TOKEN: "adm", ...extra } as NodeJS.ProcessEnv);
  const users: Record<string, string> = { alice: A, bob: B, carol: C };
  const app = buildApp(cfg, new MemoryStore(), async (name) => (users[name] ? { uuid: users[name], name } : null), () => t, fetchImpl);
  const login = async (name: string) => {
    const { serverId } = (await app.inject({ method: "POST", url: "/v1/auth/nonce" })).json();
    const r = await app.inject({ method: "POST", url: "/v1/auth/mojang", payload: { username: name, serverId } });
    return r.json().token as string;
  };
  const as = (token: string) => ({
    get: (url: string) => app.inject({ method: "GET", url, headers: { authorization: `Bearer ${token}` } }),
    post: (url: string, payload: object = {}) => app.inject({ method: "POST", url, payload, headers: { authorization: `Bearer ${token}` } }),
    del: (url: string) => app.inject({ method: "DELETE", url, headers: { authorization: `Bearer ${token}` } }),
  });
  const admin = (method: "POST" | "PUT", url: string, payload: unknown) => app.inject({ method, url, payload: payload as object, headers: { authorization: "Bearer adm" } });
  return { app, login, as, admin, advance: (ms: number) => (t += ms), now: () => t };
}

describe("offline sign-in", () => {
  it("is refused unless enabled, and uses the launcher's offline uuid", async () => {
    const off = setup();
    expect((await off.app.inject({ method: "POST", url: "/v1/auth/offline", payload: { username: "Dev" } })).statusCode).toBe(403);
    const on = setup({ KITE_ALLOW_OFFLINE: "1" });
    const r = await on.app.inject({ method: "POST", url: "/v1/auth/offline", payload: { username: "Steve_Dev" } });
    expect(r.statusCode).toBe(200);
    expect(r.json().uuid).toBe(offlineUuid("Steve_Dev"));
    expect((await on.app.inject({ method: "POST", url: "/v1/auth/offline", payload: { username: "bad name" } })).statusCode).toBe(400);
  });

  it("matches the Rust launcher's offline uuid", () => {
    // launcher/core auth::offline_session("Steve_Dev"): SHA-1 based, version nibble 3.
    expect(offlineUuid("Steve_Dev")).toBe("d39f581a49753f5e978a4d0237751c56");
  });

  it("can't be enabled in production", () => {
    expect(() => loadConfig({ NODE_ENV: "production", KITE_JWT_SECRET: "x", KITE_ALLOW_OFFLINE: "1" } as NodeJS.ProcessEnv)).toThrow();
  });
});

describe("friends", () => {
  it("requests, accept, presence, remove, block", async () => {
    const { login, as, advance } = setup();
    const a = as(await login("alice"));
    const b = as(await login("bob"));
    expect((await a.post("/v1/friends/requests", { name: "nobody" })).json().error).toBe("unknown_user");
    expect((await a.post("/v1/friends/requests", { name: "alice" })).json().error).toBe("self");
    const sent = await a.post("/v1/friends/requests", { name: "BOB" });
    expect(sent.json().accepted).toBe(false);
    expect((await a.post("/v1/friends/requests", { name: "bob" })).json().error).toBe("request_exists");

    const incoming = (await b.get("/v1/friends/requests")).json().requests;
    expect(incoming).toMatchObject([{ name: "alice", direction: "incoming" }]);
    expect((await a.post(`/v1/friends/requests/${incoming[0].id}/accept`)).statusCode).toBe(403);
    expect((await b.post(`/v1/friends/requests/${incoming[0].id}/accept`)).statusCode).toBe(200);
    expect((await a.get("/v1/friends")).json().friends).toMatchObject([{ uuid: B, name: "bob", status: "offline" }]);

    await b.post("/v1/presence/heartbeat", { source: "launcher", status: "busy", activity: "Browsing mods" });
    expect((await a.get("/v1/friends")).json().friends[0]).toMatchObject({ status: "busy", activity: "Browsing mods", server: null });
    await b.post("/v1/presence/heartbeat", { server: "play.example.net" });
    expect((await a.get("/v1/friends")).json().friends[0]).toMatchObject({ status: "busy", activity: "Playing on play.example.net", server: "play.example.net" });
    await b.post("/v1/presence/heartbeat", { source: "launcher", status: "invisible" });
    expect((await a.get("/v1/friends")).json().friends[0]).toMatchObject({ status: "offline", server: null, lastSeen: null });
    advance(200_000);
    await b.post("/v1/presence/heartbeat", { source: "launcher", status: "online" });
    expect((await a.get("/v1/friends")).json().friends[0].status).toBe("online");

    expect((await a.post("/v1/friends/requests", { name: "bob" })).json().error).toBe("already_friends");
    await a.post("/v1/blocks", { uuid: B });
    expect((await a.get("/v1/friends")).json().friends).toEqual([]);
    expect((await b.post("/v1/friends/requests", { name: "alice" })).json().error).toBe("blocked");
    expect((await a.get("/v1/blocks")).json().blocked).toEqual([{ uuid: B, name: "bob" }]);
    await a.del(`/v1/blocks/${B}`);
    expect((await b.post("/v1/friends/requests", { name: "alice" })).statusCode).toBe(200);
  });

  it("a request to someone who already asked you accepts it", async () => {
    const { login, as } = setup();
    const a = as(await login("alice"));
    const b = as(await login("bob"));
    await a.post("/v1/friends/requests", { name: "bob" });
    expect((await b.post("/v1/friends/requests", { name: "alice" })).json().accepted).toBe(true);
    expect((await a.get("/v1/friends/requests")).json().requests).toEqual([]);
  });
});

describe("messages", () => {
  it("only between friends, threads with unread counts, updates since", async () => {
    const { login, as, advance, now } = setup();
    const a = as(await login("alice"));
    const b = as(await login("bob"));
    const c = as(await login("carol"));
    expect((await a.post(`/v1/messages/${B}`, { text: "hi" })).json().error).toBe("not_friends");
    await a.post("/v1/friends/requests", { name: "bob" });
    await b.post("/v1/friends/requests", { name: "alice" });
    const since = now();
    advance(10);
    await a.post(`/v1/messages/${B}`, { text: "hi bob" });
    advance(10);
    await a.post(`/v1/messages/${B}`, { text: "you there?" });
    expect((await a.post(`/v1/messages/${B}`, { text: "   " })).statusCode).toBe(400);

    const threads = (await b.get("/v1/messages")).json().threads;
    expect(threads).toMatchObject([{ with: A, unread: 2, last: { text: "you there?" } }]);
    const updates = (await b.get(`/v1/social/updates?since=${since}`)).json();
    expect(updates.messages.map((m: { text: string }) => m.text)).toEqual(["hi bob", "you there?"]);
    expect(updates.messages[0].fromName).toBe("alice");
    expect((await b.get(`/v1/messages/${A}`)).json().messages).toHaveLength(2);
    await b.post(`/v1/messages/${A}/read`);
    expect((await b.get("/v1/messages")).json().threads[0].unread).toBe(0);
    expect((await c.get(`/v1/messages/${A}`)).json().messages).toEqual([]);
  });

  it("rate limits a chatty sender", async () => {
    const { login, as } = setup();
    const a = as(await login("alice"));
    const b = as(await login("bob"));
    await a.post("/v1/friends/requests", { name: "bob" });
    await b.post("/v1/friends/requests", { name: "alice" });
    for (let i = 0; i < 30; i++) expect((await a.post(`/v1/messages/${B}`, { text: `m${i}` })).statusCode).toBe(200);
    expect((await a.post(`/v1/messages/${B}`, { text: "too many" })).statusCode).toBe(429);
  });
});

describe("store", () => {
  it("buys with coins, keeps a ledger, unlocks the loadout", async () => {
    const { login, as, admin } = setup({ KITE_WELCOME_COINS: "500" });
    const a = as(await login("alice"));
    expect((await a.get("/v1/me")).json().coins).toBe(500);
    const store = (await a.get("/v1/store")).json();
    expect(store.items.find((i: { id: string }) => i.id === "wings-angel")).toMatchObject({ price: 600, free: false });
    expect(store.items.some((i: { id: string }) => i.id === "cape-founder")).toBe(false);
    expect((await a.post("/v1/store/buy", { id: "wings-angel" })).json().error).toBe("insufficient_coins");
    await admin("POST", "/v1/admin/coins", { uuid: A, amount: 200, label: "Gift" });
    const bought = (await a.post("/v1/store/buy", { id: "wings-angel" })).json();
    expect(bought.coins).toBe(100);
    expect(bought.owned).toContain("wings-angel");
    expect((await a.post("/v1/store/buy", { id: "wings-angel" })).json().error).toBe("already_owned");
    expect((await a.post("/v1/store/buy", { id: "hat-bandana" })).json().error).toBe("already_owned");
    expect((await a.post("/v1/store/buy", { id: "cape-founder" })).json().error).toBe("not_for_sale");
    const labels = (await a.get("/v1/me/transactions")).json().transactions.map((t: { label: string; coins: number }) => [t.label, t.coins]);
    expect(labels).toEqual([["Angel Wings", -600], ["Gift", 200], ["Welcome to Kite", 500]]);
  });

  it("welcome coins are given once", async () => {
    const { login, as } = setup({ KITE_WELCOME_COINS: "50" });
    await login("alice");
    const a = as(await login("alice"));
    expect((await a.get("/v1/me")).json().coins).toBe(50);
  });
});

describe("tebex", () => {
  const secret = "whsec";
  const sign = (raw: string) => createHmac("sha256", secret).update(createHash("sha256").update(raw).digest("hex")).digest("hex");
  const env = { KITE_TEBEX_PUBLIC_TOKEN: "pub", KITE_TEBEX_WEBHOOK_SECRET: secret, KITE_COIN_BUNDLES: BUNDLES };

  it("verifies signatures", () => {
    expect(verifyWebhook("{}", sign("{}"), secret)).toBe(true);
    expect(verifyWebhook("{}", sign("{ }"), secret)).toBe(false);
    expect(verifyWebhook("{}", undefined, secret)).toBe(false);
  });

  it("is off without configuration", async () => {
    const { login, as } = setup();
    const a = as(await login("alice"));
    expect((await a.get("/v1/coins/bundles")).json()).toEqual({ enabled: false, bundles: [] });
    expect((await a.post("/v1/coins/checkout", { bundle: "5001" })).statusCode).toBe(503);
  });

  it("checkout creates a basket; the webhook credits once and refunds", async () => {
    const calls: string[] = [];
    const fake = (async (url: string, init?: RequestInit) => {
      calls.push(`${init?.method} ${url} ${init?.body}`);
      const body = url.endsWith("/baskets") ? { data: { ident: "bsk1" } } : { data: { ident: "bsk1", links: { checkout: "https://pay.tebex.io/bsk1" } } };
      return new Response(JSON.stringify(body), { status: 200 });
    }) as typeof fetch;
    const { app, login, as } = setup(env, fake);
    const a = as(await login("alice"));
    expect((await a.get("/v1/coins/bundles")).json().enabled).toBe(true);
    expect((await a.post("/v1/coins/checkout", { bundle: "5001" })).json().url).toBe("https://pay.tebex.io/bsk1");
    expect(calls[0]).toContain(`"uuid":"${A}"`);
    expect(calls[1]).toContain('"package_id":"5001"');

    const hook = (body: object, sig?: string) => {
      const raw = JSON.stringify(body);
      return app.inject({ method: "POST", url: "/v1/webhooks/tebex", payload: raw, headers: { "content-type": "application/json", "x-signature": sig ?? sign(raw) } });
    };
    expect((await hook({ id: "v1", type: "validation.webhook" })).json()).toEqual({ id: "v1" });
    expect((await hook({ id: "x", type: "payment.completed" }, "bad")).statusCode).toBe(403);
    const paid = { id: "h1", type: "payment.completed", subject: { transaction_id: "tbx-1", custom: { uuid: A }, products: [{ id: 5001, quantity: 1 }] } };
    await hook(paid);
    await hook(paid);
    expect((await a.get("/v1/me")).json().coins).toBe(1100);
    await hook({ ...paid, id: "h2", type: "payment.refunded" });
    expect((await a.get("/v1/me")).json().coins).toBe(0);
  });
});

describe("feeds and stats", () => {
  it("admin replaces feeds; public reads; validation", async () => {
    const { app, admin, login, as } = setup();
    expect((await app.inject({ method: "GET", url: "/v1/news" })).json()).toEqual({ news: [] });
    const news = [
      { id: "a", tag: "Update", title: "Old", date: 1, size: "sm" },
      { id: "b", tag: "Event", title: "New", date: 2, size: "lg", body: ["Hello"] },
    ];
    expect((await app.inject({ method: "PUT", url: "/v1/admin/feeds/news", payload: news })).statusCode).toBe(401);
    expect((await admin("PUT", "/v1/admin/feeds/news", news)).json()).toEqual({ ok: true, count: 2 });
    expect((await app.inject({ method: "GET", url: "/v1/news" })).json().news.map((n: { id: string }) => n.id)).toEqual(["b", "a"]);
    expect((await admin("PUT", "/v1/admin/feeds/news", [{ id: "x" }])).statusCode).toBe(400);
    expect((await admin("PUT", "/v1/admin/feeds/nope", [])).statusCode).toBe(404);
    await admin("PUT", "/v1/admin/feeds/servers", [{ id: "s", name: "Example", address: "play.example.net", region: "EU" }]);
    expect((await app.inject({ method: "GET", url: "/v1/servers" })).json().servers[0]).toMatchObject({ partner: false, genres: [] });

    expect((await app.inject({ method: "GET", url: "/v1/stats" })).json()).toEqual({ online: 0 });
    const a = as(await login("alice"));
    await a.post("/v1/presence/heartbeat", { source: "launcher", status: "online" });
    expect((await app.inject({ method: "GET", url: "/v1/stats" })).json()).toEqual({ online: 1 });
  });

  it("presence view rules", () => {
    expect(presenceView([])).toEqual({ status: "offline", activity: null, server: null });
    const l = { uuid: A, source: "launcher" as const, at: 1, status: "away" as const, activity: null, server: null };
    expect(presenceView([l]).activity).toBe("In the launcher");
    expect(presenceView([{ ...l, source: "game", status: "online", server: null }]).activity).toBe("Playing Minecraft");
  });
});
