package gg.kite.mods;

import gg.kite.core.GameState;
import gg.kite.core.HudElement;
import gg.kite.core.Mod;
import gg.kite.core.Setting;
import gg.kite.core.UiRenderer;
import java.util.List;

/**
 * Shared look for text-based HUD mods: background panel, border, colors, shadow, anchor/scale/opacity.
 * Public so third-party plugins can build text widgets the same way.
 */
public abstract class HudTextMod extends Mod {
    public enum Align { LEFT, CENTER, RIGHT }

    // The shared look comes first, in Setting.LOOK_ORDER; subclasses add their own options after it.
    protected final Setting.Color textColor = add(new Setting.Color("textColor", "Text color", 0xFFFFFFFF));
    protected final Setting.Bool shadow = add(new Setting.Bool("shadow", "Text shadow", true));
    protected final Setting.Bool background = add(new Setting.Bool("background", "Background", true));
    protected final Setting.Color bgColor = add(new Setting.Color("bgColor", "Background color", 0x66000000), background);
    protected final Setting.Bool border = add(new Setting.Bool("border", "Border", false));
    protected final Setting.Color borderColor = add(new Setting.Color("borderColor", "Border color", 0xAA8B5CF6), border);
    /** Where text sits when the HUD editor made the box wider than the text. */
    protected final Setting.Choice<Align> align = add(new Setting.Choice<Align>("align", "Text alignment", Align.LEFT));

    protected static final int PAD = 3;

    protected HudTextMod(String id, String name, String description, HudElement.Anchor anchor, float x, float y) {
        this(id, name, description, Category.HUD, anchor, x, y);
    }

    protected HudTextMod(String id, String name, String description, Category category, HudElement.Anchor anchor, float x, float y) {
        super(id, name, description, category, true);
        this.hud = new HudElement(anchor, x, y);
        hud.resizable = true;
    }

    /** Lines to show; return empty to draw nothing. */
    protected abstract List<String> lines(GameState s);

    /** Extra unscaled height reserved under the text (graphs, icons). */
    protected float extraHeight(UiRenderer r) { return 0; }

    /** Minimum inner width, for widgets whose extra content is wider than the text. */
    protected float minInnerWidth() { return 0; }

    /** Draw into the reserved area; (x, y) is its top-left inside the panel, w its width. */
    protected void drawExtra(UiRenderer r, GameState s, float x, float y, float w) {}

    /** Colour for line {@code i}; override for per-line colouring (warnings). */
    protected int lineColor(int i, GameState s) { return textColor.argb(); }

    @Override
    public void renderHud(UiRenderer r, GameState s) {
        List<String> lines = lines(s);
        if (lines == null || lines.isEmpty()) {
            if (!previewing()) return;
            lines = java.util.Collections.singletonList(name);
        }
        float textW = minInnerWidth();
        for (String l : lines) textW = Math.max(textW, r.textWidth(l));
        float extra = extraHeight(r);
        float naturalW = textW + PAD * 2, naturalH = lines.size() * (r.fontHeight() + 1) - 1 + PAD * 2 + extra;
        hud.natural(naturalW, naturalH);
        // The HUD editor's side handles can make the box bigger than the content; text is aligned inside it.
        float w = Math.max(naturalW, hud.boxWidth()), h = Math.max(naturalH, hud.boxHeight());
        float innerW = w - PAD * 2;
        float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), w, h);
        r.push(p[0], p[1], hud.scale());
        if (background.on()) r.rect(0, 0, w, h, withOpacity(bgColor.argb()), 3f);
        if (border.on()) outline(r, 0, 0, w, h, withOpacity(borderColor.argb()));
        float y = PAD + (h - naturalH) / 2f;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            r.text(line, PAD + alignOffset(innerW, r.textWidth(line)), y, withOpacity(lineColor(i, s)), shadow.on());
            y += r.fontHeight() + 1;
        }
        if (extra > 0) drawExtra(r, s, PAD + alignOffset(innerW, textW), y, textW);
        r.pop();
    }

    /** X offset of something {@code width} wide inside {@code inner}, by the alignment setting. */
    protected float alignOffset(float inner, float width) {
        float free = Math.max(0, inner - width);
        switch (align.val()) {
            case CENTER: return Math.round(free / 2f);
            case RIGHT: return free;
            default: return 0;
        }
    }

    /** Good / warning / bad colours; blue-yellow-orange when the colorblind option is on. */
    protected int good() { return fx != null && fx.colorblind ? 0xFF4DA6FF : 0xFF55FF77; }
    protected int okay() { return 0xFFFFDD55; }
    protected int bad() { return fx != null && fx.colorblind ? 0xFFFF9A1F : 0xFFFF5555; }

    /** Green (1) through yellow to red (0), or the colorblind-safe equivalent. */
    public static int gradient(float frac, boolean colorblind) {
        float f = Math.max(0f, Math.min(1f, frac));
        if (colorblind) {
            // orange (0) -> yellow -> blue (1)
            int r = Math.round(f < .5f ? 255 : 255 * (1 - (f - .5f) * 2) + 77 * (f - .5f) * 2);
            int g = Math.round(f < .5f ? 154 + 67 * f * 2 : 221 - 55 * (f - .5f) * 2);
            int b = Math.round(f < .5f ? 31 + 54 * f * 2 : 85 + 170 * (f - .5f) * 2);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        int red = Math.round(255 * Math.min(1f, (1f - f) * 2f));
        int green = Math.round(255 * Math.min(1f, f * 2f));
        return 0xFF000000 | (red << 16) | (green << 8);
    }

    protected int gradient(float frac) { return gradient(frac, fx != null && fx.colorblind); }

    /** One-pixel frame inside (x, y, w, h). */
    public static void outline(UiRenderer r, float x, float y, float w, float h, int argb) {
        r.rect(x, y, w, 1, argb, 0f);
        r.rect(x, y + h - 1, w, 1, argb, 0f);
        r.rect(x, y + 1, 1, h - 2, argb, 0f);
        r.rect(x + w - 1, y + 1, 1, h - 2, argb, 0f);
    }

    protected int withOpacity(int argb) {
        int a = Math.round(((argb >>> 24) & 0xFF) * hud.opacity());
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** "minecraft:diamond_sword" -> "Diamond Sword". */
    public static String prettyId(String id) {
        if (id == null) return "";
        String s = id.substring(id.indexOf(':') + 1).replace('_', ' ');
        StringBuilder b = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }
}
