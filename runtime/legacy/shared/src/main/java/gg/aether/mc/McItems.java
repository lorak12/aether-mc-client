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

    /** Lower-case entity name ("zombie", "pigzombie"...): legacy versions have no namespaced entity ids. */
    public static String entityId(Entity e) {
        String n = EntityType.getEntityName(e);
        return n == null ? (e instanceof net.minecraft.entity.player.PlayerEntity ? "player" : "unknown") : n.toLowerCase(Locale.ROOT);
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
        net.minecraft.nbt.NbtList list = st.getEnchantments();
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                net.minecraft.enchantment.Enchantment e = net.minecraft.enchantment.Enchantment.byRawId(list.getCompound(i).getShort("id"));
                if (e != null) ench.add(net.minecraft.client.resource.language.I18n.translate(e.getTranslationKey()).toLowerCase(Locale.ROOT));
            }
        }
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

    static GameState.ItemStack stack(ItemStack st) {
        if (st == null || st.getItem() == null) return null;
        List<String> ench = new ArrayList<String>();
        return new GameState.ItemStack(id(st), LegacyCompat.count(st), st.getMaxDamage() - st.getDamage(), st.getMaxDamage(), st.getCustomName(), ench);
    }
}
