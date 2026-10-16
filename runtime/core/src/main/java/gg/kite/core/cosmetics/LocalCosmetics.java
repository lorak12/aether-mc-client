package gg.kite.core.cosmetics;

import gg.kite.core.Json;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * The player's own cosmetics in {@code config/kite/cosmetics}. Each entry becomes one cosmetic:
 * <ul>
 *   <li>a picture in the folder's root (PNG, JPG, GIF, BMP): a cape. A 2:1 image (64x32, a vertical strip of 2:1
 *       frames, or an animated GIF of them) is a cape texture as it is; any other picture is laid out on the cape
 *       ({@link Images#pictureCape}). GIFs animate.</li>
 *   <li>a folder with a cosmetic.json: see {@link CosmeticRegistry}. The texture may be a JPG or a GIF too, and
 *       {@code "model": "file.obj"} uses an OBJ model ({@link ObjModel}) instead of {@code "parts"}.</li>
 *   <li>a folder with an .obj and no cosmetic.json: an OBJ model with defaults (the slot from the folder name).</li>
 * </ul>
 * Converted textures, atlases and thumbnails are cached in {@code .cache/} (keyed by the source files' size and
 * date) so loading stays fast; names starting with "." or "_" (the cache, the Blender kit) are skipped.
 */
final class LocalCosmetics {
    static final String CACHE = ".cache";

    private final Path dir, cache;
    /** Published cosmetics downloaded from the backend: shared like the built-in set, ids from their cosmetic.json. */
    private final boolean shared;
    final List<Cosmetic> loaded = new ArrayList<Cosmetic>();
    final List<String> problems = new ArrayList<String>();
    private final Set<String> usedCache = new HashSet<String>();

    LocalCosmetics(Path dir) { this(dir, false); }

    LocalCosmetics(Path dir, boolean shared) {
        this.dir = dir;
        this.cache = dir.resolve(CACHE);
        this.shared = shared;
    }

    /** Load one folder (the importer's draft); problems land in {@link #problems}. */
    void loadFolder(Path folder) {
        String file = folder.getFileName().toString();
        try {
            folder(folder, file);
        } catch (IOException | RuntimeException e) {
            problems.add(file + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    void load() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            problems.add(dir + ": " + e.getMessage());
            return;
        }
        List<Path> entries = list(dir);
        if (entries == null) return;
        for (Path p : entries) {
            String file = p.getFileName().toString();
            if (file.startsWith(".") || file.startsWith("_")) continue;
            try {
                if (Files.isDirectory(p)) folder(p, file);
                else if (Images.isImageName(file)) picture(p, file);
                else if (file.toLowerCase(Locale.ROOT).endsWith(".bbmodel")) {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("model", file);
                    modelFolder(dir, stem(file), m);
                }
            } catch (IOException | RuntimeException e) {
                problems.add(file + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        sweepCache();
    }

    // ---- entries ----------------------------------------------------------------------------------------------------

    private void picture(Path p, String file) throws IOException {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("slot", "cape");
        Tex t = texture(p, true, false);
        t.into(m);
        loaded.add(CosmeticRegistry.parse(stem(file), m, shared, head(t.png), fileSource(t.png), null, null));
    }

    private void folder(Path p, String name) throws IOException {
        Path json = p.resolve("cosmetic.json");
        Map<String, Object> m;
        if (Files.isRegularFile(json)) {
            m = Json.parseObject(new String(Files.readAllBytes(json), StandardCharsets.UTF_8));
        } else {
            Path model = firstWith(p, ".bbmodel");
            if (model == null) model = firstWith(p, ".obj");
            if (model == null) return; // not a cosmetic folder
            m = new LinkedHashMap<String, Object>();
            m.put("model", model.getFileName().toString());
        }
        if (m.get("model") instanceof String) {
            modelFolder(p, name, m);
            return;
        }
        Slot slot = Slot.byId(str(m.get("slot"), ""));
        if (slot == null) throw new IllegalArgumentException("unknown slot \"" + m.get("slot") + "\"");
        String texName = str(m.get("texture"), null);
        Path tex = texName != null ? inside(p, texName) : defaultTexture(p);
        if (tex == null) throw new IllegalArgumentException("no texture.png (or other picture) in the folder");
        Tex t = texture(tex, slot == Slot.CAPE, Boolean.TRUE.equals(m.get("picture")));
        t.into(m);
        Path icon = m.get("iconImage") instanceof String ? inside(p, (String) m.get("iconImage")) : null;
        loaded.add(CosmeticRegistry.parse(name, m, shared, head(t.png), fileSource(t.png), icon == null ? null : fileSource(icon), null));
    }

    /** The slot from cosmetic.json, else a guess from the name; models can't be capes. */
    private static Slot modelSlot(Map<String, Object> m, String name) {
        Slot slot = Slot.byId(str(m.get("slot"), ""));
        if (slot == null && m.get("slot") != null) throw new IllegalArgumentException("unknown slot \"" + m.get("slot") + "\"");
        if (slot == null) slot = guessSlot(name);
        if (slot == null || slot == Slot.CAPE) {
            if (slot == Slot.CAPE && m.get("slot") != null) throw new IllegalArgumentException("capes are pictures, not models: pick another slot");
            slot = Slot.HAT;
        }
        m.put("slot", slot.id());
        return slot;
    }

    private void modelFolder(Path p, String name, Map<String, Object> m) throws IOException {
        if (((String) m.get("model")).toLowerCase(Locale.ROOT).endsWith(".bbmodel")) bbFolder(p, name, m);
        else objFolder(p, name, m);
    }

    /** A Blockbench project: the textures come embedded in it. */
    private void bbFolder(Path p, String name, Map<String, Object> m) throws IOException {
        Path file = inside(p, (String) m.get("model"));
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException(m.get("model") + " is missing");
        Slot slot = modelSlot(m, name);
        List<Path> sources = new ArrayList<Path>();
        sources.add(file);
        Path json = p.resolve("cosmetic.json");
        if (Files.isRegularFile(json) && !p.equals(dir)) sources.add(json);
        String key = hash(sources, "bb:" + slot + ":" + Json.write(m));
        CosmeticModel.Bone bone = options(m).bone;
        BbModel.Result r = BbModel.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), p, slot, bone, str(m.get("animation"), "none"));
        Path png = cacheFile(key + "-texture.png");
        if (!Files.isRegularFile(png)) write(png, Images.png(r.texture));
        r.model.setPlacement(CosmeticModel.Placement.parse(m.get("placement")));
        Path icon = cacheFile(key + "-icon.png");
        if (!Files.isRegularFile(icon)) write(icon, Images.png(Thumbnail.render(r.model, r.texture, slot)));
        m.put("frames", 1);
        m.remove("texture");
        loaded.add(CosmeticRegistry.parse(name, m, shared, head(png), fileSource(png), fileSource(icon), r.model));
    }

    private void objFolder(Path p, String name, Map<String, Object> m) throws IOException {
        Path objFile = inside(p, (String) m.get("model"));
        if (!Files.isRegularFile(objFile)) throw new IllegalArgumentException(m.get("model") + " is missing");
        ObjModel obj = ObjModel.parse(new String(Files.readAllBytes(objFile), StandardCharsets.UTF_8));
        List<Path> sources = new ArrayList<Path>();
        sources.add(objFile);
        Path json = p.resolve("cosmetic.json");
        if (Files.isRegularFile(json)) sources.add(json);
        for (String lib : obj.mtllibs) {
            Path mtl = p.resolve(lib).normalize();
            if (!mtl.startsWith(p)) mtl = p.resolve(mtl.getFileName().toString());
            if (Files.isRegularFile(mtl)) {
                obj.parseMtl(new String(Files.readAllBytes(mtl), StandardCharsets.UTF_8));
                sources.add(mtl);
            }
        }
        // Material images resolve inside the folder (Blender often writes absolute paths: the file name is used).
        Map<String, Path> mapFiles = new LinkedHashMap<String, Path>();
        for (String map : obj.maps()) {
            Path f = p.resolve(map).normalize();
            if (!f.startsWith(p) || !Files.isRegularFile(f)) f = p.resolve(Paths.fileName(map));
            if (Files.isRegularFile(f)) {
                mapFiles.put(map, f);
                sources.add(f);
            }
        }
        // A texture named in cosmetic.json, or a texture.png next to a colour-only model, is used for every face.
        Path forced = m.get("texture") instanceof String ? inside(p, (String) m.get("texture")) : obj.maps().isEmpty() ? existing(p.resolve("texture.png")) : null;
        if (forced != null) sources.add(forced);

        Slot slot = modelSlot(m, name);
        ObjModel.Options opt = options(m);
        String key = hash(sources, "obj:" + slot);

        Tex tex;
        ObjModel.Atlas atlas = null;
        BufferedImage frame0;
        if (forced != null || (obj.singleTexture() && mapFiles.size() == 1)) {
            Path only = forced != null ? forced : mapFiles.values().iterator().next();
            tex = texture(only, false, false);
            frame0 = firstFrame(tex.png, tex.frames);
        } else {
            Map<String, BufferedImage> images = new LinkedHashMap<String, BufferedImage>();
            for (Map.Entry<String, Path> e : mapFiles.entrySet()) images.put(e.getKey(), Images.read(Files.readAllBytes(e.getValue())).frames.get(0));
            atlas = obj.atlas(images);
            Path png = cacheFile(key + "-atlas.png");
            if (!Files.isRegularFile(png)) write(png, Images.png(atlas.image));
            tex = new Tex(png, 1, 100);
            frame0 = atlas.image;
        }
        CosmeticModel model = obj.model(slot, opt, atlas);
        model.setPlacement(CosmeticModel.Placement.parse(m.get("placement")));
        if (obj.translucent() && !m.containsKey("translucent")) m.put("translucent", true);
        tex.into(m);

        Path icon = m.get("iconImage") instanceof String ? inside(p, (String) m.get("iconImage")) : null;
        if (icon == null) {
            icon = cacheFile(key + "-icon.png");
            if (!Files.isRegularFile(icon)) write(icon, Images.png(Thumbnail.render(model, frame0, slot)));
        }
        loaded.add(CosmeticRegistry.parse(name, m, shared, head(tex.png), fileSource(tex.png), fileSource(icon), model));
    }

    static ObjModel.Options options(Map<String, Object> m) {
        ObjModel.Options o = new ObjModel.Options();
        if (m.get("scale") instanceof Number) {
            float s = ((Number) m.get("scale")).floatValue();
            if (s <= 0) throw new IllegalArgumentException("\"scale\" must be positive");
            o.scale = s;
        }
        if (m.get("offset") != null) o.offset = CosmeticModel.floats(m.get("offset"), 3, null, "offset");
        o.zUp = "z".equalsIgnoreCase(str(m.get("up"), "y"));
        o.animation = str(m.get("animation"), "none");
        if (m.get("bone") != null) {
            o.bone = CosmeticModel.Bone.byId(String.valueOf(m.get("bone")));
            if (o.bone == null) throw new IllegalArgumentException("unknown bone \"" + m.get("bone") + "\"");
        }
        if (m.get("groups") instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) m.get("groups")).entrySet()) {
                if (e.getValue() instanceof Map) o.groups.put(String.valueOf(e.getKey()), (Map<?, ?>) e.getValue());
            }
        }
        return o;
    }

    /** A slot from words in a name ("dragon_wings" -> wings), or null. */
    static Slot guessSlot(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("cape") || n.contains("cloak")) return Slot.CAPE;
        if (n.contains("wing")) return Slot.WINGS;
        if (n.contains("ear")) return Slot.EARS;
        if (n.contains("boot") || n.contains("shoe")) return Slot.BOOTS;
        if (n.contains("glass") || n.contains("mask") || n.contains("shade") || n.contains("face") || n.contains("beard")) return Slot.FACE;
        if (n.contains("bag") || n.contains("pack") || n.contains("back") || n.contains("sword") || n.contains("quiver") || n.contains("tail")) return Slot.BACK;
        if (n.contains("hat") || n.contains("crown") || n.contains("halo") || n.contains("horn") || n.contains("helm") || n.contains("cap")) return Slot.HAT;
        return null;
    }

    // ---- textures ---------------------------------------------------------------------------------------------------

    /** A PNG the renderer can load, with its frame layout ({@code frames} 0 = let the registry work it out). */
    static final class Tex {
        final Path png;
        final int frames, frameTime;

        Tex(Path png, int frames, int frameTime) {
            this.png = png;
            this.frames = frames;
            this.frameTime = frameTime;
        }

        /** Fill in frames / frameTime unless cosmetic.json sets them. */
        void into(Map<String, Object> m) {
            if (frames > 0 && !(m.get("frames") instanceof Number)) m.put("frames", frames);
            if (frames > 1 && !(m.get("frameTime") instanceof Number)) m.put("frameTime", frameTime);
            m.remove("texture");
        }
    }

    /**
     * {@code file} as a renderer-ready PNG. A PNG that already fits (models: any; capes: the 2:1 layout) is used where
     * it is; everything else is converted once into the cache.
     */
    Tex texture(Path file, boolean cape, boolean forcePicture) throws IOException {
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException(file.getFileName() + " is missing");
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png") && !forcePicture) {
            int[] size = Png.size(head(file));
            if (size == null) throw new IllegalArgumentException(file.getFileName() + " is not a PNG");
            if (!cape || capeStrip(size[0], size[1])) return new Tex(file, 0, 100);
        }
        String key = hash(Collections.singletonList(file), (cape ? "cape" : "model") + (forcePicture ? ":picture" : ""));
        Path png = cacheFile(key + ".png"), meta = cacheFile(key + ".json");
        if (Files.isRegularFile(png) && Files.isRegularFile(meta)) {
            try {
                Map<String, Object> mm = Json.parseObject(new String(Files.readAllBytes(meta), StandardCharsets.UTF_8));
                return new Tex(png, (int) CosmeticModel.num(mm.get("frames"), 1), (int) CosmeticModel.num(mm.get("frameTime"), 100));
            } catch (RuntimeException ignored) {
                // Rebuild below.
            }
        }
        Images.Picture pic = Images.read(Files.readAllBytes(file));
        if (cape) pic = !forcePicture && Images.capeLayout(pic) ? pic : Images.pictureCape(pic);
        else pic = Images.fit(pic);
        write(png, Images.png(Images.strip(pic)));
        Map<String, Object> mm = new LinkedHashMap<String, Object>();
        mm.put("frames", pic.frames.size());
        mm.put("frameTime", pic.frameTimeMs);
        write(meta, Json.write(mm).getBytes(StandardCharsets.UTF_8));
        return new Tex(png, pic.frames.size(), pic.frameTimeMs);
    }

    /** A cape PNG as it is: 2:1 frames stacked vertically. */
    static boolean capeStrip(int w, int h) {
        if (w < 2 || w % 2 != 0) return false;
        int fh = w / 2;
        return h >= fh && h % fh == 0 && h / fh <= 256;
    }

    private static BufferedImage firstFrame(Path png, int frames) throws IOException {
        BufferedImage img = ImageIO.read(png.toFile());
        if (img == null) throw new IOException("can't read " + png.getFileName());
        int n = Math.max(1, frames);
        return img.getHeight() % n == 0 && n > 1 ? img.getSubimage(0, 0, img.getWidth(), img.getHeight() / n) : img;
    }

    // ---- cache ------------------------------------------------------------------------------------------------------

    private Path cacheFile(String name) throws IOException {
        Files.createDirectories(cache);
        usedCache.add(name);
        return cache.resolve(name);
    }

    private void sweepCache() {
        List<Path> files = Files.isDirectory(cache) ? list(cache) : null;
        if (files == null) return;
        for (Path f : files) {
            if (usedCache.contains(f.getFileName().toString())) continue;
            try {
                Files.deleteIfExists(f);
            } catch (IOException ignored) { }
        }
    }

    /** Cache key from the sources' paths, sizes and dates. */
    private String hash(List<Path> sources, String salt) throws IOException {
        StringBuilder sb = new StringBuilder(salt);
        for (Path s : sources) {
            sb.append('|').append(s.toAbsolutePath().normalize()).append(':').append(Files.size(s)).append(':').append(Files.getLastModifiedTime(s).toMillis());
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 10; i++) hex.append(String.format(Locale.ROOT, "%02x", d[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    private static void write(Path f, byte[] b) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.write(tmp, b);
        Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // ---- files ------------------------------------------------------------------------------------------------------

    private List<Path> list(Path d) {
        List<Path> out = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
            for (Path p : ds) out.add(p);
        } catch (IOException e) {
            problems.add(d + ": " + e.getMessage());
            return null;
        }
        Collections.sort(out);
        return out;
    }

    private static Path firstWith(Path folder, String ext) throws IOException {
        List<Path> out = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path p : ds) if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext) && Files.isRegularFile(p)) out.add(p);
        }
        Collections.sort(out);
        return out.isEmpty() ? null : out.get(0);
    }

    /** texture.png, else the first picture in the folder that isn't the icon. */
    private static Path defaultTexture(Path folder) throws IOException {
        Path png = folder.resolve("texture.png");
        if (Files.isRegularFile(png)) return png;
        List<Path> out = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path p : ds) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Images.isImageName(n) && !n.startsWith("icon")) out.add(p);
            }
        }
        Collections.sort(out);
        return out.isEmpty() ? null : out.get(0);
    }

    private static Path existing(Path p) { return Files.isRegularFile(p) ? p : null; }

    /** {@code name} resolved in {@code folder}; files elsewhere (../) are refused. */
    static Path inside(Path folder, String name) {
        Path f = folder.resolve(name).normalize();
        if (!f.startsWith(folder)) throw new IllegalArgumentException("files must be inside the folder");
        return f;
    }

    static String stem(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    private static String str(Object o, String def) { return o instanceof String && !((String) o).isEmpty() ? (String) o : def; }

    private static Cosmetic.TextureSource fileSource(final Path p) { return () -> Files.readAllBytes(p); }

    static byte[] head(Path p) throws IOException { return CosmeticRegistry.head(p); }

    /** Path helpers that work for "C:\\textures\\wing.png" written by Blender on another machine. */
    static final class Paths {
        private Paths() {}

        static String fileName(String path) {
            String p = path.replace('\\', '/');
            int slash = p.lastIndexOf('/');
            return slash < 0 ? p : p.substring(slash + 1);
        }
    }
}
