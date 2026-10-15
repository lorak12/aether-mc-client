package gg.aether.core.cosmetics;

/**
 * A cape that moves like cloth: the vanilla cape box (10 x 16 x 1 pixels, 64 x 32 texture layout) split into one
 * row per pixel of height and one column per pixel of width, then bent along its length and rippled across its
 * width. It hangs from the same point as vanilla's cape and reacts to the same movement values, so any cape texture
 * (Mojang capes too) fits.
 *
 * <ul>
 *   <li>Standing still it sways a little: a slow wave runs down the cloth and the hem ripples gently.</li>
 *   <li>Moving, the bottom trails behind further than the top, the wave speeds up and grows, and the hem flutters.</li>
 *   <li>Jumping, falling and turning lift and swing it like vanilla's.</li>
 * </ul>
 *
 * A moving player's cape is a {@link Sim}: vanilla's movement values (a per-tick trail plus a walking bob, so they
 * step and jitter) go through damped springs, and the wave phases are integrated over time, so a change of speed
 * changes how fast the wave runs instead of jumping it to another phase.
 */
public final class WavyCape {
    private WavyCape() {}

    /** Vanilla's cape box, in the cape part's local pixels. */
    static final float X0 = -5, Y0 = 0, Z0 = -1, WIDTH = 10, HEIGHT = 16, DEPTH = 1;
    /** Where the cape part hangs on the body (vanilla PlayerCapeModel). */
    static final float PIVOT_Z = 2;
    private static final int ROWS = 16;
    private static final float UV_W = 64, UV_H = 32;
    /** The steepest the cloth gets, in degrees back from hanging straight down: the root, and any row. */
    static final float MAX_ROOT = 85, MAX_ROW = 105;

    /** The player's movement, from the render state (vanilla AvatarRenderState's cape fields). */
    public static final class Motion {
        /** Ticks since the entity spawned, with partial ticks: the idle sway of a cape without a {@link Sim}. */
        public float ageTicks;
        /** Vertical movement (jumping, falling) plus vanilla's step bob, -6..32 plus the bob. */
        public float capeFlap;
        /** Forward speed, 0..150. */
        public float capeLean;
        /** Sideways speed (turning, strafing), -20..20. */
        public float capeLean2;
        /** Body yaw in degrees, any range (it wraps): turning on the spot swings the cloth too. */
        public float bodyYaw;

        public Motion() {}

        public Motion(float ageTicks, float capeFlap, float capeLean, float capeLean2) {
            this.ageTicks = ageTicks;
            this.capeFlap = capeFlap;
            this.capeLean = capeLean;
            this.capeLean2 = capeLean2;
        }

        public Motion(float ageTicks, float capeFlap, float capeLean, float capeLean2, float bodyYaw) {
            this(ageTicks, capeFlap, capeLean, capeLean2);
            this.bodyYaw = bodyYaw;
        }
    }

    /** The shape for one frame: root angles plus per-row bend and ripple. Package-private for tests. */
    static final class Shape {
        /** Tilt back from hanging straight down, and sideways swing, in degrees (vanilla's cape rotation). */
        float root, side;
        /** Extra bend of each row relative to the root, degrees; row k spans y k..k+1. */
        final float[] bend = new float[ROWS];
        /** Ripple across the width: amplitude (pixels at the hem), spatial frequency (rad/pixel) and phase. */
        float ripple, rippleK, ripplePhase;
    }

    /** Vanilla's rigid cape: no bend and no ripple. */
    static Shape rigid(Motion m) {
        Shape s = new Shape();
        s.root = 6f + m.capeLean / 2f + m.capeFlap;
        s.side = m.capeLean2 / 2f;
        return s;
    }

    /**
     * The cloth shape for a single {@code m} with no history (a still preview): settled on the movement, phases
     * from its age at the standing wave speeds.
     */
    static Shape shape(Motion m) {
        Sim sim = new Sim();
        sim.reset(0, m);
        float t = m.ageTicks;
        sim.wave = wrap(t * WAVE_OMEGA);
        sim.wave2 = wrap(t * WAVE_OMEGA * 1.7f);
        sim.ripple = wrap(t * RIPPLE_OMEGA);
        sim.idle = wrap(t * IDLE_OMEGA);
        return sim.shape();
    }

    /** The cape's quads for a single {@code m} on the {@link CosmeticModel.Bone#BODY BODY} bone; see {@link Sim}. */
    public static Mesh mesh(Motion m) { return build(shape(m)); }

    // ---- simulation -------------------------------------------------------------------------------------------------

    /** Wave speeds standing still in radians per tick; full speed adds the matching {@code *_RUN}. */
    private static final float WAVE_OMEGA = 0.075f, WAVE_RUN = 0.3f, RIPPLE_OMEGA = 0.11f, RIPPLE_RUN = 0.45f, IDLE_OMEGA = 0.045f;
    /** Integration sub-step, the longest frame stepped at once, and the gap that starts over; in ticks. */
    static final float STEP = 0.25f, MAX_DT = 4f, RESET_DT = 40f;
    private static final float TWO_PI = (float) (Math.PI * 2);

    private static float wrap(float phase) {
        phase %= TWO_PI;
        return phase < 0 ? phase + TWO_PI : phase;
    }

    /** {@code a - b} in degrees, folded into -180..180. */
    static float angleDiff(float a, float b) {
        float d = (a - b) % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }

    /**
     * One player's cape over time. Call {@link #mesh(double, Motion)} every frame with a steady clock: the cloth is
     * stepped by the time since the last call, in fixed sub-steps and at most {@code MAX_DT} at once, so drawing the
     * same player twice in a frame (inventory or menu preview) does not move it twice, a frame hitch does not kick
     * it, and a long gap (out of sight, teleport, respawn) starts it settled on the current movement.
     */
    public static final class Sim {
        private boolean started;
        private double time;
        /** Filtered movement (vanilla's units) and the springs' velocities. */
        float lean, leanV, flap, flapV, side, sideV;
        /** The body yaw the cloth trails behind, degrees: turning on the spot. */
        float yaw;
        /** Wave phases, radians. */
        float wave, wave2, ripple, idle;

        public Sim() {}

        /** Clock time of the last step, or NaN before the first. */
        public double time() { return started ? time : Double.NaN; }

        /** Step to {@code now} (ticks on any steady clock) with movement {@code m}; the cape's quads on the body bone. */
        public Mesh mesh(double now, Motion m) { return build(step(now, m)); }

        Shape step(double now, Motion m) {
            float dt = (float) (now - time);
            if (!started || !(Math.abs(dt) <= RESET_DT) || Float.isNaN(lean + flap + side + yaw + leanV + flapV + sideV)) {
                reset(now, m);
            } else if (dt > 0) { // otherwise the same frame again
                time = now;
                advance(Math.min(dt, MAX_DT), m);
            }
            return shape();
        }

        void reset(double now, Motion m) {
            started = true;
            time = now;
            lean = targetLean(m);
            flap = targetFlap(m);
            side = clamp(m.capeLean2, -20f, 20f);
            leanV = flapV = sideV = 0;
            yaw = Float.isNaN(m.bodyYaw) ? 0 : m.bodyYaw % 360f;
        }

        private static float targetLean(Motion m) { return clamp(m.capeLean, 0f, 150f); }

        private static float targetFlap(Motion m) { return clamp(m.capeFlap, -8f, 40f); }

        private void advance(float dt, Motion m) {
            float tl = targetLean(m), tf = targetFlap(m), sway = clamp(m.capeLean2, -20f, 20f);
            float bodyYaw = Float.isNaN(m.bodyYaw) ? yaw : m.bodyYaw;
            // A turn far beyond anything the cloth could trail (respawn, teleport, a preview turned around) snaps.
            if (Math.abs(angleDiff(bodyYaw, yaw)) > 90f) yaw = bodyYaw % 360f;
            int n = (int) Math.ceil(dt / STEP);
            float h = dt / n, follow = 1f - (float) Math.exp(-0.35f * h);
            for (int i = 0; i < n; i++) {
                // Turning: the cloth trails the body's yaw, and the lag swings it sideways like vanilla's lean.
                float lag = angleDiff(bodyYaw, yaw);
                yaw += lag * follow;
                float ts = clamp(sway + clamp(lag * 0.4f, -12f, 12f), -30f, 30f);
                // Damped springs (semi-implicit Euler, stable at this step): frequency w, damping z -> w^2, 2zw.
                leanV += (0.64f * (tl - lean) - 1.44f * leanV) * h; // w 0.8, z 0.9
                lean = clamp(lean + leanV * h, 0f, 150f);
                flapV += (0.49f * (tf - flap) - 1.05f * flapV) * h; // w 0.7, z 0.75
                flap = clamp(flap + flapV * h, -8f, 40f);
                sideV += (0.36f * (ts - side) - 1.02f * sideV) * h; // w 0.6, z 0.85
                side = clamp(side + sideV * h, -30f, 30f);
                float speed = clamp(lean / 150f, 0f, 1f);
                float omega = WAVE_OMEGA + speed * WAVE_RUN;
                wave = wrap(wave + omega * h);
                wave2 = wrap(wave2 + omega * 1.7f * h);
                ripple = wrap(ripple + (RIPPLE_OMEGA + speed * RIPPLE_RUN) * h);
                idle = wrap(idle + IDLE_OMEGA * h);
            }
            yaw %= 360f;
        }

        Shape shape() {
            Shape s = new Shape();
            // 0 standing .. 1 at full speed (walking is about 0.4, sprinting 0.55, flying fast 1).
            float speed = clamp(lean / 150f, 0f, 1f);
            // Softer than vanilla at the shoulders; the bend below adds the rest towards the hem.
            s.root = clamp(5f + lean * 0.3f + flap * 0.5f, 0f, MAX_ROOT);
            s.side = side / 2f + 1.6f * (1f - speed * 0.5f) * (float) Math.sin(idle);
            float trail = lean * 0.18f + Math.max(0f, flap) * 0.25f;
            float amp = 3.5f + speed * 11f;
            float k = 0.28f + speed * 0.05f;
            for (int i = 0; i < ROWS; i++) {
                float y = i + 0.5f, f = y / HEIGHT;
                float w = amp * f * (float) (Math.sin(wave - y * k) + 0.25 * Math.sin(wave2 - y * k * 1.9 + 1.3));
                float b = trail * (float) Math.pow(f, 1.6) + w;
                // Never swing into the player's back, nor up over the head.
                s.bend[i] = clamp(b, 1f - s.root, MAX_ROW - s.root);
            }
            s.ripple = 0.18f + speed * 0.45f;
            s.rippleK = 0.6f;
            s.ripplePhase = ripple;
            return s;
        }
    }

    // ---- geometry ---------------------------------------------------------------------------------------------------

    // Vanilla box UV faces (see CosmeticModel.cube), corners indexed by bits: 1 = max x, 2 = max y, 4 = max z.
    private static final int[][] FACES = { { 5, 4, 0, 1 }, { 3, 2, 6, 7 }, { 0, 4, 6, 2 }, { 1, 0, 2, 3 }, { 5, 1, 3, 7 }, { 4, 5, 7, 6 } };
    private static final float[][] NORMALS = { { 0, -1, 0 }, { 0, 1, 0 }, { -1, 0, 0 }, { 0, 0, -1 }, { 1, 0, 0 }, { 0, 0, 1 } };

    static Mesh build(Shape s) {
        Mesh mesh = new Mesh();
        Mesh.Layer out = mesh.layerFor(CosmeticModel.Bone.BODY, false);
        Spine spine = new Spine(s);
        // The cape part's rotation without its fixed half turn (the spine works in the turned space), then the pivot.
        float r = (float) Math.toRadians(s.root), side = (float) Math.toRadians(s.side);
        float[] rot = CosmeticModel.Mat.mul(CosmeticModel.Mat.mul(CosmeticModel.Mat.rotateZYX(0, 0, r), CosmeticModel.Mat.rotateZYX(side, 0, 0)),
                CosmeticModel.Mat.rotateZYX(0, -side, 0));
        float[] m = CosmeticModel.Mat.mul(CosmeticModel.Mat.translate(0, 0, PIVOT_Z), rot);

        float x1 = X0 + WIDTH, y1 = Y0 + HEIGHT, z1 = Z0 + DEPTH;
        float[][] corners = new float[8][];
        for (int i = 0; i < 8; i++) corners[i] = new float[] { (i & 1) != 0 ? x1 : X0, (i & 2) != 0 ? y1 : Y0, (i & 4) != 0 ? z1 : Z0 };
        float w = WIDTH, h = HEIGHT, d = DEPTH;
        float[][] uv = {
                { d, 0, d + w, d },
                { d + w, d, d + w + w, 0 },
                { 0, d, d, d + h },
                { d, d, d + w, d + h },
                { d + w, d, d + w + d, d + h },
                { d + w + d, d, d + w + d + w, d + h },
        };
        for (int f = 0; f < 6; f++) {
            float[] ur = uv[f];
            float u1 = ur[0] / UV_W, v1 = ur[1] / UV_H, u2 = ur[2] / UV_W, v2 = ur[3] / UV_H;
            float[][] tex = { { u2, v1 }, { u1, v1 }, { u1, v2 }, { u2, v2 } };
            float[][] c = new float[4][];
            for (int k = 0; k < 4; k++) c[k] = corners[FACES[f][k]];
            // Split each edge into one piece per pixel (so rows line up with the spine and columns with the ripple).
            int na = pieces(c[0], c[1]), nb = pieces(c[1], c[2]);
            float[] ref = CosmeticModel.Mat.normal(rot, -NORMALS[f][0], NORMALS[f][1], -NORMALS[f][2]);
            for (int j = 0; j < nb; j++) {
                for (int i = 0; i < na; i++) {
                    float a0 = (float) i / na, a1 = (float) (i + 1) / na, b0 = (float) j / nb, b1 = (float) (j + 1) / nb;
                    float[][] ab = { { a0, b0 }, { a1, b0 }, { a1, b1 }, { a0, b1 } };
                    float[][] p = new float[4][];
                    float[][] t = new float[4][];
                    for (int k = 0; k < 4; k++) {
                        float[] local = bilerp(c, ab[k][0], ab[k][1]);
                        float[] bent = spine.place(local[0], local[1], local[2]);
                        p[k] = CosmeticModel.Mat.point(m, bent[0], bent[1], bent[2]);
                        t[k] = bilerp(tex, ab[k][0], ab[k][1]);
                    }
                    quad(out, p, t, ref);
                }
            }
        }
        return mesh;
    }

    private static int pieces(float[] a, float[] b) {
        float dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        return Math.max(1, Math.round((float) Math.sqrt(dx * dx + dy * dy + dz * dz)));
    }

    /** Bilinear point of quad {@code c} (corner 0 at (0,0), 1 at (1,0), 2 at (1,1), 3 at (0,1)). */
    private static float[] bilerp(float[][] c, float a, float b) {
        float[] r = new float[c[0].length];
        for (int i = 0; i < r.length; i++) {
            float top = c[0][i] + (c[1][i] - c[0][i]) * a, bottom = c[3][i] + (c[2][i] - c[3][i]) * a;
            r[i] = top + (bottom - top) * b;
        }
        return r;
    }

    private static void quad(Mesh.Layer out, float[][] p, float[][] t, float[] ref) {
        // Normal from the bent geometry, facing the same way as the flat face.
        float ax = p[2][0] - p[0][0], ay = p[2][1] - p[0][1], az = p[2][2] - p[0][2];
        float bx = p[3][0] - p[1][0], by = p[3][1] - p[1][1], bz = p[3][2] - p[1][2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) {
            nx = ref[0];
            ny = ref[1];
            nz = ref[2];
        } else {
            float sign = nx * ref[0] + ny * ref[1] + nz * ref[2] < 0 ? -1f / len : 1f / len;
            nx *= sign;
            ny *= sign;
            nz *= sign;
        }
        float[] data = out.next();
        int o = (out.quads() - 1) * Mesh.STRIDE;
        data[o++] = nx;
        data[o++] = ny;
        data[o++] = nz;
        for (int k = 0; k < 4; k++) {
            data[o++] = p[k][0];
            data[o++] = p[k][1];
            data[o++] = p[k][2];
            data[o++] = t[k][0];
            data[o++] = t[k][1];
        }
    }

    /**
     * The bent centre line. Vanilla turns the cape box half a turn around Y, so here x and z are flipped: the cloth
     * hangs down +y from the pivot, its outer face towards +z (the player's back), and bending by an angle lifts it
     * towards +z.
     */
    private static final class Spine {
        final float[] y = new float[ROWS + 1], z = new float[ROWS + 1], knot = new float[ROWS + 1];
        final float[] bend;
        final Shape s;

        Spine(Shape s) {
            this.s = s;
            bend = s.bend;
            for (int k = 0; k < ROWS; k++) {
                float a = (float) Math.toRadians(bend[k]);
                y[k + 1] = y[k] + (float) Math.cos(a);
                z[k + 1] = z[k] + (float) Math.sin(a);
            }
            // Angles at the row boundaries, for smooth normals.
            knot[0] = bend[0];
            knot[ROWS] = bend[ROWS - 1];
            for (int k = 1; k < ROWS; k++) knot[k] = (bend[k - 1] + bend[k]) / 2f;
        }

        /** Where the box-local point {@code (lx, ly, lz)} ends up after the half turn and the bend. */
        float[] place(float lx, float ly, float lz) {
            float x = -lx, sy = ly - Y0, h = -lz; // the half turn: h is 0 at the inner face, 1 at the outer
            int k = Math.max(0, Math.min(ROWS - 1, (int) Math.floor(sy)));
            float f = sy - k, a = (float) Math.toRadians(bend[k]);
            float py = y[k] + f * (float) Math.cos(a), pz = z[k] + f * (float) Math.sin(a);
            float n = (float) Math.toRadians(knot[k] + (knot[k + 1] - knot[k]) * f);
            float along = sy / HEIGHT;
            float off = h + s.ripple * along * along * (float) Math.sin(x * s.rippleK + s.ripplePhase + sy * 0.35f);
            // Offset along the normal (0, -sin n, cos n).
            float wy = py - off * (float) Math.sin(n), wz = pz + off * (float) Math.cos(n);
            return new float[] { x, wy, wz };
        }
    }
}
