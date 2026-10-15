package gg.aether.mods;

import gg.aether.core.GameState;
import gg.aether.core.HudElement;
import gg.aether.core.Mod;
import gg.aether.core.Setting;
import gg.aether.core.UiRenderer;
import java.util.ArrayList;
import java.util.List;

/** Replaces the vanilla crosshair with a configurable one. */
public final class Crosshair extends Mod {
    public enum Shape { CROSS, T, DOT, CIRCLE, CUSTOM }
    /** Side of the custom drawing in pixels (odd, so there is a centre pixel). */
    static final int GRID = 17;

    private final Setting.Choice<Shape> shape = add(new Setting.Choice<Shape>("shape", "Shape", Shape.CROSS));
    private final Setting.Int size = add(new Setting.Int("size", "Size", 6, 1, 30)).visibleWhen(this::drawn);
    private final Setting.Int thickness = add(new Setting.Int("thickness", "Thickness", 1, 1, 6)).visibleWhen(this::drawn);
    private final Setting.Int gap = add(new Setting.Int("gap", "Gap", 2, 0, 12)).visibleWhen(this::drawn);
    private final Setting.Color color = add(new Setting.Color("color", "Color", 0xFFFFFFFF));
    private final Setting.PixelGrid pixels = add(new Setting.PixelGrid("pixels", "Custom crosshair", GRID, plus(GRID, 4))).visibleWhen(this::custom);
    private final Setting.Int pixelSize = add(new Setting.Int("pixelSize", "Pixel size", 1, 1, 4)).visibleWhen(this::custom);
    private final Setting.Bool centerDot = add(new Setting.Bool("centerDot", "Center dot", false)).visibleWhen(this::drawn);
    private final Setting.Bool outline = add(new Setting.Bool("outline", "Outline", true));
    private final Setting.Color outlineColor = add(new Setting.Color("outlineColor", "Outline color", 0xFF000000)).dependsOn(outline);
    private final Setting.Bool dynamic = add(new Setting.Bool("dynamic", "Change color over entities", true));
    private final Setting.Color entityColor = add(new Setting.Color("entityColor", "Entity color", 0xFFFF4040)).dependsOn(dynamic);
    private final Setting.Bool hideInScreens = add(new Setting.Bool("hideInScreens", "Hide while a menu is open", true));

    public Crosshair() {
        super("crosshair", "Crosshair", "Custom crosshair shape, size and colour", Category.VISUAL, true);
        hud = new HudElement(HudElement.Anchor.CENTER, 0, 0);
        pixels.ink = color::argb;
        pixels.preview = (r, cx, cy) -> drawCustom(r, cx, cy, false);
    }

    /** Rows (saved format) of a {@code n}x{@code n} plus with arms {@code arm} pixels long and a filled centre. */
    static String[] plus(int n, int arm) {
        String[] rows = new String[n];
        int c = n / 2;
        for (int y = 0; y < n; y++) {
            StringBuilder b = new StringBuilder(n);
            for (int x = 0; x < n; x++) b.append((x == c && Math.abs(y - c) <= arm) || (y == c && Math.abs(x - c) <= arm) ? '#' : '.');
            rows[y] = b.toString();
        }
        return rows;
    }

    private boolean custom() { return shape.val() == Shape.CUSTOM; }
    /** The built-in shapes (sized by size / thickness / gap) rather than the pixel drawing. */
    private boolean drawn() { return !custom(); }

    @Override public void onTick(GameState s) { fx.hideVanillaCrosshair = true; }

    /** Filled rectangles {x, y, w, h} that make up the shape, centred on (cx, cy). Pure geometry, unit-tested. */
    static List<float[]> segments(Shape shape, float cx, float cy, int size, int th, int gap, boolean dot) {
        List<float[]> out = new ArrayList<float[]>();
        float t = th, half = t / 2f;
        switch (shape) {
            case CROSS:
            case T:
                out.add(new float[] { cx - gap - size, cy - half, size, t });
                out.add(new float[] { cx + gap, cy - half, size, t });
                out.add(new float[] { cx - half, cy + gap, t, size });
                if (shape == Shape.CROSS) out.add(new float[] { cx - half, cy - gap - size, t, size });
                break;
            case DOT: {
                float d = Math.max(t, size / 2f);
                out.add(new float[] { cx - d / 2f, cy - d / 2f, d, d });
                break;
            }
            case CIRCLE: {
                int steps = 32;
                float radius = size + gap;
                for (int i = 0; i < steps; i++) {
                    double a = 2 * Math.PI * i / steps;
                    out.add(new float[] { (float) (cx + Math.cos(a) * radius - half), (float) (cy + Math.sin(a) * radius - half), t, t });
                }
                break;
            }
        }
        if (dot && shape != Shape.DOT) out.add(new float[] { cx - half, cy - half, t, t });
        return out;
    }

    /**
     * Filled rectangles {x, y, w, h} for a pixel drawing, one per horizontal run of set pixels, {@code cell} units per
     * pixel with the centre pixel centred on (cx, cy). Pure geometry, unit-tested.
     */
    static List<float[]> pixelSegments(Setting.PixelGrid g, float cx, float cy, float cell) {
        List<float[]> out = new ArrayList<float[]>();
        float ox = cx - (g.center() + 0.5f) * cell, oy = cy - (g.center() + 0.5f) * cell;
        for (int[] run : g.runs()) out.add(new float[] { ox + run[0] * cell, oy + run[1] * cell, run[2] * cell, cell });
        return out;
    }

    @Override
    public void renderHud(UiRenderer r, GameState s) {
        if (hideInScreens.on() && s.screenOpen()) return;
        boolean overEntity = dynamic.on() && s.target().kind == GameState.Target.Kind.ENTITY;
        if (shape.val() == Shape.CUSTOM) {
            float extent = GRID * pixelSize.val() + 2;
            float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), extent, extent);
            drawCustom(r, p[0] + extent * hud.scale() / 2f, p[1] + extent * hud.scale() / 2f, overEntity);
            return;
        }
        float extent = (size.val() + gap.val()) * 2 + thickness.val() + 2;
        float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), extent, extent);
        float cx = p[0] + extent * hud.scale() / 2f, cy = p[1] + extent * hud.scale() / 2f;
        List<float[]> segs = segments(shape.val(), cx, cy, Math.round(size.val() * hud.scale()), Math.max(1, Math.round(thickness.val() * hud.scale())),
                Math.round(gap.val() * hud.scale()), centerDot.on());
        paint(r, segs, overEntity);
    }

    /**
     * The custom drawing centred on (cx, cy), each pixel a whole number of real screen pixels and lined up with them
     * so it stays crisp at any GUI scale.
     */
    void drawCustom(UiRenderer r, float cx, float cy, boolean overEntity) {
        float ps = r.pixelScale() > 0 ? r.pixelScale() : 1f;
        float cell = Math.max(1, Math.round(pixelSize.val() * hud.scale() * ps)) / ps;
        float left = Math.round((cx - cell / 2f) * ps) / ps, top = Math.round((cy - cell / 2f) * ps) / ps;
        paint(r, pixelSegments(pixels, left + cell / 2f, top + cell / 2f, cell), overEntity);
    }

    /** Outline (when on) under every segment, then the segments in the crosshair (or entity) colour. */
    private void paint(UiRenderer r, List<float[]> segs, boolean overEntity) {
        int fill = fade(overEntity ? entityColor.argb() : color.argb());
        if (outline.on()) {
            int oc = fade(outlineColor.argb());
            for (float[] g : segs) r.rect(g[0] - 1, g[1] - 1, g[2] + 2, g[3] + 2, oc, 0f);
        }
        for (float[] g : segs) r.rect(g[0], g[1], g[2], g[3], fill, 0f);
    }

    private int fade(int argb) {
        int a = Math.round(((argb >>> 24) & 0xFF) * hud.opacity());
        return (a << 24) | (argb & 0xFFFFFF);
    }
}
