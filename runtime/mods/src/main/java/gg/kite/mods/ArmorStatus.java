package gg.kite.mods;

import gg.kite.core.GameState;
import gg.kite.core.HudElement;
import gg.kite.core.Mod;
import gg.kite.core.Setting;
import gg.kite.core.UiRenderer;
import java.util.ArrayList;
import java.util.List;

/** Worn armor (plus held / offhand items) with durability as numbers, percent or bars. */
public final class ArmorStatus extends Mod {
    public enum Display { NUMBER, PERCENT, NONE }

    private final Setting.Color textColor = add(new Setting.Color("textColor", "Text color", 0xFFFFFFFF));
    private final Setting.Bool shadow = add(new Setting.Bool("shadow", "Text shadow", true));
    private final Setting.Bool background = add(new Setting.Bool("background", "Background", false));
    private final Setting.Color bgColor = add(new Setting.Color("bgColor", "Background color", 0x66000000), background);
    private final Setting.Choice<Display> display = add(new Setting.Choice<Display>("display", "Durability", Display.NUMBER));
    private final Setting.Bool showMax = add(new Setting.Bool("showMax", "Show max (123/363)", false)).visibleWhen(() -> display.val() == Display.NUMBER);
    private final Setting.Bool bar = add(new Setting.Bool("bar", "Durability bar", false));
    private final Setting.Bool colorize = add(new Setting.Bool("colorize", "Color by durability", true));
    private final Setting.Int warn = add(new Setting.Int("warn", "Warning below (%)", 10, 0, 90));
    private final Setting.Color warnColor = add(new Setting.Color("warnColor", "Warning color", 0xFFFF3030)).visibleWhen(() -> warn.val() > 0);
    private final Setting.Bool names = add(new Setting.Bool("names", "Item names", false));
    private final Setting.Bool enchants = add(new Setting.Bool("enchants", "Enchantments", false));
    private final Setting.Bool held = add(new Setting.Bool("held", "Held item", false));
    private final Setting.Bool offhand = add(new Setting.Bool("offhand", "Offhand item", false));
    private final Setting.Bool horizontal = add(new Setting.Bool("horizontal", "Horizontal", false));
    private final Setting.Bool reverse = add(new Setting.Bool("reverse", "Boots first", false));

    private static final int ROW = 18;

    public ArmorStatus() {
        super("armor", "Armor Status", "Armor, held and offhand items with durability", Category.HUD, true);
        hud = new HudElement(HudElement.Anchor.MIDDLE_RIGHT, -6, 0);
    }

    /** Green at full durability fading to red at zero. */
    static int durabilityColor(int cur, int max) { return durabilityColor(cur, max, false); }

    static int durabilityColor(int cur, int max, boolean colorblind) {
        if (max <= 0) return 0xFFFFFFFF;
        return HudTextMod.gradient(cur / (float) max, colorblind);
    }

    static String format(Display d, int cur, int max) { return format(d, cur, max, false); }

    static String format(Display d, int cur, int max, boolean withMax) {
        switch (d) {
            case NUMBER: return max <= 0 ? "" : withMax ? cur + "/" + max : String.valueOf(cur);
            case PERCENT: return max <= 0 ? "" : Math.round(100f * cur / max) + "%";
            default: return "";
        }
    }

    private static final class Entry {
        final String id, name;
        final int cur, max;
        final List<String> enchants;
        Entry(String id, String name, int cur, int max, List<String> enchants) { this.id = id; this.name = name; this.cur = cur; this.max = max; this.enchants = enchants; }
    }

    private List<Entry> entries(GameState s) {
        List<Entry> out = new ArrayList<Entry>();
        if (held.on() && s.heldItem() != null) out.add(of(s.heldItem()));
        if (offhand.on() && s.offhandItem() != null) out.add(of(s.offhandItem()));
        List<GameState.ArmorPiece> armor = new ArrayList<GameState.ArmorPiece>(s.armor());
        if (reverse.on()) java.util.Collections.reverse(armor);
        for (GameState.ArmorPiece a : armor) if (a != null) out.add(new Entry(a.itemId, HudTextMod.prettyId(a.itemId), a.durability, a.maxDurability, java.util.Collections.<String>emptyList()));
        return out;
    }

    private static Entry of(GameState.ItemStack st) {
        return new Entry(st.itemId, st.name != null ? st.name : HudTextMod.prettyId(st.itemId), st.durability, st.maxDurability, st.enchantments);
    }

    @Override
    public void renderHud(UiRenderer r, GameState s) {
        List<Entry> list = entries(s);
        if (list.isEmpty()) {
            if (!previewing()) return;
            list.add(new Entry("minecraft:diamond_helmet", "Diamond Helmet", 363, 363, java.util.Collections.<String>emptyList()));
            list.add(new Entry("minecraft:diamond_chestplate", "Diamond Chestplate", 420, 528, java.util.Collections.<String>emptyList()));
            list.add(new Entry("minecraft:diamond_leggings", "Diamond Leggings", 300, 495, java.util.Collections.<String>emptyList()));
            list.add(new Entry("minecraft:diamond_boots", "Diamond Boots", 30, 429, java.util.Collections.<String>emptyList()));
        }
        // Layout: each entry is an icon plus up to three text rows (value, name, enchantments).
        float cellW = 20, maxTextW = 0;
        for (Entry e : list) maxTextW = Math.max(maxTextW, textWidth(r, e));
        cellW += maxTextW + 4;
        float cellH = Math.max(ROW, rows(list.get(0)) * (r.fontHeight() + 1) + 2);
        for (Entry e : list) cellH = Math.max(cellH, rows(e) * (r.fontHeight() + 1) + 2);
        float w = horizontal.on() ? list.size() * cellW : cellW, h = horizontal.on() ? cellH : list.size() * cellH;
        float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), w, h);
        r.push(p[0], p[1], hud.scale());
        if (background.on()) r.rect(-2, -2, w + 4, h + 4, fade(bgColor.argb()), 3f);
        float x = 0, y = 0;
        for (Entry e : list) {
            r.item(e.id, x, y + (cellH - 16) / 2f);
            float ty = y + 1, tx = x + 20;
            String val = format(display.val(), e.cur, e.max, showMax.on());
            boolean low = e.max > 0 && warn.val() > 0 && e.cur * 100 < warn.val() * e.max;
            int c = low ? warnColor.argb() : colorize.on() ? durabilityColor(e.cur, e.max, fx.colorblind) : textColor.argb();
            if (rows(e) == 1) ty = y + (cellH - r.fontHeight()) / 2f;
            if (!val.isEmpty()) {
                r.text(val, tx, ty, fade(c), shadow.on());
                ty += r.fontHeight() + 1;
            }
            if (names.on()) {
                r.text(e.name, tx, ty, fade(textColor.argb()), shadow.on());
                ty += r.fontHeight() + 1;
            }
            if (enchants.on() && !e.enchants.isEmpty()) r.text(join(e.enchants), tx, ty, fade(0xFFA78BFA), shadow.on());
            if (bar.on() && e.max > 0) {
                r.rect(x + 2, y + (cellH + 16) / 2f - 2, 13, 2, fade(0xFF000000), 0);
                r.rect(x + 2, y + (cellH + 16) / 2f - 2, 13f * e.cur / e.max, 1, fade(c), 0);
            }
            if (horizontal.on()) x += cellW; else y += cellH;
        }
        r.pop();
    }

    private int rows(Entry e) {
        int n = 0;
        if (display.val() != Display.NONE && e.max > 0) n++;
        if (names.on()) n++;
        if (enchants.on() && !e.enchants.isEmpty()) n++;
        return Math.max(1, n);
    }

    private float textWidth(UiRenderer r, Entry e) {
        float w = r.textWidth(format(display.val(), e.cur, e.max, showMax.on()));
        if (names.on()) w = Math.max(w, r.textWidth(e.name));
        if (enchants.on() && !e.enchants.isEmpty()) w = Math.max(w, r.textWidth(join(e.enchants)));
        return w;
    }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(b.length() == 0 ? "" : ", ").append(s);
        return b.toString();
    }

    private int fade(int argb) {
        int a = Math.round(((argb >>> 24) & 0xFF) * hud.opacity());
        return (a << 24) | (argb & 0xFFFFFF);
    }
}
