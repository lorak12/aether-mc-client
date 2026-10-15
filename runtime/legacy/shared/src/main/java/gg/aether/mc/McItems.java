package gg.aether.mc;

import gg.aether.client.AetherClient;
import gg.aether.core.Events;
import gg.aether.core.GameState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/** Registry lookups for legacy Minecraft (numeric-id era registries keyed by Identifier). */
public final class McItems {
    private McItems() {}

    /** Item for a registry id ("minecraft:diamond_sword"), null if unknown. */
    public static Item item(String id) {
        try {
//#if >=1.8
            return Item.REGISTRY.get(new Identifier(id));
//#else
            return (Item) Item.REGISTRY.get(id);
//#endif
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String id(ItemStack st) {
//#if >=1.8
        Object id = st == null || st.getItem() == null ? null : Item.REGISTRY.getIdentifier(st.getItem());
//#else
        Object id = st == null || st.getItem() == null ? null : Item.REGISTRY.getId(st.getItem());
//#endif
        return id == null ? "minecraft:air" : id.toString();
    }

    public static String blockId(Block b) {
//#if >=1.8
        Object id = Block.REGISTRY.getIdentifier(b);
//#else
        Object id = Block.REGISTRY.getId(b);
//#endif
        return id == null ? "minecraft:air" : id.toString();
    }

    /** Snake-case entity name ("zombie", "pig_zombie"...): legacy versions have no namespaced entity ids. */
    public static String entityId(Entity e) {
        String n = EntityType.getEntityName(e);
        return n == null ? (e instanceof net.minecraft.entity.player.PlayerEntity ? "player" : "unknown")
                : n.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT); // "ArmorStand" -> "armor_stand"
    }

    // ---- tooltips ---------------------------------------------------------------------------------------------------

    private static final int[] FORMAT_RGB = { 0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF };

    /** The colour code ("\u00a7b") nearest to an RGB colour. */
    private static String formatCode(int rgb) {
        int best = 15;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < FORMAT_RGB.length; i++) {
            long dr = ((rgb >> 16) & 0xFF) - ((FORMAT_RGB[i] >> 16) & 0xFF), dg = ((rgb >> 8) & 0xFF) - ((FORMAT_RGB[i] >> 8) & 0xFF), db = (rgb & 0xFF) - (FORMAT_RGB[i] & 0xFF);
            long d = dr * dr + dg * dg + db * db;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return "\u00a7" + Integer.toHexString(best);
    }

    /** ItemStack.getTooltip return hook: posts Events.Tooltip; returns the lines plus what mods added, or null if none. */
    public static List<String> tooltip(ItemStack st, boolean advanced, List<String> lines) {
        if (st == null || st.getItem() == null || lines == null) return null;
        List<String> comps = new ArrayList<String>(), ench = new ArrayList<String>();
        if (st.hasNbt()) {
            for (Object k : st.getNbt().getKeys()) comps.add(String.valueOf(k));
            java.util.Collections.sort(comps);
        }
        ench.addAll(enchantNames(st));
        boolean damageable = st.isDamageable();
        Events.Tooltip e = new Events.Tooltip(id(st), st.getCustomName() != null ? st.getCustomName() : id(st), st.getRarity().name().toLowerCase(Locale.ROOT),
                LegacyCompat.count(st), st.getMaxCount(), damageable ? st.getMaxDamage() - st.getDamage() : 0, damageable ? st.getMaxDamage() : 0,
                new ArrayList<String>(), comps, ench, LegacyCompat.food(st), advanced);
        AetherClient.mods().events().post(e);
        if (e.add.isEmpty()) return null;
        List<String> out = new ArrayList<String>(lines);
        for (int i = 0; i < e.add.size(); i++) out.add(formatCode(e.colors.get(i) & 0xFFFFFF) + e.add.get(i));
        return out;
    }

    // ---- container search -------------------------------------------------------------------------------------------

    private static String query = "";
    private static boolean focused;
    private static int boxX, boxY, boxW, boxH = 12;
    private static Object screen;

    private static boolean enabled(Object s) {
        return AetherClient.fx().containerSearch && s != null && !s.getClass().getSimpleName().startsWith("CreativeInventory");
    }

    /** End of the container screen's render: search box above the panel, non-matching slots dimmed. */
    public static void renderSearch(gg.aether.core.UiRenderer r, Object s, int left, int top, int width, List<?> slots) {
        if (!enabled(s)) return;
        if (s != screen) {
            screen = s;
            focused = false;
        }
        boxW = Math.max(90, Math.min(width, 160));
        boxX = left + (width - boxW) / 2;
        boxY = Math.max(1, top - boxH - 3);
        gg.aether.core.ItemQuery q = new gg.aether.core.ItemQuery(query);
        int stacks = 0, items = 0;
        if (!q.isEmpty()) {
            gg.aether.core.Overrides fx = AetherClient.fx();
            for (Object o : slots) {
                net.minecraft.inventory.slot.Slot sl = (net.minecraft.inventory.slot.Slot) o;
                ItemStack st = sl.getStack();
                int sx = left + sl.x, sy = top + sl.y;
                if (!sl.hasStack() || st == null || !q.matches(id(st), String.valueOf(st.getCustomName()), new ArrayList<String>(), enchantNames(st))) {
                    r.rect(sx, sy, 16, 16, fx.searchDim, 0f);
                } else {
                    stacks++;
                    items += LegacyCompat.count(st);
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

    private static List<String> enchantNames(ItemStack st) {
        List<String> ench = new ArrayList<String>();
        net.minecraft.nbt.NbtList list = st.getEnchantments();
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                net.minecraft.enchantment.Enchantment e = net.minecraft.enchantment.Enchantment.byRawId(list.getCompound(i).getShort("id"));
                if (e != null) ench.add(net.minecraft.client.resource.language.I18n.translate(e.getTranslationKey()).toLowerCase(Locale.ROOT));
            }
        }
        return ench;
    }

    /** Container screen click: focus / clear the search box. True if consumed. */
    public static boolean click(Object s, int x, int y, int button) {
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
        if (glfw == gg.aether.core.Keys.ESCAPE || glfw == gg.aether.core.Keys.ENTER) {
            focused = false;
            return true;
        }
        if (glfw == gg.aether.core.Keys.BACKSPACE) {
            if (!query.isEmpty()) query = query.substring(0, query.length() - 1);
            return true;
        }
        return true; // letters (E, 1-9, Q...) must not close the screen or move items while typing
    }

    public static boolean character(Object s, char c) {
        if (!focused || !enabled(s) || s != screen) return false;
        if (c >= 32 && query.length() < 50) query += c;
        return true;
    }

    static GameState.ItemStack stack(ItemStack st) {
        if (st == null || st.getItem() == null) return null;
        List<String> ench = new ArrayList<String>();
        return new GameState.ItemStack(id(st), LegacyCompat.count(st), st.getMaxDamage() - st.getDamage(), st.getMaxDamage(), st.getCustomName(), ench);
    }
}
