package gg.aether.core.ui;

import static org.junit.jupiter.api.Assertions.*;

import gg.aether.core.Setting;
import gg.aether.core.UiRenderer;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pixel grid setting and its editor popup. */
class PixelEditorTest {
    static final class Blank implements UiRenderer {
        @Override public int screenWidth() { return 640; }
        @Override public int screenHeight() { return 360; }
        @Override public void rect(float x, float y, float w, float h, int argb, float cornerRadius) { }
        @Override public void text(String s, float x, float y, int argb, boolean shadow) { }
        @Override public int textWidth(String s) { return s.length() * 5; }
        @Override public int fontHeight() { return 9; }
        @Override public void item(String itemId, float x, float y) { }
        @Override public void push(float x, float y, float scale) { }
        @Override public void pop() { }
    }

    static Setting.PixelGrid grid() { return new Setting.PixelGrid("px", "Pixels", 5, ".....", "..#..", ".###.", "..#..", "....."); }

    @Test
    void gridSavesAsRowsAndLoadsBack() {
        Setting.PixelGrid g = grid();
        assertEquals(Setting.Kind.PIXEL_GRID, g.kind());
        assertEquals(Arrays.asList(".....", "..#..", ".###.", "..#..", "....."), g.toJson());
        g.set(0, 0, true);
        g.set(2, 2, false);
        Object saved = g.toJson();
        Setting.PixelGrid back = grid();
        back.fromJson(saved);
        assertArrayEquals(g.snapshot(), back.snapshot());
        assertTrue(back.at(0, 0));
        assertFalse(back.at(2, 2));
        assertEquals(5, back.count());
        // Reset restores the default without sharing its array.
        back.reset();
        back.set(4, 4, true);
        back.reset();
        assertEquals(5, back.count());
        assertFalse(back.at(4, 4));
    }

    @Test
    void gridLoadsOtherSizesCentredAndIgnoresJunk() {
        Setting.PixelGrid g = grid();
        g.fromJson(Arrays.asList("1", "x"));
        // A 1-wide, 2-tall drawing lands centred: column 2, rows 1 and 2.
        assertTrue(g.at(2, 1));
        assertTrue(g.at(2, 2));
        assertEquals(2, g.count());
        g.fromJson("not a list");
        assertEquals(2, g.count());
        g.fromJson(Arrays.asList(42, null, "#####"));
        assertEquals(5, g.count());
        assertTrue(g.at(0, 3));
        assertFalse(g.at(-1, 0));
        assertFalse(g.at(5, 0));
    }

    @Test
    void runsMergeHorizontalPixels() {
        List<int[]> runs = grid().runs();
        assertEquals(3, runs.size());
        assertArrayEquals(new int[] { 2, 1, 1 }, runs.get(0));
        assertArrayEquals(new int[] { 1, 2, 3 }, runs.get(1));
        assertArrayEquals(new int[] { 2, 3, 1 }, runs.get(2));
    }

    private static void frame(Ui ui) {
        ui.begin(new Blank(), true);
        ui.end();
    }

    @Test
    void editorPaintsErasesAndMirrors() {
        Setting.PixelGrid g = grid();
        g.clear();
        Ui ui = new Ui(null);
        PixelEditor ed = new PixelEditor(g);
        ui.openPopup(ed);
        PixelEditor.mirrorX = false;
        PixelEditor.mirrorY = false;
        frame(ui);
        float c = ed.cellSize;
        // Drag from (0, 0) to (4, 0): a full row, no gaps.
        ui.mouseDown(ed.gridX + c * 0.5f, ed.gridY + c * 0.5f, 0);
        ui.mouseDrag(ed.gridX + c * 4.5f, ed.gridY + c * 0.5f);
        ui.mouseUp(ed.gridX + c * 4.5f, ed.gridY + c * 0.5f, 0);
        assertEquals(5, g.count());
        // Right-drag erases.
        frame(ui);
        ui.mouseDown(ed.gridX + c * 0.5f, ed.gridY + c * 0.5f, 1);
        ui.mouseDrag(ed.gridX + c * 1.5f, ed.gridY + c * 0.5f);
        ui.mouseUp(ed.gridX + c * 1.5f, ed.gridY + c * 0.5f, 1);
        assertEquals(3, g.count());
        // Left-click on a set pixel erases it.
        frame(ui);
        ui.mouseDown(ed.gridX + c * 4.5f, ed.gridY + c * 0.5f, 0);
        ui.mouseUp(ed.gridX + c * 4.5f, ed.gridY + c * 0.5f, 0);
        assertFalse(g.at(4, 0));
        // Mirrored both ways, one click sets four pixels.
        PixelEditor.mirrorX = true;
        PixelEditor.mirrorY = true;
        frame(ui);
        g.clear();
        ui.mouseDown(ed.gridX + c * 0.5f, ed.gridY + c * 1.5f, 0);
        ui.mouseUp(ed.gridX + c * 0.5f, ed.gridY + c * 1.5f, 0);
        assertTrue(g.at(0, 1) && g.at(4, 1) && g.at(0, 3) && g.at(4, 3));
        assertEquals(4, g.count());
        PixelEditor.mirrorX = false;
        PixelEditor.mirrorY = false;
        assertTrue(ui.popupOpen());
    }
}
