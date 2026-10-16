package gg.kite.core.ui;

import gg.kite.core.Setting;
import java.util.List;
import java.util.Locale;

import static gg.kite.core.ui.Theme.*;

/**
 * Popup colour editor: a saturation/value square with hue and alpha bars, an old/new preview, HEX / RGB / HSL tabs
 * (gradient channel sliders with typed values, or a hex field with copy and paste), chroma (rainbow) with its speed,
 * presets and the player's saved colours (shared by every colour setting).
 */
final class ColorPicker implements Ui.Popup {
    enum Mode { HEX, RGB, HSL }

    static final int[] PRESETS = { 0xFFFFFFFF, 0xFFFF5555, 0xFFFFAA00, 0xFFFFFF55, 0xFF55FF55, 0xFF55FFFF, 0xFF8B5CF6, 0xFFA78BFA, 0xFFFF55FF,
            0xFF000000, 0x66000000, 0x00000000 };
    static final float W = 204, PAD = 8, BAR = 10, ROW_H = 16, ROW_GAP = 4;
    /** Height of the saturation/value square: 96, less on short screens so the popup always fits. */
    private float svH = 96;
    private static final int SV_W_PX = 112, svH_PX = 72, BAR_PX = 96;

    /** The tab the next picker opens on. */
    private static Mode lastMode = Mode.HEX;

    final Setting.Color target;
    private final Ui ui;
    private final float ax, ay;
    private final int initial;
    private Mode mode = lastMode;
    /** Hue (0..360), saturation and value (0..1) kept separately so hue survives grey/black colours. */
    private float hue, sat, val;
    private int lastSeen;
    private final Ui.Text hex = new Ui.Text("", 9);
    private final Ui.Text[] fields = { new Ui.Text("", 4), new Ui.Text("", 4), new Ui.Text("", 4), new Ui.Text("", 4) };
    private int[] svCache;
    private float svFor = -1;

    ColorPicker(Ui ui, Setting.Color target, float ax, float ay) {
        this.ui = ui;
        this.target = target;
        this.ax = ax;
        this.ay = ay;
        this.initial = target.base();
        sync(target.base());
    }

    // ---- state ------------------------------------------------------------------------------------------------------

    /** Take {@code argb} as the current colour: HSV follows it (keeping the hue for greys); hex text unless typing. */
    private void sync(int argb) {
        float[] hsv = toHsv(argb);
        if (hsv[1] > 0.001f && hsv[2] > 0.001f) hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
        lastSeen = argb;
        if (!ui.focused(hex)) hex.value = hex(argb);
    }

    private void set(int argb) {
        target.set(argb);
        sync(argb);
    }

    /** Apply the HSV state (keeping alpha). */
    private void applyHsv() {
        int argb = (target.base() & 0xFF000000) | (fromHsv(hue, sat, val) & 0xFFFFFF);
        target.set(argb);
        lastSeen = argb;
        if (!ui.focused(hex)) hex.value = hex(argb);
    }

    private void setAlpha(int a) {
        int argb = (Math.max(0, Math.min(255, a)) << 24) | (target.base() & 0xFFFFFF);
        target.set(argb);
        lastSeen = argb;
        if (!ui.focused(hex)) hex.value = hex(argb);
    }

    // ---- rendering --------------------------------------------------------------------------------------------------

    @Override public void render(Ui u) {
        if (target.base() != lastSeen && !u.focused(hex)) sync(target.base());
        // Live preview while typing a complete hex value.
        if (u.focused(hex)) {
            Integer typed = parseHex(hex.value);
            if (typed != null && typed != target.base()) {
                target.set(typed);
                sync(typed);
            }
        }
        float rowsH = 4 * ROW_H + 3 * ROW_GAP;
        List<Integer> saved = u.palette();
        float savedH = saved == null ? 0 : 8 + ROW_H;
        float rest = PAD + 8 + 20 + 8 + rowsH + 10 + ROW_H + 8 + 12 + savedH + PAD;
        svH = Math.max(40, Math.min(96, u.r.screenHeight() - 8 - rest));
        float h = rest + svH;
        float x = Math.max(4, Math.min(ax, u.r.screenWidth() - W - 4)), y = ay;
        if (y + h > u.r.screenHeight() - 4) y = ay - h - Ui.ROW - 4;
        y = Math.max(4, Math.min(y, u.r.screenHeight() - h - 4));
        u.r.rect(x - 1, y - 1, W + 2, h + 2, BORDER_HOVER, 7);
        u.r.rect(x, y, W, h, 0xFA1C1C22, 6);
        u.absorb(x, y, W, h);

        float cx = x + PAD, cy = y + PAD, cw = W - PAD * 2;
        float svW = cw - (BAR + 6) * 2;
        svSquare(u, cx, cy, svW);
        hueBar(u, cx + svW + 6, cy);
        alphaBar(u, cx + svW + 6 + BAR + 6, cy);
        cy += svH + 8;

        preview(u, cx, cy);
        tabs(u, cx + cw, cy + 2);
        cy += 20 + 8;

        switch (mode) {
            case RGB: rgbRows(u, cx, cy, cw); break;
            case HSL: hslRows(u, cx, cy, cw); break;
            default: hexRows(u, cx, cy, cw); break;
        }
        cy += rowsH + 10;

        u.r.rect(cx, cy - 5, cw, 1, BORDER, 0);
        chromaRow(u, cx, cy, cw);
        cy += ROW_H + 8;

        presets(u, cx, cy, cw);
        cy += 12 + 8;

        if (saved != null) savedRow(u, saved, cx, cy, cw);
    }

    private void svSquare(Ui u, final float sx, final float sy, final float sw) {
        if (svFor != hue || svCache == null) {
            svCache = svPixels(hue, SV_W_PX, svH_PX);
            svFor = hue;
        }
        if (!u.r.image("kite:picker/sv", SV_W_PX, svH_PX, svCache, (long) (hue * 100) + 1, sx, sy, sw, svH)) {
            int n = 12;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) u.r.rect(sx + i * sw / n, sy + j * svH / n, sw / n + 1, svH / n + 1, fromHsv(hue, i / (n - 1f), 1 - j / (n - 1f)), 0);
            }
        }
        u.outline(sx, sy, sw, svH, 0x30FFFFFF);
        float kx = sx + sat * sw, ky = sy + (1 - val) * svH;
        knob(u, kx, ky, 0xFF000000 | fromHsv(hue, sat, val));
        Ui.Drag drag = (mx, my) -> {
            sat = clamp((mx - sx) / sw);
            val = clamp(1 - (my - sy) / svH);
            applyHsv();
        };
        u.hitDrag(sx - 2, sy - 2, sw + 4, svH + 4, (mx, my, b) -> drag.to(mx, my), drag);
    }

    /** A round handle: white ring with a dark edge, filled with {@code fill}. */
    private static void knob(Ui u, float cx, float cy, int fill) {
        u.r.rect(cx - 5, cy - 5, 10, 10, 0xC0000000, 5);
        u.r.rect(cx - 4, cy - 4, 8, 8, 0xFFFFFFFF, 4);
        u.r.rect(cx - 2.5f, cy - 2.5f, 5, 5, fill, 2.5f);
    }

    /** A horizontal handle across a vertical bar at {@code y}. */
    private static void barKnob(Ui u, float x, float y) {
        u.r.rect(x - 2, y - 2.5f, BAR + 4, 5, 0xC0000000, 2);
        u.r.rect(x - 1, y - 1.5f, BAR + 2, 3, 0xFFFFFFFF, 1.5f);
    }

    private void hueBar(Ui u, final float hx, final float sy) {
        if (!u.r.image("kite:picker/hue", 1, BAR_PX, huePixels(BAR_PX), 1, hx, sy, BAR, svH)) {
            for (int i = 0; i < 24; i++) u.r.rect(hx, sy + i * svH / 24, BAR, svH / 24 + 1, fromHsv(i * 15, 1, 1), 0);
        }
        u.outline(hx, sy, BAR, svH, 0x30FFFFFF);
        barKnob(u, hx, sy + hue / 360f * svH);
        Ui.Drag drag = (mx, my) -> {
            hue = clamp((my - sy) / svH) * 359.9f;
            applyHsv();
        };
        u.hitDrag(hx - 3, sy - 2, BAR + 6, svH + 4, (mx, my, b) -> drag.to(mx, my), drag);
    }

    private void alphaBar(Ui u, final float bx, final float sy) {
        int rgb = target.base() & 0xFFFFFF;
        u.checker(bx, sy, BAR, svH);
        int[] px = new int[BAR_PX];
        for (int i = 0; i < BAR_PX; i++) px[i] = withAlpha(rgb, Math.round(255f * (1 - i / (BAR_PX - 1f))));
        if (!u.r.image("kite:picker/alpha", 1, BAR_PX, px, rgb + 1L, bx, sy, BAR, svH)) {
            for (int i = 0; i < 24; i++) u.r.rect(bx, sy + i * svH / 24, BAR, svH / 24 + 1, withAlpha(rgb, Math.round(255f * (1 - i / 23f))), 0);
        }
        u.outline(bx, sy, BAR, svH, 0x30FFFFFF);
        barKnob(u, bx, sy + (1 - (target.base() >>> 24) / 255f) * svH);
        Ui.Drag drag = (mx, my) -> setAlpha(Math.round((1 - clamp((my - sy) / svH)) * 255));
        u.hitDrag(bx - 3, sy - 2, BAR + 6, svH + 4, (mx, my, b) -> drag.to(mx, my), drag);
    }

    /** Old (click to restore) and new colour side by side. */
    private void preview(Ui u, float px, float py) {
        float w = 22, h = 20;
        u.checker(px, py, w * 2, h);
        u.r.rect(px, py, w, h, initial, 0);
        u.r.rect(px + w, py, w, h, target.argb(), 0);
        boolean oh = u.over(px, py, w, h);
        u.outline(px, py, w * 2, h, oh ? 0xFFFFFFFF : 0x40FFFFFF);
        if (oh) u.r.rect(px + w / 2f - 3, py + h / 2f - 0.5f, 6, 1, contrast(initial), 0);
        u.hit(px, py, w, h, (mx, my, b) -> set(initial));
    }

    private void tabs(Ui u, float right, float ty) {
        float tw = 34, x = right - tw * 3;
        u.r.rect(x, ty, tw * 3, ROW_H, RAISED, 4);
        for (final Mode m : Mode.values()) {
            boolean sel = m == mode, hover = u.over(x, ty, tw, ROW_H);
            if (sel) u.r.rect(x + 1, ty + 1, tw - 2, ROW_H - 2, ACCENT, 3);
            else if (hover) u.r.rect(x + 1, ty + 1, tw - 2, ROW_H - 2, RAISED_HOVER, 3);
            String label = m.name();
            u.text(label, x + (tw - u.tw(label)) / 2f, ty + (ROW_H - u.fh()) / 2f + 1, sel ? 0xFFFFFFFF : hover ? TEXT : MUTED);
            u.hit(x, ty, tw, ROW_H, Ui.Sound.SELECT, (mx, my, b) -> {
                mode = m;
                lastMode = m;
                u.blur();
            });
            x += tw;
        }
    }

    // ---- tabs -------------------------------------------------------------------------------------------------------

    private void hexRows(final Ui u, float x, float y, float w) {
        float bw = 38;
        float fw = w - (bw + 4) * 2 - 10;
        u.textMid("#", x, y, ROW_H, MUTED);
        u.selectNextField();
        u.textField(hex, hex, x + 10, y, fw, ROW_H, "RRGGBB", "#0123456789abcdefABCDEF", () -> {
            Integer parsed = parseHex(hex.value);
            if (parsed != null) set(parsed);
            hex.value = hex(target.base());
        });
        u.button("Copy", x + 10 + fw + 4, y, bw, ROW_H, Ui.Style.SECONDARY, (mx, my, b) -> {
            u.copy("#" + hex(target.base()));
            u.flash("Copied #" + hex(target.base()));
        });
        u.button("Paste", x + w - bw, y, bw, ROW_H, Ui.Style.SECONDARY, (mx, my, b) -> {
            Integer parsed = parseHex(u.paste());
            if (parsed != null) set(parsed);
            else u.flash("The clipboard has no colour", WARNING);
        });
        int c = target.base();
        int[] hsl = hsl(c);
        String[] info = {
                String.format(Locale.ROOT, "RGB   %d, %d, %d", (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF),
                String.format(Locale.ROOT, "HSL   %d°, %d%%, %d%%", hsl[0], hsl[1], hsl[2]),
                String.format(Locale.ROOT, "Alpha %d%%", Math.round((c >>> 24) / 2.55f)) };
        float ry = y + ROW_H + ROW_GAP;
        for (String line : info) {
            u.textMid(line, x + 2, ry, ROW_H, LABEL);
            ry += ROW_H + ROW_GAP;
        }
    }

    private void rgbRows(Ui u, float x, float y, float w) {
        final int c = target.base();
        final int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF, a = c >>> 24;
        int[][] ramps = new int[3][];
        for (int ch = 0; ch < 3; ch++) {
            int[] px = new int[64];
            for (int i = 0; i < 64; i++) {
                int v = Math.round(i * 255f / 63);
                px[i] = 0xFF000000 | ((ch == 0 ? v : r) << 16) | ((ch == 1 ? v : g) << 8) | (ch == 2 ? v : b);
            }
            ramps[ch] = px;
        }
        channel(u, 0, "R", ramps[0], r / 255f, String.valueOf(r), 255, x, y, w, v -> set((c & 0xFF00FFFF) | (v << 16)));
        channel(u, 1, "G", ramps[1], g / 255f, String.valueOf(g), 255, x, y + (ROW_H + ROW_GAP), w, v -> set((c & 0xFFFF00FF) | (v << 8)));
        channel(u, 2, "B", ramps[2], b / 255f, String.valueOf(b), 255, x, y + (ROW_H + ROW_GAP) * 2, w, v -> set((c & 0xFFFFFF00) | v));
        alphaRow(u, a, x, y + (ROW_H + ROW_GAP) * 3, w);
    }

    private void hslRows(Ui u, float x, float y, float w) {
        final int c = target.base();
        final float[] hsl = toHsl(c);
        // Keep the picker's hue for greys, like the square does.
        if (hsl[1] < 0.001f || hsl[2] <= 0.001f || hsl[2] >= 0.999f) hsl[0] = hue;
        final int a = c >>> 24;
        int[] hRamp = new int[64], sRamp = new int[64], lRamp = new int[64];
        for (int i = 0; i < 64; i++) {
            float t = i / 63f;
            hRamp[i] = fromHsl(t * 360, Math.max(0.5f, hsl[1]), Math.max(0.35f, Math.min(0.65f, hsl[2])));
            sRamp[i] = fromHsl(hsl[0], t, hsl[2]);
            lRamp[i] = fromHsl(hsl[0], hsl[1], t);
        }
        channel(u, 0, "H", hRamp, hsl[0] / 360f, String.valueOf(Math.round(hsl[0])), 360, x, y, w, v -> {
            set((c & 0xFF000000) | (fromHsl(v, hsl[1], hsl[2]) & 0xFFFFFF));
            hue = Math.min(359.9f, v);
        });
        channel(u, 1, "S", sRamp, hsl[1], String.valueOf(Math.round(hsl[1] * 100)), 100, x, y + (ROW_H + ROW_GAP), w,
                v -> set((c & 0xFF000000) | (fromHsl(hsl[0], v / 100f, hsl[2]) & 0xFFFFFF)));
        channel(u, 2, "L", lRamp, hsl[2], String.valueOf(Math.round(hsl[2] * 100)), 100, x, y + (ROW_H + ROW_GAP) * 2, w,
                v -> set((c & 0xFF000000) | (fromHsl(hsl[0], hsl[1], v / 100f) & 0xFFFFFF)));
        alphaRow(u, a, x, y + (ROW_H + ROW_GAP) * 3, w);
    }

    private void alphaRow(Ui u, int a, float x, float y, float w) {
        int rgb = target.base() & 0xFFFFFF;
        int[] ramp = new int[64];
        for (int i = 0; i < 64; i++) ramp[i] = withAlpha(rgb, Math.round(i * 255f / 63));
        channel(u, 3, "A", ramp, a / 255f, String.valueOf(Math.round(a / 2.55f)), 100, x, y, w, v -> setAlpha(Math.round(v * 2.55f)));
    }

    interface IntSetter { void set(int v); }

    /** One channel: label, gradient slider and a typed value (0..max). */
    private void channel(final Ui u, int index, String label, int[] ramp, float frac, String value, final int max, float x, float y, float w, final IntSetter set) {
        u.textMid(label, x, y, ROW_H, MUTED);
        final float fw = 34, tx = x + 12, tw = w - 12 - fw - 6, ty = y + 5;
        u.checker(tx, ty, tw, 6);
        String key = "kite:picker/" + mode.name().toLowerCase(Locale.ROOT) + index;
        long version = java.util.Arrays.hashCode(ramp) & 0xFFFFFFFFL;
        if (!u.r.image(key, ramp.length, 1, ramp, version, tx, ty, tw, 6)) {
            int n = 16;
            for (int i = 0; i < n; i++) u.r.rect(tx + i * tw / n, ty, tw / n + 1, 6, ramp[i * (ramp.length - 1) / (n - 1)], 0);
        }
        u.outline(tx, ty, tw, 6, 0x30FFFFFF);
        float kx = tx + clamp(frac) * tw;
        u.r.rect(kx - 3, y + 1, 6, ROW_H - 2, 0xC0000000, 3);
        u.r.rect(kx - 2, y + 2, 4, ROW_H - 4, u.over(tx - 4, y, tw + 8, ROW_H) ? 0xFFFFFFFF : 0xFFDDE3EE, 2);
        Ui.Drag drag = (mx, my) -> {
            float f = clamp((mx - tx) / tw);
            set.set(Math.round(f * max));
            u.tick(f);
        };
        u.hitDrag(tx - 4, y, tw + 8, ROW_H, (mx, my, b) -> drag.to(mx, my), drag);

        final Ui.Text f = fields[index];
        if (!u.focused(f)) f.value = value;
        u.selectNextField();
        u.textField(f, f, x + w - fw, y, fw, ROW_H, value, "0123456789", () -> {
            try {
                set.set(Math.max(0, Math.min(max, Integer.parseInt(f.value.trim()))));
            } catch (NumberFormatException ignored) { }
        });
    }

    private void chromaRow(final Ui u, float x, float y, float w) {
        final boolean on = target.chroma();
        u.toggle(x, y + 2, on, () -> target.chroma(!on));
        // Rainbow label while on.
        String label = "Chroma";
        float lx = x + 30;
        for (int i = 0; i < label.length(); i++) {
            String ch = label.substring(i, i + 1);
            int color = on ? Setting.Color.chromaAt(0xFFFF5555, target.speed(), Setting.Color.clock.getAsLong() + i * 120L) : LABEL;
            u.textMid(ch, lx, y, ROW_H, color);
            lx += u.tw(ch);
        }
        if (!on) {
            u.textMid("Rainbow colour cycle", lx + 8, y, ROW_H, FAINT);
            u.hit(x + 28, y, w - 28, ROW_H, Ui.Sound.TOGGLE_ON, (mx, my, b) -> target.chroma(true));
            return;
        }
        u.hit(x + 28, y, lx - x - 28, ROW_H, Ui.Sound.TOGGLE_OFF, (mx, my, b) -> target.chroma(false));
        float sx = lx + 10, sw = x + w - sx;
        final double lo = Setting.Color.MIN_SPEED, hi = Setting.Color.MAX_SPEED;
        // Left = slow, right = fast.
        float frac = (float) ((hi - target.speed()) / (hi - lo));
        u.slider(sx, y - 3, sw, frac, String.format(Locale.ROOT, "%.1fs", target.speed()),
                f -> target.speed(Math.round((hi - f * (hi - lo)) * 10) / 10.0));
    }

    private void presets(Ui u, float x, float y, float w) {
        float ps = (w - (PRESETS.length - 1) * 3) / PRESETS.length;
        for (int i = 0; i < PRESETS.length; i++) {
            final int c = PRESETS[i];
            float px = x + i * (ps + 3);
            u.checker(px, y, ps, 12);
            u.r.rect(px, y, ps, 12, c, 3);
            boolean sel = c == target.base();
            if (sel || u.over(px, y, ps, 12)) u.outline(px - 1, y - 1, ps + 2, 14, sel ? ACCENT : 0xFFFFFFFF);
            u.hit(px, y, ps, 12, (mx, my, b) -> set(c));
        }
    }

    /** "Saved" label, the saved swatches (click applies, right-click removes) and a Save button for the current colour. */
    private void savedRow(Ui u, final List<Integer> saved, float x, float y, float w) {
        float bw = 40, sw = 14, gap = 3;
        u.textMid("Saved", x, y, ROW_H, MUTED);
        float sx = x + 34, maxX = x + w - bw - 6;
        int fit = Math.max(0, (int) ((maxX - sx + gap) / (sw + gap)));
        if (saved.isEmpty()) u.textMid(u.fit("Save colours to reuse them", maxX - sx), sx, y, ROW_H, FAINT);
        for (int i = 0; i < Math.min(fit, saved.size()); i++) {
            final int c = saved.get(i);
            float px = sx + i * (sw + gap), py = y + (ROW_H - sw) / 2f;
            u.checker(px, py, sw, sw);
            u.r.rect(px, py, sw, sw, c, 3);
            boolean sel = c == target.base();
            if (sel || u.over(px, py, sw, sw)) u.outline(px - 1, py - 1, sw + 2, sw + 2, sel ? ACCENT : 0xFFFFFFFF);
            u.hit(px, py, sw, sw, (mx, my, b) -> {
                if (b == 1) saved.remove(Integer.valueOf(c));
                else set(c);
            });
        }
        final int current = target.base();
        boolean has = saved.contains(current);
        u.button(has ? "Saved" : "+ Save", x + w - bw, y, bw, ROW_H, has ? Ui.Style.GHOST : Ui.Style.SECONDARY, (mx, my, b) -> {
            if (saved.contains(current)) return;
            saved.add(0, current);
            while (saved.size() > gg.kite.core.ClientSettings.MAX_SAVED_COLORS) saved.remove(saved.size() - 1);
            u.flash("Saved #" + hex(current) + " (right-click a saved colour to remove it)");
        });
    }

    // ---- colour maths -----------------------------------------------------------------------------------------------

    private static float clamp(float f) { return Math.max(0f, Math.min(1f, f)); }

    static String hex(int argb) {
        return (argb >>> 24) == 0xFF ? String.format("%06X", argb & 0xFFFFFF) : String.format("%08X", argb);
    }

    /** Black or white, whichever reads better on {@code argb}. */
    private static int contrast(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return r * 299 + g * 587 + b * 114 > 150000 ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** "#RGB", "#RRGGBB", "#AARRGGBB", with or without "#", "0x" or spaces -> ARGB; null if invalid. */
    static Integer parseHex(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        else if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        if (!s.matches("[0-9a-fA-F]+")) return null;
        try {
            if (s.length() == 3) {
                int r = Integer.parseInt(s.substring(0, 1), 16), g = Integer.parseInt(s.substring(1, 2), 16), b = Integer.parseInt(s.substring(2, 3), 16);
                return 0xFF000000 | (r * 17 << 16) | (g * 17 << 8) | b * 17;
            }
            if (s.length() == 6) return 0xFF000000 | (int) Long.parseLong(s, 16);
            if (s.length() == 8) return (int) Long.parseLong(s, 16);
        } catch (NumberFormatException ignored) { }
        return null;
    }

    /** ARGB -> {hue 0..360, saturation 0..1, value 0..1}. */
    static float[] toHsv(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        return new float[] { hueOf(r, g, b, max, d), max == 0 ? 0 : d / max, max };
    }

    /** ARGB -> {hue 0..360, saturation 0..1, lightness 0..1}. */
    static float[] toHsl(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min, l = (max + min) / 2;
        float s = d == 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
        return new float[] { hueOf(r, g, b, max, d), Math.min(1, s), l };
    }

    /** Rounded HSL for display: {degrees, percent, percent}. */
    static int[] hsl(int argb) {
        float[] f = toHsl(argb);
        return new int[] { Math.round(f[0]) % 360, Math.round(f[1] * 100), Math.round(f[2] * 100) };
    }

    private static float hueOf(float r, float g, float b, float max, float d) {
        float h;
        if (d == 0) h = 0;
        else if (max == r) h = 60 * (((g - b) / d) % 6);
        else if (max == g) h = 60 * ((b - r) / d + 2);
        else h = 60 * ((r - g) / d + 4);
        return h < 0 ? h + 360 : h;
    }

    /** Opaque RGB for hue (degrees), saturation and value. */
    static int fromHsv(float h, float s, float v) {
        float c = v * s;
        return fromChroma(h, c, v - c);
    }

    /** Opaque RGB for hue (degrees), saturation and lightness. */
    static int fromHsl(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s;
        return fromChroma(h, c, l - c / 2);
    }

    private static int fromChroma(float h, float c, float m) {
        h = ((h % 360) + 360) % 360;
        float x = c * (1 - Math.abs((h / 60) % 2 - 1));
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return 0xFF000000 | (ch(r + m) << 16) | (ch(g + m) << 8) | ch(b + m);
    }

    private static int ch(float f) { return Math.max(0, Math.min(255, Math.round(f * 255))); }

    private static int[] svPixels(float hue, int w, int h) {
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = fromHsv(hue, x / (w - 1f), 1 - y / (h - 1f));
        return px;
    }

    private static int[] huePixels(int n) {
        int[] px = new int[n];
        for (int i = 0; i < n; i++) px[i] = fromHsv(i * 360f / n, 1, 1);
        return px;
    }
}
