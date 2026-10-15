package gg.aether.core.cosmetics;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gg.aether.core.ClientHost;
import gg.aether.core.ConfigStore;
import gg.aether.core.Json;
import gg.aether.core.ModManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cosmetic models (box UV like vanilla), the catalog, loadout persistence and the backend sync. */
class CosmeticsTest {
    // ---- models -----------------------------------------------------------------------------------------------------

    private static Map<String, Object> cube(double[] from, double[] size, int u, int v, Object... extra) {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("from", list(from));
        c.put("size", list(size));
        c.put("uv", Arrays.asList((long) u, (long) v));
        for (int i = 0; i + 1 < extra.length; i += 2) c.put((String) extra[i], extra[i + 1]);
        return c;
    }

    private static List<Object> list(double... a) {
        List<Object> out = new ArrayList<Object>();
        for (double d : a) out.add(d);
        return out;
    }

    private static Map<String, Object> part(String bone, Object... kv) {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("bone", bone);
        for (int i = 0; i + 1 < kv.length; i += 2) p.put((String) kv[i], kv[i + 1]);
        return p;
    }

    private static float[] vertex(Mesh.Layer l, int quad, int k) {
        int o = quad * Mesh.STRIDE + 3 + k * 5;
        float[] d = l.data();
        return new float[] { d[o], d[o + 1], d[o + 2], d[o + 3], d[o + 4] };
    }

    @Test void boxUvMatchesVanillaCube() {
        // An 8x8x8 head-sized cube at uv (0, 0) in a 64x32 texture, like a skin's head.
        Map<String, Object> p = part("head", "cubes", Arrays.asList(cube(new double[] { -4, -8, -4 }, new double[] { 8, 8, 8 }, 0, 0)));
        Mesh.Layer l = CosmeticModel.parse(Arrays.asList(p), 64, 32).mesh(new CosmeticModel.Motion()).layer(CosmeticModel.Bone.HEAD, false);
        assertEquals(6, l.quads());
        // Face 3 is north (the face): vertex 0 is (max x, min y, min z) at (u + d + w, v + d) = (16, 8).
        assertArrayEquals(new float[] { 4, -8, -4, 16 / 64f, 8 / 32f }, vertex(l, 3, 0), 1e-5f);
        assertArrayEquals(new float[] { -4, 0, -4, 8 / 64f, 16 / 32f }, vertex(l, 3, 2), 1e-5f);
        // Face 0 ("down", the top in model space) samples the skin's head-top square.
        assertArrayEquals(new float[] { 4, -8, 4, 16 / 64f, 0 }, vertex(l, 0, 0), 1e-5f);
        float[] d = l.data();
        assertArrayEquals(new float[] { 0, 0, -1 }, new float[] { d[3 * Mesh.STRIDE], d[3 * Mesh.STRIDE + 1], d[3 * Mesh.STRIDE + 2] }, 1e-5f);
    }

    // ---- wavy capes -------------------------------------------------------------------------------------------------

    /** Every vertex of {@code want} ({@code x, y, z, u, v}) appears in {@code got}. */
    private static void assertContainsVertices(Mesh.Layer want, Mesh.Layer got) {
        for (int q = 0; q < want.quads(); q++) {
            for (int k = 0; k < 4; k++) {
                float[] w = vertex(want, q, k);
                boolean found = false;
                for (int gq = 0; gq < got.quads() && !found; gq++) {
                    for (int gk = 0; gk < 4 && !found; gk++) {
                        float[] g = vertex(got, gq, gk);
                        boolean same = true;
                        for (int i = 0; i < 5; i++) same &= Math.abs(g[i] - w[i]) < 1e-3f;
                        found = same;
                    }
                }
                assertTrue(found, "missing vertex " + Arrays.toString(w));
            }
        }
    }

    @Test void flatWavyCapeIsVanillasCape() {
        // Vanilla PlayerCapeModel: a 10x16x1 box at uv (0, 0) on the body at (0, 0, 2), turned by
        // rotX(6 + lean / 2 + flap) * rotY(180). As a part that is rotation (-angle, 180, 0) in ZYX order.
        WavyCape.Motion m = new WavyCape.Motion(0, 3, 40, 0);
        float angle = 6 + 40 / 2f + 3;
        Map<String, Object> p = part("body", "pivot", list(0, 0, 2), "rotation", list(-angle, 180, 0),
                "cubes", Arrays.asList(cube(new double[] { -5, 0, -1 }, new double[] { 10, 16, 1 }, 0, 0)));
        Mesh.Layer vanilla = CosmeticModel.parse(Arrays.asList(p), 64, 32).mesh(new CosmeticModel.Motion()).layer(CosmeticModel.Bone.BODY, false);
        Mesh.Layer flat = WavyCape.build(WavyCape.rigid(m)).layer(CosmeticModel.Bone.BODY, false);
        // One quad per pixel: front and back 10x16, sides 1x16, top and bottom 10x1.
        assertEquals(2 * 160 + 2 * 16 + 2 * 10, flat.quads());
        assertContainsVertices(vanilla, flat);
        // Normals face outwards: the quads of the outer (back) face point behind the player (+z on the body).
        float[] d = flat.data();
        boolean anyBackwards = false;
        for (int q = 0; q < flat.quads(); q++) anyBackwards |= d[q * Mesh.STRIDE + 2] > 0.8f;
        assertTrue(anyBackwards);
    }

    @Test void sidewaysSwingMatchesVanilla() {
        // With capeLean2 vanilla adds rotZ(b) * rotY(-b), b = lean2 / 2: the bottom corner (x -5 local) moves like this.
        WavyCape.Motion m = new WavyCape.Motion(0, 0, 0, 16);
        float a = (float) Math.toRadians(6), b = (float) Math.toRadians(8);
        float[] mat = CosmeticModel.Mat.mul(CosmeticModel.Mat.translate(0, 0, 2), CosmeticModel.Mat.mul(CosmeticModel.Mat.mul(
                CosmeticModel.Mat.rotateZYX(0, 0, a), CosmeticModel.Mat.rotateZYX(b, 0, 0)), CosmeticModel.Mat.rotateZYX(0, (float) Math.PI - b, 0)));
        float[] corner = CosmeticModel.Mat.point(mat, -5, 16, -1);
        Mesh.Layer flat = WavyCape.build(WavyCape.rigid(m)).layer(CosmeticModel.Bone.BODY, false);
        boolean found = false;
        for (int q = 0; q < flat.quads() && !found; q++) {
            for (int k = 0; k < 4; k++) {
                float[] v = vertex(flat, q, k);
                if (Math.abs(v[0] - corner[0]) < 1e-3f && Math.abs(v[1] - corner[1]) < 1e-3f && Math.abs(v[2] - corner[2]) < 1e-3f) found = true;
            }
        }
        assertTrue(found);
    }

    /** Mean z (distance behind the player) of the cape's lowest vertices. */
    private static float hemZ(Mesh.Layer l) {
        float maxY = -1e9f, sum = 0;
        int n = 0;
        for (int q = 0; q < l.quads(); q++) for (int k = 0; k < 4; k++) maxY = Math.max(maxY, vertex(l, q, k)[1]);
        for (int q = 0; q < l.quads(); q++) {
            for (int k = 0; k < 4; k++) {
                float[] v = vertex(l, q, k);
                if (v[1] > maxY - 1.5f) {
                    sum += v[2];
                    n++;
                }
            }
        }
        return sum / n;
    }

    @Test void capeSwaysStandingStillAndTrailsWhenRunning() {
        Mesh.Layer a = WavyCape.mesh(new WavyCape.Motion(0, 0, 0, 0)).layer(CosmeticModel.Bone.BODY, false);
        Mesh.Layer b = WavyCape.mesh(new WavyCape.Motion(20, 0, 0, 0)).layer(CosmeticModel.Bone.BODY, false);
        float idleMove = Math.abs(hemZ(a) - hemZ(b));
        assertTrue(idleMove > 0.05f && idleMove < 2f, "idle sway " + idleMove);

        float standing = 0, running = 0;
        for (int t = 0; t < 100; t += 5) {
            standing += hemZ(WavyCape.mesh(new WavyCape.Motion(t, 0, 0, 0)).layer(CosmeticModel.Bone.BODY, false));
            running += hemZ(WavyCape.mesh(new WavyCape.Motion(t, 0, 100, 0)).layer(CosmeticModel.Bone.BODY, false));
        }
        assertTrue(running > standing + 20 * 3, "running " + running / 20 + " vs standing " + standing / 20);
    }

    @Test void capeNeverSwingsIntoTheBack() {
        // The back of the body is at z = 2; the cloth may touch it but not pass through.
        float[][] motions = { { 0, 0, 0 }, { -6, 0, 0 }, { 0, 150, 0 }, { 32, 150, 0 }, { 0, 100, 0 }, { 20, 60, 0 } };
        for (float[] mo : motions) {
            for (int t = 0; t < 200; t += 7) {
                Mesh.Layer l = WavyCape.mesh(new WavyCape.Motion(t, mo[0], mo[1], mo[2])).layer(CosmeticModel.Bone.BODY, false);
                for (int q = 0; q < l.quads(); q++) {
                    for (int k = 0; k < 4; k++) {
                        float[] v = vertex(l, q, k);
                        assertTrue(v[2] > 1.6f, "z " + v[2] + " at t " + t + " motion " + Arrays.toString(mo));
                        assertFalse(Float.isNaN(v[0] + v[1] + v[2]));
                    }
                }
            }
        }
    }

    /** A scripted player feeding the cape like vanilla does (Player.moveCloak, bob and walkDist, extractCapeState). */
    private static final class CapePlayer {
        double x, y, z, xo, yo, zo, cx, cy, cz, cxo, cyo, czo, vy;
        float bob, bobO, walk, walkO, yaw, yawO;
        boolean ground = true;

        /** One tick: {@code speed} blocks forward, jumping whenever on the ground if {@code jump}, turning {@code turn}. */
        void tick(double speed, boolean jump, double lift, float turn) {
            xo = x;
            yo = y;
            zo = z;
            bobO = bob;
            walkO = walk;
            yawO = yaw;
            yaw += turn;
            double r = Math.toRadians(yaw);
            x += -Math.sin(r) * speed;
            z += Math.cos(r) * speed;
            y += lift;
            if (ground && jump) {
                vy = 0.42;
                ground = false;
            }
            if (!ground) {
                y += vy;
                vy = (vy - 0.08) * 0.98;
                if (y <= 0) {
                    y = 0;
                    vy = 0;
                    ground = true;
                }
            }
            walk += (float) speed * 0.6f;
            bob += ((ground ? (float) Math.min(0.1, speed) : 0f) - bob) * 0.4f;
            cxo = cx;
            cyo = cy;
            czo = cz;
            cx += (x - cx) * 0.25;
            cy += (y - cy) * 0.25;
            cz += (z - cz) * 0.25;
        }

        /** The render state's cape values at partial tick {@code f}, with the body yaw folded into -180..180. */
        WavyCape.Motion motion(float f) {
            double d = cxo + (cx - cxo) * f - (xo + (x - xo) * f), e = cyo + (cy - cyo) * f - (yo + (y - yo) * f);
            double g = czo + (cz - czo) * f - (zo + (z - zo) * f);
            float h = yawO + WavyCape.angleDiff(yaw, yawO) * f;
            double i = Math.sin(Math.toRadians(h)), j = -Math.cos(Math.toRadians(h));
            float flap = Math.max(-6f, Math.min(32f, (float) e * 10f));
            float lean = Math.max(0f, Math.min(150f, (float) (d * i + g * j) * 100f));
            float lean2 = Math.max(-20f, Math.min(20f, (float) (d * j - g * i) * 100f));
            float k = bobO + (bob - bobO) * f, l = walkO + (walk - walkO) * f;
            flap += (float) Math.sin(l * 6f) * 32f * k;
            return new WavyCape.Motion(0, flap, lean, lean2, WavyCape.angleDiff(h, 0));
        }
    }

    /** Where the hem's centre hangs (y, z in the cape's pixels) for a shape. */
    private static float[] hem(WavyCape.Shape s) {
        float y = 0, z = 0;
        for (float b : s.bend) {
            double a = Math.toRadians(s.root + b);
            y += (float) Math.cos(a);
            z += (float) Math.sin(a);
        }
        return new float[] { y, z };
    }

    /** Frame stats for a run: worst hem speed (pixels per tick) and how often the hem turns back per tick. */
    private static float[] runCape(WavyCape.Sim sim, CapePlayer p, int ticks, double speed, boolean jump, double lift) {
        float maxSpeed = 0, prevDir = 0;
        int reversals = 0;
        float[] last = null;
        double lastT = 0;
        // Uneven frames: 144 fps with every 7th frame a 30 ms hitch.
        double t = 0;
        int frame = 0, tick = 0;
        while (tick < ticks) {
            t += frame++ % 7 == 6 ? 0.6 : 20.0 / 144;
            while (t >= tick + 1 && tick < ticks) {
                p.tick(speed * Math.min(1, (tick + 1) / 10.0), jump, lift, 0); // speeding up over half a second
                tick++;
            }
            WavyCape.Motion m = p.motion((float) (t - Math.floor(t)));
            WavyCape.Shape s = sim.step(t, m);
            assertTrue(s.root >= 0 && s.root <= WavyCape.MAX_ROOT, "root " + s.root);
            assertTrue(Math.abs(s.side) < 20f, "side " + s.side);
            for (float b : s.bend) {
                assertTrue(s.root + b >= 1f - 1e-3f && s.root + b <= WavyCape.MAX_ROW + 1e-3f, "row " + (s.root + b));
                assertFalse(Float.isNaN(b));
            }
            float[] h = hem(s);
            if (last != null) {
                float dz = h[1] - last[1], dt = (float) (t - lastT);
                maxSpeed = Math.max(maxSpeed, (float) Math.hypot(h[0] - last[0], dz) / dt);
                if (Math.abs(dz) > 1e-4f) {
                    float dir = Math.signum(dz);
                    if (prevDir != 0 && dir != prevDir) reversals++;
                    prevDir = dir;
                }
            }
            last = h;
            lastT = t;
        }
        return new float[] { maxSpeed, reversals / (float) ticks };
    }

    @Test void wavyCapeStaysCalmWalkingSprintingJumpingAndFlying() {
        // speed (blocks/tick), jump, vertical lift per tick: walk, sprint, sprint-jump, creative fly, fast fly, falling.
        Object[][] runs = { { 0.216, false, 0.0 }, { 0.28, false, 0.0 }, { 0.32, true, 0.0 }, { 0.55, false, 0.0 },
                { 1.1, false, 0.0 }, { 0.0, false, -0.9 }, { 0.0, false, 0.4 } };
        for (Object[] r : runs) {
            WavyCape.Sim sim = new WavyCape.Sim();
            CapePlayer p = new CapePlayer();
            float[] stats = runCape(sim, p, 200, (Double) r[0], (Boolean) r[1], (Double) r[2]);
            String what = Arrays.toString(r) + " hem speed " + stats[0] + " px/tick, reversals " + stats[1] + "/tick";
            // A natural wave moves the hem well under a pixel per tick; the old phase bug threw it tens of pixels.
            assertTrue(stats[0] < 3f, what);
            // The hem turns back with the wave and the walking bob (a step every ~3 ticks), never frame to frame.
            assertTrue(stats[1] < 0.4f, what);
        }
    }

    @Test void wavyCapeTrailsFurtherAtSpeed() {
        float[] hemZ = new float[3];
        double[] speeds = { 0, 0.28, 1.1 };
        for (int i = 0; i < 3; i++) {
            WavyCape.Sim sim = new WavyCape.Sim();
            CapePlayer p = new CapePlayer();
            float sum = 0;
            for (int tick = 0; tick < 200; tick++) {
                p.tick(speeds[i], false, 0, 0);
                WavyCape.Shape s = sim.step(tick, p.motion(0));
                if (tick >= 100) sum += hem(s)[1];
            }
            hemZ[i] = sum / 100;
        }
        assertTrue(hemZ[1] > hemZ[0] + 2 && hemZ[2] > hemZ[1] + 2, Arrays.toString(hemZ));
    }

    @Test void wavyCapeYawWrapDoesNotSpike() {
        // Turning steadily through +-180: the folded yaw jumps by 360 there, the unfolded one does not.
        WavyCape.Sim folded = new WavyCape.Sim(), open = new WavyCape.Sim();
        for (int i = 0; i <= 120; i++) {
            float yaw = 150f + i * 1.5f;
            WavyCape.Shape a = folded.step(i * 0.5, new WavyCape.Motion(0, 0, 0, 0, WavyCape.angleDiff(yaw, 0)));
            WavyCape.Shape b = open.step(i * 0.5, new WavyCape.Motion(0, 0, 0, 0, yaw));
            assertEquals(b.side, a.side, 1e-2f, "at yaw " + yaw);
            assertEquals(b.root, a.root, 1e-3f);
            // 3 degrees a tick swings it a little to one side, never across.
            if (i >= 40) assertTrue(a.side > 0 && a.side < 6f, "side " + a.side + " at yaw " + yaw);
        }
    }

    @Test void wavyCapeStepsOncePerFrame() {
        WavyCape.Sim once = new WavyCape.Sim(), twice = new WavyCape.Sim();
        CapePlayer p = new CapePlayer();
        for (int frame = 0; frame < 300; frame++) {
            if (frame % 3 == 0) p.tick(0.32, true, 0, 2f);
            double t = frame / 3.0;
            WavyCape.Motion m = p.motion((frame % 3) / 3f);
            WavyCape.Shape a = once.step(t, m);
            // The same player drawn again in the same frame (inventory preview, shadow pass...).
            twice.step(t, m);
            twice.step(t, m);
            WavyCape.Shape b = twice.step(t, m);
            assertEquals(a.root, b.root, 0f);
            assertEquals(a.side, b.side, 0f);
            assertArrayEquals(a.bend, b.bend, 0f);
            assertEquals(a.ripplePhase, b.ripplePhase, 0f);
        }
    }

    @Test void wavyCapeSettlesAfterAGapOrTeleport() {
        WavyCape.Sim sim = new WavyCape.Sim();
        WavyCape.Motion fast = new WavyCape.Motion(0, 30, 150, 20, 90);
        for (int i = 0; i < 50; i++) sim.step(i, fast);
        // Out of sight for a minute, then back standing on the other side of the world.
        WavyCape.Motion still = new WavyCape.Motion(0, 0, 0, 0, -90);
        WavyCape.Shape s = sim.step(50 + 1200, still);
        assertEquals(5f, s.root, 1e-3f);
        assertTrue(Math.abs(s.side) < 2f, "side " + s.side);
        // A long hitch steps at most a few ticks: it does not fling the cloth.
        WavyCape.Sim hitch = new WavyCape.Sim();
        hitch.step(0, still);
        WavyCape.Shape h = hitch.step(30, fast);
        assertTrue(h.root < 60f, "root after hitch " + h.root);
        for (float b : h.bend) assertFalse(Float.isNaN(b));
    }

    @Test void wavyCapesSettingPersists() {
        Cosmetics cos = new Cosmetics();
        assertTrue(cos.wavyCapes());
        cos.setWavyCapes(false);
        Cosmetics back = new Cosmetics();
        back.fromJson(cos.toJson());
        assertFalse(back.wavyCapes());
    }

    @Test void mirroredCubeReversesVerticesAndFlipsX() {
        Map<String, Object> p = part("body", "cubes", Arrays.asList(cube(new double[] { 1, 2, 3 }, new double[] { 4, 5, 6 }, 0, 0, "mirror", true)));
        Mesh.Layer l = CosmeticModel.parse(Arrays.asList(p), 64, 32).mesh(new CosmeticModel.Motion()).layer(CosmeticModel.Bone.BODY, false);
        // Vanilla: mirrored cubes swap min/max x, then reverse each polygon; the west face's normal points east.
        float[] v0 = vertex(l, 3, 0);
        assertArrayEquals(new float[] { 1, 7, 3 }, new float[] { v0[0], v0[1], v0[2] }, 1e-5f);
        assertEquals(1f, l.data()[2 * Mesh.STRIDE], 1e-5f);
    }

    @Test void mirroredPartIsReflectedOntoTheOtherLeg() {
        Map<String, Object> p = part("right_leg", "pivot", list(-2, 9, 1), "rotation", list(0, 30, 15), "mirror", true,
                "cubes", Arrays.asList(cube(new double[] { -6, -4, 0 }, new double[] { 6, 5, 1 }, 0, 0)));
        Mesh m = CosmeticModel.parse(Arrays.asList(p), 64, 32).mesh(new CosmeticModel.Motion());
        Mesh.Layer right = m.layer(CosmeticModel.Bone.RIGHT_LEG, false), left = m.layer(CosmeticModel.Bone.LEFT_LEG, false);
        assertNotNull(right);
        assertNotNull(left);
        // Collect every vertex position; the left leg's set is the right leg's set reflected across X.
        List<String> r = new ArrayList<String>(), lft = new ArrayList<String>();
        for (int q = 0; q < 6; q++) for (int k = 0; k < 4; k++) {
            float[] a = vertex(right, q, k), b = vertex(left, q, k);
            r.add(String.format("%.3f %.3f %.3f", -a[0] + 0f, a[1], a[2]));
            lft.add(String.format("%.3f %.3f %.3f", b[0] + 0f, b[1], b[2]));
        }
        Collections.sort(r);
        Collections.sort(lft);
        assertEquals(r, lft);
    }

    @Test void animationFollowsTimeFlightAndWalking() {
        Map<String, Object> anim = new LinkedHashMap<String, Object>();
        anim.put("rot", list(0, 20, 0));
        anim.put("speed", 0.1);
        anim.put("flyAmp", 2.0);
        anim.put("walkAmp", 1.0);
        Map<String, Object> p = part("body", "animate", anim, "cubes", Arrays.asList(cube(new double[] { -10, 0, 0 }, new double[] { 10, 1, 1 }, 0, 0)));
        CosmeticModel m = CosmeticModel.parse(Arrays.asList(p), 64, 32);
        float z0 = tipZ(m, new CosmeticModel.Motion(0, 0, false));
        float z1 = tipZ(m, new CosmeticModel.Motion(15.7f, 0, false)); // sin(1.57) = 1: full swing
        float flying = tipZ(m, new CosmeticModel.Motion(15.7f, 0, true));
        float walking = tipZ(m, new CosmeticModel.Motion(15.7f, 1, false));
        assertEquals(0, z0, 1e-4f);
        assertTrue(Math.abs(z1) > 3, "20 degrees at 10 px moves the tip by ~3.4 px");
        assertTrue(Math.abs(flying) > Math.abs(z1) * 1.5);
        assertTrue(Math.abs(walking) > Math.abs(z1) * 1.5);
    }

    /** Z of the cube's far (min x) end. */
    private static float tipZ(CosmeticModel m, CosmeticModel.Motion motion) {
        Mesh.Layer l = m.mesh(motion).layer(CosmeticModel.Bone.BODY, false);
        return vertex(l, 3, 1)[2];
    }

    @Test void badModelsAreRejectedWithAReason() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CosmeticModel.parse(Arrays.asList(part("tail")), 64, 32));
        assertTrue(e.getMessage().contains("bone"));
        Map<String, Object> noSize = new LinkedHashMap<String, Object>();
        noSize.put("from", list(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CosmeticModel.parse(Arrays.asList(part("head", "cubes", Arrays.asList(noSize))), 64, 32));
        assertThrows(IllegalArgumentException.class, () -> CosmeticModel.parse(Collections.emptyList(), 64, 32));
    }

    // ---- catalog ----------------------------------------------------------------------------------------------------

    static final List<String> STARTERS = Arrays.asList("cape-aether", "cape-midnight", "cape-aurora", "cape-ember", "cape-galaxy",
            "wings-angel", "wings-dragon", "wings-fairy", "hat-tophat", "hat-crown", "hat-halo", "hat-bandana", "hat-bandana-blue",
            "ears-cat", "ears-bunny", "face-shades", "boots-winged", "boots-frost", "back-explorer");

    @Test void everyStarterLoads() throws IOException {
        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(null);
        assertEquals(Collections.emptyList(), reg.problems());
        List<String> ids = new ArrayList<String>();
        for (Cosmetic c : reg.all()) ids.add(c.id);
        assertEquals(STARTERS, ids);
        for (Slot s : Slot.values()) assertFalse(reg.bySlot(s).isEmpty(), "starter in " + s);
        for (Cosmetic c : reg.all()) {
            assertTrue(c.builtin);
            byte[] png = c.readTexture();
            assertArrayEquals(new int[] { c.textureWidth, c.textureHeight }, Png.size(png), c.id);
            assertEquals(0, c.textureHeight % c.frames);
            if (c.slot == Slot.CAPE) {
                assertNull(c.model);
                assertEquals(c.textureWidth / 2, c.frameHeight(), c.id + " frames are 2:1");
                assertTrue(c.textureWidth >= 256, c.id + " is high resolution");
            } else {
                assertFalse(c.model.mesh(new CosmeticModel.Motion()).isEmpty(), c.id);
                assertTrue(c.hasIconImage(), c.id + " has a menu icon");
                assertArrayEquals(new int[] { 128, 128 }, Png.size(c.readIconImage()), c.id);
            }
        }
        assertTrue(reg.get("cape-aurora").animated());
        assertEquals(24, reg.get("cape-aurora").frames);
        assertFalse(reg.get("cape-aether").animated());
        assertTrue(reg.get("wings-fairy").translucent);
        assertNotNull(reg.get("hat-halo").model.mesh(new CosmeticModel.Motion()).layer(CosmeticModel.Bone.HEAD, true), "halo glows");
    }

    @Test void animatedCapeFramesLoop() {
        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(null);
        Cosmetic c = reg.get("cape-ember");
        assertEquals(0, c.frame(0));
        assertEquals(1, c.frame(c.frameTimeMs));
        assertEquals(0, c.frame((long) c.frameTimeMs * c.frames));
    }

    private static void png(Path file, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(img, "png", file.toFile());
    }

    @Test void localFolderAddsCapesAndModelsAndReportsProblems(@TempDir Path dir) throws IOException {
        png(dir.resolve("My_Cape.png"), 64, 32);
        png(dir.resolve("flicker.png"), 128, 192); // 3 frames of 128x64
        png(dir.resolve("wide.png"), 64, 64 + 16); // not whole 2:1 frames: a picture laid out on a cape
        Path hat = dir.resolve("party-hat");
        Files.createDirectories(hat);
        png(hat.resolve("hat.png"), 128, 64);
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("name", "Party Hat");
        json.put("slot", "hat");
        json.put("texture", "hat.png");
        json.put("textureSize", Arrays.asList(32L, 16L));
        json.put("parts", Arrays.asList(part("head", "cubes", Arrays.asList(cube(new double[] { -2, -14, -2 }, new double[] { 4, 6, 4 }, 0, 0)))));
        Files.write(hat.resolve("cosmetic.json"), Json.write(json).getBytes(StandardCharsets.UTF_8));
        Path broken = dir.resolve("broken");
        Files.createDirectories(broken);
        Files.write(broken.resolve("cosmetic.json"), "{\"slot\": \"tail\"}".getBytes(StandardCharsets.UTF_8));

        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(dir);
        Cosmetic cape = reg.get("local/my_cape");
        assertNotNull(cape);
        assertEquals("My Cape", cape.name);
        assertFalse(cape.builtin);
        Cosmetic flicker = reg.get("local/flicker");
        assertEquals(3, flicker.frames);
        assertEquals(64, flicker.frameHeight());
        Cosmetic partyHat = reg.get("local/party-hat");
        assertEquals(Slot.HAT, partyHat.slot);
        assertEquals("Party Hat", partyHat.name);
        assertNotNull(partyHat.model);
        Cosmetic wide = reg.get("local/wide");
        assertEquals(1, wide.frames);
        assertEquals(wide.textureWidth, wide.frameHeight() * 2);
        assertEquals(1, reg.problems().size(), reg.problems().toString());
        assertTrue(reg.problems().toString().contains("broken"));
        assertTrue(reg.problems().toString().contains("unknown slot"));
        assertEquals(STARTERS.size() + 4, reg.all().size());
    }

    @Test void pathsOutsideTheCosmeticFolderAreRefused(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("sneaky");
        Files.createDirectories(f);
        Files.write(f.resolve("cosmetic.json"), "{\"slot\": \"cape\", \"texture\": \"../../secret.png\"}".getBytes(StandardCharsets.UTF_8));
        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(dir);
        assertNull(reg.get("local/sneaky"));
        assertTrue(reg.problems().get(0).contains("inside the folder"));
    }

    // ---- loadout ----------------------------------------------------------------------------------------------------

    @Test void loadoutEquipPreviewAndVisibility() {
        Cosmetics cos = new Cosmetics();
        cos.load(null);
        cos.setBackend(null);
        cos.tick(host("8667ba71-b85a-4004-af54-457a9734eed7", "Steve"), 0);
        cos.equip(Slot.CAPE, "cape-aurora");
        cos.equip(Slot.CAPE, "hat-crown"); // wrong slot: ignored
        cos.equip(Slot.HAT, "does-not-exist"); // unknown: ignored
        assertEquals("cape-aurora", cos.equippedId(Slot.CAPE));
        assertNull(cos.equippedId(Slot.HAT));

        Cosmetics.Equipped me = cos.forPlayer("8667BA71B85A4004AF54457A9734EED7");
        assertEquals("cape-aurora", me.get(Slot.CAPE).id);
        assertTrue(me.badge);
        assertTrue(cos.badge("8667ba71-b85a-4004-af54-457a9734eed7"));
        assertFalse(cos.badge("00000000000000000000000000000001"), "strangers are not client users");
        assertTrue(cos.forPlayer("00000000000000000000000000000001").isEmpty());

        cos.preview(Slot.CAPE, "cape-ember");
        cos.preview(Slot.HAT, "hat-halo");
        assertEquals("cape-ember", cos.own().get(Slot.CAPE).id);
        assertEquals("hat-halo", cos.own().get(Slot.HAT).id);
        cos.preview(Slot.CAPE, "");
        assertNull(cos.own().get(Slot.CAPE), "trying on \"None\"");
        cos.clearPreview();
        assertEquals("cape-aurora", cos.own().get(Slot.CAPE).id);

        cos.setShowAll(false);
        assertTrue(cos.own().isEmpty());
        cos.setShowAll(true);
        cos.setBadges(false);
        assertFalse(cos.badge("8667ba71b85a4004af54457a9734eed7"));
        cos.equip(Slot.CAPE, null);
        assertTrue(cos.own().isEmpty());
    }

    @Test void loadoutAndTogglesPersistInTheConfig(@TempDir Path dir) throws IOException {
        ModManager mm = new ModManager();
        mm.cosmetics().load(null);
        mm.cosmetics().equip(Slot.WINGS, "wings-dragon");
        mm.cosmetics().equip(Slot.EARS, "ears-cat");
        mm.cosmetics().setShowOthers(false);
        mm.cosmetics().setHideUnderArmor(false);
        ConfigStore store = new ConfigStore(dir.resolve("config.json"));
        store.save(mm);

        ModManager again = new ModManager();
        again.cosmetics().load(null);
        assertTrue(new ConfigStore(dir.resolve("config.json")).load(again));
        assertEquals("wings-dragon", again.cosmetics().equippedId(Slot.WINGS));
        assertEquals("ears-cat", again.cosmetics().equippedId(Slot.EARS));
        assertFalse(again.cosmetics().showOthers());
        assertFalse(again.cosmetics().hideUnderArmor());
        assertTrue(again.cosmetics().showAll());
    }

    @Test void onlyBuiltInCosmeticsAreShared(@TempDir Path dir) throws IOException {
        png(dir.resolve("mine.png"), 64, 32);
        Cosmetics cos = new Cosmetics();
        cos.load(dir);
        cos.equip(Slot.CAPE, "local/mine");
        cos.equip(Slot.HAT, "hat-tophat");
        Map<String, Object> shared = cos.sharedLoadout();
        assertNull(shared.get("cape"), "others don't have local files");
        assertEquals("hat-tophat", shared.get("hat"));
        assertTrue(shared.containsKey("boots"), "empty slots are cleared on the backend");
    }

    // ---- backend sync -----------------------------------------------------------------------------------------------

    private static ClientHost host(final String uuid, final String name) {
        return new ClientHost() {
            @Override public String sessionUuid() { return uuid; }
            @Override public String sessionName() { return name; }
        };
    }

    /** A fake backend with the real routes' shapes. */
    static final class FakeBackend implements AutoCloseable {
        final HttpServer server;
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        volatile String loadout;
        /** What the backend stores (the launcher's wardrobe edits it too), and what the account owns. */
        final Map<String, Object> stored = Collections.synchronizedMap(new java.util.LinkedHashMap<String, Object>());
        final java.util.Set<String> owned = Collections.synchronizedSet(new java.util.HashSet<String>(Arrays.asList("cape-aurora", "cape-ember", "cape-aether", "hat-crown")));
        volatile String heartbeat;
        volatile String joinedServerId;
        volatile long coins = 1000;
        volatile boolean unlimited;
        /** Published cosmetics: id -> files (base64), and the last publish body. */
        final Map<String, Map<String, String>> published = Collections.synchronizedMap(new java.util.LinkedHashMap<String, Map<String, String>>());
        volatile String lastPublish = "";
        final String issuedNonce = "0123456789abcdef0123456789abcdef";

        FakeBackend() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }

        private void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
            String body = read(ex.getRequestBody());
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            calls.add(method + " " + path);
            if (path.equals("/v1/auth/nonce")) {
                send(ex, 200, "{\"serverId\":\"" + issuedNonce + "\"}");
            } else if (path.equals("/v1/auth/mojang")) {
                Map<String, Object> b = Json.parseObject(body);
                boolean ok = issuedNonce.equals(b.get("serverId")) && issuedNonce.equals(joinedServerId) && "Steve".equals(b.get("username"));
                send(ex, ok ? 200 : 401, ok ? "{\"token\":\"jwt\",\"uuid\":\"8667ba71b85a4004af54457a9734eed7\",\"name\":\"Steve\"}" : "{\"error\":\"not_verified\"}");
            } else if (!"Bearer jwt".equals(auth)) {
                send(ex, 401, "{\"error\":\"unauthorized\"}");
            } else if (path.equals("/v1/me")) {
                send(ex, 200, "{\"loadout\":" + Json.write(new java.util.LinkedHashMap<String, Object>(stored)) + ",\"owned\":" + Json.write(new ArrayList<Object>(owned))
                        + ",\"coins\":" + coins + ",\"unlimited\":" + unlimited + "}");
            } else if (path.equals("/v1/admin/cosmetics")) {
                lastPublish = body;
                send(ex, unlimited ? 200 : 403, unlimited ? "{\"ok\":true,\"cosmetic\":{\"id\":\"" + Json.parseObject(body).get("id") + "\"}}" : "{\"error\":\"not_admin\"}");
            } else if (path.startsWith("/v1/cosmetics/") && path.endsWith("/bundle")) {
                String id = path.substring("/v1/cosmetics/".length(), path.length() - "/bundle".length());
                Map<String, String> files = published.get(id);
                if (files == null) send(ex, 404, "{\"error\":\"not_found\"}");
                else send(ex, 200, "{\"id\":\"" + id + "\",\"version\":1,\"files\":" + Json.write(new java.util.LinkedHashMap<String, Object>(files)) + "}");
            } else if (path.equals("/v1/store")) {
                StringBuilder custom = new StringBuilder();
                for (String id : new ArrayList<String>(published.keySet())) {
                    custom.append(",{\"id\":\"").append(id).append("\",\"slot\":\"hat\",\"name\":\"Crown of Tests\",\"rarity\":\"epic\",\"free\":false,"
                            + "\"price\":300,\"custom\":true,\"version\":1,\"author\":\"nakii14\"}");
                }
                send(ex, 200, "{\"items\":[{\"id\":\"cape-galaxy\",\"slot\":\"cape\",\"name\":\"Galaxy\",\"rarity\":\"legendary\",\"free\":false,\"price\":1200},"
                        + "{\"id\":\"cape-aether\",\"slot\":\"cape\",\"name\":\"Aether\",\"rarity\":\"rare\",\"free\":true,\"price\":null}" + custom + "],"
                        + "\"owned\":" + Json.write(new ArrayList<Object>(owned)) + ",\"coins\":" + coins + ",\"unlimited\":" + unlimited + "}");
            } else if (path.equals("/v1/store/buy")) {
                Object id = Json.parseObject(body).get("id");
                if (owned.contains(id)) send(ex, 409, "{\"error\":\"already_owned\"}");
                else if (!unlimited && coins < 1200) send(ex, 402, "{\"error\":\"insufficient_coins\"}");
                else {
                    if (!unlimited) coins -= 1200;
                    owned.add((String) id);
                    send(ex, 200, "{\"coins\":" + coins + ",\"unlimited\":" + unlimited + ",\"owned\":" + Json.write(new ArrayList<Object>(owned)) + "}");
                }
            } else if (path.equals("/v1/me/loadout")) {
                loadout = body;
                List<Object> rejected = new ArrayList<Object>();
                for (Map.Entry<String, Object> e : Json.parseObject(body).entrySet()) {
                    if (e.getValue() == null) stored.remove(e.getKey());
                    else if (owned.contains(e.getValue())) stored.put(e.getKey(), e.getValue());
                    else rejected.add(e.getValue());
                }
                send(ex, 200, "{\"loadout\":" + Json.write(new java.util.LinkedHashMap<String, Object>(stored)) + ",\"rejected\":" + Json.write(rejected) + "}");
            } else if (path.equals("/v1/presence/heartbeat")) {
                heartbeat = body;
                send(ex, 200, "{\"ok\":true,\"ttlMs\":90000}");
            } else if (path.equals("/v1/presence/lookup")) {
                boolean asked = body.contains("aaaaaaaabbbbccccddddeeeeeeeeeeee");
                send(ex, 200, asked ? "{\"users\":{\"aaaaaaaabbbbccccddddeeeeeeeeeeee\":{\"badge\":true,\"loadout\":{\"cape\":\"cape-galaxy\",\"wings\":\"local/x\",\"hat\":\"hat-crown\"}}}}" : "{\"users\":{}}");
            } else {
                send(ex, 404, "{\"error\":\"not_found\"}");
            }
        }

        private static void send(HttpExchange ex, int code, String body) throws IOException {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        }

        private static String read(InputStream in) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }

        @Override public void close() { server.stop(0); }
    }

    interface Check { boolean ok(); }

    static void await(Cosmetics cos, ClientHost host, Check check) throws InterruptedException {
        long now = 1_000_000;
        for (int i = 0; i < 200 && !check.ok(); i++) {
            cos.tick(host, now);
            now += 20_000; // every tick is 20 s later, so heartbeats and lookups come due
            Thread.sleep(25);
        }
    }

    @Test void syncSignsInPushesTheLoadoutAndSeesOtherUsers() throws Exception {
        try (final FakeBackend backend = new FakeBackend()) {
            final Cosmetics cos = new Cosmetics();
            cos.load(null);
            cos.equip(Slot.CAPE, "cape-aurora");
            cos.setBackend(backend.url());
            final ClientHost host = new ClientHost() {
                @Override public String sessionUuid() { return "8667ba71-b85a-4004-af54-457a9734eed7"; }
                @Override public String sessionName() { return "Steve"; }
                @Override public boolean joinSession(String serverId) {
                    backend.joinedServerId = serverId;
                    return true;
                }
                @Override public List<String> playerUuids() {
                    return Arrays.asList("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "8667ba71-b85a-4004-af54-457a9734eed7");
                }
                @Override public String currentServer() { return "play.example.net"; }
            };
            final String other = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
            await(cos, host, () -> cos.badge(other) && backend.loadout != null && backend.calls.contains("POST /v1/presence/heartbeat"));

            assertTrue(cos.badge(other), backend.calls.toString());
            Cosmetics.Equipped eq = cos.forPlayer(other);
            assertEquals("cape-galaxy", eq.get(Slot.CAPE).id);
            assertEquals("hat-crown", eq.get(Slot.HAT).id);
            assertNull(eq.get(Slot.WINGS), "unknown / local ids from others are ignored");
            Map<String, Object> pushed = Json.parseObject(backend.loadout);
            assertEquals("cape-aurora", pushed.get("cape"));
            assertTrue(pushed.containsKey("wings") && pushed.get("wings") == null);
            assertTrue(cos.status().startsWith("Connected"), cos.status());
            assertEquals("play.example.net", Json.parseObject(backend.heartbeat).get("server"), "friends can join from the launcher");
            assertEquals(1, Collections.frequency(backend.calls, "POST /v1/auth/mojang"), "signs in once");
            assertFalse(backend.calls.toString().contains("8667ba71b85a4004af54457a9734eed7"), "never looks itself up");

            cos.setShowOthers(false);
            assertTrue(cos.forPlayer(other).isEmpty());
            assertTrue(cos.forPlayer(other).badge, "badges still show with others' cosmetics hidden");

            // The loadout is pushed again only when it changes.
            final int puts = Collections.frequency(backend.calls, "PUT /v1/me/loadout");
            cos.equip(Slot.CAPE, "cape-ember");
            await(cos, host, () -> Collections.frequency(backend.calls, "PUT /v1/me/loadout") > puts);
            assertEquals("cape-ember", Json.parseObject(backend.loadout).get("cape"));
        }
    }

    static ClientHost signedIn(final FakeBackend backend) {
        return new ClientHost() {
            @Override public String sessionUuid() { return "8667ba71b85a4004af54457a9734eed7"; }
            @Override public String sessionName() { return "Steve"; }
            @Override public boolean joinSession(String serverId) {
                backend.joinedServerId = serverId;
                return true;
            }
        };
    }

    @Test void launcherWardrobeChangesShowInGameAndStartupDoesNotOverwriteThem() throws Exception {
        try (final FakeBackend backend = new FakeBackend()) {
            // Last session ended in sync on Aurora; since then the launcher equipped Ember.
            final Cosmetics cos = new Cosmetics();
            cos.load(null);
            Map<String, Object> saved = new java.util.LinkedHashMap<String, Object>();
            saved.put("loadout", Collections.singletonMap("cape", "cape-aurora"));
            saved.put("synced", Collections.singletonMap("cape", "cape-aurora"));
            cos.fromJson(saved);
            backend.stored.put("cape", "cape-ember");
            cos.setBackend(backend.url());
            ClientHost host = signedIn(backend);
            await(cos, host, () -> "cape-ember".equals(cos.equippedId(Slot.CAPE)));
            assertEquals("cape-ember", cos.equippedId(Slot.CAPE), "the game takes the launcher's pick");
            assertNull(backend.loadout, "and does not push its old loadout over it");
            assertEquals("cape-ember", ((Map<?, ?>) ((Map<?, ?>) cos.toJson()).get("synced")).get("cape"));

            // Equipping in the launcher while playing shows up too.
            backend.stored.put("hat", "hat-crown");
            await(cos, host, () -> "hat-crown".equals(cos.equippedId(Slot.HAT)));
            assertEquals("hat-crown", cos.equippedId(Slot.HAT));

            // Changing it in game goes to the backend (and so to the launcher).
            cos.equip(Slot.CAPE, "cape-aether");
            await(cos, host, () -> "cape-aether".equals(backend.stored.get("cape")));
            assertEquals("cape-aether", backend.stored.get("cape"));
            assertEquals("cape-aether", cos.equippedId(Slot.CAPE));
        }
    }

    @Test void editsMadeWhileOfflineWinAtTheNextStart() throws Exception {
        try (final FakeBackend backend = new FakeBackend()) {
            final Cosmetics cos = new Cosmetics();
            cos.load(null);
            Map<String, Object> saved = new java.util.LinkedHashMap<String, Object>();
            saved.put("loadout", Collections.singletonMap("cape", "cape-ember")); // changed in game, never uploaded
            saved.put("synced", Collections.singletonMap("cape", "cape-aurora"));
            cos.fromJson(saved);
            backend.stored.put("cape", "cape-aurora");
            cos.setBackend(backend.url());
            await(cos, signedIn(backend), () -> "cape-ember".equals(backend.stored.get("cape")));
            assertEquals("cape-ember", backend.stored.get("cape"));
            assertEquals("cape-ember", cos.equippedId(Slot.CAPE));
        }
    }

    @Test void itemsNotOwnedOnTheBackendAreMarkedAndNotKept() throws Exception {
        try (final FakeBackend backend = new FakeBackend()) {
            final Cosmetics cos = new Cosmetics();
            cos.load(null);
            cos.setBackend(backend.url());
            ClientHost host = signedIn(backend);
            await(cos, host, () -> cos.synced() != null);
            assertFalse(cos.owns(cos.registry().get("cape-galaxy")), "legendary cape not bought");
            assertTrue(cos.owns(cos.registry().get("cape-ember")));
            // Equipped anyway (e.g. an old config): the backend keeps the rest and the game drops it with a reason.
            cos.equip(Slot.CAPE, "cape-galaxy");
            cos.equip(Slot.HAT, "hat-crown");
            await(cos, host, () -> "hat-crown".equals(backend.stored.get("hat")) && cos.equippedId(Slot.CAPE) == null);
            assertNull(cos.equippedId(Slot.CAPE));
            assertEquals("hat-crown", cos.equippedId(Slot.HAT));
            assertTrue(cos.status().startsWith("Not in your wardrobe"), cos.status());
        }
    }

    @Test void shopListsPricesAndBuysWithCoins() throws Exception {
        try (final FakeBackend backend = new FakeBackend()) {
            final Cosmetics cos = new Cosmetics();
            cos.load(null);
            cos.setBackend(backend.url());
            final ClientHost host = signedIn(backend);
            final Shop shop = cos.shop();
            assertTrue(shop.available());
            await(cos, host, () -> cos.synced() != null);
            assertEquals(1000, shop.coins(), "the balance comes with /v1/me");
            shop.refresh();
            await(cos, host, () -> shop.offers() != null);
            assertEquals(1200, shop.offer("cape-galaxy").price);
            assertTrue(shop.offer("cape-aether").free());
            assertFalse(shop.affordable(1200));

            shop.buy("cape-galaxy");
            final Shop.Event[] got = new Shop.Event[1];
            await(cos, host, () -> (got[0] = shop.poll()) != null);
            assertFalse(got[0].ok);
            assertTrue(got[0].message.startsWith("Not enough coins"), got[0].message);

            backend.coins = 1500;
            shop.refresh();
            await(cos, host, () -> shop.coins() == 1500);
            shop.buy("cape-galaxy");
            await(cos, host, () -> (got[0] = shop.poll()) != null);
            assertTrue(got[0].ok, got[0].message);
            assertEquals("cape-galaxy", got[0].boughtId);
            assertEquals(300, shop.coins());
            assertTrue(cos.owns(cos.registry().get("cape-galaxy")));
            assertNull(shop.buying());
        }
    }

    @Test void adminAccountsHaveUnlimitedCoins() throws Exception {
        try (final FakeBackend backend = new FakeBackend()) {
            backend.unlimited = true;
            backend.coins = 999_999_999;
            final Cosmetics cos = new Cosmetics();
            cos.load(null);
            cos.setBackend(backend.url());
            final ClientHost host = signedIn(backend);
            await(cos, host, () -> cos.synced() != null);
            assertTrue(cos.shop().unlimited());
            assertTrue(cos.shop().affordable(Long.MAX_VALUE));
        }
    }

    @Test void offlineAccountsStayLocal() throws Exception {
        try (FakeBackend backend = new FakeBackend()) {
            final Cosmetics cos = new Cosmetics();
            cos.load(null);
            cos.setBackend(backend.url());
            ClientHost host = host("8667ba71b85a4004af54457a9734eed7", "Steve"); // joinSession() fails
            await(cos, host, () -> cos.status().contains("verify"));
            assertTrue(cos.status().contains("verify"), cos.status());
            assertNull(backend.loadout);
            assertTrue(cos.badge("8667ba71b85a4004af54457a9734eed7"), "you always see your own badge");
        }
    }

    @Test void noBackendMeansLocalOnly() {
        Cosmetics cos = new Cosmetics();
        cos.setBackend("");
        assertTrue(cos.status().startsWith("Offline"));
    }
}
