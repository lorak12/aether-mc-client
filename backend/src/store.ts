import { createRequire } from "node:module";
import type { DatabaseSync as DatabaseSyncT } from "node:sqlite";
import { CATALOG, Slot } from "./catalog.js";

export type Loadout = Partial<Record<Slot, string>>;
export type Status = "online" | "away" | "busy" | "invisible";
export type Source = "launcher" | "game";

export interface User {
  uuid: string;
  name: string;
}
export interface Txn {
  id: number;
  at: number;
  label: string;
  coins: number;
}
export interface PresenceRow {
  uuid: string;
  source: Source;
  at: number;
  status: Status;
  activity: string | null;
  server: string | null;
}
export interface FriendRequest {
  id: number;
  from: string;
  to: string;
  at: number;
}
export interface Message {
  id: number;
  from: string;
  to: string;
  text: string;
  at: number;
  read: boolean;
}

export class StoreError extends Error {
  constructor(public code: string, public status = 400) {
    super(code);
  }
}

/** Persistence boundary. Routes only talk to this; `SqliteStore` is the implementation (":memory:" in tests). */
export interface Store {
  // users
  upsertUser(uuid: string, name: string, now: number): Promise<void>;
  user(uuid: string): Promise<User | undefined>;
  userByName(name: string): Promise<User | undefined>;
  users(uuids: string[]): Promise<Map<string, User>>;
  // cosmetics
  owned(uuid: string): Promise<Set<string>>;
  grant(uuid: string, cosmeticId: string): Promise<void>;
  revoke(uuid: string, cosmeticId: string): Promise<void>;
  getLoadout(uuid: string): Promise<Loadout>;
  setLoadout(uuid: string, l: Loadout): Promise<void>;
  // coins
  balance(uuid: string): Promise<number>;
  /** Add (or with a negative amount remove) coins. A repeated `ref` is ignored and returns false. */
  addCoins(uuid: string, amount: number, label: string, now: number, ref?: string): Promise<boolean>;
  /** Spend coins on a cosmetic: checks ownership and balance, all or nothing. Returns the new balance. */
  buy(uuid: string, cosmeticId: string, price: number, label: string, now: number): Promise<number>;
  transactions(uuid: string, limit: number): Promise<Txn[]>;
  // presence
  touchPresence(uuid: string, source: Source, p: { status?: Status; activity?: string | null; server?: string | null }, now: number): Promise<void>;
  /** Users with a game heartbeat within the ttl (client users, for the badge). */
  online(uuids: string[], now: number, ttlMs: number): Promise<string[]>;
  presence(uuids: string[], now: number, ttlMs: number): Promise<PresenceRow[]>;
  countOnline(now: number, ttlMs: number): Promise<number>;
  // friends
  friends(uuid: string): Promise<{ uuid: string; since: number }[]>;
  areFriends(a: string, b: string): Promise<boolean>;
  addFriends(a: string, b: string, now: number): Promise<void>;
  removeFriends(a: string, b: string): Promise<void>;
  requests(uuid: string): Promise<FriendRequest[]>;
  request(id: number): Promise<FriendRequest | undefined>;
  requestBetween(from: string, to: string): Promise<FriendRequest | undefined>;
  addRequest(from: string, to: string, now: number): Promise<FriendRequest>;
  deleteRequest(id: number): Promise<void>;
  blocks(uuid: string): Promise<string[]>;
  isBlocked(a: string, b: string): Promise<boolean>;
  block(blocker: string, blocked: string): Promise<void>;
  unblock(blocker: string, blocked: string): Promise<void>;
  // messages
  addMessage(from: string, to: string, text: string, now: number): Promise<Message>;
  thread(a: string, b: string, before: number | null, limit: number): Promise<Message[]>;
  /** Per friend: last message and unread count. */
  threads(uuid: string): Promise<{ with: string; last: Message; unread: number }[]>;
  markRead(reader: string, from: string): Promise<void>;
  incoming(uuid: string, since: number): Promise<Message[]>;
  // key/value (manifests, feeds)
  getKv(key: string): Promise<string | undefined>;
  setKv(key: string, value: string): Promise<void>;
  // payments
  addCheckout(ident: string, uuid: string, bundle: string, now: number): Promise<void>;
  checkout(ident: string): Promise<{ uuid: string; bundle: string } | undefined>;
  // manifests (kept for the existing routes)
  setManifest(channel: string, payload: string, signature: string | null): Promise<void>;
  getManifest(channel: string): Promise<{ payload: string; signature: string | null } | undefined>;
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS users (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, name_lower TEXT NOT NULL, created INTEGER NOT NULL, last_login INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS users_name ON users(name_lower);
CREATE TABLE IF NOT EXISTS grants (uuid TEXT NOT NULL, cosmetic TEXT NOT NULL, PRIMARY KEY (uuid, cosmetic));
CREATE TABLE IF NOT EXISTS loadouts (uuid TEXT PRIMARY KEY, json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS coins (uuid TEXT PRIMARY KEY, balance INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS txns (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, at INTEGER NOT NULL, label TEXT NOT NULL, coins INTEGER NOT NULL, ref TEXT UNIQUE);
CREATE INDEX IF NOT EXISTS txns_uuid ON txns(uuid, at);
CREATE TABLE IF NOT EXISTS presence (uuid TEXT NOT NULL, source TEXT NOT NULL, at INTEGER NOT NULL, status TEXT NOT NULL, activity TEXT, server TEXT, PRIMARY KEY (uuid, source));
CREATE TABLE IF NOT EXISTS friends (a TEXT NOT NULL, b TEXT NOT NULL, since INTEGER NOT NULL, PRIMARY KEY (a, b));
CREATE TABLE IF NOT EXISTS requests (id INTEGER PRIMARY KEY AUTOINCREMENT, from_uuid TEXT NOT NULL, to_uuid TEXT NOT NULL, at INTEGER NOT NULL, UNIQUE (from_uuid, to_uuid));
CREATE TABLE IF NOT EXISTS blocks (blocker TEXT NOT NULL, blocked TEXT NOT NULL, PRIMARY KEY (blocker, blocked));
CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY AUTOINCREMENT, from_uuid TEXT NOT NULL, to_uuid TEXT NOT NULL, text TEXT NOT NULL, at INTEGER NOT NULL, read INTEGER NOT NULL DEFAULT 0);
CREATE INDEX IF NOT EXISTS messages_pair ON messages(from_uuid, to_uuid, at);
CREATE INDEX IF NOT EXISTS messages_to ON messages(to_uuid, at);
CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS checkouts (ident TEXT PRIMARY KEY, uuid TEXT NOT NULL, bundle TEXT NOT NULL, at INTEGER NOT NULL);
`;

type Row = Record<string, unknown>;
const msg = (r: Row): Message => ({ id: Number(r.id), from: String(r.from_uuid), to: String(r.to_uuid), text: String(r.text), at: Number(r.at), read: !!r.read });
const req = (r: Row): FriendRequest => ({ id: Number(r.id), from: String(r.from_uuid), to: String(r.to_uuid), at: Number(r.at) });
const placeholders = (n: number) => Array(n).fill("?").join(",");

// Loaded through require: the test bundler (Vite 5) doesn't know node:sqlite is a builtin.
const { DatabaseSync } = createRequire(import.meta.url)("node:sqlite") as { DatabaseSync: typeof DatabaseSyncT };

export class SqliteStore implements Store {
  private db: DatabaseSyncT;
  private free = new Set(CATALOG.filter((c) => c.free).map((c) => c.id));

  constructor(path = ":memory:") {
    this.db = new DatabaseSync(path);
    this.db.exec("PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON;");
    this.db.exec(SCHEMA);
  }

  close() {
    this.db.close();
  }

  private all(sql: string, ...args: (string | number | null)[]): Row[] {
    return this.db.prepare(sql).all(...args) as Row[];
  }
  private get(sql: string, ...args: (string | number | null)[]): Row | undefined {
    return this.db.prepare(sql).get(...args) as Row | undefined;
  }
  private run(sql: string, ...args: (string | number | null)[]) {
    return this.db.prepare(sql).run(...args);
  }
  private tx<T>(fn: () => T): T {
    this.db.exec("BEGIN IMMEDIATE");
    try {
      const r = fn();
      this.db.exec("COMMIT");
      return r;
    } catch (e) {
      this.db.exec("ROLLBACK");
      throw e;
    }
  }

  // ---- users ----
  async upsertUser(uuid: string, name: string, now: number) {
    this.run(
      "INSERT INTO users (uuid, name, name_lower, created, last_login) VALUES (?, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, name_lower = excluded.name_lower, last_login = excluded.last_login",
      uuid,
      name,
      name.toLowerCase(),
      now,
      now,
    );
  }
  async user(uuid: string) {
    const r = this.get("SELECT uuid, name FROM users WHERE uuid = ?", uuid);
    return r ? { uuid: String(r.uuid), name: String(r.name) } : undefined;
  }
  async userByName(name: string) {
    // Names move between accounts; the most recent login wins.
    const r = this.get("SELECT uuid, name FROM users WHERE name_lower = ? ORDER BY last_login DESC LIMIT 1", name.toLowerCase());
    return r ? { uuid: String(r.uuid), name: String(r.name) } : undefined;
  }
  async users(uuids: string[]) {
    const out = new Map<string, User>();
    if (!uuids.length) return out;
    for (const r of this.all(`SELECT uuid, name FROM users WHERE uuid IN (${placeholders(uuids.length)})`, ...uuids)) {
      out.set(String(r.uuid), { uuid: String(r.uuid), name: String(r.name) });
    }
    return out;
  }

  // ---- cosmetics ----
  async owned(uuid: string) {
    return new Set([...this.free, ...this.all("SELECT cosmetic FROM grants WHERE uuid = ?", uuid).map((r) => String(r.cosmetic))]);
  }
  async grant(uuid: string, id: string) {
    this.run("INSERT OR IGNORE INTO grants (uuid, cosmetic) VALUES (?, ?)", uuid, id);
  }
  async revoke(uuid: string, id: string) {
    this.run("DELETE FROM grants WHERE uuid = ? AND cosmetic = ?", uuid, id);
    // Unequip anything the user no longer owns.
    if (this.free.has(id)) return;
    const l = await this.getLoadout(uuid);
    let changed = false;
    for (const [slot, cid] of Object.entries(l)) {
      if (cid === id) {
        delete l[slot as Slot];
        changed = true;
      }
    }
    if (changed) await this.setLoadout(uuid, l);
  }
  async getLoadout(uuid: string): Promise<Loadout> {
    const r = this.get("SELECT json FROM loadouts WHERE uuid = ?", uuid);
    return r ? (JSON.parse(String(r.json)) as Loadout) : {};
  }
  async setLoadout(uuid: string, l: Loadout) {
    this.run("INSERT INTO loadouts (uuid, json) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET json = excluded.json", uuid, JSON.stringify(l));
  }

  // ---- coins ----
  private bal(uuid: string) {
    return Number(this.get("SELECT balance FROM coins WHERE uuid = ?", uuid)?.balance ?? 0);
  }
  async balance(uuid: string) {
    return this.bal(uuid);
  }
  async addCoins(uuid: string, amount: number, label: string, now: number, ref?: string) {
    return this.tx(() => {
      if (ref && this.get("SELECT 1 FROM txns WHERE ref = ?", ref)) return false;
      this.run("INSERT INTO coins (uuid, balance) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET balance = balance + excluded.balance", uuid, amount);
      this.run("INSERT INTO txns (uuid, at, label, coins, ref) VALUES (?, ?, ?, ?, ?)", uuid, now, label, amount, ref ?? null);
      return true;
    });
  }
  async buy(uuid: string, cosmeticId: string, price: number, label: string, now: number) {
    return this.tx(() => {
      if (this.free.has(cosmeticId) || this.get("SELECT 1 FROM grants WHERE uuid = ? AND cosmetic = ?", uuid, cosmeticId)) throw new StoreError("already_owned", 409);
      const b = this.bal(uuid);
      if (b < price) throw new StoreError("insufficient_coins", 402);
      this.run("INSERT INTO coins (uuid, balance) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET balance = balance + excluded.balance", uuid, -price);
      this.run("INSERT INTO grants (uuid, cosmetic) VALUES (?, ?)", uuid, cosmeticId);
      this.run("INSERT INTO txns (uuid, at, label, coins) VALUES (?, ?, ?, ?)", uuid, now, label, -price);
      return b - price;
    });
  }
  async transactions(uuid: string, limit: number) {
    return this.all("SELECT id, at, label, coins FROM txns WHERE uuid = ? ORDER BY at DESC, id DESC LIMIT ?", uuid, limit).map((r) => ({
      id: Number(r.id),
      at: Number(r.at),
      label: String(r.label),
      coins: Number(r.coins),
    }));
  }

  // ---- presence ----
  async touchPresence(uuid: string, source: Source, p: { status?: Status; activity?: string | null; server?: string | null }, now: number) {
    const prev = this.get("SELECT status FROM presence WHERE uuid = ? AND source = ?", uuid, source);
    this.run(
      "INSERT INTO presence (uuid, source, at, status, activity, server) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(uuid, source) DO UPDATE SET at = excluded.at, status = excluded.status, activity = excluded.activity, server = excluded.server",
      uuid,
      source,
      now,
      p.status ?? String(prev?.status ?? "online"),
      p.activity ?? null,
      p.server ?? null,
    );
  }
  async online(uuids: string[], now: number, ttlMs: number) {
    if (!uuids.length) return [];
    const rows = this.all(`SELECT uuid FROM presence WHERE source = 'game' AND at >= ? AND uuid IN (${placeholders(uuids.length)})`, now - ttlMs, ...uuids);
    const set = new Set(rows.map((r) => String(r.uuid)));
    return uuids.filter((u) => set.has(u));
  }
  async presence(uuids: string[], now: number, ttlMs: number) {
    if (!uuids.length) return [];
    return this.all(`SELECT * FROM presence WHERE at >= ? AND uuid IN (${placeholders(uuids.length)})`, now - ttlMs, ...uuids).map((r) => ({
      uuid: String(r.uuid),
      source: String(r.source) as Source,
      at: Number(r.at),
      status: String(r.status) as Status,
      activity: r.activity == null ? null : String(r.activity),
      server: r.server == null ? null : String(r.server),
    }));
  }
  async countOnline(now: number, ttlMs: number) {
    return Number(this.get("SELECT COUNT(DISTINCT uuid) AS n FROM presence WHERE at >= ? AND status != 'invisible'", now - ttlMs)?.n ?? 0);
  }

  // ---- friends ----
  async friends(uuid: string) {
    return this.all("SELECT b, since FROM friends WHERE a = ?", uuid).map((r) => ({ uuid: String(r.b), since: Number(r.since) }));
  }
  async areFriends(a: string, b: string) {
    return !!this.get("SELECT 1 FROM friends WHERE a = ? AND b = ?", a, b);
  }
  async addFriends(a: string, b: string, now: number) {
    this.tx(() => {
      this.run("INSERT OR IGNORE INTO friends (a, b, since) VALUES (?, ?, ?)", a, b, now);
      this.run("INSERT OR IGNORE INTO friends (a, b, since) VALUES (?, ?, ?)", b, a, now);
      this.run("DELETE FROM requests WHERE (from_uuid = ? AND to_uuid = ?) OR (from_uuid = ? AND to_uuid = ?)", a, b, b, a);
    });
  }
  async removeFriends(a: string, b: string) {
    this.run("DELETE FROM friends WHERE (a = ? AND b = ?) OR (a = ? AND b = ?)", a, b, b, a);
  }
  async requests(uuid: string) {
    return this.all("SELECT * FROM requests WHERE from_uuid = ? OR to_uuid = ? ORDER BY at DESC", uuid, uuid).map(req);
  }
  async request(id: number) {
    const r = this.get("SELECT * FROM requests WHERE id = ?", id);
    return r ? req(r) : undefined;
  }
  async requestBetween(from: string, to: string) {
    const r = this.get("SELECT * FROM requests WHERE from_uuid = ? AND to_uuid = ?", from, to);
    return r ? req(r) : undefined;
  }
  async addRequest(from: string, to: string, now: number) {
    const r = this.run("INSERT INTO requests (from_uuid, to_uuid, at) VALUES (?, ?, ?)", from, to, now);
    return { id: Number(r.lastInsertRowid), from, to, at: now };
  }
  async deleteRequest(id: number) {
    this.run("DELETE FROM requests WHERE id = ?", id);
  }
  async blocks(uuid: string) {
    return this.all("SELECT blocked FROM blocks WHERE blocker = ?", uuid).map((r) => String(r.blocked));
  }
  async isBlocked(a: string, b: string) {
    return !!this.get("SELECT 1 FROM blocks WHERE (blocker = ? AND blocked = ?) OR (blocker = ? AND blocked = ?)", a, b, b, a);
  }
  async block(blocker: string, blocked: string) {
    this.tx(() => {
      this.run("INSERT OR IGNORE INTO blocks (blocker, blocked) VALUES (?, ?)", blocker, blocked);
      this.run("DELETE FROM friends WHERE (a = ? AND b = ?) OR (a = ? AND b = ?)", blocker, blocked, blocked, blocker);
      this.run("DELETE FROM requests WHERE (from_uuid = ? AND to_uuid = ?) OR (from_uuid = ? AND to_uuid = ?)", blocker, blocked, blocked, blocker);
    });
  }
  async unblock(blocker: string, blocked: string) {
    this.run("DELETE FROM blocks WHERE blocker = ? AND blocked = ?", blocker, blocked);
  }

  // ---- messages ----
  async addMessage(from: string, to: string, text: string, now: number) {
    const r = this.run("INSERT INTO messages (from_uuid, to_uuid, text, at) VALUES (?, ?, ?, ?)", from, to, text, now);
    return { id: Number(r.lastInsertRowid), from, to, text, at: now, read: false };
  }
  async thread(a: string, b: string, before: number | null, limit: number) {
    const rows = this.all(
      "SELECT * FROM messages WHERE ((from_uuid = ? AND to_uuid = ?) OR (from_uuid = ? AND to_uuid = ?)) AND id < ? ORDER BY id DESC LIMIT ?",
      a,
      b,
      b,
      a,
      before ?? Number.MAX_SAFE_INTEGER,
      limit,
    );
    return rows.map(msg).reverse();
  }
  async threads(uuid: string) {
    const rows = this.all(
      `SELECT m.*, (SELECT COUNT(*) FROM messages u WHERE u.to_uuid = ? AND u.from_uuid = other AND u.read = 0) AS unread
       FROM (SELECT *, CASE WHEN from_uuid = ? THEN to_uuid ELSE from_uuid END AS other FROM messages WHERE from_uuid = ? OR to_uuid = ?) m
       WHERE m.id = (SELECT MAX(id) FROM messages x WHERE (x.from_uuid = ? AND x.to_uuid = m.other) OR (x.from_uuid = m.other AND x.to_uuid = ?))`,
      uuid,
      uuid,
      uuid,
      uuid,
      uuid,
      uuid,
    );
    return rows.map((r) => ({ with: String(r.other), last: msg(r), unread: Number(r.unread) }));
  }
  async markRead(reader: string, from: string) {
    this.run("UPDATE messages SET read = 1 WHERE to_uuid = ? AND from_uuid = ? AND read = 0", reader, from);
  }
  async incoming(uuid: string, since: number) {
    return this.all("SELECT * FROM messages WHERE to_uuid = ? AND at > ? ORDER BY id LIMIT 200", uuid, since).map(msg);
  }

  // ---- kv ----
  async getKv(key: string) {
    const r = this.get("SELECT value FROM kv WHERE key = ?", key);
    return r ? String(r.value) : undefined;
  }
  async setKv(key: string, value: string) {
    this.run("INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value);
  }

  // ---- payments ----
  async addCheckout(ident: string, uuid: string, bundle: string, now: number) {
    this.run("INSERT OR REPLACE INTO checkouts (ident, uuid, bundle, at) VALUES (?, ?, ?, ?)", ident, uuid, bundle, now);
  }
  async checkout(ident: string) {
    const r = this.get("SELECT uuid, bundle FROM checkouts WHERE ident = ?", ident);
    return r ? { uuid: String(r.uuid), bundle: String(r.bundle) } : undefined;
  }

  // ---- manifests ----
  async setManifest(channel: string, payload: string, signature: string | null) {
    await this.setKv(`manifest:${channel}`, JSON.stringify({ payload, signature }));
  }
  async getManifest(channel: string) {
    const v = await this.getKv(`manifest:${channel}`);
    return v ? (JSON.parse(v) as { payload: string; signature: string | null }) : undefined;
  }
}

/** In-memory database (tests, quick local runs). */
export class MemoryStore extends SqliteStore {
  constructor() {
    super(":memory:");
  }
}
