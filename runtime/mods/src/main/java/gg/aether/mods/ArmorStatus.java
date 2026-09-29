package gg.aether.mods;

import gg.aether.core.GameState;
import gg.aether.core.HudElement;
import gg.aether.core.Mod;
import gg.aether.core.Setting;
import gg.aether.core.UiRenderer;
import java.util.List;

/** Worn armor with durability, colored green to red. */
public final class ArmorStatus extends Mod {
    public enum Display { NUMBER, PERCENT, NONE }

    private final Setting.Choice<Display> display = add(new Setting.Choice<Display>("display", "Durability", Display.NUMBER));
    private final Setting.Bool colorize = add(new Setting.Bool("colorize", "Color by durability", true));

    private static final int ROW = 18;

    public ArmorStatus() {
        super("armor", "Armor Status", "Armor pieces and their durability", Category.HUD, true);
        hud = new HudElement(HudElement.Anchor.MIDDLE_RIGHT, -6, 0);
    }

    /** 0xFF green at full health fading to red at zero. */
    static int durabilityColor(int cur, int max) {
        if (max <= 0) return 0xFFFFFFFF;
        float f = Math.max(0f, Math.min(1f, cur / (float) max));
        int red = Math.round(255 * Math.min(1f, (1f - f) * 2f));
        int green = Math.round(255 * Math.min(1f, f * 2f));
        return 0xFF000000 | (red << 16) | (green << 8);
    }

    static String format(Display d, int cur, int max) {
        switch (d) {
            case NUMBER: return String.valueOf(cur);
            case PERCENT: return max <= 0 ? "" : Math.round(100f * cur / max) + "%";
            default: return "";
        }
    }

    @Override
    public void renderHud(UiRenderer r, GameState s) {
        List<GameState.ArmorPiece> armor = s.armor();
        int shown = 0;
        for (GameState.ArmorPiece a : armor) if (a != null) shown++;
        if (shown == 0) return;
        float w = 60, h = shown * ROW;
        float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), w, h);
        r.push(p[0], p[1], hud.scale());
        float y = 0;
        for (GameState.ArmorPiece a : armor) {
            if (a == null) continue;
            r.item(a.itemId, 0, y);
            String t = format(display.val(), a.durability, a.maxDurability);
            if (!t.isEmpty()) {
                int c = colorize.on() ? durabilityColor(a.durability, a.maxDurability) : 0xFFFFFFFF;
                int alpha = Math.round(255 * hud.opacity());
                r.text(t, 20, y + (16 - r.fontHeight()) / 2f, (alpha << 24) | (c & 0xFFFFFF), true);
            }
            y += ROW;
        }
        r.pop();
    }
}
