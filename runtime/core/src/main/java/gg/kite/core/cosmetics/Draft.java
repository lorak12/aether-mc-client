package gg.kite.core.cosmetics;

import gg.kite.core.Json;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * A cosmetic being set up in the importer: the picked file copied into {@code <cosmetics>/_draft/} with a
 * cosmetic.json the editor rewrites. {@link #rebuild} loads it for the preview (as {@link CosmeticRegistry#setDraft});
 * placement changes apply live without a rebuild. Finished drafts are saved to the wardrobe (a normal local folder,
 * only you see it) or published to the store for everyone ({@link #bundle}).
 */
public final class Draft {
    public static final String FOLDER = "_draft";

    public final Importer.Kind kind;
    /** The picked file's name, for the editor. */
    public final String source;
    public String name, description = "";
    public Slot slot;
    public Cosmetic.Rarity rarity = Cosmetic.Rarity.RARE;
    /** Store price in coins (0 = free to claim). */
    public long price = 500;
    /** Body part for everything not in a named group; null = the slot's usual one. */
    public CosmeticModel.Bone bone;
    public String animation = "none";
    /** Pictures: used as a cape texture as it is (64x32 layout) instead of being laid out on the cape. */
    public boolean capeTexture;
    public final boolean looksLikeCapeTexture;
    private float[] offset = new float[3], rotation = new float[3];
    private float scale = 1;

    private final Path cosmeticsDir, folder;
    private final String file;
    private Cosmetic cosmetic;
    private String problem;

    private Draft(Path cosmeticsDir, Path folder, Importer.Kind kind, String source, String file, boolean looksLikeCapeTexture) {
        this.cosmeticsDir = cosmeticsDir;
        this.folder = folder;
        this.kind = kind;
        this.source = source;
        this.file = file;
        this.looksLikeCapeTexture = looksLikeCapeTexture;
    }

    /** Copy {@code source} (with an OBJ's .mtl and textures) into a fresh draft folder. */
    public static Draft start(Path cosmeticsDir, Path source) throws IOException {
        Importer.Kind kind = Importer.kind(source);
        if (kind == null) throw new IOException("unsupported file: use .bbmodel, OBJ, PNG, JPG or GIF");
        Path folder = cosmeticsDir.resolve(FOLDER);
        Importer.deleteTree(folder);
        Files.createDirectories(folder);
        String file;
        boolean cape = false;
        if (kind == Importer.Kind.PICTURE) {
            file = "picture" + Importer.ext(source);
            Files.copy(source, folder.resolve(file));
            cape = Importer.looksLikeCapeTexture(source);
        } else {
            Importer.copyModel(source, folder);
            file = safeNames(folder, source.getFileName().toString());
        }
        Draft d = new Draft(cosmeticsDir, folder, kind, source.getFileName().toString(), file, cape);
        d.name = Importer.suggestName(source);
        d.capeTexture = cape;
        if (kind == Importer.Kind.PICTURE) d.slot = Slot.CAPE;
        else {
            Slot guess = Importer.guessSlot(source);
            d.slot = guess == null ? Slot.HAT : guess;
            if (d.slot == Slot.WINGS) d.animation = "flap";
        }
        return d;
    }

    /** File names the store accepts (letters, digits, _ . -): rename and fix the references in OBJ / MTL / bbmodel files. */
    static final java.util.regex.Pattern SAFE = java.util.regex.Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}");

    /** Returns the model's new name. */
    private static String safeNames(Path folder, String model) throws IOException {
        Map<String, String> renamed = new LinkedHashMap<String, String>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (SAFE.matcher(n).matches()) continue;
                String safe = n.replaceAll("[^A-Za-z0-9_.-]", "_").replaceAll("^[.-]+", "_");
                if (safe.length() > 64) safe = safe.substring(safe.length() - 64);
                renamed.put(n, safe);
            }
        }
        for (Map.Entry<String, String> e : renamed.entrySet()) Files.move(folder.resolve(e.getKey()), folder.resolve(e.getValue()));
        if (renamed.isEmpty()) return model;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path p : ds) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!n.endsWith(".obj") && !n.endsWith(".mtl") && !n.endsWith(".bbmodel")) continue;
                String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                for (Map.Entry<String, String> e : renamed.entrySet()) text = text.replace(e.getKey(), e.getValue());
                Files.write(p, text.getBytes(StandardCharsets.UTF_8));
            }
        }
        return renamed.containsKey(model) ? renamed.get(model) : model;
    }

    // ---- placement --------------------------------------------------------------------------------------------------

    /** Right, up, forward in pixels. */
    public float[] offset() { return offset; }
    /** Pitch, yaw, roll in degrees. */
    public float[] rotation() { return rotation; }
    public float scale() { return scale; }

    public void setPlacement(float[] offset, float[] rotation, float scale) {
        this.offset = offset.clone();
        this.rotation = rotation.clone();
        this.scale = scale;
        if (cosmetic != null && cosmetic.model != null) cosmetic.model.setPlacement(placement());
    }

    public CosmeticModel.Placement placement() { return new CosmeticModel.Placement(offset, rotation, scale); }

    // ---- building ---------------------------------------------------------------------------------------------------

    /** The draft as loaded, or null when it failed (see {@link #problem()}). */
    public Cosmetic cosmetic() { return cosmetic; }

    public String problem() { return problem; }

    /** cosmetic.json for the current settings. */
    Map<String, Object> json() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", name == null || name.trim().isEmpty() ? "Cosmetic" : name.trim());
        if (!description.trim().isEmpty()) m.put("description", description.trim());
        m.put("slot", slot.id());
        m.put("rarity", rarity.name().toLowerCase(Locale.ROOT));
        if (kind == Importer.Kind.PICTURE) {
            m.put("texture", file);
            if (!capeTexture) m.put("picture", true);
        } else {
            m.put("model", file);
            if (bone != null) m.put("bone", bone.name().toLowerCase(Locale.ROOT));
            if (!animation.equals("none")) m.put("animation", animation);
            if (!placement().isNone()) m.put("placement", placement().toJson());
        }
        return m;
    }

    /** Write cosmetic.json and load the draft for the preview. Returns the cosmetic or null (see {@link #problem()}). */
    public Cosmetic rebuild(CosmeticRegistry registry) {
        try {
            Files.write(folder.resolve("cosmetic.json"), Json.write(json()).getBytes(StandardCharsets.UTF_8));
            LocalCosmetics lc = new LocalCosmetics(folder);
            lc.loadFolder(folder);
            if (!lc.problems.isEmpty() || lc.loaded.isEmpty()) {
                String p = lc.problems.isEmpty() ? "didn't load" : lc.problems.get(0);
                problem = p.startsWith(FOLDER + ": ") ? p.substring(FOLDER.length() + 2) : p;
                cosmetic = null;
            } else {
                problem = null;
                cosmetic = lc.loaded.get(0);
                if (cosmetic.model != null) cosmetic.model.setPlacement(placement());
            }
        } catch (IOException | RuntimeException e) {
            problem = e.getMessage();
            cosmetic = null;
        }
        registry.setDraft(cosmetic);
        return cosmetic;
    }

    /** Throw the draft away. */
    public void discard(CosmeticRegistry registry) {
        registry.setDraft(null);
        Importer.deleteTree(folder);
    }

    /** Move it into the wardrobe as a local cosmetic (only you see it). Returns its id. */
    public String saveLocal(CosmeticRegistry registry) throws IOException {
        Files.write(folder.resolve("cosmetic.json"), Json.write(json()).getBytes(StandardCharsets.UTF_8));
        Importer.deleteTree(folder.resolve(LocalCosmetics.CACHE));
        Path target = Importer.freeFolder(cosmeticsDir, Importer.slug(name == null ? "cosmetic" : name));
        Files.move(folder, target);
        registry.setDraft(null);
        return CosmeticRegistry.LOCAL_PREFIX + target.getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    /** Store id for this draft: "custom-" plus the name. */
    public String storeId() { return "custom-" + Importer.slug(name == null ? "cosmetic" : name); }

    /** The POST /v1/admin/cosmetics body: settings, every file of the draft and a store thumbnail. */
    public Map<String, Object> bundle() throws IOException {
        if (cosmetic == null) throw new IOException(problem == null ? "the draft didn't load" : problem);
        Files.write(folder.resolve("cosmetic.json"), Json.write(json()).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> files = new LinkedHashMap<String, Object>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.startsWith(".") || !Files.isRegularFile(p) || n.equals("icon.png")) continue;
                files.put(n, Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
            }
        }
        files.put("icon.png", Base64.getEncoder().encodeToString(icon()));
        Map<String, Object> b = new LinkedHashMap<String, Object>();
        b.put("id", storeId());
        b.put("slot", slot.id());
        b.put("name", json().get("name"));
        b.put("rarity", rarity.name().toLowerCase(Locale.ROOT));
        b.put("price", price);
        b.put("description", description.trim());
        b.put("files", files);
        return b;
    }

    /** The menu thumbnail; for capes the outside of the cape from the first frame. */
    private byte[] icon() throws IOException {
        if (cosmetic.hasIconImage()) return cosmetic.readIconImage();
        BufferedImage tex = ImageIO.read(new ByteArrayInputStream(cosmetic.readTexture()));
        int fh = cosmetic.frameHeight();
        float[] r = cosmetic.icon;
        int x0 = Math.round(r[0] * tex.getWidth()), y0 = Math.round(r[1] * fh), x1 = Math.round(r[2] * tex.getWidth()), y1 = Math.round(r[3] * fh);
        int w = Math.max(1, x1 - x0), h = Math.max(1, y1 - y0);
        float s = Math.min(128f / w, 128f / h);
        BufferedImage out = new BufferedImage(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(tex, 0, 0, out.getWidth(), out.getHeight(), x0, y0, x0 + w, y0 + h, null);
        g.dispose();
        return Images.png(out);
    }

    /** Slots a draft of this kind can use. */
    public List<Slot> slots() {
        if (kind == Importer.Kind.PICTURE) return java.util.Collections.singletonList(Slot.CAPE);
        return java.util.Arrays.asList(Slot.WINGS, Slot.HAT, Slot.EARS, Slot.FACE, Slot.BOOTS, Slot.BACK);
    }
}
