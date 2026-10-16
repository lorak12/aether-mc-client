package gg.kite.core.cosmetics;

import gg.kite.core.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every cosmetic the client knows: the built-in set (jar resources under {@value #BUILTIN}, listed in index.json) and
 * the player's own under {@code config/kite/cosmetics} (see {@link LocalCosmetics}: folders with a cosmetic.json,
 * pictures and GIFs that become capes, OBJ models).
 *
 * <pre>
 * {
 *   "id": "wings-angel",              built-in only; local ids are "local/&lt;folder&gt;"
 *   "name": "Angel Wings", "description": "...", "slot": "wings", "rarity": "epic",
 *   "texture": "texture.png",         default texture.png
 *   "textureSize": [64, 64],          UV space of one frame; the PNG may be any multiple (high resolution)
 *   "frames": 1, "frameTime": 100,    frames are stacked vertically; capes infer the count from the 2:1 layout
 *   "translucent": false,             models: blend semi-transparent pixels (default: cutout)
 *   "icon": [0, 0, 64, 64],           thumbnail region in UV space
 *   "iconImage": "icon.png",          or a pre-rendered thumbnail (the starters have one)
 *   "parts": [ ... ]                  models, see CosmeticModel
 * }
 * </pre>
 */
public final class CosmeticRegistry {
    static final String BUILTIN = "/kite/cosmetics/";
    public static final String LOCAL_PREFIX = "local/";
    /** Published cosmetics downloaded from the backend, inside the local folder. */
    static final String REMOTE = ".remote";

    private Cosmetic draft;

    private final Map<String, Cosmetic> byId = new LinkedHashMap<String, Cosmetic>();
    private final List<String> problems = new ArrayList<String>();
    private Path localDir;

    public synchronized Cosmetic get(String id) {
        if (id == null) return null;
        if (draft != null && draft.id.equals(id)) return draft;
        return byId.get(id);
    }

    /** The importer's work in progress: found by {@link #get} (for the preview) but not listed anywhere. */
    public synchronized void setDraft(Cosmetic c) { draft = c; }

    public synchronized List<Cosmetic> all() { return new ArrayList<Cosmetic>(byId.values()); }

    public synchronized List<Cosmetic> bySlot(Slot slot) {
        List<Cosmetic> out = new ArrayList<Cosmetic>();
        for (Cosmetic c : byId.values()) if (c.slot == slot) out.add(c);
        return out;
    }

    /** Cosmetics that failed to load, as "where: why". */
    public synchronized List<String> problems() { return Collections.unmodifiableList(new ArrayList<String>(problems)); }

    public synchronized Path localDir() { return localDir; }

    /** (Re)load the built-in set and, when {@code local} is non-null, the player's folder (created if missing). */
    public synchronized void load(Path local) {
        byId.clear();
        problems.clear();
        localDir = local;
        loadBuiltin();
        if (local != null) loadLocal(local);
    }

    private void loadBuiltin() {
        byte[] index = resource(BUILTIN + "index.json");
        if (index == null) {
            problems.add("built-in: index.json missing");
            return;
        }
        Object list;
        try {
            list = Json.parseObject(new String(index, StandardCharsets.UTF_8)).get("cosmetics");
        } catch (RuntimeException e) {
            problems.add("built-in: index.json: " + e.getMessage());
            return;
        }
        if (!(list instanceof List)) return;
        for (Object o : (List<?>) list) {
            final String folder = String.valueOf(o);
            final String base = BUILTIN + folder + "/";
            try {
                byte[] json = resource(base + "cosmetic.json");
                if (json == null) throw new IllegalArgumentException("cosmetic.json missing");
                Map<String, Object> m = Json.parseObject(new String(json, StandardCharsets.UTF_8));
                String tex = base + str(m.get("texture"), "texture.png");
                String icon = m.get("iconImage") instanceof String ? base + m.get("iconImage") : null;
                add(parse(folder, m, true, resource(tex), resourceSource(tex), icon == null ? null : resourceSource(icon)));
            } catch (RuntimeException e) {
                problems.add("built-in " + folder + ": " + e.getMessage());
            }
        }
    }

    private void loadLocal(Path dir) {
        LocalCosmetics local = new LocalCosmetics(dir);
        local.load();
        problems.addAll(local.problems);
        List<Cosmetic> all = new ArrayList<Cosmetic>(local.loaded);
        Path remote = dir.resolve(REMOTE);
        if (Files.isDirectory(remote)) {
            LocalCosmetics published = new LocalCosmetics(remote, true);
            published.load();
            for (String p : published.problems) problems.add("store " + p);
            all.addAll(published.loaded);
        }
        for (Cosmetic c : all) {
            try {
                add(c);
            } catch (RuntimeException e) {
                problems.add(c.id + ": " + e.getMessage());
            }
        }
    }

    private void add(Cosmetic c) {
        if (byId.containsKey(c.id)) throw new IllegalArgumentException("duplicate id " + c.id);
        byId.put(c.id, c);
    }

    /**
     * Build a cosmetic from its JSON. {@code head} holds at least the texture's first 24 bytes; {@code iconImage} is the
     * optional pre-rendered thumbnail.
     */
    static Cosmetic parse(String folder, Map<String, Object> m, boolean builtin, byte[] head, Cosmetic.TextureSource source,
                          Cosmetic.TextureSource iconImage) {
        return parse(folder, m, builtin, head, source, iconImage, null);
    }

    /** {@code prebuilt}: an already built model (OBJ import) used instead of {@code "parts"}. */
    static Cosmetic parse(String folder, Map<String, Object> m, boolean builtin, byte[] head, Cosmetic.TextureSource source,
                          Cosmetic.TextureSource iconImage, CosmeticModel prebuilt) {
        String id = builtin ? str(m.get("id"), folder) : LOCAL_PREFIX + folder.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        Slot slot = Slot.byId(str(m.get("slot"), ""));
        if (slot == null) throw new IllegalArgumentException("unknown slot \"" + m.get("slot") + "\"");
        int[] size = Png.size(head);
        if (size == null) throw new IllegalArgumentException("texture is missing or not a PNG");
        int w = size[0], h = size[1];
        int frames;
        if (m.get("frames") instanceof Number) frames = ((Number) m.get("frames")).intValue();
        else if (slot == Slot.CAPE && h > w / 2) frames = Math.max(1, Math.round(h / (w / 2f)));
        else frames = 1;
        if (frames < 1 || frames > 256) throw new IllegalArgumentException("frames must be 1..256");
        if (h % frames != 0) throw new IllegalArgumentException("texture height " + h + " is not " + frames + " equal frames");
        int frameH = h / frames;
        int frameTime = (int) CosmeticModel.num(m.get("frameTime"), 100);

        float uvW, uvH;
        List<?> ts = m.get("textureSize") instanceof List ? (List<?>) m.get("textureSize") : null;
        if (ts != null && ts.size() == 2 && ts.get(0) instanceof Number && ts.get(1) instanceof Number) {
            uvW = ((Number) ts.get(0)).floatValue();
            uvH = ((Number) ts.get(1)).floatValue();
        } else if (slot == Slot.CAPE) {
            uvW = 64;
            uvH = 32;
        } else {
            uvW = w;
            uvH = frameH;
        }

        CosmeticModel model = null;
        float[] icon;
        if (slot == Slot.CAPE) {
            if (Math.abs(w / (float) frameH - 2f) > 0.01f) throw new IllegalArgumentException("cape frames must be 2:1 (like 64x32), got " + w + "x" + frameH);
            icon = new float[] { 1 / 64f, 1 / 32f, 11 / 64f, 17 / 32f }; // the cape's outside
        } else {
            model = prebuilt != null ? prebuilt : CosmeticModel.parse(m.get("parts"), uvW, uvH);
            if (prebuilt == null && m.get("placement") != null) model.setPlacement(CosmeticModel.Placement.parse(m.get("placement")));
            icon = new float[] { 0, 0, 1, 1 };
        }
        List<?> ic = m.get("icon") instanceof List ? (List<?>) m.get("icon") : null;
        if (ic != null && ic.size() == 4) {
            float[] r = new float[4];
            for (int i = 0; i < 4; i++) r[i] = CosmeticModel.num(ic.get(i), 0);
            icon = new float[] { r[0] / uvW, r[1] / uvH, (r[0] + r[2]) / uvW, (r[1] + r[3]) / uvH };
        }
        String name = str(m.get("name"), prettify(folder));
        return new Cosmetic(id, name, str(m.get("description"), ""), slot, Cosmetic.Rarity.byId(str(m.get("rarity"), "common")), builtin, model,
                w, h, frames, frameTime, Boolean.TRUE.equals(m.get("translucent")), icon, source, iconImage);
    }

    private static Cosmetic.TextureSource resourceSource(final String path) {
        return () -> {
            byte[] b = resource(path);
            if (b == null) throw new IOException("missing " + path);
            return b;
        };
    }

    /** "my_cool-cape" -> "My cool cape". */
    static String prettify(String folder) {
        String s = folder.replace('_', ' ').replace('-', ' ').trim();
        return s.isEmpty() ? "Cosmetic" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String str(Object o, String def) { return o instanceof String && !((String) o).isEmpty() ? (String) o : def; }

    static byte[] head(Path p) throws IOException {
        if (!Files.isRegularFile(p)) return null;
        try (InputStream in = Files.newInputStream(p)) {
            byte[] b = new byte[24];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return n == b.length ? b : null;
        }
    }

    static byte[] resource(String path) {
        try (InputStream in = CosmeticRegistry.class.getResourceAsStream(path)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
