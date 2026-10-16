package gg.kite.mods;

import static org.junit.jupiter.api.Assertions.*;

import gg.kite.core.GameState;
import gg.kite.core.ModManager;
import gg.kite.core.Setting;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The custom (pixel art) crosshair. */
class CrosshairTest {
    static Setting.PixelGrid arrow() {
        return new Setting.PixelGrid("px", "Pixels", 5, "..#..", ".###.", "#.#.#", "..#..", "..#..");
    }

    @Test
    void pixelSegmentsMergeRunsAroundTheCentre() {
        List<float[]> segs = Crosshair.pixelSegments(arrow(), 100, 50, 2);
        assertEquals(7, segs.size());
        // Row 1 ".###." is one 3-pixel run.
        assertArrayEquals(new float[] { 97, 47, 6, 2 }, segs.get(1));
        // Row 2 "#.#.#": the centre pixel (2, 2) is centred exactly on (100, 50).
        float[] centre = segs.get(3);
        assertArrayEquals(new float[] { 99, 49, 2, 2 }, centre);
        assertEquals(100f, centre[0] + centre[2] / 2f);
        assertEquals(50f, centre[1] + centre[3] / 2f);
        // Top-left of the grid is 2.5 cells up and left of the centre.
        assertArrayEquals(new float[] { 95, 49, 2, 2 }, segs.get(2));
        assertArrayEquals(new float[] { 99, 45, 2, 2 }, segs.get(0));
        assertTrue(Crosshair.pixelSegments(new Setting.PixelGrid("e", "E", 5), 0, 0, 1).isEmpty());
    }

    @Test
    void defaultDrawingIsAPlus() {
        String[] rows = Crosshair.plus(Crosshair.GRID, 4);
        assertEquals(Crosshair.GRID, rows.length);
        assertEquals(".................", rows[0]);
        assertEquals("....#########....", rows[8]);
        assertEquals("........#........", rows[4]);
        Setting.PixelGrid g = new Setting.PixelGrid("p", "P", Crosshair.GRID, rows);
        assertEquals(17, g.count());
    }

    private static Setting.PixelGrid pixels(ModManager mm) {
        for (Setting<?> s : mm.get("crosshair").settings()) if (s instanceof Setting.PixelGrid) return (Setting.PixelGrid) s;
        throw new AssertionError("no pixel grid");
    }

    @SuppressWarnings("unchecked")
    @Test
    void customShapeDrawsTheDrawingCentredOnScreenInTheCrosshairColour() {
        ModManager mm = HudModsTest.mm("crosshair");
        Setting.Choice<Crosshair.Shape> shape = null;
        for (Setting<?> s : mm.get("crosshair").settings()) if (s.key.equals("shape")) shape = (Setting.Choice<Crosshair.Shape>) s;
        Setting<?> sizeSetting = null;
        for (Setting<?> s : mm.get("crosshair").settings()) if (s.key.equals("size")) sizeSetting = s;
        assertTrue(sizeSetting.visible());
        assertFalse(pixels(mm).visible());
        shape.set(Crosshair.Shape.CUSTOM);
        // The drawing and pixel size replace size / thickness / gap.
        assertFalse(sizeSetting.visible());
        assertTrue(pixels(mm).visible());
        for (Setting<?> s : mm.get("crosshair").settings()) {
            if (s.key.equals("outline")) ((Setting.Bool) s).set(false);
            if (s.key.equals("color")) ((Setting.Color) s).set(0xFF00FF00);
        }
        Setting.PixelGrid g = pixels(mm);
        g.clear();
        g.set(g.center(), g.center(), true);
        g.set(g.center() + 1, g.center(), true);
        HarnessModsTest.World w = new HarnessModsTest.World();
        mm.tick(w);
        final List<float[]> rects = new ArrayList<float[]>();
        final List<Integer> colors = new ArrayList<Integer>();
        mm.renderHud(new ModsTest.Rec() {
            @Override public void rect(float x, float y, float w2, float h, int argb, float r) {
                rects.add(new float[] { x, y, w2, h });
                colors.add(argb);
            }
        }, w);
        assertEquals(1, rects.size(), "one merged run");
        // Screen 800x600: the centre pixel spans 399.5..400.5 snapped to whole pixels -> 400..401, plus one to the right.
        assertEquals(Arrays.toString(new float[] { 400, 300, 2, 1 }), Arrays.toString(rects.get(0)));
        assertEquals(Integer.valueOf(0xFF00FF00), colors.get(0));
    }
}
