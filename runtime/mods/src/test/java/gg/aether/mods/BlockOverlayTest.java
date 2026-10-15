package gg.aether.mods;

import static org.junit.jupiter.api.Assertions.*;

import gg.aether.core.GameState;
import gg.aether.core.WorldRenderer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Block Overlay follows the block's real outline shape (slabs, stairs, fences) instead of the full cube. */
class BlockOverlayTest {
    static final int STROKE = 0xFFFF0000, FILL = 0x2200FF00, FACE = 0x440000FF;

    /** Records what the overlay draws. */
    static final class Rec implements WorldRenderer {
        final List<double[]> lines = new ArrayList<double[]>(), boxes = new ArrayList<double[]>();
        final List<double[]> fills = new ArrayList<double[]>(), faces = new ArrayList<double[]>();
        final List<String> faceSides = new ArrayList<String>();
        float width;
        public double cameraX() { return 0; }
        public double cameraY() { return 0; }
        public double cameraZ() { return 0; }
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb, float width, boolean walls) {
            assertEquals(STROKE, argb);
            this.width = width;
            lines.add(new double[] { x1, y1, z1, x2, y2, z2 });
        }
        public void box(double x0, double y0, double z0, double x1, double y1, double z1, int stroke, float width, int fill, boolean walls) {
            boxes.add(new double[] { x0, y0, z0, x1, y1, z1 });
        }
        @Override public void face(double x0, double y0, double z0, double x1, double y1, double z1, String side, int argb, boolean walls) {
            (argb == FACE ? faces : fills).add(new double[] { x0, y0, z0, x1, y1, z1 });
            if (argb == FACE) faceSides.add(side);
        }
        public void text(String text, double x, double y, double z, int argb, float scale, boolean walls) {}

        /** Total area of the faces, ignoring the tiny outward offset. */
        static double area(List<double[]> fs) {
            double a = 0;
            for (double[] f : fs) {
                double dx = f[3] - f[0], dy = f[4] - f[1], dz = f[5] - f[2];
                a += dx < 1e-9 ? dy * dz : dy < 1e-9 ? dx * dz : dx * dy;
            }
            return a;
        }
    }

    static GameState.Target block(int x, int y, int z, String face, double[]... shape) {
        GameState.Target t = new GameState.Target(GameState.Target.Kind.BLOCK, "Block", "minecraft:block", 3);
        t.x = x;
        t.y = y;
        t.z = z;
        t.face = face;
        t.shape = Arrays.asList(shape);
        return t;
    }

    static Rec draw(GameState.Target t) {
        Rec r = new Rec();
        WorldMods.BlockOverlay.draw(r, t, STROKE, 3, FILL, FACE, false);
        return r;
    }

    /** Line length, ignoring the outward offset. */
    static double len(double[] l) {
        double dx = l[3] - l[0], dy = l[4] - l[1], dz = l[5] - l[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static double maxY(List<double[]> ls) {
        double m = -1e9;
        for (double[] l : ls) m = Math.max(m, Math.max(l[1], l[4]));
        return m;
    }

    @Test
    void unknownShapeFallsBackToTheFullCube() {
        GameState.Target t = new GameState.Target(GameState.Target.Kind.BLOCK, "Stone", "minecraft:stone", 3);
        t.x = 5;
        t.y = 64;
        t.z = -3;
        t.face = "up";
        Rec r = draw(t);
        assertEquals(12, r.lines.size());
        for (double[] l : r.lines) assertEquals(1.004, len(l), 1e-9);
        assertEquals(6, r.fills.size());
        assertEquals(6, Rec.area(r.fills), 1e-6);
        assertEquals(1, r.faces.size());
        assertEquals("up", r.faceSides.get(0));
        assertEquals(65.004, r.faces.get(0)[1], 1e-9);
        assertEquals(3f, r.width);
    }

    @Test
    void bottomSlabOutlinesHalfABlock() {
        Rec r = draw(block(0, 64, 0, "up", new double[] { 0, 64, 0, 1, 64.5, 1 }));
        assertEquals(12, r.lines.size());
        assertEquals(64.502, maxY(r.lines), 1e-9);
        assertEquals(4 * 0.5 + 2, Rec.area(r.fills), 1e-6);
        // The face highlight sits on the slab's top, not on the cube's.
        assertEquals(1, r.faces.size());
        assertEquals(64.504, r.faces.get(0)[1], 1e-9);
        assertTrue(r.boxes.isEmpty());
    }

    /** A north-facing bottom stair: a full-width slab plus the upper half at the back (z 0..0.5). */
    static GameState.Target stairs(String face) {
        return block(0, 0, 0, face, new double[] { 0, 0, 0, 1, 0.5, 1 }, new double[] { 0, 0.5, 0, 1, 1, 0.5 });
    }

    @Test
    void stairsOutlineHasNoInternalEdgesAndNoInternalFill() {
        Rec r = draw(stairs("up"));
        // Vanilla's stair outline: an L-shaped profile on each side (6 edges each) joined by 6 lines along x.
        assertEquals(18, r.lines.size());
        double total = 0;
        for (double[] l : r.lines) total += len(l);
        // Two L profiles (1 + 1 + 0.5 + 0.5 + 0.5 + 0.5 = 4 each) + 6 lines of 1, each stretched by the 0.002 offsets.
        assertEquals(4 * 2 + 6, total, 0.1);
        // Outer surface only: no face drawn between the two boxes (y = 0.5, z 0..0.5).
        for (double[] f : r.fills) {
            boolean internal = Math.abs(f[1] - f[4]) < 1e-9 && Math.abs(f[1] - 0.5) < 0.01 && f[5] <= 0.5 + 1e-9;
            assertFalse(internal, "internal face drawn: " + Arrays.toString(f));
        }
        // Area of the stair's surface: bottom 1, back 1, front 0.5 + 0.5, tops 0.5 + 0.5, two L sides 0.75 each.
        assertEquals(1 + 1 + 1 + 1 + 1.5, Rec.area(r.fills), 1e-6);
    }

    @Test
    void stairsFaceHighlightFollowsTheHitStep() {
        GameState.Target t = stairs("up");
        t.hitX = 0.3;
        t.hitY = 0.5;
        t.hitZ = 0.8;
        Rec r = draw(t);
        assertEquals(0.5, Rec.area(r.faces), 1e-6);
        for (double[] f : r.faces) {
            assertEquals(0.504, f[1], 1e-9);
            assertTrue(f[2] >= 0.5 - 1e-9);
        }
        t.hitY = 1;
        t.hitZ = 0.2;
        r = draw(t);
        assertEquals(0.5, Rec.area(r.faces), 1e-6);
        for (double[] f : r.faces) assertEquals(1.004, f[1], 1e-9);
        // The bottom spans both grid cells: highlighted whole.
        t.face = "down";
        t.hitY = 0;
        t.hitZ = 0.9;
        r = draw(t);
        assertEquals(1, Rec.area(r.faces), 1e-6);
        assertEquals("down", r.faceSides.get(0));
    }

    @Test
    void faceWithoutHitPointUsesTheOutermostPlane() {
        Rec r = draw(stairs("up"));
        assertEquals(0.5, Rec.area(r.faces), 1e-6);
        for (double[] f : r.faces) assertEquals(1.004, f[1], 1e-9);
    }

    @Test
    void fencePostWithArmOutlinesAsOneSolid() {
        // Fence post (6/16..10/16) with an arm reaching north at two heights.
        double a = 6 / 16.0, b = 10 / 16.0, c = 7 / 16.0, d = 9 / 16.0;
        Rec r = draw(block(0, 0, 0, "north",
                new double[] { a, 0, a, b, 1.5, b },
                new double[] { c, 6 / 16.0, 0, d, 9 / 16.0, a },
                new double[] { c, 12 / 16.0, 0, d, 15 / 16.0, a }));
        // Post 12 edges, each arm 12 edges where 4 sit on the post (joined, not separate lines), 4 post lines split... count is stable:
        assertFalse(r.lines.isEmpty());
        assertTrue(maxY(r.lines) > 1.5);
        // No line inside the post (x strictly between a and b, z strictly between a and b).
        for (double[] l : r.lines) {
            boolean inside = l[0] > a + 0.01 && l[0] < b - 0.01 && l[2] > a + 0.01 && l[2] < b - 0.01;
            assertFalse(inside, Arrays.toString(l));
        }
        // The post's 1.5-tall vertical edges are single merged lines.
        int tall = 0;
        for (double[] l : r.lines) if (len(l) > 1.4) tall++;
        assertEquals(4, tall);
    }

    @Test
    void vanillaEdgesAreUsedWhenProvided() {
        GameState.Target t = stairs("up");
        t.edges = Collections.singletonList(new double[] { 0, 0, 0, 1, 0, 0 });
        Rec r = draw(t);
        assertEquals(1, r.lines.size());
        // Pushed out of the surface (away from the shape's centre) on the perpendicular axes.
        assertEquals(-0.002, r.lines.get(0)[1], 1e-9);
        assertEquals(-0.002, r.lines.get(0)[2], 1e-9);
    }

    @Test
    void transparentPartsAreSkipped() {
        Rec r = new Rec();
        WorldMods.BlockOverlay.draw(r, stairs("up"), 0, 3, 0, 0, false);
        assertTrue(r.lines.isEmpty() && r.fills.isEmpty() && r.faces.isEmpty());
    }

    @Test
    void hugeShapesFallBackToPerBoxOutlines() {
        double[][] many = new double[40][];
        for (int i = 0; i < 40; i++) many[i] = new double[] { i / 40.0, i / 40.0, i / 40.0, i / 40.0 + 0.01, i / 40.0 + 0.01, i / 40.0 + 0.01 };
        Rec r = draw(block(0, 0, 0, "up", many));
        assertEquals(40, r.boxes.size());
    }
}
