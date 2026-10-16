package gg.kite.core.cosmetics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A cosmetic's 3D model: parts attached to player bones, each a pivot + rotation holding box-UV cubes (the layout of
 * vanilla's ModelPart and Blockbench's "box UV"), optionally animated. Same conventions as vanilla: units are pixels
 * (1/16 block), Y points down, rotations are degrees applied Z, then Y, then X around the part's pivot.
 *
 * <pre>
 * "parts": [{
 *   "bone": "body",               head | body | right_arm | left_arm | right_leg | left_leg (children inherit)
 *   "pivot": [-1, 3, 2],          offset from the bone (or parent part) origin
 *   "rotation": [0, -20, 0],
 *   "scale": [1, 1, 1],
 *   "mirror": true,               also add a copy reflected across X (left/right pairs share one definition)
 *   "animate": { "rot": [0, 20, 0], "bob": 0, "spin": 0, "speed": 0.1, "phase": 0, "flySpeed": 3, "flyAmp": 1.5, "walkAmp": 0 },
 *   "cubes": [{ "from": [-8, -1, 0], "size": [8, 3, 1], "uv": [0, 0], "inflate": 0, "mirror": false, "glow": false }],
 *   "children": [ ...parts... ]
 * }]
 * </pre>
 */
public final class CosmeticModel {
    /** Player model parts a cosmetic can follow. */
    public enum Bone {
        HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG;

        static Bone byId(String id) {
            for (Bone b : values()) if (b.name().equalsIgnoreCase(id.trim().replace('-', '_'))) return b;
            return null;
        }
    }

    /** What the wearer is doing, for animations. */
    public static final class Motion {
        /** Ticks since the entity spawned, with partial ticks. */
        public float ageTicks;
        /** 0 standing .. 1 sprinting. */
        public float walkSpeed;
        /** Gliding or creative-flying: wings flap harder. */
        public boolean flying;

        public Motion() {}

        public Motion(float ageTicks, float walkSpeed, boolean flying) {
            this.ageTicks = ageTicks;
            this.walkSpeed = walkSpeed;
            this.flying = flying;
        }
    }

    static final class Cube {
        float[] from, size;
        float u, v, inflate;
        boolean mirror, glow;

        Cube copy() {
            Cube c = new Cube();
            c.from = from.clone();
            c.size = size.clone();
            c.u = u;
            c.v = v;
            c.inflate = inflate;
            c.mirror = mirror;
            c.glow = glow;
            return c;
        }
    }

    /** Offsets that follow a sine wave: rotation (deg) and bob (px) amplitudes, plus a constant spin (deg/tick). */
    static final class Anim {
        float[] rot = { 0, 0, 0 };
        float bob, spin, speed = 0.1f, phase, flySpeed = 1f, flyAmp = 1f, walkAmp;

        Anim copy() {
            Anim a = new Anim();
            a.rot = rot.clone();
            a.bob = bob;
            a.spin = spin;
            a.speed = speed;
            a.phase = phase;
            a.flySpeed = flySpeed;
            a.flyAmp = flyAmp;
            a.walkAmp = walkAmp;
            return a;
        }
    }

    static final class Part {
        /** Object / group name from an imported model ("" for cosmetic.json parts). */
        String name = "";
        Bone bone;
        float[] pivot = { 0, 0, 0 }, rotation = { 0, 0, 0 }, scale = { 1, 1, 1 };
        Anim anim;
        final List<Cube> cubes = new ArrayList<Cube>();
        /**
         * Free-form polygons (imported OBJ meshes): 4 vertices of (x, y, z, u, v) relative to the pivot, UVs 0..1;
         * triangles repeat their last vertex. {@link #glowPolys} are drawn full-bright.
         */
        final List<float[]> polys = new ArrayList<float[]>(), glowPolys = new ArrayList<float[]>();
        final List<Part> children = new ArrayList<Part>();

        /** The part reflected across X: positions and Y/Z rotations negate, textures mirror. */
        Part mirrored() {
            Part p = new Part();
            p.bone = bone == Bone.RIGHT_ARM ? Bone.LEFT_ARM : bone == Bone.LEFT_ARM ? Bone.RIGHT_ARM
                    : bone == Bone.RIGHT_LEG ? Bone.LEFT_LEG : bone == Bone.LEFT_LEG ? Bone.RIGHT_LEG : bone;
            p.pivot = new float[] { -pivot[0], pivot[1], pivot[2] };
            p.rotation = new float[] { rotation[0], -rotation[1], -rotation[2] };
            p.scale = scale.clone();
            if (anim != null) {
                p.anim = anim.copy();
                p.anim.rot = new float[] { anim.rot[0], -anim.rot[1], -anim.rot[2] };
                p.anim.spin = -anim.spin;
            }
            for (Cube c : cubes) {
                Cube m = c.copy();
                m.from[0] = -(c.from[0] + c.size[0]);
                m.mirror = !c.mirror;
                p.cubes.add(m);
            }
            for (Part child : children) p.children.add(child.mirrored());
            return p;
        }
    }

    /**
     * Where the whole cosmetic sits, on top of its model: moved, turned and resized around its own centre. Set by
     * cosmetic.json's "placement" and live by the importer's sliders.
     */
    public static final class Placement {
        public static final Placement NONE = new Placement(new float[3], new float[3], 1);

        /** Right, up and forward, in pixels, from the wearer's point of view. */
        public final float[] offset;
        /** Pitch, yaw and roll in degrees. */
        public final float[] rotation;
        public final float scale;

        public Placement(float[] offset, float[] rotation, float scale) {
            this.offset = offset.clone();
            this.rotation = rotation.clone();
            this.scale = scale <= 0 ? 1 : scale;
        }

        public boolean isNone() {
            for (int i = 0; i < 3; i++) if (offset[i] != 0 || rotation[i] != 0) return false;
            return scale == 1;
        }

        /** {"offset": [right, up, forward], "rotation": [pitch, yaw, roll], "scale": s}; missing keys change nothing. */
        public static Placement parse(Object json) {
            if (!(json instanceof Map)) return NONE;
            Map<?, ?> m = (Map<?, ?>) json;
            return new Placement(floats(m.get("offset"), 3, new float[3], "placement.offset"), floats(m.get("rotation"), 3, new float[3], "placement.rotation"),
                    num(m.get("scale"), 1));
        }

        public Map<String, Object> toJson() {
            Map<String, Object> o = new java.util.LinkedHashMap<String, Object>();
            o.put("offset", list(offset));
            o.put("rotation", list(rotation));
            o.put("scale", Math.round(scale * 100) / 100.0);
            return o;
        }

        private static List<Object> list(float[] v) {
            List<Object> out = new ArrayList<Object>();
            for (float f : v) out.add(Math.round(f * 100) / 100.0);
            return out;
        }
    }

    private final List<Part> roots;
    final float uvWidth, uvHeight;
    private volatile Placement placement = Placement.NONE;
    /** Per bone: the centre of the geometry at rest (what placement turns and scales around); built on first use. */
    private volatile float[][] centres;

    public Placement placement() { return placement; }

    /** Move the whole cosmetic (cheap: the next {@link #mesh} uses it). */
    public void setPlacement(Placement p) { placement = p == null ? Placement.NONE : p; }

    private CosmeticModel(List<Part> roots, float uvWidth, float uvHeight) {
        this.roots = roots;
        this.uvWidth = uvWidth;
        this.uvHeight = uvHeight;
    }

    /** A model built from parts directly (OBJ import); cube UVs, if any, are 0..1. */
    static CosmeticModel of(List<Part> roots) {
        if (roots.isEmpty()) throw new IllegalArgumentException("the model has no faces");
        return new CosmeticModel(Collections.unmodifiableList(new ArrayList<Part>(roots)), 1, 1);
    }

    /** Number of free-form polygons (imported meshes). */
    public int polyCount() {
        int n = 0;
        List<Part> todo = new ArrayList<Part>(roots);
        while (!todo.isEmpty()) {
            Part p = todo.remove(todo.size() - 1);
            n += p.polys.size() + p.glowPolys.size();
            todo.addAll(p.children);
        }
        return n;
    }

    /** Number of cubes, mirrored copies included. */
    public int cubeCount() {
        int n = 0;
        List<Part> todo = new ArrayList<Part>(roots);
        while (!todo.isEmpty()) {
            Part p = todo.remove(todo.size() - 1);
            n += p.cubes.size();
            todo.addAll(p.children);
        }
        return n;
    }

    /** Bones this model draws on. */
    public List<Bone> bones() {
        List<Bone> out = new ArrayList<Bone>();
        for (Part p : roots) if (!out.contains(p.bone)) out.add(p.bone);
        return out;
    }

    // ---- parsing ----------------------------------------------------------------------------------------------------

    /** Parse the {@code "parts"} list of a cosmetic.json. UVs are in a {@code uvWidth x uvHeight} texture space. */
    public static CosmeticModel parse(Object parts, float uvWidth, float uvHeight) {
        if (!(parts instanceof List) || ((List<?>) parts).isEmpty()) throw new IllegalArgumentException("\"parts\" must be a non-empty list");
        if (uvWidth <= 0 || uvHeight <= 0) throw new IllegalArgumentException("texture size must be positive");
        List<Part> roots = new ArrayList<Part>();
        for (Object o : (List<?>) parts) parsePart(o, null, roots);
        return new CosmeticModel(Collections.unmodifiableList(roots), uvWidth, uvHeight);
    }

    private static void parsePart(Object o, Bone inherited, List<Part> into) {
        if (!(o instanceof Map)) throw new IllegalArgumentException("a part must be an object");
        Map<?, ?> m = (Map<?, ?>) o;
        Part p = new Part();
        Object bone = m.get("bone");
        if (bone != null) {
            p.bone = Bone.byId(String.valueOf(bone));
            if (p.bone == null) throw new IllegalArgumentException("unknown bone \"" + bone + "\"");
        } else if (inherited == null) {
            throw new IllegalArgumentException("a top-level part needs a \"bone\"");
        } else {
            p.bone = inherited;
        }
        p.pivot = floats(m.get("pivot"), 3, p.pivot, "pivot");
        p.rotation = floats(m.get("rotation"), 3, p.rotation, "rotation");
        p.scale = floats(m.get("scale"), 3, p.scale, "scale");
        if (m.get("animate") instanceof Map) p.anim = parseAnim((Map<?, ?>) m.get("animate"));
        if (m.get("cubes") instanceof List) for (Object c : (List<?>) m.get("cubes")) p.cubes.add(parseCube(c));
        if (m.get("children") instanceof List) for (Object c : (List<?>) m.get("children")) parsePart(c, p.bone, p.children);
        into.add(p);
        if (Boolean.TRUE.equals(m.get("mirror"))) into.add(p.mirrored());
    }

    private static Cube parseCube(Object o) {
        if (!(o instanceof Map)) throw new IllegalArgumentException("a cube must be an object");
        Map<?, ?> m = (Map<?, ?>) o;
        Cube c = new Cube();
        c.from = floats(m.get("from"), 3, null, "from");
        c.size = floats(m.get("size"), 3, null, "size");
        for (float s : c.size) if (s < 0) throw new IllegalArgumentException("cube size must not be negative");
        float[] uv = floats(m.get("uv"), 2, new float[] { 0, 0 }, "uv");
        c.u = uv[0];
        c.v = uv[1];
        c.inflate = num(m.get("inflate"), 0);
        c.mirror = Boolean.TRUE.equals(m.get("mirror"));
        c.glow = Boolean.TRUE.equals(m.get("glow"));
        return c;
    }

    static Anim parseAnim(Map<?, ?> m) {
        Anim a = new Anim();
        a.rot = floats(m.get("rot"), 3, a.rot, "animate.rot");
        a.bob = num(m.get("bob"), 0);
        a.spin = num(m.get("spin"), 0);
        a.speed = num(m.get("speed"), a.speed);
        a.phase = num(m.get("phase"), 0);
        a.flySpeed = num(m.get("flySpeed"), 1);
        a.flyAmp = num(m.get("flyAmp"), 1);
        a.walkAmp = num(m.get("walkAmp"), 0);
        return a;
    }

    static float num(Object o, float def) { return o instanceof Number ? ((Number) o).floatValue() : def; }

    static float[] floats(Object o, int n, float[] def, String what) {
        if (o == null) {
            if (def == null) throw new IllegalArgumentException("missing \"" + what + "\"");
            return def.clone();
        }
        if (!(o instanceof List) || ((List<?>) o).size() != n) throw new IllegalArgumentException("\"" + what + "\" must be a list of " + n + " numbers");
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            Object v = ((List<?>) o).get(i);
            if (!(v instanceof Number)) throw new IllegalArgumentException("\"" + what + "\" must be a list of " + n + " numbers");
            out[i] = ((Number) v).floatValue();
        }
        return out;
    }

    // ---- meshing ----------------------------------------------------------------------------------------------------

    /** Build this frame's quads. */
    public Mesh mesh(Motion motion) {
        Placement pl = placement;
        Mesh mesh = new Mesh();
        if (pl.isNone()) {
            for (Part p : roots) emit(p, Mat.identity(), motion, mesh);
            return mesh;
        }
        float[][] c = centres();
        float[][] mats = new float[Bone.values().length][];
        for (Part p : roots) {
            int b = p.bone.ordinal();
            if (mats[b] == null) mats[b] = placementMatrix(pl, c[b]);
            emit(p, mats[b], motion, mesh);
        }
        return mesh;
    }

    /** Offset, then rotate and scale around the centre. Right / up / forward are -X / -Y / -Z in model space. */
    private static float[] placementMatrix(Placement pl, float[] c) {
        float[] m = Mat.translate(-pl.offset[0] + c[0], -pl.offset[1] + c[1], -pl.offset[2] + c[2]);
        m = Mat.mul(m, Mat.rotateZYX((float) Math.toRadians(pl.rotation[2]), (float) Math.toRadians(pl.rotation[1]), (float) Math.toRadians(pl.rotation[0])));
        m = Mat.mul(m, Mat.scale(pl.scale, pl.scale, pl.scale));
        return Mat.mul(m, Mat.translate(-c[0], -c[1], -c[2]));
    }

    private float[][] centres() {
        float[][] c = centres;
        if (c != null) return c;
        Mesh rest = new Mesh();
        for (Part p : roots) emit(p, Mat.identity(), new Motion(), rest);
        c = new float[Bone.values().length][];
        for (Bone bone : Bone.values()) {
            float[] lo = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE }, hi = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
            for (boolean glow : new boolean[] { false, true }) {
                Mesh.Layer l = rest.layer(bone, glow);
                if (l == null) continue;
                float[] d = l.data();
                for (int q = 0; q < l.quads(); q++) {
                    for (int k = 0; k < 4; k++) {
                        int o = q * Mesh.STRIDE + 3 + k * 5;
                        for (int a = 0; a < 3; a++) {
                            lo[a] = Math.min(lo[a], d[o + a]);
                            hi[a] = Math.max(hi[a], d[o + a]);
                        }
                    }
                }
            }
            c[bone.ordinal()] = lo[0] > hi[0] ? new float[3] : new float[] { (lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2 };
        }
        centres = c;
        return c;
    }

    // ---- animation presets ------------------------------------------------------------------------------------------

    /** Presets the importer offers (cosmetic.json "animation"). */
    public static final String[] PRESETS = { "none", "flap", "bob", "spin", "sway" };

    /**
     * Apply an animation preset to imported parts. "flap" waves parts named like wings (or else any off-centre part)
     * about their pivots, mirrored left and right; "bob" floats everything up and down; "spin" turns it around the
     * vertical axis (halos); "sway" rocks it side to side (tails). Returns the new root list.
     */
    static List<Part> preset(List<Part> roots, String preset) {
        if (preset == null || preset.isEmpty() || preset.equals("none")) return roots;
        if (preset.equals("flap")) {
            boolean named = false;
            for (Part p : roots) if (p.name.toLowerCase(Locale.ROOT).contains("wing")) named = true;
            for (Part p : roots) {
                boolean wing = named ? p.name.toLowerCase(Locale.ROOT).contains("wing") : Math.abs(p.pivot[0]) > 0.5f;
                if (!wing || Math.abs(p.pivot[0]) < 0.01f) continue;
                Anim a = new Anim();
                a.rot = new float[] { 0, p.pivot[0] < 0 ? 25 : -25, 0 };
                a.speed = 0.15f;
                a.flySpeed = 2.5f;
                a.flyAmp = 1.6f;
                p.anim = a;
            }
            return roots;
        }
        Anim a = new Anim();
        if (preset.equals("bob")) {
            a.bob = 0.6f;
            a.speed = 0.08f;
        } else if (preset.equals("spin")) {
            a.spin = 2f;
            a.speed = 0;
        } else if (preset.equals("sway")) {
            a.rot = new float[] { 0, 0, 6 };
            a.speed = 0.07f;
            a.walkAmp = 1.5f;
        } else {
            throw new IllegalArgumentException("unknown animation \"" + preset + "\" (none, flap, bob, spin, sway)");
        }
        // One wrapper per bone, at the bone's origin, carries the motion for everything on it.
        Map<Bone, Part> wrappers = new java.util.LinkedHashMap<Bone, Part>();
        for (Part p : roots) {
            Part w = wrappers.get(p.bone);
            if (w == null) {
                w = new Part();
                w.bone = p.bone;
                w.anim = a.copy();
                wrappers.put(p.bone, w);
            }
            w.children.add(p);
        }
        return new ArrayList<Part>(wrappers.values());
    }

    private void emit(Part p, float[] parent, Motion motion, Mesh mesh) {
        float rx = p.rotation[0], ry = p.rotation[1], rz = p.rotation[2], bob = 0;
        if (p.anim != null) {
            Anim a = p.anim;
            float t = motion.ageTicks;
            float wave = (float) Math.sin(t * a.speed * (motion.flying ? a.flySpeed : 1f) + a.phase);
            float amp = (motion.flying ? a.flyAmp : 1f) * (1f + a.walkAmp * Math.max(0f, Math.min(1f, motion.walkSpeed)));
            rx += a.rot[0] * amp * wave;
            ry += a.rot[1] * amp * wave + a.spin * t;
            rz += a.rot[2] * amp * wave;
            bob = a.bob * amp * wave;
        }
        float[] m = Mat.mul(parent, Mat.translate(p.pivot[0], p.pivot[1] + bob, p.pivot[2]));
        m = Mat.mul(m, Mat.rotateZYX((float) Math.toRadians(rz), (float) Math.toRadians(ry), (float) Math.toRadians(rx)));
        if (p.scale[0] != 1 || p.scale[1] != 1 || p.scale[2] != 1) m = Mat.mul(m, Mat.scale(p.scale[0], p.scale[1], p.scale[2]));
        for (Cube c : p.cubes) cube(c, m, mesh.layerFor(p.bone, c.glow));
        if (!p.polys.isEmpty()) polys(p.polys, m, mesh.layerFor(p.bone, false));
        if (!p.glowPolys.isEmpty()) polys(p.glowPolys, m, mesh.layerFor(p.bone, true));
        for (Part child : p.children) emit(child, m, motion, mesh);
    }

    // Box UV faces in vanilla ModelPart.Cube order and vertex order. Corners are indexed by bits: 1 = max x,
    // 2 = max y, 4 = max z.
    private static final int[][] FACES = {
            { 5, 4, 0, 1 }, // down
            { 3, 2, 6, 7 }, // up
            { 0, 4, 6, 2 }, // west (-x)
            { 1, 0, 2, 3 }, // north (-z)
            { 5, 1, 3, 7 }, // east (+x)
            { 4, 5, 7, 6 }, // south (+z)
    };
    private static final float[][] NORMALS = { { 0, -1, 0 }, { 0, 1, 0 }, { -1, 0, 0 }, { 0, 0, -1 }, { 1, 0, 0 }, { 0, 0, 1 } };

    private void cube(Cube c, float[] m, Mesh.Layer out) {
        float x0 = c.from[0] - c.inflate, y0 = c.from[1] - c.inflate, z0 = c.from[2] - c.inflate;
        float x1 = c.from[0] + c.size[0] + c.inflate, y1 = c.from[1] + c.size[1] + c.inflate, z1 = c.from[2] + c.size[2] + c.inflate;
        if (c.mirror) {
            float t = x0;
            x0 = x1;
            x1 = t;
        }
        float[][] corners = new float[8][];
        for (int i = 0; i < 8; i++) {
            corners[i] = Mat.point(m, (i & 1) != 0 ? x1 : x0, (i & 2) != 0 ? y1 : y0, (i & 4) != 0 ? z1 : z0);
        }
        float w = c.size[0], h = c.size[1], d = c.size[2], u = c.u, v = c.v;
        // Unfolded box: [u, v] is the top-left of the "down" face's left neighbour, like vanilla.
        float[][] uv = {
                { u + d, v, u + d + w, v + d },                 // down
                { u + d + w, v + d, u + d + w + w, v },         // up
                { u, v + d, u + d, v + d + h },                 // west
                { u + d, v + d, u + d + w, v + d + h },         // north
                { u + d + w, v + d, u + d + w + d, v + d + h }, // east
                { u + d + w + d, v + d, u + d + w + d + w, v + d + h }, // south
        };
        for (int f = 0; f < 6; f++) {
            float[] r = uv[f];
            float u1 = r[0] / uvWidth, v1 = r[1] / uvHeight, u2 = r[2] / uvWidth, v2 = r[3] / uvHeight;
            // Vanilla Polygon: vertex 0 (u2, v1), 1 (u1, v1), 2 (u1, v2), 3 (u2, v2); mirroring reverses the order.
            float[][] tex = { { u2, v1 }, { u1, v1 }, { u1, v2 }, { u2, v2 } };
            int[] idx = FACES[f];
            float[] n = NORMALS[f];
            float nx = c.mirror ? -n[0] : n[0];
            float[] normal = Mat.normal(m, nx, n[1], n[2]);
            float[] data = out.next();
            int o = (out.quads() - 1) * Mesh.STRIDE;
            data[o++] = normal[0];
            data[o++] = normal[1];
            data[o++] = normal[2];
            for (int k = 0; k < 4; k++) {
                int src = c.mirror ? 3 - k : k;
                float[] p = corners[idx[src]];
                data[o++] = p[0];
                data[o++] = p[1];
                data[o++] = p[2];
                data[o++] = tex[src][0];
                data[o++] = tex[src][1];
            }
        }
    }

    private static void polys(List<float[]> list, float[] m, Mesh.Layer out) {
        for (float[] q : list) {
            float[] data = out.next();
            int o = (out.quads() - 1) * Mesh.STRIDE;
            float[][] p = new float[4][];
            for (int k = 0; k < 4; k++) p[k] = Mat.point(m, q[k * 5], q[k * 5 + 1], q[k * 5 + 2]);
            // Counter-clockwise seen from outside, like the cube faces: normal = (b - a) x (c - a).
            float ax = p[1][0] - p[0][0], ay = p[1][1] - p[0][1], az = p[1][2] - p[0][2];
            float bx = p[2][0] - p[0][0], by = p[2][1] - p[0][1], bz = p[2][2] - p[0][2];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-6f) {
                nx /= len;
                ny /= len;
                nz /= len;
            } else {
                ny = -1;
            }
            data[o++] = nx;
            data[o++] = ny;
            data[o++] = nz;
            for (int k = 0; k < 4; k++) {
                data[o++] = p[k][0];
                data[o++] = p[k][1];
                data[o++] = p[k][2];
                data[o++] = q[k * 5 + 3];
                data[o++] = q[k * 5 + 4];
            }
        }
    }

    /** Minimal row-major 4x4 matrices (float[16]) for posing parts. */
    static final class Mat {
        static float[] identity() { return new float[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 }; }

        static float[] translate(float x, float y, float z) {
            float[] m = identity();
            m[3] = x;
            m[7] = y;
            m[11] = z;
            return m;
        }

        static float[] scale(float x, float y, float z) {
            float[] m = identity();
            m[0] = x;
            m[5] = y;
            m[10] = z;
            return m;
        }

        /** Rz * Ry * Rx, like JOML's rotationZYX used by ModelPart.translateAndRotate. */
        static float[] rotateZYX(float z, float y, float x) {
            return mul(mul(rot(2, z), rot(1, y)), rot(0, x));
        }

        private static float[] rot(int axis, float a) {
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            float[] m = identity();
            if (a == 0) return m;
            switch (axis) {
                case 0: m[5] = c; m[6] = -s; m[9] = s; m[10] = c; break;
                case 1: m[0] = c; m[2] = s; m[8] = -s; m[10] = c; break;
                default: m[0] = c; m[1] = -s; m[4] = s; m[5] = c; break;
            }
            return m;
        }

        static float[] mul(float[] a, float[] b) {
            float[] r = new float[16];
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    float s = 0;
                    for (int k = 0; k < 4; k++) s += a[i * 4 + k] * b[k * 4 + j];
                    r[i * 4 + j] = s;
                }
            }
            return r;
        }

        static float[] point(float[] m, float x, float y, float z) {
            return new float[] {
                    m[0] * x + m[1] * y + m[2] * z + m[3],
                    m[4] * x + m[5] * y + m[6] * z + m[7],
                    m[8] * x + m[9] * y + m[10] * z + m[11] };
        }

        /** Direction through the rotation/scale part, renormalised. */
        static float[] normal(float[] m, float x, float y, float z) {
            float nx = m[0] * x + m[1] * y + m[2] * z, ny = m[4] * x + m[5] * y + m[6] * z, nz = m[8] * x + m[9] * y + m[10] * z;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-6f) return new float[] { x, y, z };
            return new float[] { nx / len, ny / len, nz / len };
        }
    }

    @Override public String toString() { return String.format(Locale.ROOT, "CosmeticModel(%d cubes, %d polygons)", cubeCount(), polyCount()); }
}
