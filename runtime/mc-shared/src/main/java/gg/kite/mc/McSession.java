package gg.kite.mc;

import gg.kite.core.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The signed-in account. Vanilla looks the player's own profile (skin, cape) up once at startup; when that times out
 * the player wears the default skin all session, so {@link #tick} retries it. {@link #switchTo} swaps the account
 * without a restart: the launcher (KITE_SESSION_PORT / KITE_SESSION_KEY, a loopback endpoint that lives while the
 * game runs) refreshes the chosen account's sign-in and hands the session over.
 */
public final class McSession {
    private McSession() {}

    private static final Logger LOG = LoggerFactory.getLogger("kite");
    /** Waits before each retry of the own-profile lookup. */
    private static final long[] RETRY_MS = { 2000, 5000, 15000, 30000, 60000, 120000 };

    // Render thread.
    private static int retries;
    private static long nextTry;
    private static boolean settled, fetching;

    /** Client tick: retry the own-profile lookup while it failed, with back-off. */
    public static void tick(Minecraft mc) {
        if (settled || fetching || mc.getUser() == null) return;
        long now = System.currentTimeMillis();
        if (now < nextTry) return;
        CompletableFuture<?> f = Compat.profileFuture(mc);
        if (f == null || !f.isDone()) return;
        // Offline accounts (the launcher's placeholder token) have no Mojang profile to fetch.
        if (Compat.offlineSession(mc) || "0".equals(mc.getUser().getAccessToken())) {
            settled = true;
            return;
        }
        Object current = f.isCompletedExceptionally() ? null : f.getNow(null);
        if (Compat.hasTextures(current)) {
            settled = true;
            return;
        }
        if (retries >= RETRY_MS.length) {
            settled = true;
            LOG.warn("Couldn't load your skin from Mojang after {} retries", retries);
            return;
        }
        fetching = true;
        final int attempt = ++retries;
        final UUID id = Compat.userId(mc.getUser());
        LOG.info("Your skin didn't load at startup (profile lookup failed); retrying ({}/{})", attempt, RETRY_MS.length);
        Compat.fetchProfile(mc).whenComplete((result, err) -> mc.execute(() -> {
            fetching = false;
            if (mc.getUser() == null || !id.equals(Compat.userId(mc.getUser()))) return; // switched meanwhile
            if (Compat.hasTextures(result)) {
                Compat.setProfile(mc, result);
                settled = true;
                LOG.info("Loaded your skin on retry {}", attempt);
            } else {
                nextTry = System.currentTimeMillis() + RETRY_MS[attempt - 1];
            }
        }));
    }

    private static void resetRetry() {
        retries = 0;
        nextTry = 0;
        settled = false;
    }

    // ---- live account switching -------------------------------------------------------------------------------------

    /** Called on the render thread with null when the switch worked, else a message for the player. */
    public interface Done { void done(String error); }

    /** True when the launcher offers live switching. */
    public static boolean liveSwitch() { return port() > 0 && key() != null; }

    private static int port() {
        try {
            return Integer.parseInt(System.getenv("KITE_SESSION_PORT").trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String key() {
        String k = System.getenv("KITE_SESSION_KEY");
        return k == null || k.trim().isEmpty() ? null : k.trim();
    }

    /** Ask the launcher for {@code uuid}'s session and sign in as it. Main menu only. */
    public static void switchTo(final String uuid, final Done done) {
        final Minecraft mc = Minecraft.getInstance();
        Thread t = new Thread(() -> {
            String error;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port()), 3000);
                // Refreshing a Microsoft sign-in takes a few round trips.
                s.setSoTimeout(45000);
                Map<String, Object> req = new LinkedHashMap<String, Object>();
                req.put("key", key());
                req.put("uuid", uuid);
                OutputStream out = s.getOutputStream();
                out.write((Json.write(req) + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                String line = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
                Object reply = line == null ? null : Json.parse(line);
                if (!(reply instanceof Map)) throw new IOException("no answer");
                Map<?, ?> m = (Map<?, ?>) reply;
                if (Boolean.TRUE.equals(m.get("ok"))) {
                    final String name = str(m.get("username")), id = str(m.get("uuid")), token = str(m.get("token"));
                    final boolean online = !"offline".equals(m.get("kind"));
                    mc.execute(() -> {
                        try {
                            Compat.switchUser(mc, Compat.newUser(name, uuid(id), token), online);
                            resetRetry();
                            LOG.info("Switched account to {}", name);
                            done.done(null);
                        } catch (RuntimeException e) {
                            LOG.warn("Account switch failed", e);
                            done.done("Couldn't switch accounts: " + e.getMessage());
                        }
                    });
                    return;
                }
                error = String.valueOf(m.get("error"));
            } catch (IOException | RuntimeException e) {
                error = "The launcher isn't reachable (" + e.getMessage() + ")";
            }
            final String message = error;
            mc.execute(() -> done.done(message));
        }, "Kite account switch");
        t.setDaemon(true);
        t.start();
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    /** Mojang ids come without dashes. */
    static UUID uuid(String s) {
        String h = s.replace("-", "");
        if (h.length() != 32) return UUID.fromString(s);
        return UUID.fromString(h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-" + h.substring(16, 20) + "-" + h.substring(20));
    }
}
