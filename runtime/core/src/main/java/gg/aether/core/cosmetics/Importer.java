package gg.aether.core.cosmetics;

import gg.aether.core.Json;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Brings a file from anywhere on the computer into the cosmetics folder as a ready cosmetic: a picture or GIF (a cape),
 * or an OBJ model with its .mtl and textures (Blender, Blockbench). Also writes the Blender kit: a player reference
 * to model around and a how-to.
 */
public final class Importer {
    private Importer() {}

    public enum Kind { PICTURE, MODEL }

    /** Extensions the importer takes (for file dialogs). */
    public static final String[] EXTENSIONS = { "bbmodel", "obj", "png", "jpg", "jpeg", "gif", "bmp" };

    /** What {@code file} would import as, or null if unsupported. */
    public static Kind kind(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".obj") || n.endsWith(".bbmodel")) return Kind.MODEL;
        return Images.isImageName(n) ? Kind.PICTURE : null;
    }

    /** The picture is already a cape texture (2:1 frames, a strip of them, or a GIF of them). */
    public static boolean looksLikeCapeTexture(Path file) {
        try {
            Images.Picture p = Images.read(Files.readAllBytes(file));
            if (p.animated()) return Images.capeLayout(p);
            return LocalCosmetics.capeStrip(p.width(), p.height()) && p.width() >= 64;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** A slot guess for a model from its file name ("dragon_wings.obj" -> wings), null if no clue. */
    public static Slot guessSlot(Path file) {
        Slot s = LocalCosmetics.guessSlot(LocalCosmetics.stem(file.getFileName().toString()));
        return s == Slot.CAPE ? null : s;
    }

    /** "my cool-cape.gif" -> "My cool cape". */
    public static String suggestName(Path file) { return CosmeticRegistry.prettify(LocalCosmetics.stem(file.getFileName().toString())); }

    /**
     * Copy {@code source} into a new folder under {@code cosmeticsDir} with a cosmetic.json. {@code capeTexture}: a
     * picture is used as a cape texture as it is instead of being laid out on the cape. {@code texture}: optional image
     * for a model without materials. Returns the new cosmetic's id ("local/&lt;folder&gt;").
     */
    public static String importFile(Path cosmeticsDir, Path source, String name, Slot slot, Cosmetic.Rarity rarity, boolean capeTexture, Path texture)
            throws IOException {
        Kind kind = kind(source);
        if (kind == null) throw new IOException("unsupported file: use PNG, JPG, GIF or OBJ");
        if (!Files.isRegularFile(source)) throw new IOException(source.getFileName() + " is missing");
        if (kind == Kind.PICTURE) slot = Slot.CAPE;
        else if (slot == null || slot == Slot.CAPE) throw new IOException("pick a slot for the model (capes are pictures)");
        String display = name == null || name.trim().isEmpty() ? suggestName(source) : name.trim();
        Files.createDirectories(cosmeticsDir);
        Path folder = freeFolder(cosmeticsDir, slug(display));
        Files.createDirectories(folder);
        try {
            Map<String, Object> json = new LinkedHashMap<String, Object>();
            json.put("name", display);
            json.put("slot", slot.id());
            json.put("rarity", (rarity == null ? Cosmetic.Rarity.COMMON : rarity).name().toLowerCase(Locale.ROOT));
            if (kind == Kind.PICTURE) {
                String file = "picture" + ext(source);
                Files.copy(source, folder.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                json.put("texture", file);
                if (!capeTexture) json.put("picture", true);
            } else {
                copyModel(source, folder);
                json.put("model", source.getFileName().toString());
                if (texture != null) {
                    String file = "texture" + ext(texture);
                    Files.copy(texture, folder.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                    json.put("texture", file);
                }
            }
            Files.write(folder.resolve("cosmetic.json"), (Json.write(json) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            deleteTree(folder);
            throw e;
        }
        return CosmeticRegistry.LOCAL_PREFIX + folder.getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    /**
     * The model and what it needs: an OBJ's .mtl files and the images those name; a Blockbench project's textures
     * that aren't embedded (found next to it by name).
     */
    static void copyModel(Path obj, Path folder) throws IOException {
        Path srcDir = obj.toAbsolutePath().getParent();
        Files.copy(obj, folder.resolve(obj.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        if (obj.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bbmodel")) {
            Object textures = Json.parseObject(new String(Files.readAllBytes(obj), StandardCharsets.UTF_8)).get("textures");
            if (textures instanceof List) {
                for (Object t : (List<?>) textures) {
                    if (!(t instanceof Map) || String.valueOf(((Map<?, ?>) t).get("source")).startsWith("data:")) continue;
                    Object n = ((Map<?, ?>) t).get("name");
                    Path img = n instanceof String ? find(srcDir, (String) n) : null;
                    if (img != null) Files.copy(img, folder.resolve(img.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return;
        }
        ObjModel m = ObjModel.parse(new String(Files.readAllBytes(obj), StandardCharsets.UTF_8));
        for (String lib : m.mtllibs) {
            Path mtl = find(srcDir, lib);
            if (mtl == null) continue;
            Files.copy(mtl, folder.resolve(mtl.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            m.parseMtl(new String(Files.readAllBytes(mtl), StandardCharsets.UTF_8));
            for (String map : m.maps()) {
                Path img = find(mtl.getParent(), map);
                if (img == null) img = find(srcDir, map);
                if (img != null) Files.copy(img, folder.resolve(img.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** {@code ref} as written in an OBJ/MTL: relative to {@code dir}, absolute, or just its file name next to it. */
    private static Path find(Path dir, String ref) {
        String r = ref.replace('\\', '/');
        try {
            Path direct = dir.resolve(r);
            if (Files.isRegularFile(direct)) return direct;
        } catch (RuntimeException ignored) { }
        Path byName = dir.resolve(LocalCosmetics.Paths.fileName(r));
        return Files.isRegularFile(byName) ? byName : null;
    }

    // ---- Blender kit ------------------------------------------------------------------------------------------------

    public static final String KIT = "_creator-kit";

    /**
     * Write the creator kit into {@code cosmeticsDir}/{@value #KIT} (refreshing it) and return the folder: the
     * Blockbench template, the Blender player reference, an example cosmetic.json and the guide.
     */
    public static Path writeCreatorKit(Path cosmeticsDir) throws IOException {
        Path kit = cosmeticsDir.resolve(KIT);
        Files.createDirectories(kit);
        Files.write(kit.resolve("kite_cosmetic_template.bbmodel"), blockbenchTemplate().getBytes(StandardCharsets.UTF_8));
        byte[] guide = resourceGuide();
        if (guide != null) Files.write(kit.resolve("GUIDE.md"), guide);
        Files.write(kit.resolve("player_reference.obj"), referenceObj().getBytes(StandardCharsets.UTF_8));
        Files.write(kit.resolve("player_reference.mtl"), REFERENCE_MTL.getBytes(StandardCharsets.UTF_8));
        Files.write(kit.resolve("HOW-TO.txt"), HOW_TO.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> example = new LinkedHashMap<String, Object>();
        example.put("name", "My Wings");
        example.put("slot", "wings");
        example.put("rarity", "epic");
        example.put("model", "my_wings.obj");
        Map<String, Object> groups = new LinkedHashMap<String, Object>();
        Map<String, Object> wing = new LinkedHashMap<String, Object>();
        wing.put("pivot", list(-0.06, 1.3, -0.15));
        Map<String, Object> anim = new LinkedHashMap<String, Object>();
        anim.put("rot", list(0, 25, 0));
        anim.put("speed", 0.15);
        anim.put("flySpeed", 2.5);
        wing.put("animate", anim);
        groups.put("Wing.R", wing);
        Map<String, Object> left = new LinkedHashMap<String, Object>(wing);
        left.put("pivot", list(0.06, 1.3, -0.15));
        Map<String, Object> leftAnim = new LinkedHashMap<String, Object>(anim);
        leftAnim.put("rot", list(0, -25, 0));
        left.put("animate", leftAnim);
        groups.put("Wing.L", left);
        example.put("groups", groups);
        Files.write(kit.resolve("example-cosmetic.json"), (Json.write(example) + "\n").getBytes(StandardCharsets.UTF_8));
        return kit;
    }

    private static byte[] resourceGuide() { return CosmeticRegistry.resource("/aether/cosmetics-guide.md"); }

    /**
     * A Blockbench project (Generic Model) to start from: the player as ref_* cubes (ignored on import) in a grey
     * texture, and an empty group per body part with the right pivot. Cubes put in a group follow that part.
     */
    static String blockbenchTemplate() {
        // 64x64 reference texture: skin, shirt, trousers, face.
        BufferedImage tex = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int[] colours = { 0xFFC89F7F, 0xFF3F6FA8, 0xFF3A3F63, 0xFFE04848 };
        for (int i = 0; i < colours.length; i++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) tex.setRGB(i * 4 + x, y, colours[i]);
        String png;
        try {
            png = "data:image/png;base64," + Base64.getEncoder().encodeToString(Images.png(tex));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // Blockbench space: pixels, Y up, facing north (-Z); the wearer's right is +X.
        Object[][] boxes = {
                { "ref_head", new float[] { -4, 24, -4, 4, 32, 4 }, 0 },
                { "ref_face", new float[] { -3, 25, -4.3f, 3, 29, -4 }, 3 },
                { "ref_body", new float[] { -4, 12, -2, 4, 24, 2 }, 1 },
                { "ref_right_arm", new float[] { 4, 12, -2, 8, 24, 2 }, 0 },
                { "ref_left_arm", new float[] { -8, 12, -2, -4, 24, 2 }, 0 },
                { "ref_right_leg", new float[] { 0, 0, -2, 4, 12, 2 }, 2 },
                { "ref_left_leg", new float[] { -4, 0, -2, 0, 12, 2 }, 2 },
        };
        List<Object> elements = new ArrayList<Object>(), refChildren = new ArrayList<Object>();
        for (Object[] b : boxes) {
            float[] c = (float[]) b[1];
            int colour = (Integer) b[2];
            Map<String, Object> e = new LinkedHashMap<String, Object>();
            String uuid = java.util.UUID.randomUUID().toString();
            e.put("name", b[0]);
            e.put("type", "cube");
            e.put("uuid", uuid);
            e.put("from", list(c[0], c[1], c[2]));
            e.put("to", list(c[3], c[4], c[5]));
            e.put("origin", list(0, 0, 0));
            e.put("locked", true);
            Map<String, Object> faces = new LinkedHashMap<String, Object>();
            for (String side : new String[] { "north", "east", "south", "west", "up", "down" }) {
                Map<String, Object> f = new LinkedHashMap<String, Object>();
                f.put("uv", list(colour * 4 + 0.5, 0.5, colour * 4 + 3.5, 3.5));
                f.put("texture", 0);
                faces.put(side, f);
            }
            e.put("faces", faces);
            elements.add(e);
            refChildren.add(uuid);
        }
        List<Object> outliner = new ArrayList<Object>();
        Map<String, Object> ref = group("ref_player (not exported)", new float[] { 0, 0, 0 });
        ref.put("children", refChildren);
        ref.put("locked", true);
        outliner.add(ref);
        // Body-part pivots in Blockbench space (vanilla PlayerModel, standing).
        String[] names = { "head", "body", "right_arm", "left_arm", "right_leg", "left_leg" };
        for (int i = 0; i < names.length; i++) outliner.add(group(names[i], BbModel.fromModel(ObjModel.BONE_PIVOTS[i])));
        Map<String, Object> texture = new LinkedHashMap<String, Object>();
        texture.put("name", "reference.png");
        texture.put("id", "0");
        texture.put("width", 64);
        texture.put("height", 64);
        texture.put("uv_width", 64);
        texture.put("uv_height", 64);
        texture.put("uuid", java.util.UUID.randomUUID().toString());
        texture.put("source", png);
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("format_version", "4.5");
        meta.put("model_format", "free");
        meta.put("box_uv", false);
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("width", 64);
        res.put("height", 64);
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("meta", meta);
        root.put("name", "kite_cosmetic");
        root.put("resolution", res);
        root.put("elements", elements);
        root.put("outliner", outliner);
        root.put("textures", java.util.Collections.singletonList(texture));
        return Json.write(root);
    }

    private static Map<String, Object> group(String name, float[] origin) {
        Map<String, Object> g = new LinkedHashMap<String, Object>();
        g.put("name", name);
        g.put("origin", list(origin[0], origin[1], origin[2]));
        g.put("rotation", list(0, 0, 0));
        g.put("uuid", java.util.UUID.randomUUID().toString());
        g.put("export", !name.startsWith("ref_"));
        g.put("children", new ArrayList<Object>());
        return g;
    }

    /** The player as boxes in OBJ space (blocks, Y up, standing on the origin, facing +Z), objects named ref_*. */
    static String referenceObj() {
        StringBuilder sb = new StringBuilder("# Kite player reference: 1 unit = 1 block, Y up, the player faces +Z (Blender: -Y).\n");
        sb.append("# Objects named ref_* are ignored by the client, so exporting them by accident is harmless.\nmtllib player_reference.mtl\n");
        // name, model-space box (x0, y0, z0, x1, y1, z1 in pixels, Y down from the neck), material
        Object[][] boxes = {
                { "ref_head", new float[] { -4, -8, -4, 4, 0, 4 }, "ref_skin" },
                { "ref_face", new float[] { -3, -5, -4.3f, 3, -1, -4 }, "ref_front" },
                { "ref_body", new float[] { -4, 0, -2, 4, 12, 2 }, "ref_shirt" },
                { "ref_right_arm", new float[] { -8, 0, -2, -4, 12, 2 }, "ref_skin" },
                { "ref_left_arm", new float[] { 4, 0, -2, 8, 12, 2 }, "ref_skin" },
                { "ref_right_leg", new float[] { -4, 12, -2, 0, 24, 2 }, "ref_pants" },
                { "ref_left_leg", new float[] { 0, 12, -2, 4, 24, 2 }, "ref_pants" },
        };
        int base = 1;
        for (Object[] b : boxes) {
            float[] m = (float[]) b[1];
            sb.append("o ").append(b[0]).append('\n').append("usemtl ").append(b[2]).append('\n');
            for (int i = 0; i < 8; i++) {
                float mx = (i & 1) != 0 ? m[3] : m[0], my = (i & 2) != 0 ? m[4] : m[1], mz = (i & 4) != 0 ? m[5] : m[2];
                // Model pixels -> OBJ blocks: the inverse of ObjModel.toModel.
                sb.append(String.format(Locale.ROOT, "v %.4f %.4f %.4f%n", mx / 16f, (24 - my) / 16f, -mz / 16f));
            }
            int[][] faces = { { 0, 1, 3, 2 }, { 4, 6, 7, 5 }, { 0, 4, 5, 1 }, { 2, 3, 7, 6 }, { 0, 2, 6, 4 }, { 1, 5, 7, 3 } };
            for (int[] f : faces) sb.append(String.format(Locale.ROOT, "f %d %d %d %d%n", base + f[0], base + f[1], base + f[2], base + f[3]));
            base += 8;
        }
        return sb.toString();
    }

    private static final String REFERENCE_MTL = "newmtl ref_skin\nKd 0.58 0.40 0.30\n\nnewmtl ref_front\nKd 0.9 0.2 0.2\n\n"
            + "newmtl ref_shirt\nKd 0.05 0.35 0.45\n\nnewmtl ref_pants\nKd 0.10 0.10 0.35\n";

    private static final String HOW_TO = String.join("\n",
            "Making a Kite cosmetic in Blender (GUIDE.md has everything, Blockbench too)",
            "=============================================================================",
            "",
            "1. File > Import > Wavefront (.obj): player_reference.obj from this folder. That is the player, at real size:",
            "   1 Blender unit = 1 block, standing on the origin, facing -Y (the red patch is the face).",
            "2. Model your cosmetic around it. Keep it low-poly (a few thousand faces at most).",
            "3. Name objects after the part they move with: head, body, right_arm, left_arm, right_leg, left_leg",
            "   (Arm.L, leg_r... work too). Unnamed objects follow the slot's usual part: hats, ears and face items the head;",
            "   wings and backpacks the body; boots the nearest leg.",
            "4. Colours: plain material colours work as they are. For a painted look, UV unwrap and give the material an",
            "   Image Texture (PNG, JPG or an animated GIF). Name a material \"glow\" (or give it Emission) to make it full-bright.",
            "5. File > Export > Wavefront (.obj) with the defaults (Forward -Z, Up Y). Untick the reference objects or leave",
            "   them in: objects named ref_* are ignored.",
            "6. In game (admins): Right Shift > Wardrobe > Import > Choose a file, pick the .obj. The .mtl and textures next",
            "   to it are copied along. Set the name, price, slot and position in the editor, then Publish (or Save).",
            "",
            "Moving parts (wings that flap): add a cosmetic.json next to the .obj (see example-cosmetic.json). Under",
            "\"groups\", per object name: \"pivot\" is the hinge in Blender coordinates (snap the 3D cursor there and read its",
            "location in the N panel), \"animate\" waves it: rot (degrees per axis), bob, speed, spin, flySpeed, flyAmp, walkAmp.",
            "Blender animations and armatures are not imported.",
            "",
            "Other cosmetic.json keys for OBJ models: \"scale\" (multiply the size), \"offset\" [x, y, z] in blocks, \"up\": \"z\" if",
            "you exported with Z up, \"bone\" for everything, \"texture\" to use one image for every face, \"translucent\": true.",
            "",
            "Blockbench works the same way: File > Export > Export OBJ Model. Pictures and GIFs need no tools at all: import",
            "one and it becomes a cape (a 64x32-layout image is used as a cape texture as it is).",
            "");

    // ---- helpers ----------------------------------------------------------------------------------------------------

    static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 40) s = s.substring(0, 40).replaceAll("-+$", "");
        return s.isEmpty() ? "cosmetic" : s;
    }

    static Path freeFolder(Path dir, String slug) {
        Path p = dir.resolve(slug);
        for (int i = 2; Files.exists(p) || Files.exists(dir.resolve(slug + ".png")); i++) p = dir.resolve(slug + "-" + i);
        return p;
    }

    static String ext(Path file) {
        String n = file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? ".png" : n.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static List<Object> list(double... v) {
        List<Object> out = new ArrayList<Object>();
        for (double d : v) out.add(d);
        return out;
    }

    static void deleteTree(Path p) {
        try {
            if (Files.isDirectory(p)) {
                try (java.util.stream.Stream<Path> s = Files.list(p)) {
                    for (Path c : (Iterable<Path>) s::iterator) deleteTree(c);
                }
            }
            Files.deleteIfExists(p);
        } catch (IOException ignored) { }
    }
}
