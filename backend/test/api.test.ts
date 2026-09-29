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
    expect((await put({ wings: "wings-angel" })).statusCode).toBe(403); // not owned
    expect((await put({ cape: "wings-angel" })).statusCode).toBe(400); // wrong slot
    expect((await put({ cape: null })).json().loadout).toEqual({});
  });

  it("admin grant unlocks, revoke unequips", async () => {
    const { app, login, auth } = setup();
    const { token } = await login("alice");
    const grant = (revoke?: boolean) =>
      app.inject({ method: "POST", url: "/v1/admin/grant", headers: { authorization: "Bearer adm" }, payload: { uuid: UUID_A, cosmeticId: "wings-angel", revoke } });
    expect((await app.inject({ method: "POST", url: "/v1/admin/grant", payload: {} })).statusCode).toBe(401);
    await grant();
    const r = await app.inject({ method: "PUT", url: "/v1/me/loadout", headers: auth(token), payload: { wings: "wings-angel" } });
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
