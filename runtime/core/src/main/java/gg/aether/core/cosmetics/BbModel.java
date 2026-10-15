package gg.aether.core.cosmetics;

import gg.aether.core.Json;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Blockbench projects (.bbmodel, any format: Generic Model, Modded Entity, Bedrock...) as cosmetics, textures included.
 *
 * <p>Blockbench's own space: pixels, Y up, the player stands on the origin facing north (-Z); the template from
 * {@link Importer#writeBlockbenchTemplate} shows the player and has a group per body part. Cubes (with per-face UVs,
 * face rotation, inflate, element and group rotations) and mesh elements are baked into polygons. A group named after
 * a body part (head, body, right_arm, left_arm, right_leg, left_leg) makes everything in it follow that part; other
 * top-level groups become separate parts pivoting on the group's origin (what the "flap" animation turns), and loose
 * elements follow the slot's usual part. Groups and elements named ref_* (the template's player) and elements with
 * export turned off are skipped.
 */
final class BbModel {
    static final int MAX_FACES = 40000;

    private BbModel() {}

    /** A converted project: the model and the texture to draw it with (several textures are packed side by side). */
    static final class Result {
        final CosmeticModel model;
        final BufferedImage texture;

        Result(CosmeticModel model, BufferedImage texture) {
            this.model = model;
            this.texture = texture;
        }
    }

    /** One texture of the project and the UV space its faces use. */
    private static final class Tex {
        BufferedImage image;
        float uvW, uvH;
        boolean used;
        /** Where it sits in the packed texture, 0..1. */
        float u0, v0, u1, v1;
    }

    private static final class Group {
        String name;
        float[] origin = { 0, 0, 0 }, rotation = { 0, 0, 0 };
        boolean skip;
        Group parent;
    }

    /** One face to emit: corners in Blockbench space, UVs 0..1 in the packed texture. */
    private static final class Face {
        float[][] pos, uv;
        Group top;
    }

    static Result parse(String json, Path folder, Slot slot, CosmeticModel.Bone forcedBone, String animation) throws IOException {
        Map<String, Object> root;
        try {
            root = Json.parseObject(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a Blockbench project (" + e.getMessage() + ")");
        }
        if (!(root.get("elements") instanceof List)) throw new IllegalArgumentException("not a Blockbench project: no elements");
        float resW = 16, resH = 16;
        if (root.get("resolution") instanceof Map) {
            Map<?, ?> r = (Map<?, ?>) root.get("resolution");
            resW = CosmeticModel.num(r.get("width"), 16);
            resH = CosmeticModel.num(r.get("height"), 16);
        }
        List<Tex> textures = textures(root, folder, resW, resH);

        // Which group (if any) each element sits in, from the outliner.
        Map<String, Group> groupOf = new HashMap<String, Group>();
        Map<String, Map<?, ?>> groupDefs = new HashMap<String, Map<?, ?>>();
        if (root.get("groups") instanceof List) {
            for (Object g : (List<?>) root.get("groups")) if (g instanceof Map && ((Map<?, ?>) g).get("uuid") != null) groupDefs.put(String.valueOf(((Map<?, ?>) g).get("uuid")), (Map<?, ?>) g);
        }
        if (root.get("outliner") instanceof List) for (Object n : (List<?>) root.get("outliner")) outline(n, null, groupOf, groupDefs);
        // Only textures the exported elements use go into the packed image (not the template's reference one).
        for (Object o : (List<?>) root.get("elements")) {
            if (!(o instanceof Map) || !exported((Map<?, ?>) o, groupOf) || !(((Map<?, ?>) o).get("faces") instanceof Map)) continue;
            for (Object f : ((Map<?, ?>) ((Map<?, ?>) o).get("faces")).values()) {
                Tex t = f instanceof Map ? texture(((Map<?, ?>) f).get("texture"), textures) : null;
                if (t != null) t.used = true;
            }
        }
        BufferedImage packed = pack(textures);

        List<Face> faces = new ArrayList<Face>();
        for (Object o : (List<?>) root.get("elements")) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> e = (Map<?, ?>) o;
            if (!exported(e, groupOf)) continue;
            Group g = groupOf.get(String.valueOf(e.get("uuid")));
            String type = e.get("type") == null ? "cube" : String.valueOf(e.get("type"));
            List<Face> made = type.equals("mesh") ? mesh(e, textures) : type.equals("cube") ? cube(e, textures) : new ArrayList<Face>();
            for (Face f : made) {
                // Element rotation, then each group's rotation around its origin, innermost first.
                float[] origin = CosmeticModel.floats(orZero(e.get("origin")), 3, null, "origin");
                float[] rot = rotation(e);
                for (int i = 0; i < f.pos.length; i++) {
                    float[] p = f.pos[i];
                    if (!type.equals("mesh")) p = rotate(p, origin, rot);
                    for (Group gg = g; gg != null; gg = gg.parent) p = rotate(p, gg.origin, gg.rotation);
                    f.pos[i] = p;
                }
                f.top = topGroup(g);
                faces.add(f);
                if (faces.size() > MAX_FACES) throw new IllegalArgumentException("more than " + MAX_FACES + " faces");
            }
        }
        if (faces.isEmpty()) throw new IllegalArgumentException("the project has no cubes to show (only ref_ or hidden ones?)");

        // Parts: per body-part group, per other top-level group (pivot = its origin), and one for loose elements.
        Map<String, CosmeticModel.Part> parts = new LinkedHashMap<String, CosmeticModel.Part>();
        Map<String, float[]> pivots = new HashMap<String, float[]>();
        for (Face f : faces) {
            float[][] model = new float[f.pos.length][];
            float cx = 0;
            for (int i = 0; i < f.pos.length; i++) {
                model[i] = toModel(f.pos[i]);
                cx += model[i][0];
            }
            cx /= f.pos.length;
            String gname = f.top == null ? "" : f.top.name;
            CosmeticModel.Bone named = f.top == null ? null : ObjModel.boneNamed(gname);
            CosmeticModel.Bone bone = named != null ? named : forcedBone != null ? forcedBone : defaultBone(slot, cx);
            String key = bone + "|" + gname;
            CosmeticModel.Part part = parts.get(key);
            if (part == null) {
                part = new CosmeticModel.Part();
                part.name = gname;
                part.bone = bone;
                float[] bp = ObjModel.BONE_PIVOTS[bone.ordinal()];
                float[] pivot = f.top != null && named == null ? toModel(f.top.origin) : bp.clone();
                part.pivot = new float[] { pivot[0] - bp[0], pivot[1] - bp[1], pivot[2] - bp[2] };
                parts.put(key, part);
                pivots.put(key, pivot);
            }
            float[] pivot = pivots.get(key);
            for (int i = 1; i + 1 < f.pos.length; i += 2) {
                int[] idx = { 0, i, i + 1, Math.min(i + 2, f.pos.length - 1) };
                float[] q = new float[20];
                for (int k = 0; k < 4; k++) {
                    float[] p = model[idx[k]];
                    q[k * 5] = p[0] - pivot[0];
                    q[k * 5 + 1] = p[1] - pivot[1];
                    q[k * 5 + 2] = p[2] - pivot[2];
                    q[k * 5 + 3] = f.uv[idx[k]][0];
                    q[k * 5 + 4] = f.uv[idx[k]][1];
                }
                part.polys.add(q);
            }
        }
        List<CosmeticModel.Part> roots = CosmeticModel.preset(new ArrayList<CosmeticModel.Part>(parts.values()), animation);
        return new Result(CosmeticModel.of(roots), packed);
    }

    /** Blockbench (pixels, Y up, facing -Z) -> model pixels (Y down from the neck, facing -Z): a turn about Z. */
    static float[] toModel(float[] p) { return new float[] { -p[0], 24 - p[1], p[2] }; }

    /** Model pixels -> Blockbench (the inverse of {@link #toModel}). */
    static float[] fromModel(float[] p) { return new float[] { -p[0], 24 - p[1], p[2] }; }

    private static CosmeticModel.Bone defaultBone(Slot slot, float centreX) {
        switch (slot) {
            case HAT:
            case EARS:
            case FACE: return CosmeticModel.Bone.HEAD;
            case BOOTS: return centreX < 0 ? CosmeticModel.Bone.RIGHT_LEG : CosmeticModel.Bone.LEFT_LEG;
            default: return CosmeticModel.Bone.BODY;
        }
    }

    // ---- outliner ---------------------------------------------------------------------------------------------------

    private static void outline(Object node, Group parent, Map<String, Group> groupOf, Map<String, Map<?, ?>> defs) {
        if (node instanceof String) {
            Map<?, ?> def = defs.get(node);
            if (def == null) {
                if (parent != null) groupOf.put((String) node, parent);
                return;
            }
            node = def;
        }
        if (!(node instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) node;
        Group g = new Group();
        g.name = m.get("name") == null ? "" : String.valueOf(m.get("name"));
        if (m.get("origin") instanceof List) g.origin = CosmeticModel.floats(m.get("origin"), 3, null, "group origin");
        if (m.get("rotation") instanceof List) g.rotation = CosmeticModel.floats(m.get("rotation"), 3, null, "group rotation");
        g.skip = g.name.toLowerCase(Locale.ROOT).startsWith("ref_") || Boolean.FALSE.equals(m.get("export"));
        g.parent = parent;
        if (m.get("children") instanceof List) for (Object c : (List<?>) m.get("children")) outline(c, g, groupOf, defs);
    }

    private static boolean exported(Map<?, ?> e, Map<String, Group> groupOf) {
        if (Boolean.FALSE.equals(e.get("export"))) return false;
        String name = e.get("name") == null ? "" : String.valueOf(e.get("name"));
        return !name.toLowerCase(Locale.ROOT).startsWith("ref_") && !skipped(groupOf.get(String.valueOf(e.get("uuid"))));
    }

    private static boolean skipped(Group g) {
        for (; g != null; g = g.parent) if (g.skip) return true;
        return false;
    }

    private static Group topGroup(Group g) {
        if (g == null) return null;
        // The outermost body-part group wins; otherwise the outermost group.
        Group top = g, bone = null;
        for (Group x = g; x != null; x = x.parent) {
            top = x;
            if (ObjModel.boneNamed(x.name) != null) bone = x;
        }
        return bone != null ? bone : top;
    }

    // ---- elements ---------------------------------------------------------------------------------------------------

    private static final String[] FACES = { "north", "south", "east", "west", "up", "down" };

    /** A cube's six faces, corners top-left, bottom-left, bottom-right, top-right as seen from outside. */
    private static List<Face> cube(Map<?, ?> e, List<Tex> textures) {
        float[] from = CosmeticModel.floats(e.get("from"), 3, null, "from"), to = CosmeticModel.floats(e.get("to"), 3, null, "to");
        float inf = CosmeticModel.num(e.get("inflate"), 0);
        float x0 = Math.min(from[0], to[0]) - inf, y0 = Math.min(from[1], to[1]) - inf, z0 = Math.min(from[2], to[2]) - inf;
        float x1 = Math.max(from[0], to[0]) + inf, y1 = Math.max(from[1], to[1]) + inf, z1 = Math.max(from[2], to[2]) + inf;
        List<Face> out = new ArrayList<Face>();
        Map<?, ?> faces = e.get("faces") instanceof Map ? (Map<?, ?>) e.get("faces") : null;
        if (faces == null) return out;
        for (String side : FACES) {
            if (!(faces.get(side) instanceof Map)) continue;
            Map<?, ?> fd = (Map<?, ?>) faces.get(side);
            Tex t = texture(fd.get("texture"), textures);
            if (t == null || !(fd.get("uv") instanceof List)) continue;
            float[][] c;
            switch (side) {
                case "north": c = new float[][] { { x1, y1, z0 }, { x1, y0, z0 }, { x0, y0, z0 }, { x0, y1, z0 } }; break;
                case "south": c = new float[][] { { x0, y1, z1 }, { x0, y0, z1 }, { x1, y0, z1 }, { x1, y1, z1 } }; break;
                case "east": c = new float[][] { { x1, y1, z1 }, { x1, y0, z1 }, { x1, y0, z0 }, { x1, y1, z0 } }; break;
                case "west": c = new float[][] { { x0, y1, z0 }, { x0, y0, z0 }, { x0, y0, z1 }, { x0, y1, z1 } }; break;
                case "up": c = new float[][] { { x0, y1, z0 }, { x0, y1, z1 }, { x1, y1, z1 }, { x1, y1, z0 } }; break;
                default: c = new float[][] { { x0, y0, z1 }, { x0, y0, z0 }, { x1, y0, z0 }, { x1, y0, z1 } }; break;
            }
            if (degenerate(c)) continue;
            float[] r = CosmeticModel.floats(fd.get("uv"), 4, null, "uv");
            // UV corners in the same order as the positions: top-left, bottom-left, bottom-right, top-right.
            float[][] uv = { { r[0], r[1] }, { r[0], r[3] }, { r[2], r[3] }, { r[2], r[1] } };
            int steps = ((int) (CosmeticModel.num(fd.get("rotation"), 0) / 90) % 4 + 4) % 4;
            float[][] turned = new float[4][];
            for (int i = 0; i < 4; i++) turned[i] = uv[(i + steps) % 4];
            Face f = new Face();
            f.pos = c;
            f.uv = new float[4][];
            for (int i = 0; i < 4; i++) f.uv[i] = place(turned[i], t);
            out.add(f);
        }
        return out;
    }

    /** Mesh elements: vertices relative to the element origin, faces of 3 or 4 vertices with their own UVs. */
    private static List<Face> mesh(Map<?, ?> e, List<Tex> textures) {
        List<Face> out = new ArrayList<Face>();
        if (!(e.get("vertices") instanceof Map) || !(e.get("faces") instanceof Map)) return out;
        Map<?, ?> verts = (Map<?, ?>) e.get("vertices");
        float[] origin = CosmeticModel.floats(orZero(e.get("origin")), 3, null, "origin"), rot = rotation(e);
        for (Object fo : ((Map<?, ?>) e.get("faces")).values()) {
            if (!(fo instanceof Map)) continue;
            Map<?, ?> fd = (Map<?, ?>) fo;
            Tex t = texture(fd.get("texture"), textures);
            if (t == null || !(fd.get("vertices") instanceof List)) continue;
            List<?> ids = (List<?>) fd.get("vertices");
            if (ids.size() < 3 || ids.size() > 4) continue;
            Map<?, ?> uvs = fd.get("uv") instanceof Map ? (Map<?, ?>) fd.get("uv") : new HashMap<Object, Object>();
            float[][] pos = new float[ids.size()][], uv = new float[ids.size()][];
            for (int i = 0; i < ids.size(); i++) {
                float[] v = CosmeticModel.floats(verts.get(ids.get(i)), 3, null, "vertex");
                pos[i] = rotate(new float[] { v[0] + origin[0], v[1] + origin[1], v[2] + origin[2] }, origin, rot);
                float[] u = uvs.get(ids.get(i)) == null ? new float[] { 0, 0 } : CosmeticModel.floats(uvs.get(ids.get(i)), 2, null, "uv");
                uv[i] = place(u, t);
            }
            if (pos.length == 4) sortQuad(pos, uv);
            Face f = new Face();
            f.pos = pos;
            f.uv = uv;
            out.add(f);
        }
        return out;
    }

    /** Blockbench stores quad vertices in no particular order: put them around the centre. */
    private static void sortQuad(float[][] pos, float[][] uv) {
        float[] c = new float[3];
        for (float[] p : pos) for (int a = 0; a < 3; a++) c[a] += p[a] / 4;
        float[] n = cross(sub(pos[1], pos[0]), sub(pos[2], pos[0]));
        if (len(n) < 1e-6f) n = cross(sub(pos[2], pos[0]), sub(pos[3], pos[0]));
        float[] ax = sub(pos[0], c), ay = cross(n, ax);
        final double[] ang = new double[4];
        for (int i = 0; i < 4; i++) {
            float[] d = sub(pos[i], c);
            ang[i] = Math.atan2(dot(d, ay), dot(d, ax));
        }
        Integer[] order = { 0, 1, 2, 3 };
        java.util.Arrays.sort(order, (a, b) -> Double.compare(ang[a], ang[b]));
        float[][] p2 = new float[4][], u2 = new float[4][];
        for (int i = 0; i < 4; i++) {
            p2[i] = pos[order[i]];
            u2[i] = uv[order[i]];
        }
        System.arraycopy(p2, 0, pos, 0, 4);
        System.arraycopy(u2, 0, uv, 0, 4);
    }

    private static boolean degenerate(float[][] c) { return len(cross(sub(c[1], c[0]), sub(c[2], c[0]))) < 1e-9f; }

    private static float[] rotation(Map<?, ?> e) {
        Object r = e.get("rotation");
        if (r instanceof List) return CosmeticModel.floats(r, 3, null, "rotation");
        if (r instanceof Map) { // old block-model form: {"angle": 22.5, "axis": "y"}
            Map<?, ?> m = (Map<?, ?>) r;
            float a = CosmeticModel.num(m.get("angle"), 0);
            String axis = String.valueOf(m.get("axis"));
            return new float[] { axis.equals("x") ? a : 0, axis.equals("y") ? a : 0, axis.equals("z") ? a : 0 };
        }
        return new float[3];
    }

    private static Object orZero(Object o) { return o instanceof List ? o : java.util.Arrays.asList(0, 0, 0); }

    /** Rotate {@code p} around {@code o} by Euler degrees, Z then Y then X like Blockbench (three.js "ZYX"). */
    private static float[] rotate(float[] p, float[] o, float[] deg) {
        if (deg[0] == 0 && deg[1] == 0 && deg[2] == 0) return p;
        float[] m = CosmeticModel.Mat.rotateZYX((float) Math.toRadians(deg[2]), (float) Math.toRadians(deg[1]), (float) Math.toRadians(deg[0]));
        float[] r = CosmeticModel.Mat.point(m, p[0] - o[0], p[1] - o[1], p[2] - o[2]);
        return new float[] { r[0] + o[0], r[1] + o[1], r[2] + o[2] };
    }

    // ---- textures ---------------------------------------------------------------------------------------------------

    private static List<Tex> textures(Map<String, Object> root, Path folder, float resW, float resH) throws IOException {
        List<Tex> out = new ArrayList<Tex>();
        if (!(root.get("textures") instanceof List)) return out;
        for (Object o : (List<?>) root.get("textures")) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            Tex t = new Tex();
            t.image = image(m, folder);
            if (t.image == null) throw new IllegalArgumentException("texture \"" + m.get("name") + "\" is missing: save the project with the texture embedded");
            t.uvW = CosmeticModel.num(m.get("uv_width"), resW);
            t.uvH = CosmeticModel.num(m.get("uv_height"), resH);
            // Animated textures (frames stacked vertically) show their first frame.
            if (m.get("frame_time") != null && t.image.getHeight() > t.image.getWidth() && t.image.getHeight() % t.image.getWidth() == 0) {
                t.image = t.image.getSubimage(0, 0, t.image.getWidth(), t.image.getWidth());
            }
            out.add(t);
        }
        return out;
    }

    private static BufferedImage image(Map<?, ?> m, Path folder) throws IOException {
        Object src = m.get("source");
        if (src instanceof String && ((String) src).startsWith("data:")) {
            String s = (String) src;
            byte[] png = Base64.getDecoder().decode(s.substring(s.indexOf(',') + 1));
            return ImageIO.read(new ByteArrayInputStream(png));
        }
        // Not embedded: a file next to the project, by name.
        for (Object key : new Object[] { m.get("name"), m.get("path") }) {
            if (!(key instanceof String) || folder == null) continue;
            Path f = folder.resolve(LocalCosmetics.Paths.fileName((String) key));
            if (Files.isRegularFile(f)) return Images.read(Files.readAllBytes(f)).frames.get(0);
        }
        return null;
    }

    /** Textures side by side in one image; each remembers its region. */
    private static BufferedImage pack(List<Tex> textures) {
        int w = 0, h = 0;
        for (Tex t : textures) {
            if (!t.used) continue;
            w += t.image.getWidth();
            h = Math.max(h, t.image.getHeight());
        }
        if (w == 0) return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        int x = 0;
        for (Tex t : textures) {
            if (!t.used) continue;
            g.drawImage(t.image, x, 0, null);
            t.u0 = x / (float) w;
            t.v0 = 0;
            t.u1 = (x + t.image.getWidth()) / (float) w;
            t.v1 = t.image.getHeight() / (float) h;
            x += t.image.getWidth();
        }
        g.dispose();
        return out;
    }

    private static Tex texture(Object ref, List<Tex> textures) {
        if (textures.isEmpty()) return null;
        if (ref instanceof Number) {
            int i = ((Number) ref).intValue();
            return i >= 0 && i < textures.size() ? textures.get(i) : null;
        }
        return ref == null || Boolean.FALSE.equals(ref) ? null : textures.get(0);
    }

    /** A UV in the texture's own pixel space -> 0..1 in the packed image. */
    private static float[] place(float[] uv, Tex t) {
        float u = Math.max(0, Math.min(1, uv[0] / t.uvW)), v = Math.max(0, Math.min(1, uv[1] / t.uvH));
        return new float[] { t.u0 + (t.u1 - t.u0) * u, t.v0 + (t.v1 - t.v0) * v };
    }

    // ---- vectors ----------------------------------------------------------------------------------------------------

    private static float[] sub(float[] a, float[] b) { return new float[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] }; }

    private static float[] cross(float[] a, float[] b) { return new float[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] }; }

    private static float dot(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    private static float len(float[] a) { return (float) Math.sqrt(dot(a, a)); }
}
