package gg.aether.mc;

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

    static GameState.ItemStack stack(ItemStack st) {
        if (st == null || st.getItem() == null) return null;
        List<String> ench = new ArrayList<String>();
        return new GameState.ItemStack(id(st), LegacyCompat.count(st), st.getMaxDamage() - st.getDamage(), st.getMaxDamage(), st.getCustomName(), ench);
    }
}
