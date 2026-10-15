package gg.aether.core;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;

/**
 * Players' faces by uuid for the account switcher, also for accounts that aren't signed in: the skin's face with its
 * hat layer on top (8x8 ARGB, like vanilla's player heads). Looked up off-thread from Mojang's session server (the
 * profile, then its skin texture) and kept for the session; failed lookups are retried after a minute.
 */
public final class SkinFaces {
    private SkinFaces() {}

    /** No Mojang skin: an offline account, or a player wearing a default skin. */
    public static final int[] NONE = new int[0];

    static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final long RETRY_MS = 60_000;

    /** GET {@code url}: the body, or null when there is nothing there (204, 404...). Throws when it should be retried. */
    interface Http { byte[] get(String url) throws IOException; }

    static Http http = SkinFaces::download;

    private static final Map<String, int[]> FACES = new HashMap<String, int[]>();
    private static final Map<String, Long> FAILED = new HashMap<String, Long>();
    private static final Set<String> PENDING = new HashSet<String>();
    private static final ExecutorService LOOKUPS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aether-skin-faces");
        t.setDaemon(true);
        return t;
    });

    /**
     * {@code uuid}'s face (64 ARGB pixels), {@link #NONE}, or null while it is being looked up (the first call starts
     * the lookup). Any thread.
     */
    public static synchronized int[] face(String uuid) {
        final String id = normalize(uuid);
        if (id == null) return NONE;
        int[] f = FACES.get(id);
        if (f != null || PENDING.contains(id)) return f;
        Long failedAt = FAILED.get(id);
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_MS) return null;
        PENDING.add(id);
        LOOKUPS.execute(() -> {
            int[] result = null;
            try {
                result = lookup(id);
            } catch (Exception | LinkageError ignored) {
                // Offline, rate limited or a bad answer: try again later.
            }
            synchronized (SkinFaces.class) {
                PENDING.remove(id);
                if (result != null) FACES.put(id, result);
                else FAILED.put(id, System.currentTimeMillis());
            }
        });
        return null;
    }

    /** Forget every face (tests). */
    static synchronized void clear() {
        FACES.clear();
        FAILED.clear();
    }

    /** 32 lower-case hex digits, or null when {@code uuid} isn't one. */
    static String normalize(String uuid) {
        if (uuid == null) return null;
        String id = uuid.replace("-", "").toLowerCase(Locale.ROOT);
        return id.matches("[0-9a-f]{32}") ? id : null;
    }

    static int[] lookup(String id) throws IOException {
        byte[] profile = http.get(PROFILE_URL + id);
        if (profile == null) return NONE;
        String url = skinUrl(new String(profile, StandardCharsets.UTF_8));
        if (url == null) return NONE;
        byte[] png = http.get(url);
        if (png == null) return NONE;
        BufferedImage skin = ImageIO.read(new ByteArrayInputStream(png));
        if (skin == null) throw new IOException("the skin is not an image");
        return face(skin);
    }

    /** The skin texture URL in a session server profile (base64 "textures" property), or null without a skin. */
    static String skinUrl(String profileJson) {
        Object props = Json.parseObject(profileJson).get("properties");
        if (!(props instanceof List)) return null;
        for (Object o : (List<?>) props) {
            if (!(o instanceof Map) || !"textures".equals(((Map<?, ?>) o).get("name"))) continue;
            Object value = ((Map<?, ?>) o).get("value");
            if (!(value instanceof String)) return null;
            Map<String, Object> textures = Json.parseObject(new String(Base64.getDecoder().decode((String) value), StandardCharsets.UTF_8));
            Object t = textures.get("textures");
            Object skin = t instanceof Map ? ((Map<?, ?>) t).get("SKIN") : null;
            Object url = skin instanceof Map ? ((Map<?, ?>) skin).get("url") : null;
            // Mojang hands out http:// texture links; the same files are served over https.
            return url instanceof String ? ((String) url).replaceFirst("^http://", "https://") : null;
        }
        return null;
    }

    /** The face (8x8 at 8,8) with the hat layer (8x8 at 40,8) over it, from a 64x64 or legacy 64x32 skin. */
    public static int[] face(BufferedImage skin) {
        int[] out = new int[64];
        if (skin.getWidth() < 16 || skin.getHeight() < 16) return out;
        boolean hat = skin.getWidth() >= 48;
        // Legacy skins often fill the hat with an opaque colour; vanilla ignores a hat without transparency there.
        if (hat && skin.getHeight() < 64) {
            boolean seeThrough = false;
            for (int y = 0; y < 8 && !seeThrough; y++) for (int x = 0; x < 8 && !seeThrough; x++) seeThrough = (skin.getRGB(40 + x, 8 + y) >>> 24) < 128;
            hat = seeThrough;
        }
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int c = skin.getRGB(8 + x, 8 + y) | 0xFF000000;
                if (hat) c = over(skin.getRGB(40 + x, 8 + y), c);
                out[y * 8 + x] = c;
            }
        }
        return out;
    }

    /** {@code top} (ARGB) blended over the opaque {@code below}. */
    private static int over(int top, int below) {
        int a = top >>> 24;
        if (a == 0) return below;
        if (a == 255) return top;
        int r = ((top >> 16 & 0xFF) * a + (below >> 16 & 0xFF) * (255 - a)) / 255;
        int g = ((top >> 8 & 0xFF) * a + (below >> 8 & 0xFF) * (255 - a)) / 255;
        int b = ((top & 0xFF) * a + (below & 0xFF) * (255 - a)) / 255;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static byte[] download(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Aether");
            int code = c.getResponseCode();
            if (code == 204 || code == 400 || code == 404) return null;
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 1 << 20) throw new IOException("too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
