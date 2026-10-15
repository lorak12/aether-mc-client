package gg.aether.core.ui;

import gg.aether.core.GameState;
import gg.aether.core.HudElement;
import gg.aether.core.Keys;
import gg.aether.core.Mod;
import gg.aether.core.UiRenderer;
import java.util.ArrayList;
import java.util.List;

import static gg.aether.core.ui.Theme.*;

/**
 * Drag-and-drop HUD layout. Drag to move: edges and centres snap to the screen (edges and centre lines) and to the
 * other widgets, with guide lines (hold Ctrl to place freely). The selected widget has handles: corners scale it,
 * sides widen / heighten text widgets (other widgets scale from that side). Scroll scales, arrow keys nudge,
 * right-click resets.
 */
final class HudEditor {
    /** Snap distance in GUI pixels. */
    static final float SNAP = 4;
    /** Handle size and how far around it still grabs (small, so the middle of a short widget still moves it). */
    static final float HANDLE = 5, GRAB = 2;

    /** What a drag changes: the position, or one of the eight handles. */
    enum Grip {
        MOVE(0, 0), N(0, -1), S(0, 1), W(-1, 0), E(1, 0), NW(-1, -1), NE(1, -1), SW(-1, 1), SE(1, 1);

        final int dx, dy;
        Grip(int dx, int dy) { this.dx = dx; this.dy = dy; }
        boolean corner() { return dx != 0 && dy != 0; }
    }

    private final ClientGui gui;
    private HudElement selected, drag;
    private Grip grip;
    private float grabDx, grabDy;
    /** Fixed edges while resizing (screen space). */
    private float anchorX, anchorY;
    /** Guide lines from the last snap: vertical x positions and horizontal y positions. */
    private final List<Float> guidesX = new ArrayList<Float>(), guidesY = new ArrayList<Float>();

    HudEditor(ClientGui gui) { this.gui = gui; }

    void render(UiRenderer r, GameState s) {
        Ui ui = gui.ui;
        float sw = gui.screenW, sh = gui.screenH;
        r.rect(0, 0, sw, sh, 0x33000000, 0);
        r.rect(sw / 2f, 0, 1, sh, 0x22FFFFFF, 0);
        r.rect(0, sh / 2f, sw, 1, 0x22FFFFFF, 0);
        r.font(UiRenderer.Font.VANILLA);
        gui.mm.renderHud(r, s);
        r.font(UiRenderer.Font.UI);
        if (selected != null && !visible(selected)) selected = null;
        HudElement hovered = drag != null ? drag : at(ui.mouseX(), ui.mouseY());
        for (Mod m : gui.mm.all()) {
            HudElement h = m.hud();
            if (h == null || !visible(h)) continue;
            float[] b = h.bounds();
            boolean hot = h == hovered || h == selected;
            ui.outline(b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, hot ? ACCENT : 0x66FFFFFF);
            if (h == selected) handles(r, h, b);
            if (hot) label(ui, m, h, b, sw);
        }
        if (drag != null) {
            for (float x : guidesX) r.rect(x, 0, 1, sh, 0xCCFF5DD8, 0);
            for (float y : guidesY) r.rect(0, y, sw, 1, 0xCCFF5DD8, 0);
        }
        String help = "Drag to move (Ctrl: no snapping)  ·  Corners scale, sides resize  ·  Scroll to scale  ·  Arrows nudge  ·  Right-click resets";
        float hw = ui.tw(help) + 14, hy = Math.round(sh * 0.62f);
        r.rect((sw - hw) / 2f, hy, hw, ui.fh() + 9, 0xD80E0E11, 5);
        ui.text(help, (sw - hw) / 2f + 7, hy + 5, MUTED);
        ui.button("Done", sw / 2f - 32, hy + ui.fh() + 13, 64, 16, Ui.Style.PRIMARY, (mx, my, b) -> gui.closeEditor());
    }

    private void label(Ui ui, Mod m, HudElement h, float[] b, float sw) {
        String text = m.name + "  " + Math.round(h.scale() * 100) + "%";
        if (h.resizable && (h.boxWidth() > 0 || h.boxHeight() > 0)) text += "  " + Math.round(h.lastWidth()) + "x" + Math.round(h.lastHeight());
        float lw = ui.tw(text) + 8;
        float ly = b[1] - ui.fh() - 6 < 0 ? b[1] + b[3] + 2 : b[1] - ui.fh() - 6;
        float lx = Math.max(0, Math.min(sw - lw, b[0]));
        ui.r.rect(lx, ly, lw, ui.fh() + 5, 0xE01C1C22, 3);
        ui.text(text, lx + 4, ly + 3, TEXT);
    }

    private static void handles(UiRenderer r, HudElement h, float[] b) {
        for (Grip g : Grip.values()) {
            if (g == Grip.MOVE) continue;
            float[] c = handleAt(g, b);
            boolean side = !g.corner();
            r.rect(c[0] - HANDLE / 2f - 1, c[1] - HANDLE / 2f - 1, HANDLE + 2, HANDLE + 2, 0xE0000000, 1);
            r.rect(c[0] - HANDLE / 2f, c[1] - HANDLE / 2f, HANDLE, HANDLE, side && !h.resizable ? 0xFFB8C2D6 : 0xFFFFFFFF, 1);
        }
    }

    /** Centre of a handle on the bounds {x, y, w, h}. */
    static float[] handleAt(Grip g, float[] b) {
        return new float[] { b[0] + (g.dx + 1) / 2f * b[2], b[1] + (g.dy + 1) / 2f * b[3] };
    }

    private boolean visible(HudElement h) {
        if (!h.drawn()) return false;
        for (Mod m : gui.mm.all()) if (m.hud() == h) return gui.mm.active(m);
        return false;
    }

    private HudElement at(float x, float y) {
        List<Mod> all = gui.mm.all();
        for (int i = all.size() - 1; i >= 0; i--) {
            Mod m = all.get(i);
            HudElement h = m.hud();
            if (h == null || !h.drawn() || !gui.mm.active(m)) continue;
            float[] b = h.bounds();
            if (x >= b[0] && y >= b[1] && x < b[0] + b[2] && y < b[1] + b[3]) return h;
        }
        return null;
    }

    /** The selected element's handle under (x, y), or null. */
    private Grip gripAt(float x, float y) {
        if (selected == null) return null;
        float[] b = selected.bounds();
        for (Grip g : Grip.values()) {
            if (g == Grip.MOVE) continue;
            float[] c = handleAt(g, b);
            if (Math.abs(x - c[0]) <= HANDLE / 2f + GRAB && Math.abs(y - c[1]) <= HANDLE / 2f + GRAB) return g;
        }
        return null;
    }

    boolean mouseDown(float x, float y, int button) {
        if (gui.ui.mouseDown(x, y, button)) return true;
        Grip g = button == 0 ? gripAt(x, y) : null;
        if (g != null) {
            startDrag(selected, g, x, y);
            return true;
        }
        HudElement h = at(x, y);
        if (h != null && button == 0) {
            selected = h;
            startDrag(h, Grip.MOVE, x, y);
        } else if (h != null && button == 1) {
            h.reset();
        } else if (h == null) {
            selected = null;
        }
        return true;
    }

    private void startDrag(HudElement h, Grip g, float x, float y) {
        float[] b = h.bounds();
        drag = h;
        grip = g;
        grabDx = x - b[0];
        grabDy = y - b[1];
        // The opposite edges stay put while resizing.
        anchorX = g.dx < 0 ? b[0] + b[2] : b[0];
        anchorY = g.dy < 0 ? b[1] + b[3] : b[1];
    }

    boolean mouseDrag(float x, float y) {
        gui.ui.mouseMoved(x, y);
        if (drag == null) return gui.ui.mouseDrag(x, y);
        guidesX.clear();
        guidesY.clear();
        if (grip == Grip.MOVE) move(x, y);
        else if (grip.corner() || !drag.resizable) scaleFrom(x, y);
        else resizeSide(x, y);
        return true;
    }

    private boolean snapping() { return !gui.ui.ctrlDown(); }

    private void move(float x, float y) {
        float sw = gui.screenW, sh = gui.screenH;
        float w = drag.lastWidth(), h = drag.lastHeight(), dw = w * drag.scale(), dh = h * drag.scale();
        float nx = x - grabDx, ny = y - grabDy;
        if (snapping()) {
            nx = snapSpan(nx, dw, linesX(), guidesX);
            ny = snapSpan(ny, dh, linesY(), guidesY);
        }
        nx = Math.max(0, Math.min(sw - dw, nx));
        ny = Math.max(0, Math.min(sh - dh, ny));
        drag.moveTo(nx, ny, Math.round(sw), Math.round(sh), w, h);
    }

    /** Corners (and sides of widgets without a box): uniform scale, the opposite corner / edge fixed. */
    private void scaleFrom(float x, float y) {
        float w = drag.lastWidth(), h = drag.lastHeight();
        if (w <= 0 || h <= 0) return;
        float sx = grip.dx == 0 ? 0 : Math.abs(x - anchorX) / w, sy = grip.dy == 0 ? 0 : Math.abs(y - anchorY) / h;
        float s = Math.max(sx, sy);
        s = Math.round(Math.max(HudElement.MIN_SCALE, Math.min(HudElement.MAX_SCALE, s)) * 20f) / 20f;
        drag.setScale(s);
        float dw = w * drag.scale(), dh = h * drag.scale();
        float[] b = drag.bounds();
        float nx = grip.dx < 0 ? anchorX - dw : grip.dx > 0 ? anchorX : b[0] + (b[2] - dw) / 2f;
        float ny = grip.dy < 0 ? anchorY - dh : grip.dy > 0 ? anchorY : b[1] + (b[3] - dh) / 2f;
        place(nx, ny, dw, dh, w, h);
    }

    /** Sides of a resizable widget: the box grows or shrinks (never below the content), the opposite edge fixed. */
    private void resizeSide(float x, float y) {
        float sc = drag.scale();
        float w = drag.lastWidth(), h = drag.lastHeight();
        float[] b = drag.bounds();
        if (grip.dx != 0) {
            float edge = x;
            if (snapping()) edge = snapLine(edge, linesX(), guidesX);
            float nw = Math.max(drag.naturalWidth(), Math.abs(edge - anchorX) / sc);
            drag.setBox(nw, drag.boxHeight());
            w = Math.max(drag.naturalWidth(), drag.boxWidth());
            float nx = grip.dx < 0 ? anchorX - w * sc : anchorX;
            place(nx, b[1], w * sc, h * sc, w, h);
        } else {
            float edge = y;
            if (snapping()) edge = snapLine(edge, linesY(), guidesY);
            float nh = Math.max(drag.naturalHeight(), Math.abs(edge - anchorY) / sc);
            drag.setBox(drag.boxWidth(), nh);
            h = Math.max(drag.naturalHeight(), drag.boxHeight());
            float ny = grip.dy < 0 ? anchorY - h * sc : anchorY;
            place(b[0], ny, w * sc, h * sc, w, h);
        }
    }

    private void place(float nx, float ny, float dw, float dh, float w, float h) {
        float sw = gui.screenW, sh = gui.screenH;
        nx = Math.max(0, Math.min(sw - dw, nx));
        ny = Math.max(0, Math.min(sh - dh, ny));
        drag.moveTo(nx, ny, Math.round(sw), Math.round(sh), w, h);
    }

    /** Screen edges, centre and every other visible widget's edges and centre. */
    private List<Float> linesX() { return lines(true); }
    private List<Float> linesY() { return lines(false); }

    private List<Float> lines(boolean xAxis) {
        List<Float> out = new ArrayList<Float>();
        float size = xAxis ? gui.screenW : gui.screenH;
        out.add(0f);
        out.add(size / 2f);
        out.add(size);
        for (Mod m : gui.mm.all()) {
            HudElement h = m.hud();
            if (h == null || h == drag || !h.drawn() || !gui.mm.active(m)) continue;
            float[] b = h.bounds();
            float start = xAxis ? b[0] : b[1], len = xAxis ? b[2] : b[3];
            out.add(start);
            out.add(start + len / 2f);
            out.add(start + len);
        }
        return out;
    }

    /** Snap a span [pos, pos + len] so its start, centre or end meets the nearest line within {@link #SNAP}. */
    static float snapSpan(float pos, float len, List<Float> lines, List<Float> guides) {
        float best = SNAP + 1, delta = 0, hit = 0;
        float[] points = { pos, pos + len / 2f, pos + len };
        for (float p : points) {
            for (float l : lines) {
                float d = Math.abs(l - p);
                if (d < best) {
                    best = d;
                    delta = l - p;
                    hit = l;
                }
            }
        }
        if (best > SNAP) return pos;
        guides.add(hit);
        return pos + delta;
    }

    /** Snap a single edge to the nearest line within {@link #SNAP}. */
    static float snapLine(float edge, List<Float> lines, List<Float> guides) {
        float best = SNAP + 1, out = edge;
        for (float l : lines) {
            float d = Math.abs(l - edge);
            if (d < best) {
                best = d;
                out = l;
            }
        }
        if (best <= SNAP) guides.add(out);
        return best <= SNAP ? out : edge;
    }

    boolean mouseUp(float x, float y, int button) {
        boolean was = drag != null;
        drag = null;
        grip = null;
        guidesX.clear();
        guidesY.clear();
        return gui.ui.mouseUp(x, y, button) || was;
    }

    boolean scroll(float x, float y, float delta) {
        gui.ui.mouseMoved(x, y);
        HudElement h = at(x, y);
        if (h == null) h = selected;
        if (h != null) h.setScale(Math.round((h.scale() + (delta > 0 ? 0.1f : -0.1f)) * 10f) / 10f);
        return true;
    }

    /** Arrow keys nudge the selected widget by one pixel (ten with Ctrl). */
    boolean key(int code) {
        if (selected == null || drag != null) return false;
        int dx = code == Keys.LEFT ? -1 : code == Keys.RIGHT ? 1 : 0, dy = code == Keys.UP ? -1 : code == Keys.DOWN ? 1 : 0;
        if (dx == 0 && dy == 0) return false;
        // Move the offset itself (not the last drawn bounds), so several presses between frames all count;
        // drawing keeps the widget on screen.
        int step = gui.ui.ctrlDown() ? 10 : 1;
        float[] b = selected.bounds();
        float freeX = gui.screenW - b[2], freeY = gui.screenH - b[3];
        float baseX = selected.anchor.fx * freeX, baseY = selected.anchor.fy * freeY;
        selected.offsetX = Math.max(-baseX, Math.min(freeX - baseX, selected.offsetX + dx * step));
        selected.offsetY = Math.max(-baseY, Math.min(freeY - baseY, selected.offsetY + dy * step));
        return true;
    }
}
