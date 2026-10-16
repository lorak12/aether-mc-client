package gg.kite.mc;

import gg.kite.core.Events;
import gg.kite.core.ItemQuery;
import gg.kite.core.Keys;
import gg.kite.core.Overrides;
import gg.kite.core.UiRenderer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Item tooltips, tooltip colours and the container search box, shared by every adapter. */
public final class McItems {
    private McItems() {}

    private static Overrides fx() { return Compat.mods().overrides(); }

    // ---- tooltips ---------------------------------------------------------------------------------------------------

    public static List<String> tags(ItemStack st) {
        List<String> out = new ArrayList<String>();
//#if >=1.18
        st.getItem().builtInRegistryHolder().tags().forEach(t -> out.add(t.location().toString()));
//#else
        for (net.minecraft.resources.ResourceLocation t : net.minecraft.tags.ItemTags.getAllTags().getMatchingTags(st.getItem())) out.add(t.toString());
//#endif
        Collections.sort(out);
        return out;
    }

    public static List<String> components(ItemStack st) { return ItemCompat.components(st); }

    public static List<String> enchantments(ItemStack st) { return ItemCompat.enchantments(st); }

    /** ItemStack.getTooltipLines return hook: posts Events.Tooltip; returns the lines plus what mods added, or null if none. */
    public static List<Component> tooltip(ItemStack st, boolean advanced, List<Component> lines) {
        if (st == null || st.isEmpty() || lines == null) return null;
        float[] food = ItemCompat.food(st);
        Events.Tooltip e = new Events.Tooltip(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getHoverName().getString(),
                ItemCompat.rarity(st), st.getCount(), st.getMaxStackSize(),
                st.isDamageableItem() ? st.getMaxDamage() - st.getDamageValue() : 0, st.isDamageableItem() ? st.getMaxDamage() : 0,
                tags(st), components(st), enchantments(st), food, advanced);
        Compat.mods().events().post(e);
        if (e.add.isEmpty()) return null;
        List<Component> out = new ArrayList<Component>(lines);
        for (int i = 0; i < e.add.size(); i++) out.add(Compat.color(Component.literal(e.add.get(i)), e.colors.get(i) & 0xFFFFFF));
        return out;
    }

    /** Draws the tooltip frame in the Tooltips mod's colours; false leaves the vanilla sprites. */
    public static boolean tooltipBackground(UiRenderer r, int x, int y, int w, int h, boolean customStyle) {
        Overrides fx = fx();
        if (fx.tooltipBackground == null || customStyle) return false;
        int bx = x - 3, by = y - 3, bw = w + 6, bh = h + 6;
        r.rect(bx, by, bw, bh, fx.tooltipBackground, 2f);
        if (fx.tooltipBorder != null) {
            int c = fx.tooltipBorder;
            r.rect(bx, by, bw, 1, c, 0f);
            r.rect(bx, by + bh - 1, bw, 1, c, 0f);
            r.rect(bx, by + 1, 1, bh - 2, c, 0f);
            r.rect(bx + bw - 1, by + 1, 1, bh - 2, c, 0f);
        }
        return true;
    }

    // ---- container search -------------------------------------------------------------------------------------------

    private static String query = "";
    private static boolean focused;
    private static int boxX, boxY, boxW, boxH = 12;
    private static Object screen;

    private static boolean enabled(Object s) {
        return fx().containerSearch && s != null && !s.getClass().getSimpleName().startsWith("CreativeMode");
    }

    /** End of the container screen's render: search box above the panel, non-matching slots dimmed. */
    public static void renderSearch(UiRenderer r, Object s, int left, int top, int width, List<Slot> slots) {
        if (!enabled(s)) return;
        if (s != screen) {
            screen = s;
            focused = false;
        }
        boxW = Math.max(90, Math.min(width, 160));
        boxX = left + (width - boxW) / 2;
        boxY = Math.max(1, top - boxH - 3);
        ItemQuery q = new ItemQuery(query);
        int stacks = 0, items = 0;
        if (!q.isEmpty()) {
            Overrides fx = fx();
            for (Slot sl : slots) {
                if (!sl.isActive()) continue;
                ItemStack st = sl.getItem();
                int sx = left + sl.x, sy = top + sl.y;
                if (st.isEmpty() || !q.matches(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getHoverName().getString(), tags(st), enchantments(st))) {
                    r.rect(sx, sy, 16, 16, fx.searchDim, 0f);
                } else {
                    stacks++;
                    items += st.getCount();
                    int c = fx.searchMatch;
                    r.rect(sx - 1, sy - 1, 18, 1, c, 0f);
                    r.rect(sx - 1, sy + 16, 18, 1, c, 0f);
                    r.rect(sx - 1, sy, 1, 16, c, 0f);
                    r.rect(sx + 16, sy, 1, 16, c, 0f);
                }
            }
        }
        r.rect(boxX, boxY, boxW, boxH, focused ? 0xFF8B5CF6 : 0xFF3A4150, 2f);
        r.rect(boxX + 1, boxY + 1, boxW - 2, boxH - 2, 0xF0101218, 2f);
        String shown = query.isEmpty() && !focused ? "Search (@mod #tag $ench)" : query + (focused && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
        r.text(shown, boxX + 3, boxY + 2, query.isEmpty() && !focused ? 0xFFA4A4B2 : 0xFFFFFFFF, false);
        if (!q.isEmpty()) {
            String count = stacks + " / " + items;
            r.text(count, boxX + boxW - 3 - r.textWidth(count), boxY + 2, stacks > 0 ? 0xFF55FF77 : 0xFFFF5555, false);
        }
    }

    /** Container screen click: focus / clear the search box. True if consumed. */
    public static boolean click(Object s, double x, double y, int button) {
        if (!enabled(s) || s != screen) return false;
        boolean inside = x >= boxX && x < boxX + boxW && y >= boxY && y < boxY + boxH;
        if (!inside) {
            focused = false;
            return false;
        }
        focused = true;
        if (button == 1) query = "";
        return true;
    }

    /** Key press while a container screen is open. True if the search box consumed it. */
    public static boolean key(Object s, int glfw) {
        if (!focused || !enabled(s) || s != screen) return false;
        if (glfw == Keys.ESCAPE || glfw == Keys.ENTER) {
            focused = false;
            return true;
        }
        if (glfw == Keys.BACKSPACE) {
            if (!query.isEmpty()) query = query.substring(0, query.length() - 1);
            return true;
        }
        return true; // letters (E, 1-9, Q...) must not close the screen or move items while typing
    }

    public static boolean character(Object s, int codepoint) {
        if (!focused || !enabled(s) || s != screen) return false;
        if (codepoint >= 32 && query.length() < 50) query += new String(Character.toChars(codepoint));
        return true;
    }
}
