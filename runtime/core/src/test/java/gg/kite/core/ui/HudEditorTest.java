package gg.kite.core.ui;

import static org.junit.jupiter.api.Assertions.*;

import gg.kite.core.GameState;
import gg.kite.core.HudElement;
import gg.kite.core.Keys;
import gg.kite.core.Mod;
import gg.kite.core.ModManager;
import gg.kite.core.UiRenderer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HudEditorTest {
    /** A 40 x 10 widget; resizable ones grow to the box like text widgets do. */
    static final class Box extends Mod {
        Box(String id, float x, float y, boolean resizable) {
            super(id, id, id, Category.HUD, true);
            hud = new HudElement(HudElement.Anchor.TOP_LEFT, x, y);
            hud.resizable = resizable;
        }

        @Override public void renderHud(UiRenderer r, GameState s) {
            hud.natural(40, 10);
            hud.resolve(r.screenWidth(), r.screenHeight(), Math.max(40, hud.boxWidth()), Math.max(10, hud.boxHeight()));
        }
    }

    final ModManager mm = new ModManager();
    final Box a = new Box("a", 10, 10, true), b = new Box("b", 100, 100, true), plain = new Box("plain", 200, 200, false);
    final UiTest.Rec r = new UiTest.Rec();
    final ClientGui gui;

    HudEditorTest() {
        mm.setHost(new UiTest.Host());
        for (Mod m : Arrays.<Mod>asList(a, b, plain)) {
            mm.register(m);
            mm.setEnabled(m.id, true);
        }
        gui = new ClientGui(mm, new ClientGui.Host() {
            @Override public void close() { }
            @Override public void save() { }
        }).openHudEditor();
        frame();
    }

    void frame() {
        r.clear();
        gui.render(r, UiTest.state());
    }

    void drag(float x0, float y0, float x1, float y1) {
        gui.mouseMoved(x0, y0);
        gui.mouseDown(x0, y0, 0);
        gui.mouseDrag(x1, y1);
        gui.mouseUp(x1, y1, 0);
        frame();
    }

    @Test
    void snapsToLinesWithinFourPixels() {
        List<Float> lines = Arrays.asList(0f, 50f, 100f), guides = new ArrayList<Float>();
        assertEquals(50f, HudEditor.snapSpan(47f, 20f, lines, guides), 1e-4, "the start meets a line");
        assertEquals(30f, HudEditor.snapSpan(32f, 20f, lines, guides), 1e-4, "the end meets a line");
        assertEquals(40f, HudEditor.snapSpan(40f, 10f, Arrays.asList(0f, 100f), guides), 1e-4, "nothing near: unchanged");
        assertEquals(100f, HudEditor.snapLine(97f, lines, guides), 1e-4);
        assertEquals(90f, HudEditor.snapLine(90f, lines, guides), 1e-4);
    }

    @Test
    void movingSnapsToOtherWidgetsAndCtrlPlacesFreely() {
        // Drop b just right of a's right edge (x 50): it snaps flush against it, and its top lines up with a's.
        drag(105, 105, 57, 17);
        float[] bb = b.hud().bounds();
        assertEquals(50f, bb[0], 1e-3, "left edge snapped to a's right edge");
        assertEquals(10f, bb[1], 1e-3, "top snapped to a's top");
        // With Ctrl held there is no snapping.
        gui.keyDown(Keys.LEFT_CONTROL);
        drag(70, 15, 73, 15);
        assertEquals(53f, b.hud().bounds()[0], 1e-3);
        gui.keyUp(Keys.LEFT_CONTROL);
    }

    @Test
    void cornersScaleAndSidesResizeTheBox() {
        // Select a, then drag its right side handle out by 30 px: the box widens, the scale stays.
        drag(15, 15, 15, 15);
        drag(50, 15, 80, 15);
        assertEquals(70f, a.hud().boxWidth(), 1e-3);
        assertEquals(1f, a.hud().scale(), 1e-3);
        assertEquals(10f, a.hud().bounds()[0], 1e-3, "the left edge stays put");
        // Dragging it back inside the content returns to "fit".
        drag(80, 15, 30, 15);
        assertEquals(0f, a.hud().boxWidth(), 1e-3);
        // The bottom-right corner scales uniformly from the top-left.
        drag(50, 20, 90, 30);
        assertEquals(2f, a.hud().scale(), 1e-3);
        assertEquals(10f, a.hud().bounds()[0], 1e-3);
        assertEquals(10f, a.hud().bounds()[1], 1e-3);
    }

    @Test
    void sidesOfFixedSizeWidgetsScaleThem() {
        drag(205, 205, 205, 205);
        drag(240, 205, 280, 205);
        assertEquals(0f, plain.hud().boxWidth(), 1e-3, "no box for widgets that can't use one");
        assertEquals(2f, plain.hud().scale(), 1e-3);
    }

    @Test
    void arrowsNudgeTheSelection() {
        drag(15, 15, 15, 15);
        gui.keyDown(Keys.RIGHT);
        gui.keyDown(Keys.DOWN);
        frame();
        assertEquals(11f, a.hud().bounds()[0], 1e-3);
        assertEquals(11f, a.hud().bounds()[1], 1e-3);
    }

    @Test
    void boxSizeIsSavedAndReset() {
        HudElement h = new HudElement(HudElement.Anchor.TOP_LEFT, 0, 0);
        h.natural(40, 10);
        h.setBox(90, 5);
        assertEquals(90f, h.boxWidth(), 1e-3);
        assertEquals(0f, h.boxHeight(), 1e-3, "not taller than the content: fit");
        Map<String, Object> json = h.toJson();
        HudElement back = new HudElement(HudElement.Anchor.TOP_LEFT, 0, 0);
        back.fromJson(json);
        assertEquals(90f, back.boxWidth(), 1e-3);
        back.reset();
        assertEquals(0f, back.boxWidth(), 1e-3);
    }
}
