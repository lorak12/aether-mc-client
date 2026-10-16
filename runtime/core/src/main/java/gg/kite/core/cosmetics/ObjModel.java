package gg.kite.core.cosmetics;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Wavefront OBJ models (Blender, Blockbench and most 3D tools export them) as cosmetics.
 *
 * <p>Coordinates: Blender's defaults. 1 unit = 1 block, Y up, the player stands on the origin facing +Z (Blender:
 * facing -Y, the front view). The player reference from the Blender kit shows where the head, body and limbs are.
 * Each object (or group) follows the player part it is named after (head, body, right_arm, Arm.L, leg_r...);
 * others follow the slot's usual part (hats the head, wings the body, boots the nearest leg).
 *
 * <p>Textures: one image used by every material is used as it is (an animated GIF animates). Otherwise every
 * material image and every plain material colour (Kd) is packed into one atlas, so colour-only Blender models work.
 * Materials named "glow" or with an emission colour are full-bright. Objects named ref_* (the kit's player
 * reference) are skipped.
 */
final class ObjModel {
    static final int MAX_FACES = 40000;
    /** Player bone pivots in model pixels (vanilla PlayerModel, standing). */
    static final float[][] BONE_PIVOTS = { { 0, 0, 0 }, { 0, 0, 0 }, { -5, 2, 0 }, { 5, 2, 0 }, { -1.9f, 12, 0 }, { 1.9f, 12, 0 } };

    static final class Material {
        final String name;
        int color = 0xFFFFFFFF;
        String map;
        boolean glow;
        float alpha = 1;

        Material(String name) {
            this.name = name;
            String n = name.toLowerCase(Locale.ROOT);
            glow = n.contains("glow") || n.contains("emissi");
        }
    }

    static final class Face {
        final String group;
        final Material mat;
        /** OBJ-space positions and texture coordinates (null when the face has none). */
        final float[][] pos, uv;

        Face(String group, Material mat, float[][] pos, float[][] uv) {
            this.group = group;
            this.mat = mat;
            this.pos = pos;
            this.uv = uv;
        }
    }

    final List<Face> faces = new ArrayList<Face>();
    final List<String> mtllibs = new ArrayList<String>();
    final Map<String, Material> materials = new LinkedHashMap<String, Material>();

    private ObjModel() {}

    // ---- parsing ----------------------------------------------------------------------------------------------------

    static ObjModel parse(String text) {
        ObjModel m = new ObjModel();
        List<float[]> v = new ArrayList<float[]>(), vt = new ArrayList<float[]>();
        String group = "", mat = "";
        int line = 0;
        for (String raw : text.split("\r?\n")) {
            line++;
            String l = raw.trim();
            if (l.isEmpty() || l.charAt(0) == '#') continue;
            String[] t = l.split("\\s+");
            try {
                switch (t[0]) {
                    case "v": v.add(new float[] { f(t[1]), f(t[2]), f(t[3]) }); break;
                    case "vt": vt.add(new float[] { f(t[1]), t.length > 2 ? f(t[2]) : 0 }); break;
                    case "o":
                    case "g": group = l.substring(t[0].length()).trim(); break;
                    case "usemtl": mat = l.substring(6).trim(); break;
                    case "mtllib": m.mtllibs.add(l.substring(6).trim()); break;
                    case "f": {
                        int n = t.length - 1;
                        // The Blender kit's player reference (ref_*) is never part of the cosmetic.
                        if (n < 3 || group.toLowerCase(Locale.ROOT).startsWith("ref_")) break;
                        float[][] pos = new float[n][], uv = new float[n][];
                        boolean hasUv = true;
                        for (int i = 0; i < n; i++) {
                            String[] idx = t[i + 1].split("/");
                            pos[i] = v.get(index(idx[0], v.size()));
                            if (idx.length > 1 && !idx[1].isEmpty()) uv[i] = vt.get(index(idx[1], vt.size()));
                            else hasUv = false;
                        }
                        if (m.faces.size() >= MAX_FACES) throw new IllegalArgumentException("more than " + MAX_FACES + " faces: decimate the model in Blender first");
                        m.faces.add(new Face(group, m.material(mat), pos, hasUv ? uv : null));
                        break;
                    }
                    default: break; // vn, s, l, p...: not needed
                }
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException && e.getMessage() != null && e.getMessage().startsWith("more than")) throw e;
                throw new IllegalArgumentException("line " + line + ": " + l);
            }
        }
        if (m.faces.isEmpty()) throw new IllegalArgumentException("the OBJ has no faces");
        return m;
    }

    private Material material(String name) {
        Material mt = materials.get(name);
        if (mt == null) materials.put(name, mt = new Material(name));
        return mt;
    }

    /** Read a .mtl file into this model's materials. */
    void parseMtl(String text) {
        Material cur = null;
        for (String raw : text.split("\r?\n")) {
            String l = raw.trim();
            if (l.isEmpty() || l.charAt(0) == '#') continue;
            String[] t = l.split("\\s+");
            try {
                switch (t[0]) {
                    case "newmtl": cur = material(l.substring(6).trim()); break;
                    case "Kd": if (cur != null) cur.color = (cur.color & 0xFF000000) | rgb(f(t[1]), f(t[2]), f(t[3])); break;
                    case "Ke": if (cur != null && (f(t[1]) > 0.05f || f(t[2]) > 0.05f || f(t[3]) > 0.05f)) cur.glow = true; break;
                    case "d": if (cur != null) cur.alpha = Math.max(0, Math.min(1, f(t[1]))); break;
                    case "Tr": if (cur != null) cur.alpha = 1 - Math.max(0, Math.min(1, f(t[1]))); break;
                    case "map_Kd": if (cur != null) cur.map = t[t.length - 1].replace('\\', '/'); break;
                    default: break;
                }
            } catch (RuntimeException ignored) { }
        }
    }

    /** Material images in use, in first-use order. */
    List<String> maps() {
        List<String> out = new ArrayList<String>();
        for (Face fc : faces) if (fc.mat.map != null && !out.contains(fc.mat.map)) out.add(fc.mat.map);
        return out;
    }

    /** True when every face uses the same image: the texture can be used as it is (animation included). */
    boolean singleTexture() {
        String only = null;
        for (Face fc : faces) {
            if (fc.mat.map == null) return false;
            if (only == null) only = fc.mat.map;
            else if (!only.equals(fc.mat.map)) return false;
        }
        return only != null;
    }

    boolean translucent() {
        for (Face fc : faces) if (fc.mat.alpha < 0.99f) return true;
        return false;
    }

    // ---- texture atlas ----------------------------------------------------------------------------------------------

    /** Where one material's image or colour sits in the atlas, 0..1. */
    static final class Region {
        final float u0, v0, u1, v1;
        final boolean flat;

        Region(float u0, float v0, float u1, float v1, boolean flat) {
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.flat = flat;
        }
    }

    /** Result of {@link #atlas}: the image and each material's region. */
    static final class Atlas {
        final BufferedImage image;
        final Map<Material, Region> regions;

        Atlas(BufferedImage image, Map<Material, Region> regions) {
            this.image = image;
            this.regions = regions;
        }
    }

    static final int CELL = 4;

    /** Pack {@code images} (by map name; missing ones count as plain colours) side by side, colours in a column. */
    Atlas atlas(Map<String, BufferedImage> images) {
        List<Material> used = new ArrayList<Material>();
        for (Face fc : faces) if (!used.contains(fc.mat)) used.add(fc.mat);
        int w = 0, h = 0;
        Map<String, int[]> placed = new LinkedHashMap<String, int[]>();
        for (Material mt : used) {
            BufferedImage img = mt.map == null ? null : images.get(mt.map);
            if (img == null || placed.containsKey(mt.map)) continue;
            placed.put(mt.map, new int[] { w, 0, img.getWidth(), img.getHeight() });
            w += img.getWidth();
            h = Math.max(h, img.getHeight());
        }
        List<Material> flat = new ArrayList<Material>();
        for (Material mt : used) if (mt.map == null || images.get(mt.map) == null) flat.add(mt);
        int rows = Math.max(8, h / CELL), cols = (flat.size() + rows - 1) / rows;
        int aw = w + cols * CELL, ah = Math.max(h, Math.min(rows, flat.size()) * CELL);
        aw = Math.max(aw, CELL);
        ah = Math.max(ah, CELL);
        BufferedImage atlas = new BufferedImage(aw, ah, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        Map<Material, Region> regions = new LinkedHashMap<Material, Region>();
        for (Material mt : used) {
            int[] r = mt.map == null ? null : placed.get(mt.map);
            if (r == null) continue;
            g.drawImage(images.get(mt.map), r[0], r[1], null);
            regions.put(mt, new Region(r[0] / (float) aw, r[1] / (float) ah, (r[0] + r[2]) / (float) aw, (r[1] + r[3]) / (float) ah, false));
        }
        for (int i = 0; i < flat.size(); i++) {
            Material mt = flat.get(i);
            int x = w + (i / rows) * CELL, y = (i % rows) * CELL;
            int a = Math.round(((mt.color >>> 24) & 255) * mt.alpha);
            g.setColor(new java.awt.Color((a << 24) | (mt.color & 0xFFFFFF), true));
            g.fillRect(x, y, CELL, CELL);
            float cu = (x + CELL / 2f) / aw, cv = (y + CELL / 2f) / ah;
            regions.put(mt, new Region(cu, cv, cu, cv, true));
        }
        g.dispose();
        return new Atlas(atlas, regions);
    }

    // ---- model ------------------------------------------------------------------------------------------------------

    /** Placement options from cosmetic.json. */
    static final class Options {
        /** Units per block (null: guess; models taller than 6 units are taken to be in pixels). */
        Float scale;
        float[] offset = { 0, 0, 0 };
        /** Exported with Z up instead of Blender's default Y up. */
        boolean zUp;
        /** Bone for everything not named after one; null = the slot's usual bone. */
        CosmeticModel.Bone bone;
        /** An animation preset for the whole model ({@link CosmeticModel#PRESETS}). */
        String animation = "none";
        /** Per object/group: "bone", "pivot" (OBJ units, like Blender's 3D cursor) and "animate". */
        Map<String, Map<?, ?>> groups = new LinkedHashMap<String, Map<?, ?>>();
    }

    /** Build the cosmetic model. {@code atlas} null = UVs are used as they are (one texture). */
    CosmeticModel model(Slot slot, Options opt, Atlas atlas) {
        float scale = opt.scale != null ? opt.scale : guessScale(opt.zUp);
        Map<String, CosmeticModel.Part> parts = new LinkedHashMap<String, CosmeticModel.Part>();
        Map<String, float[]> pivots = new LinkedHashMap<String, float[]>();
        for (Face fc : faces) {
            Map<?, ?> cfg = groupConfig(opt, fc.group);
            float[][] model = new float[fc.pos.length][];
            float cx = 0;
            for (int i = 0; i < fc.pos.length; i++) {
                model[i] = toModel(fc.pos[i], scale, opt);
                cx += model[i][0];
            }
            cx /= fc.pos.length;
            CosmeticModel.Bone bone = boneFor(fc.group, cfg, opt, slot, cx);
            String key = bone + "|" + fc.group;
            CosmeticModel.Part part = parts.get(key);
            if (part == null) {
                part = new CosmeticModel.Part();
                part.name = fc.group;
                part.bone = bone;
                float[] bp = BONE_PIVOTS[bone.ordinal()];
                float[] pivot = cfg != null && cfg.get("pivot") != null
                        ? toModel(CosmeticModel.floats(cfg.get("pivot"), 3, null, "pivot"), scale, opt) : bp.clone();
                part.pivot = new float[] { pivot[0] - bp[0], pivot[1] - bp[1], pivot[2] - bp[2] };
                if (cfg != null && cfg.get("rotation") != null) part.rotation = CosmeticModel.floats(cfg.get("rotation"), 3, null, "rotation");
                if (cfg != null && cfg.get("animate") instanceof Map) part.anim = CosmeticModel.parseAnim((Map<?, ?>) cfg.get("animate"));
                parts.put(key, part);
                pivots.put(key, pivot);
            }
            float[] pivot = pivots.get(key);
            Region region = atlas == null ? null : atlas.regions.get(fc.mat);
            float[][] uv = new float[fc.pos.length][];
            for (int i = 0; i < fc.pos.length; i++) uv[i] = uv(fc.uv == null ? null : fc.uv[i], region);
            // Fan into quads (triangles repeat their last vertex).
            for (int i = 1; i + 1 < fc.pos.length; i += 2) {
                int a = 0, b = i, c = i + 1, d = Math.min(i + 2, fc.pos.length - 1);
                int[] idx = { a, b, c, d };
                float[] q = new float[20];
                for (int k = 0; k < 4; k++) {
                    float[] p = model[idx[k]];
                    q[k * 5] = p[0] - pivot[0];
                    q[k * 5 + 1] = p[1] - pivot[1];
                    q[k * 5 + 2] = p[2] - pivot[2];
                    q[k * 5 + 3] = uv[idx[k]][0];
                    q[k * 5 + 4] = uv[idx[k]][1];
                }
                (fc.mat.glow ? part.glowPolys : part.polys).add(q);
            }
        }
        return CosmeticModel.of(CosmeticModel.preset(new ArrayList<CosmeticModel.Part>(parts.values()), opt.animation));
    }

    private static Map<?, ?> groupConfig(Options opt, String group) {
        for (Map.Entry<String, Map<?, ?>> e : opt.groups.entrySet()) if (e.getKey().equalsIgnoreCase(group)) return e.getValue();
        return null;
    }

    private static float[] uv(float[] t, Region r) {
        float u = t == null ? 0.5f : clamp01(t[0]), v = t == null ? 0.5f : clamp01(1 - t[1]);
        if (r == null) return new float[] { u, v };
        if (r.flat) return new float[] { r.u0, r.v0 };
        return new float[] { r.u0 + (r.u1 - r.u0) * u, r.v0 + (r.v1 - r.v0) * v };
    }

    private static float clamp01(float f) { return Math.max(0, Math.min(1, f)); }

    /** OBJ point -> model pixels: Y flips down, the front turns to -Z (a rotation, so faces keep their winding). */
    static float[] toModel(float[] p, float scale, Options opt) {
        float x = p[0], y = opt.zUp ? p[2] : p[1], z = opt.zUp ? -p[1] : p[2];
        x = x * scale + opt.offset[0];
        y = y * scale + opt.offset[1];
        z = z * scale + opt.offset[2];
        return new float[] { x * 16, 24 - y * 16, -z * 16 };
    }

    private float guessScale(boolean zUp) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (Face fc : faces) {
            for (float[] p : fc.pos) {
                float y = zUp ? p[2] : p[1];
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
            }
        }
        return hi - lo > 6 ? 1 / 16f : 1f;
    }

    private static final Pattern HEAD = Pattern.compile("(^|_)(head|helmet)($|_)"), BODY = Pattern.compile("(^|_)(body|torso|chest)($|_)");
    private static final Pattern RIGHT_ARM = Pattern.compile("right_?arm|arm_?(r|right)($|_)"), LEFT_ARM = Pattern.compile("left_?arm|arm_?(l|left)($|_)");
    private static final Pattern RIGHT_LEG = Pattern.compile("right_?leg|leg_?(r|right)($|_)"), LEFT_LEG = Pattern.compile("left_?leg|leg_?(l|left)($|_)");

    /** The bone an object name refers to ("Head", "Arm.L", "right_leg"...), or null. */
    static CosmeticModel.Bone boneNamed(String name) {
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (RIGHT_ARM.matcher(n).find()) return CosmeticModel.Bone.RIGHT_ARM;
        if (LEFT_ARM.matcher(n).find()) return CosmeticModel.Bone.LEFT_ARM;
        if (RIGHT_LEG.matcher(n).find()) return CosmeticModel.Bone.RIGHT_LEG;
        if (LEFT_LEG.matcher(n).find()) return CosmeticModel.Bone.LEFT_LEG;
        if (HEAD.matcher(n).find()) return CosmeticModel.Bone.HEAD;
        if (BODY.matcher(n).find()) return CosmeticModel.Bone.BODY;
        return null;
    }

    private static CosmeticModel.Bone boneFor(String group, Map<?, ?> cfg, Options opt, Slot slot, float centreX) {
        if (cfg != null && cfg.get("bone") != null) {
            CosmeticModel.Bone b = CosmeticModel.Bone.byId(String.valueOf(cfg.get("bone")));
            if (b == null) throw new IllegalArgumentException("unknown bone \"" + cfg.get("bone") + "\" for " + group);
            return b;
        }
        CosmeticModel.Bone named = boneNamed(group);
        if (named != null) return named;
        if (opt.bone != null) return opt.bone;
        switch (slot) {
            case HAT:
            case EARS:
            case FACE: return CosmeticModel.Bone.HEAD;
            case BOOTS: return centreX < 0 ? CosmeticModel.Bone.RIGHT_LEG : CosmeticModel.Bone.LEFT_LEG;
            default: return CosmeticModel.Bone.BODY;
        }
    }

    /** Blender writes linear colours; textures are sRGB. */
    private static int rgb(float r, float g, float b) {
        return 0xFF000000 | srgb(r) << 16 | srgb(g) << 8 | srgb(b);
    }

    private static int srgb(float linear) {
        float c = Math.max(0, Math.min(1, linear));
        float s = c <= 0.0031308f ? c * 12.92f : (float) (1.055 * Math.pow(c, 1 / 2.4) - 0.055);
        return Math.round(s * 255);
    }

    private static float f(String s) { return Float.parseFloat(s); }

    private static int index(String s, int size) {
        int i = Integer.parseInt(s);
        int r = i < 0 ? size + i : i - 1;
        if (r < 0 || r >= size) throw new IndexOutOfBoundsException();
        return r;
    }
}
