import { generateKeyPairSync, verify as edVerify } from "node:crypto";
import { describe, expect, it } from "vitest";
import { buildApp } from "../src/app.js";
import { loadConfig } from "../src/config.js";
import { MemoryStore } from "../src/store.js";

const UUID_A = "11111111111111111111111111111111";
const UUID_B = "22222222222222222222222222222222";

function setup(extra: Record<string, string> = {}) {
  let t = 1_000_000;
  const cfg = loadConfig({ AETHER_JWT_SECRET: "x".repeat(32), AETHER_ADMIN_TOKEN: "adm", ...extra } as any);
  const store = new MemoryStore();
  const users: Record<string, string> = { alice: UUID_A, bob: UUID_B };
  const app = buildApp(cfg, store, async (name) => (users[name] ? { uuid: users[name], name } : null), () => t);
  const login = async (name: string) => {
    const { serverId } = (await app.inject({ method: "POST", url: "/v1/auth/nonce" })).json();
    const r = await app.inject({ method: "POST", url: "/v1/auth/mojang", payload: { username: name, serverId } });
    return { res: r, token: r.json().token as string, serverId };
  };
  const auth = (token: string) => ({ authorization: `Bearer ${token}` });
  return { app, store, login, auth, advance: (ms: number) => (t += ms) };
}

describe("auth", () => {
  it("logs in with a verified join and rejects nonce replay / unknown users", async () => {
    const { app, login } = setup();
    const a = await login("alice");
    expect(a.res.statusCode).toBe(200);
    const replay = await app.inject({ method: "POST", url: "/v1/auth/mojang", payload: { username: "alice", serverId: a.serverId } });
    expect(replay.statusCode).toBe(401);
    expect((await login("mallory")).res.statusCode).toBe(401);
  });

  it("rejects missing/forged tokens", async () => {
    const { app } = setup();
    expect((await app.inject({ method: "GET", url: "/v1/me" })).statusCode).toBe(401);
    expect((await app.inject({ method: "GET", url: "/v1/me", headers: { authorization: "Bearer nope" } })).statusCode).toBe(401);
  });

  it("expires tokens after an hour", async () => {
    const { app, login, auth, advance } = setup();
    const { token } = await login("alice");
    expect((await app.inject({ method: "GET", url: "/v1/me", headers: auth(token) })).statusCode).toBe(200);
    advance(3_700_000);
    expect((await app.inject({ method: "GET", url: "/v1/me", headers: auth(token) })).statusCode).toBe(401);
  });
});

describe("loadout", () => {
  it("enforces ownership and slots", async () => {
    const { app, login, auth } = setup();
    const { token } = await login("alice");
    const put = (payload: object) => app.inject({ method: "PUT", url: "/v1/me/loadout", headers: auth(token), payload });
    expect((await put({ cape: "cape-aether" })).statusCode).toBe(200); // free
    expect((await put({ cape: "cape-founder" })).statusCode).toBe(403); // not owned
    expect((await put({ wings: "wings-fairy" })).statusCode).toBe(200); // free starter
    expect((await put({ wings: "wings-angel" })).statusCode).toBe(403); // epic: bought in the store
    expect((await put({ cape: "wings-fairy" })).statusCode).toBe(400); // wrong slot
    expect((await put({ cape: null })).json().loadout).toEqual({ wings: "wings-fairy" });
  });

  it("lenient mode (the game) keeps what it can and reports the rest", async () => {
    const { app, login, auth } = setup();
    const { token } = await login("alice");
    const put = (payload: object) => app.inject({ method: "PUT", url: "/v1/me/loadout?lenient=1", headers: auth(token), payload });
    await put({ cape: "cape-aether" });
    const r = await put({ cape: "cape-founder", wings: "wings-fairy", hat: "wings-fairy", ears: null });
    expect(r.statusCode).toBe(200);
    expect(r.json()).toEqual({ loadout: { cape: "cape-aether", wings: "wings-fairy" }, rejected: ["cape-founder", "wings-fairy"] });
  });

  it("admin grant unlocks, revoke unequips", async () => {
    const { app, login, auth } = setup();
    const { token } = await login("alice");
    const grant = (revoke?: boolean) =>
      app.inject({ method: "POST", url: "/v1/admin/grant", headers: { authorization: "Bearer adm" }, payload: { uuid: UUID_A, cosmeticId: "cape-founder", revoke } });
    expect((await app.inject({ method: "POST", url: "/v1/admin/grant", payload: {} })).statusCode).toBe(401);
    await grant();
    const r = await app.inject({ method: "PUT", url: "/v1/me/loadout", headers: auth(token), payload: { cape: "cape-founder" } });
    expect(r.statusCode).toBe(200);
    await grant(true);
    expect((await app.inject({ method: "GET", url: "/v1/me", headers: auth(token) })).json().loadout).toEqual({});
  });
});

describe("presence", () => {
  it("only reports online client users, expires after ttl", async () => {
    const { app, login, auth, advance } = setup();
    const a = await login("alice");
    const b = await login("bob");
    await app.inject({ method: "PUT", url: "/v1/me/loadout", headers: auth(a.token), payload: { cape: "cape-aether" } });
    await app.inject({ method: "POST", url: "/v1/presence/heartbeat", headers: auth(a.token), payload: { server: "mc.example.com" } });
    const lookup = () => app.inject({ method: "POST", url: "/v1/presence/lookup", headers: auth(b.token), payload: { uuids: [UUID_A, UUID_B, "33333333333333333333333333333333"] } });
    let users = (await lookup()).json().users;
    expect(Object.keys(users)).toEqual([UUID_A]);
    expect(users[UUID_A]).toEqual({ badge: true, loadout: { cape: "cape-aether" } });
    advance(91_000);
    users = (await lookup()).json().users;
    expect(users).toEqual({});
  });

  it("requires auth and validates uuids", async () => {
    const { app, login, auth } = setup();
    expect((await app.inject({ method: "POST", url: "/v1/presence/lookup", payload: { uuids: [] } })).statusCode).toBe(401);
    const { token } = await login("alice");
    expect((await app.inject({ method: "POST", url: "/v1/presence/lookup", headers: auth(token), payload: { uuids: ["<script>"] } })).statusCode).toBe(400);
  });
});

describe("manifest", () => {
  it("signs with ed25519 and the signature verifies", async () => {
    const { privateKey, publicKey } = generateKeyPairSync("ed25519");
    const seed = privateKey.export({ format: "der", type: "pkcs8" }).subarray(-32).toString("hex");
    const { app } = setup({ AETHER_MANIFEST_SEED: seed });
    const put = await app.inject({ method: "PUT", url: "/v1/admin/manifest/stable", headers: { authorization: "Bearer adm" }, payload: { runtimes: [{ mc: "1.8.9", version: "1.0.0" }] } });
    expect(put.statusCode).toBe(200);
    const got = (await app.inject({ method: "GET", url: "/v1/manifest/stable" })).json();
    expect(edVerify(null, Buffer.from(got.payload), publicKey, Buffer.from(got.signature, "hex"))).toBe(true);
    expect((await app.inject({ method: "GET", url: "/v1/manifest/beta" })).statusCode).toBe(404);
    expect((await app.inject({ method: "GET", url: "/v1/manifest/evil" })).statusCode).toBe(400);
  });

  it("refuses to sign when no key is configured", async () => {
    const { app } = setup();
    const r = await app.inject({ method: "PUT", url: "/v1/admin/manifest/stable", headers: { authorization: "Bearer adm" }, payload: {} });
    expect(r.statusCode).toBe(503);
  });
});

describe("admins", () => {
  function adminSetup(extra: Record<string, string> = {}) {
    const cfg = loadConfig({ AETHER_JWT_SECRET: "x".repeat(32), ...extra } as any);
    const store = new MemoryStore();
    const users: Record<string, string> = { Nakii14: UUID_A, bob: UUID_B };
    const app = buildApp(cfg, store, async (name) => (users[name] ? { uuid: users[name], name } : null));
    const login = async (name: string) => {
      const { serverId } = (await app.inject({ method: "POST", url: "/v1/auth/nonce" })).json();
      const r = await app.inject({ method: "POST", url: "/v1/auth/mojang", payload: { username: name, serverId } });
      return { authorization: `Bearer ${r.json().token}` };
    };
    return { app, store, login };
  }

  it("gives nakii14 (any case) unlimited coins and free purchases", async () => {
    const { app, login } = adminSetup();
    const h = await login("Nakii14");
    const me = (await app.inject({ method: "GET", url: "/v1/me", headers: h })).json();
    expect(me.unlimited).toBe(true);
    expect(me.coins).toBeGreaterThan(1_000_000);
    const buy = await app.inject({ method: "POST", url: "/v1/store/buy", headers: h, payload: { id: "cape-galaxy" } });
    expect(buy.statusCode).toBe(200);
    expect(buy.json().unlimited).toBe(true);
    expect(buy.json().owned).toContain("cape-galaxy");
    expect((await app.inject({ method: "POST", url: "/v1/store/buy", headers: h, payload: { id: "cape-galaxy" } })).statusCode).toBe(409);
    const store = (await app.inject({ method: "GET", url: "/v1/store", headers: h })).json();
    expect(store.unlimited).toBe(true);
    const tx = (await app.inject({ method: "GET", url: "/v1/me/transactions", headers: h })).json();
    expect(tx.transactions).toEqual([]);
  });

  it("charges everyone else, and AETHER_ADMINS adds names", async () => {
    const plain = adminSetup();
    const bob = await plain.login("bob");
    const me = (await plain.app.inject({ method: "GET", url: "/v1/me", headers: bob })).json();
    expect(me.unlimited).toBe(false);
    expect((await plain.app.inject({ method: "POST", url: "/v1/store/buy", headers: bob, payload: { id: "cape-galaxy" } })).statusCode).toBe(402);

    const extra = adminSetup({ AETHER_ADMINS: "someone, BOB" });
    const bob2 = await extra.login("bob");
    expect((await extra.app.inject({ method: "POST", url: "/v1/store/buy", headers: bob2, payload: { id: "cape-galaxy" } })).statusCode).toBe(200);
  });
});

describe("published cosmetics", () => {
  function pubSetup() {
    const cfg = loadConfig({ AETHER_JWT_SECRET: "x".repeat(32), AETHER_WELCOME_COINS: "500" } as any);
    const store = new MemoryStore();
    const users: Record<string, string> = { nakii14: UUID_A, bob: UUID_B };
    const app = buildApp(cfg, store, async (name) => (users[name] ? { uuid: users[name], name } : null));
    const login = async (name: string) => {
      const { serverId } = (await app.inject({ method: "POST", url: "/v1/auth/nonce" })).json();
      const r = await app.inject({ method: "POST", url: "/v1/auth/mojang", payload: { username: name, serverId } });
      return { authorization: `Bearer ${r.json().token}` };
    };
    return { app, login };
  }
  const png = Buffer.from("89504e470d0a1a0a0000000d49484452000000100000001008060000001ff3ff61", "hex").toString("base64");
  // Big enough to need the raised body limit (the default is 64 KB).
  const bigTexture = Buffer.alloc(200 * 1024, 7).toString("base64");
  const bundle = (price: number) => ({
    id: "custom-star-hat",
    slot: "hat",
    name: "Star Hat",
    rarity: "epic",
    price,
    description: "Shiny",
    files: { "cosmetic.json": Buffer.from('{"slot":"hat"}').toString("base64"), "icon.png": png, "texture.png": bigTexture },
  });

  it("lets admins publish, everyone buy and wear, and removes on request", async () => {
    const { app, login } = pubSetup();
    const admin = await login("nakii14");
    const bob = await login("bob");
    expect((await app.inject({ method: "GET", url: "/v1/me", headers: admin })).json().admin).toBe(true);
    expect((await app.inject({ method: "GET", url: "/v1/me", headers: bob })).json().admin).toBe(false);

    expect((await app.inject({ method: "POST", url: "/v1/admin/cosmetics", headers: bob, payload: bundle(300) })).statusCode).toBe(403);
    const pub = await app.inject({ method: "POST", url: "/v1/admin/cosmetics", headers: admin, payload: bundle(300) });
    expect(pub.statusCode).toBe(200);
    expect(pub.json().cosmetic.version).toBe(1);

    const items = (await app.inject({ method: "GET", url: "/v1/store", headers: bob })).json().items;
    const hat = items.find((i: any) => i.id === "custom-star-hat");
    expect(hat).toMatchObject({ price: 300, custom: true, author: "nakii14" });
    const b = (await app.inject({ method: "GET", url: "/v1/cosmetics/custom-star-hat/bundle" })).json();
    expect(Object.keys(b.files).sort()).toEqual(["cosmetic.json", "icon.png", "texture.png"]);
    expect((await app.inject({ method: "GET", url: "/v1/cosmetics/custom-star-hat/icon" })).headers["content-type"]).toBe("image/png");

    expect((await app.inject({ method: "PUT", url: "/v1/me/loadout", headers: bob, payload: { hat: "custom-star-hat" } })).statusCode).toBe(403);
    const buy = await app.inject({ method: "POST", url: "/v1/store/buy", headers: bob, payload: { id: "custom-star-hat" } });
    expect(buy.statusCode).toBe(200);
    expect(buy.json().coins).toBe(200);
    expect((await app.inject({ method: "PUT", url: "/v1/me/loadout", headers: bob, payload: { hat: "custom-star-hat" } })).statusCode).toBe(200);

    // Publishing again bumps the version; removing takes it out of the store.
    expect((await app.inject({ method: "POST", url: "/v1/admin/cosmetics", headers: admin, payload: bundle(400) })).json().cosmetic.version).toBe(2);
    expect((await app.inject({ method: "DELETE", url: "/v1/admin/cosmetics/custom-star-hat", headers: admin })).statusCode).toBe(200);
    expect((await app.inject({ method: "GET", url: "/v1/store", headers: bob })).json().items.some((i: any) => i.id === "custom-star-hat")).toBe(false);
  });

  it("rejects bad bundles", async () => {
    const { app, login } = pubSetup();
    const admin = await login("nakii14");
    const bad = { ...bundle(1), id: "cape-galaxy" };
    expect((await app.inject({ method: "POST", url: "/v1/admin/cosmetics", headers: admin, payload: bad })).statusCode).toBe(400);
    const noJson = { ...bundle(1), files: { "icon.png": png } };
    expect((await app.inject({ method: "POST", url: "/v1/admin/cosmetics", headers: admin, payload: noJson })).json().error).toBe("missing_cosmetic_json");
    const sneaky = { ...bundle(1), files: { "../x.json": png, "cosmetic.json": png } };
    expect((await app.inject({ method: "POST", url: "/v1/admin/cosmetics", headers: admin, payload: sneaky })).statusCode).toBe(400);
  });
});
