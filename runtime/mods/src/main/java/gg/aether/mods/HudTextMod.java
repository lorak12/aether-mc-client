package gg.aether.mods;

import gg.aether.core.GameState;
import gg.aether.core.HudElement;
import gg.aether.core.Mod;
import gg.aether.core.Setting;
import gg.aether.core.UiRenderer;
import java.util.List;

/** Shared look for text-based HUD mods: background panel, colors, shadow, anchor/scale/opacity. */
abstract class HudTextMod extends Mod {
    protected final Setting.Bool background = add(new Setting.Bool("background", "Background", true));
    protected final Setting.Color bgColor = add(new Setting.Color("bgColor", "Background color", 0x66000000));
    protected final Setting.Color textColor = add(new Setting.Color("textColor", "Text color", 0xFFFFFFFF));
    protected final Setting.Bool shadow = add(new Setting.Bool("shadow", "Text shadow", true));

    private static final int PAD = 3;

    protected HudTextMod(String id, String name, String description, HudElement.Anchor anchor, float x, float y) {
        super(id, name, description, Category.HUD, true);
        this.hud = new HudElement(anchor, x, y);
    }

    /** Lines to show; return empty to draw nothing. */
    protected abstract List<String> lines(GameState s);

    @Override
    public void renderHud(UiRenderer r, GameState s) {
        List<String> lines = lines(s);
        if (lines == null || lines.isEmpty()) return;
        int textW = 0;
        for (String l : lines) textW = Math.max(textW, r.textWidth(l));
        float w = textW + PAD * 2, h = lines.size() * (r.fontHeight() + 1) - 1 + PAD * 2;
        float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), w, h);
        r.push(p[0], p[1], hud.scale());
        if (background.on()) r.rect(0, 0, w, h, withOpacity(bgColor.argb()), 3f);
        float y = PAD;
        for (String l : lines) {
            r.text(l, PAD, y, withOpacity(textColor.argb()), shadow.on());
            y += r.fontHeight() + 1;
        }
        r.pop();
    }

    protected int withOpacity(int argb) {
        int a = Math.round(((argb >>> 24) & 0xFF) * hud.opacity());
        return (a << 24) | (argb & 0xFFFFFF);
    }
}
