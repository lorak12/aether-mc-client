package gg.kite.core.cosmetics;

import gg.kite.core.ClientHost;
import gg.kite.core.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Talks to the Kite backend on a background thread: signs in by proving the Minecraft session (Mojang "join server"
 * with a backend nonce), keeps the loadout in sync both ways with the launcher's wardrobe, sends a presence heartbeat,
 * and asks which players in the player list run the client and what they wear. Failures only change {@link #status()};
 * the game never waits.
 *
 * <p>Loadout sync: the backend's loadout is read on sign-in and every {@link #PULL_MS}. Each answer comes with the local
 * loadout it was based on; if the player has not changed anything since, the game takes the backend's (so equipping in
 * the launcher shows in game), otherwise the local edit wins and is uploaded. Only local edits are uploaded, so starting
 * the game never overwrites what was picked in the launcher.
 */
final class CosmeticsSync {
    static final long HEARTBEAT_MS = 60_000, LOOKUP_MS = 15_000, PULL_MS = 15_000, RETRY_MS = 5 * 60_000, ERROR_RETRY_MS = 60_000, TOKEN_MS = 50 * 60_000;
    static final int MAX_LOOKUP = 200;

    private final String base;
    private final Cosmetics owner;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kite-cosmetics");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean();

    /** The backend's loadout, and the local loadout it answers (what was pushed, or what was synced when pulling). */
    static final class Answer {
        final Map<String, Object> server, basis;
        final List<String> rejected;

        Answer(Map<String, Object> server, Map<String, Object> basis, List<String> rejected) {
            this.server = server;
            this.basis = basis;
            this.rejected = rejected;
        }
    }

    // Client thread.
    private long nextHeartbeat, nextLookup, nextPull;
    /** The backend's loadout has been read for this account; until then nothing is uploaded. */
    private boolean pulled;
    // Worker thread writes, client thread reads.
    private volatile long retryAt;
    private volatile Answer answer;
    /** A loadout the backend refused outright (not retried until it changes). */
    private volatile Map<String, Object> refused;
    private volatile String status = "Connecting to the Kite backend...";
    /** Account the last round ran for; switching accounts in game starts over (sign in, push the loadout). */
    private String lastUuid;
    /** The host and account as of the last tick, for store requests started from the menu. */
    private volatile ClientHost lastHost;
    private volatile String sessionUuid, sessionName;
    /** The tick clock (token expiry is measured on it, for menu requests too). */
    private volatile long lastNow;
    /** Server in the last heartbeat; a change sends the next one right away so friends can join. */
    private String lastServer = "";
    // Worker thread only.
    private String token, tokenFor;
    private long tokenUntil;

    CosmeticsSync(String base, Cosmetics owner) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.owner = owner;
    }

    String status() { return status; }

    void close() { worker.shutdownNow(); }

    /** Client thread, every tick: apply the backend's answer, then start a round of work when something is due. */
    void tick(final ClientHost host, final long now) {
        final String uuid = host.sessionUuid(), name = host.sessionName();
        lastHost = host;
        lastNow = now;
        sessionUuid = uuid;
        sessionName = name;
        if (busy.get()) return;
        if (uuid == null || name == null) return;
        if (!uuid.equals(lastUuid)) {
            lastUuid = uuid;
            answer = null;
            refused = null;
            pulled = false;
            owner.setOwned(null);
            owner.shop().wallet(-1L, false);
            nextHeartbeat = nextLookup = nextPull = 0;
        }
        Answer a = answer;
        if (a != null) {
            answer = null;
            reconcile(owner, a);
            pulled = true;
        }
        if (now < retryAt) return;
        final String server = host.currentServer() == null ? "" : host.currentServer();
        if (!server.equals(lastServer)) {
            lastServer = server;
            nextHeartbeat = 0;
        }
        final Map<String, Object> shared = owner.sharedLoadout();
        final boolean push = pulled && !shared.equals(owner.synced()) && !shared.equals(refused);
        final boolean pull = !push && now >= nextPull;
        final Map<String, Object> basis = pull ? owner.synced() : shared;
        final boolean heartbeat = now >= nextHeartbeat;
        final List<String> lookup = now >= nextLookup ? players(host) : null;
        if (!push && !pull && !heartbeat && lookup == null) return;
        if (pull) nextPull = now + PULL_MS;
        if (heartbeat) nextHeartbeat = now + HEARTBEAT_MS;
        if (lookup != null) nextLookup = now + LOOKUP_MS;
        busy.set(true);
        worker.execute(() -> {
            try {
                round(host, uuid, name, push ? shared : null, pull, basis, heartbeat ? server : null, lookup, now);
            } finally {
                busy.set(false);
            }
        });
    }

    /**
     * Client thread: take the backend's loadout unless the player changed theirs since {@code a.basis}. The very first
     * sync (nothing synced yet) keeps a local loadout when the backend has none, so it gets uploaded.
     */
    static void reconcile(Cosmetics owner, Answer a) {
        Map<String, Object> local = owner.sharedLoadout();
        boolean untouched = a.basis == null ? !isEmpty(a.server) || isEmpty(local) : local.equals(a.basis);
        if (untouched && !local.equals(a.server)) owner.adopt(a.server);
        owner.synced(a.server);
    }

    static boolean isEmpty(Map<String, Object> loadout) {
        for (Object v : loadout.values()) if (v != null) return false;
        return true;
    }

    private List<String> players(ClientHost host) {
        List<String> out = new ArrayList<String>();
        for (String u : host.playerUuids()) {
            String n = Cosmetics.norm(u);
            if (!n.isEmpty() && !owner.isSelf(n) && !out.contains(n)) out.add(n);
            if (out.size() >= MAX_LOOKUP) break;
        }
        return out;
    }

    /** Signals a 401: the token is dropped and the next round signs in again. */
    private static final class Unauthorized extends IOException {
        Unauthorized() { super("unauthorized"); }
    }

    /** {@code heartbeat}: the current server ("" for none) when a heartbeat is due, null otherwise. */
    private void round(ClientHost host, String uuid, String name, Map<String, Object> shared, boolean pull, Map<String, Object> basis, String heartbeat,
            List<String> lookup, long now) {
        try {
            if (!signIn(host, uuid, name, now)) return;
            String loadoutProblem = null;
            if (shared != null) {
                Response r = call("PUT", "/v1/me/loadout?lenient=1", Json.write(shared));
                if (r.code == 200) {
                    List<String> rejected = strings(r.json().get("rejected"));
                    answer = new Answer(loadout(r.json().get("loadout")), shared, rejected);
                    if (!rejected.isEmpty()) loadoutProblem = "Not in your wardrobe yet: " + String.join(", ", rejected) + " (get it in the launcher store)";
                } else {
                    // Refused outright: not retried until the loadout changes.
                    refused = shared;
                    loadoutProblem = "The backend rejected your loadout (" + r.error() + "); others may not see it";
                }
            } else if (pull) {
                Response r = call("GET", "/v1/me", null);
                if (r.code == 200) {
                    Map<String, Object> me = r.json();
                    owner.setOwned(new java.util.HashSet<String>(strings(me.get("owned"))));
                    owner.shop().wallet(me.get("coins"), me.get("unlimited"));
                    answer = new Answer(loadout(me.get("loadout")), basis, Collections.<String>emptyList());
                }
            }
            if (heartbeat != null) {
                Map<String, Object> hb = new LinkedHashMap<String, Object>();
                if (!heartbeat.isEmpty()) hb.put("server", heartbeat);
                call("POST", "/v1/presence/heartbeat", Json.write(hb));
            }
            if (lookup != null) {
                if (lookup.isEmpty()) {
                    owner.setRemote(Collections.<String, Cosmetics.Remote>emptyMap(), Collections.<String>emptyList());
                } else {
                    Map<String, Object> body = new LinkedHashMap<String, Object>();
                    body.put("uuids", lookup);
                    Response r = call("POST", "/v1/presence/lookup", Json.write(body));
                    if (r.code == 200) owner.setRemote(parseUsers(r.json().get("users")), lookup);
                }
            }
            if (loadoutProblem != null) status = loadoutProblem;
            else if (shared != null || !status.startsWith("Not in your wardrobe") && !status.startsWith("The backend rejected")) {
                int n = owner.remoteUsers();
                status = "Connected" + (n == 0 ? "" : " · " + n + " client user" + (n == 1 ? "" : "s") + " nearby");
            }
        } catch (Unauthorized e) {
            token = null;
        } catch (IOException | RuntimeException e) {
            status = "Backend unreachable (" + e.getMessage() + "), retrying in a minute";
            retryAt = now + ERROR_RETRY_MS;
        }
    }

    // ---- store (requests from the menu) ------------------------------------------------------------------------------

    /** GET /v1/store on the worker; the answer goes to {@code shop}. */
    void fetchStore(final Shop shop) {
        final ClientHost host = lastHost;
        final String uuid = sessionUuid, name = sessionName;
        if (host == null || uuid == null || name == null) {
            shop.storeFailed("Not signed in yet");
            return;
        }
        worker.execute(() -> {
            try {
                Response r = authedCall(host, uuid, name, "GET", "/v1/store", null);
                if (r == null) shop.storeFailed(status);
                else if (r.code == 200) {
                    Map<String, Object> json = r.json();
                    shop.storeLoaded(json);
                    if (syncPublished(json.get("items"))) owner.remoteChanged();
                }
                else shop.storeFailed(r.error());
            } catch (IOException | RuntimeException e) {
                shop.storeFailed("Backend unreachable (" + e.getMessage() + ")");
            }
        });
    }

    /**
     * Worker thread: keep {@code <cosmetics>/.remote/} in step with the published cosmetics the store lists (download
     * new versions, delete withdrawn ones). True when something changed.
     */
    boolean syncPublished(Object items) {
        java.nio.file.Path local = owner.registry().localDir();
        if (local == null || !(items instanceof List)) return false;
        java.nio.file.Path dir = local.resolve(CosmeticRegistry.REMOTE);
        boolean changed = false;
        java.util.Set<String> keep = new java.util.HashSet<String>();
        for (Object o : (List<?>) items) {
            if (!(o instanceof Map) || !Boolean.TRUE.equals(((Map<?, ?>) o).get("custom"))) continue;
            Map<?, ?> item = (Map<?, ?>) o;
            String id = String.valueOf(item.get("id"));
            if (!id.matches("custom-[a-z0-9-]{1,40}")) continue;
            keep.add(id);
            String version = String.valueOf(item.get("version"));
            java.nio.file.Path folder = dir.resolve(id), stamp = folder.resolve(".version");
            try {
                if (java.nio.file.Files.isRegularFile(stamp) && version.equals(new String(java.nio.file.Files.readAllBytes(stamp), StandardCharsets.UTF_8).trim())) continue;
                Response r = call("GET", "/v1/cosmetics/" + id + "/bundle", null, 16 << 20);
                if (r.code != 200) continue;
                Object files = r.json().get("files");
                if (!(files instanceof Map)) continue;
                java.nio.file.Path tmp = dir.resolve(id + ".tmp");
                Importer.deleteTree(tmp);
                java.nio.file.Files.createDirectories(tmp);
                for (Map.Entry<?, ?> f : ((Map<?, ?>) files).entrySet()) {
                    String fname = String.valueOf(f.getKey());
                    if (!fname.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}") || !(f.getValue() instanceof String)) continue;
                    java.nio.file.Files.write(tmp.resolve(fname), java.util.Base64.getDecoder().decode((String) f.getValue()));
                }
                // The store is the authority on what it is and where it goes.
                java.nio.file.Path json = tmp.resolve("cosmetic.json");
                Map<String, Object> meta = java.nio.file.Files.isRegularFile(json)
                        ? Json.parseObject(new String(java.nio.file.Files.readAllBytes(json), StandardCharsets.UTF_8)) : new LinkedHashMap<String, Object>();
                meta.put("id", id);
                for (String k : new String[] { "slot", "name", "rarity", "description" }) if (item.get(k) instanceof String) meta.put(k, item.get(k));
                java.nio.file.Files.write(json, Json.write(meta).getBytes(StandardCharsets.UTF_8));
                java.nio.file.Files.write(tmp.resolve(".version"), version.getBytes(StandardCharsets.UTF_8));
                Importer.deleteTree(folder);
                java.nio.file.Files.move(tmp, folder);
                changed = true;
            } catch (IOException | RuntimeException e) {
                // Next refresh tries again.
            }
        }
        if (java.nio.file.Files.isDirectory(dir)) {
            try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dir)) {
                for (java.nio.file.Path p : ds) {
                    String n = p.getFileName().toString();
                    if (n.startsWith(".") || keep.contains(n)) continue;
                    Importer.deleteTree(p);
                    changed = true;
                }
            } catch (IOException ignored) { }
        }
        return changed;
    }

    /** POST /v1/admin/cosmetics (admins): publish an imported cosmetic to the store. */
    void publish(final Shop shop, final String body) {
        final ClientHost host = lastHost;
        final String uuid = sessionUuid, name = sessionName;
        if (host == null || uuid == null || name == null) {
            shop.published(null, "Not signed in");
            return;
        }
        worker.execute(() -> {
            try {
                Response r = authedCall(host, uuid, name, "POST", "/v1/admin/cosmetics", body);
                if (r == null) shop.published(null, "Not signed in");
                else if (r.code == 200) {
                    Object c = r.json().get("cosmetic");
                    shop.published(c instanceof Map ? String.valueOf(((Map<?, ?>) c).get("id")) : "?", null);
                } else shop.published(null, r.error());
            } catch (IOException | RuntimeException e) {
                shop.published(null, "backend unreachable (" + e.getMessage() + ")");
            }
        });
    }

    /** DELETE /v1/admin/cosmetics/:id (admins). */
    void unpublish(final Shop shop, final String id) {
        final ClientHost host = lastHost;
        final String uuid = sessionUuid, name = sessionName;
        if (host == null || uuid == null || name == null) return;
        worker.execute(() -> {
            try {
                Response r = authedCall(host, uuid, name, "DELETE", "/v1/admin/cosmetics/" + id, null);
                shop.unpublished(id, r != null && r.code == 200 ? null : r == null ? "Not signed in" : r.error());
            } catch (IOException | RuntimeException e) {
                shop.unpublished(id, "backend unreachable");
            }
        });
    }

    /** POST /v1/store/buy on the worker. */
    void buy(final Shop shop, final String id) {
        final ClientHost host = lastHost;
        final String uuid = sessionUuid, name = sessionName;
        if (host == null || uuid == null || name == null) {
            shop.buyFailed(id, "unauthorized");
            return;
        }
        worker.execute(() -> {
            try {
                Map<String, Object> body = new LinkedHashMap<String, Object>();
                body.put("id", id);
                Response r = authedCall(host, uuid, name, "POST", "/v1/store/buy", Json.write(body));
                if (r == null) shop.buyFailed(id, "unauthorized");
                else if (r.code == 200) shop.bought(id, r.json());
                else shop.buyFailed(id, r.error());
            } catch (IOException | RuntimeException e) {
                shop.buyFailed(id, "backend unreachable");
            }
        });
    }

    /** Signed-in call (worker thread); signs in again once on a 401. Null when the account can't sign in. */
    private Response authedCall(ClientHost host, String uuid, String name, String method, String path, String json) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!signIn(host, uuid, name, lastNow)) return null;
            try {
                return call(method, path, json);
            } catch (Unauthorized e) {
                token = null;
            }
        }
        return null;
    }

    /** Nonce -> Mojang join -> JWT. False (with a status) when the session can't be proven. */
    private boolean signIn(ClientHost host, String uuid, String name, long now) throws IOException {
        if (token != null && now < tokenUntil && uuid.equals(tokenFor)) return true;
        token = null;
        Response nonce = call("POST", "/v1/auth/nonce", "{}");
        Object serverId = nonce.json().get("serverId");
        if (nonce.code != 200 || !(serverId instanceof String)) throw new IOException("no nonce (" + nonce.code + ")");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("username", name);
        Response r;
        if (host.joinSession((String) serverId)) {
            body.put("serverId", serverId);
            r = call("POST", "/v1/auth/mojang", Json.write(body));
        } else {
            // Offline (development) accounts: only backends started with KITE_ALLOW_OFFLINE accept these.
            r = call("POST", "/v1/auth/offline", Json.write(body));
            if (r.code != 200) {
                status = "Could not verify your Minecraft account (offline account?); only you see your cosmetics";
                retryAt = now + RETRY_MS;
                return false;
            }
        }
        Object t = r.json().get("token");
        if (r.code != 200 || !(t instanceof String)) {
            status = "The backend did not accept your account (" + r.error() + ")";
            retryAt = now + RETRY_MS;
            return false;
        }
        token = (String) t;
        tokenFor = uuid;
        tokenUntil = now + TOKEN_MS;
        return true;
    }

    /** A backend loadout object as {@link Cosmetics#sharedLoadout()} shapes it: every slot, null when empty. */
    static Map<String, Object> loadout(Object json) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<?, ?> m = json instanceof Map ? (Map<?, ?>) json : Collections.emptyMap();
        for (Slot s : Slot.values()) out.put(s.id(), m.get(s.id()) instanceof String ? m.get(s.id()) : null);
        return out;
    }

    static List<String> strings(Object json) {
        List<String> out = new ArrayList<String>();
        if (json instanceof List) for (Object o : (List<?>) json) if (o instanceof String) out.add((String) o);
        return out;
    }

    static Map<String, Cosmetics.Remote> parseUsers(Object users) {
        Map<String, Cosmetics.Remote> out = new HashMap<String, Cosmetics.Remote>();
        if (!(users instanceof Map)) return out;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) users).entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<?, ?> u = (Map<?, ?>) e.getValue();
            Map<Slot, String> loadout = new java.util.EnumMap<Slot, String>(Slot.class);
            if (u.get("loadout") instanceof Map) {
                for (Map.Entry<?, ?> l : ((Map<?, ?>) u.get("loadout")).entrySet()) {
                    Slot s = Slot.byId(String.valueOf(l.getKey()));
                    if (s != null && l.getValue() instanceof String) loadout.put(s, (String) l.getValue());
                }
            }
            out.put(Cosmetics.norm(String.valueOf(e.getKey())), new Cosmetics.Remote(!Boolean.FALSE.equals(u.get("badge")), loadout));
        }
        return out;
    }

    // ---- HTTP -------------------------------------------------------------------------------------------------------

    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        Map<String, Object> json() {
            try {
                return Json.parseObject(body);
            } catch (RuntimeException e) {
                return new HashMap<String, Object>();
            }
        }

        String error() {
            Object e = json().get("error");
            return e instanceof String ? (String) e : "HTTP " + code;
        }
    }

    private Response call(String method, String path, String json) throws IOException { return call(method, path, json, 1 << 20); }

    private Response call(String method, String path, String json, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Kite");
            c.setRequestProperty("Accept", "application/json");
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            if (json != null) {
                byte[] b = json.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(b);
                }
            }
            int code = c.getResponseCode();
            if (code == 401 && token != null) throw new Unauthorized();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(code, in == null ? "" : read(in, maxBytes));
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in, int maxBytes) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) {
                if (out.size() > maxBytes) throw new IOException("response too large");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
