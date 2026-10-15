package gg.aether.core.ui;

import gg.aether.core.Setting;
import java.util.ArrayList;
import java.util.List;

import static gg.aether.core.ui.Theme.*;

/**
 * Popup pixel editor for a {@link Setting.PixelGrid} (custom crosshairs): a checkerboard grid with the centre row and
 * column highlighted, click or drag to draw (starting on a set pixel erases instead), right-click or right-drag to
 * erase, left/right and top/bottom mirroring, undo, clear, reset and a 1:1 preview of the real rendering.
 */
final class PixelEditor implements Ui.Popup {
    static final float PAD = 8, SIDE = 120, HEADER = 18, MAX_CELL = 11, MIN_CELL = 5, PREVIEW_H = 64;
    private static final int UNDO_LIMIT = 50;
    /** Mirroring is remembered between openings. */
    static boolean mirrorX, mirrorY;

    final Setting.PixelGrid grid;
    private final List<boolean[]> undo = new ArrayList<boolean[]>();
    /** What the current stroke paints (true) or erases (false), and the last cell it touched. */
    private boolean paint;
    private int lastX = -1, lastY = -1;
    /** Where the grid was last drawn (tests click it). */
    float gridX, gridY, cellSize;

    PixelEditor(Setting.PixelGrid grid) { this.grid = grid; }

    /** The settings-row control: a thumbnail of the drawing and an Edit button, both opening the editor. */
    static void control(final Ui ui, final Setting.PixelGrid g, float x, float y, float w) {
        float ts = g.size + 2, ty = y + (Ui.ROW - ts) / 2f;
        boolean hover = ui.over(x, ty, ts, ts);
        ui.r.rect(x, ty, ts, ts, 0xFF141418, 0);
        drawPixels(ui, g, x + 1, ty + 1, 1, 0xFF000000 | g.ink.getAsInt());
        ui.outline(x, ty, ts, ts, hover ? 0xFFFFFFFF : 0x40FFFFFF);
        ui.hit(x, ty, ts, ts, (mx, my, b) -> ui.openPopup(new PixelEditor(g)));
        float bx = x + ts + 6;
        ui.button("Edit...", bx, y + 3, x + w - bx, 16, Ui.Style.SECONDARY, (mx, my, b) -> ui.openPopup(new PixelEditor(g)));
    }

    /** The drawing's set pixels as merged runs, {@code cell} units each, top-left at (x, y). */
    private static void drawPixels(Ui ui, Setting.PixelGrid g, float x, float y, float cell, int color) {
        for (int[] run : g.runs()) ui.r.rect(x + run[0] * cell, y + run[1] * cell, run[2] * cell, cell, color, 0);
    }

    @Override public void render(Ui u) {
        int n = grid.size;
        float sw = u.r.screenWidth(), sh = u.r.screenHeight();
        float cell = (float) Math.floor(Math.max(MIN_CELL, Math.min(MAX_CELL, (sh - 8 - PAD * 2 - HEADER) / n)));
        float gridW = cell * n;
        float w = PAD + gridW + 10 + SIDE + PAD, h = PAD + HEADER + Math.max(gridW, sideHeight(u)) + PAD;
        float x = Math.max(4, (sw - w) / 2f), y = Math.max(4, (sh - h) / 2f);
        u.r.rect(x - 1, y - 1, w + 2, h + 2, BORDER_HOVER, 7);
        u.r.rect(x, y, w, h, 0xFA1C1C22, 6);
        u.absorb(x, y, w, h);

        u.textMid(u.fit(grid.label, gridW - 50), x + PAD, y + PAD - 2, HEADER - 4, TEXT);
        String count = grid.count() + " px";
        u.textMid(count, x + PAD + gridW - u.tw(count), y + PAD - 2, HEADER - 4, MUTED);
        float gx = x + PAD, gy = y + PAD + HEADER;
        gridX = gx;
        gridY = gy;
        cellSize = cell;
        grid(u, gx, gy, cell);
        side(u, gx + gridW + 10, y + PAD, SIDE);
    }

    // ---- grid -------------------------------------------------------------------------------------------------------

    private void grid(final Ui u, final float gx, final float gy, final float cell) {
        final int n = grid.size, c = grid.center();
        float gw = cell * n;
        u.r.rect(gx, gy, gw, gw, 0xFF17171C, 0);
        for (int y = 0; y < n; y++) {
            for (int x = (y & 1) == 0 ? 1 : 0; x < n; x += 2) u.r.rect(gx + x * cell, gy + y * cell, cell, cell, 0xFF1E1E25, 0);
        }
        // Centre row and column, so the drawing can be kept symmetric around the aim point.
        u.r.rect(gx, gy + c * cell, gw, cell, withAlpha(ACCENT, 0x2C), 0);
        u.r.rect(gx + c * cell, gy, cell, gw, withAlpha(ACCENT, 0x2C), 0);
        drawPixels(u, grid, gx, gy, cell, 0xFF000000 | grid.ink.getAsInt());
        u.outline(gx + c * cell, gy + c * cell, cell, cell, withAlpha(ACCENT_ALT, 0xA0));
        u.outline(gx - 1, gy - 1, gw + 2, gw + 2, BORDER);

        if (u.over(gx, gy, gw, gw)) {
            int hx = (int) ((u.mouseX() - gx) / cell), hy = (int) ((u.mouseY() - gy) / cell);
            for (int[] p : mirrored(hx, hy)) u.outline(gx + p[0] * cell, gy + p[1] * cell, cell, cell, p[0] == hx && p[1] == hy ? 0xFFFFFFFF : 0x80FFFFFF);
        }

        u.hitDragAnyButton(gx, gy, gw, gw, (mx, my, b) -> {
            int px = cellAt(mx, gx, cell), py = cellAt(my, gy, cell);
            if (px < 0 || py < 0) return;
            pushUndo();
            paint = b == 0 && !grid.at(px, py);
            lastX = lastY = -1;
            strokeTo(u, px, py);
        }, (mx, my) -> {
            int px = cellAt(mx, gx, cell), py = cellAt(my, gy, cell);
            if (px >= 0 && py >= 0) strokeTo(u, px, py);
        });
    }

    private int cellAt(float m, float origin, float cell) {
        int i = (int) Math.floor((m - origin) / cell);
        return i >= 0 && i < grid.size ? i : -1;
    }

    /** Paint the line of cells from the previous one to (x, y), so fast drags leave no gaps. */
    private void strokeTo(Ui u, int x, int y) {
        int x0 = lastX < 0 ? x : lastX, y0 = lastY < 0 ? y : lastY;
        int dx = Math.abs(x - x0), dy = -Math.abs(y - y0), sx = x0 < x ? 1 : -1, sy = y0 < y ? 1 : -1, err = dx + dy;
        boolean changed = false;
        while (true) {
            changed |= apply(x0, y0);
            if (x0 == x && y0 == y) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
        lastX = x;
        lastY = y;
        if (changed) u.play(Ui.Sound.TICK, paint ? 1.45f : 0.95f);
    }

    /** Sets (x, y) and its mirror images to the stroke's value; true if anything changed. */
    private boolean apply(int x, int y) {
        boolean changed = false;
        for (int[] p : mirrored(x, y)) {
            if (grid.at(p[0], p[1]) == paint) continue;
            grid.set(p[0], p[1], paint);
            changed = true;
        }
        return changed;
    }

    /** (x, y) plus its images under the active mirroring. */
    private List<int[]> mirrored(int x, int y) {
        int m = grid.size - 1;
        List<int[]> out = new ArrayList<int[]>();
        out.add(new int[] { x, y });
        if (mirrorX) out.add(new int[] { m - x, y });
        if (mirrorY) out.add(new int[] { x, m - y });
        if (mirrorX && mirrorY) out.add(new int[] { m - x, m - y });
        return out;
    }

    private void pushUndo() {
        boolean[] now = grid.snapshot();
        if (!undo.isEmpty() && java.util.Arrays.equals(undo.get(undo.size() - 1), now)) return;
        undo.add(now);
        while (undo.size() > UNDO_LIMIT) undo.remove(0);
    }

    // ---- side column ------------------------------------------------------------------------------------------------

    private static float sideHeight(Ui u) { return PREVIEW_H + 6 + u.fh() + 8 + 16 * 2 + 4 + 8 + 16 * 2 + 4 + 8 + (u.fh() + 2) * 3; }

    private void side(final Ui u, float x, float y, float w) {
        preview(u, x, y, w);
        float cy = y + PREVIEW_H + 4;
        u.text("Preview at real size", x, cy, FAINT);
        cy += u.fh() + 8;

        u.toggle(x, cy + 2, mirrorX, () -> mirrorX = !mirrorX);
        u.textMid(u.fit("Mirror left/right", w - 30), x + 30, cy, 16, mirrorX ? TEXT : LABEL);
        cy += 20;
        u.toggle(x, cy + 2, mirrorY, () -> mirrorY = !mirrorY);
        u.textMid(u.fit("Mirror top/bottom", w - 30), x + 30, cy, 16, mirrorY ? TEXT : LABEL);
        cy += 16 + 8;

        float bw = (w - 4) / 2f;
        boolean canUndo = !undo.isEmpty();
        u.button("Undo", x, cy, bw, 16, canUndo ? Ui.Style.SECONDARY : Ui.Style.GHOST, (mx, my, b) -> {
            if (undo.isEmpty()) return;
            grid.restore(undo.remove(undo.size() - 1));
        });
        u.button("Clear", x + bw + 4, cy, bw, 16, Ui.Style.SECONDARY, (mx, my, b) -> {
            pushUndo();
            grid.clear();
        });
        cy += 20;
        u.button("Reset", x, cy, bw, 16, Ui.Style.SECONDARY, (mx, my, b) -> {
            pushUndo();
            grid.reset();
        });
        u.button("Done", x + bw + 4, cy, bw, 16, Ui.Style.PRIMARY, (mx, my, b) -> u.closePopup());
        cy += 16 + 8;

        for (String line : u.wrap("Click or drag to draw, right-click to erase. Uses the colour above.", w, 3)) {
            u.text(line, x, cy, FAINT);
            cy += u.fh() + 2;
        }
    }

    /** The crosshair over a sky / ground backdrop, drawn the way the game draws it. */
    private void preview(Ui u, float x, float y, float w) {
        float h = PREVIEW_H;
        u.r.rect(x, y, w, h / 2f, 0xFF86A9E8, 0);
        u.r.rect(x, y + h / 2f, w, h / 2f, 0xFF4E6B34, 0);
        u.r.rect(x, y + h * 0.75f, w, h / 4f, 0xFF6B4B32, 0);
        u.r.clip(x, y, w, h);
        float cx = x + w / 2f, cy = y + h / 2f;
        if (grid.preview != null) grid.preview.draw(u.r, cx, cy);
        else drawPixels(u, grid, cx - (grid.center() + 0.5f), cy - (grid.center() + 0.5f), 1, grid.ink.getAsInt());
        u.r.unclip();
        u.outline(x - 1, y - 1, w + 2, h + 2, BORDER);
    }
}
