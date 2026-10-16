package gg.kite.mc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
//#if >=1.20.5
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.enchantment.ItemEnchantments;
//#else
import java.util.Map;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
//#endif

/** Item data that moved into data components in 1.20.5 (enchantments, food, rarity, attributes; NBT before). */
public final class ItemCompat {
    private ItemCompat() {}

    /** Enchantments as shown in the tooltip ("Sharpness V"). */
    public static List<String> enchantments(ItemStack st) {
        List<String> out = new ArrayList<String>();
//#if >=1.20.5
        ItemEnchantments e = st.getEnchantments();
        for (Holder<Enchantment> h : e.keySet()) out.add(Enchantment.getFullname(h, e.getLevel(h)).getString());
//#else
        for (Map.Entry<Enchantment, Integer> e : EnchantmentHelper.getEnchantments(st).entrySet()) out.add(e.getKey().getFullname(e.getValue()).getString());
//#endif
        return out;
    }

    /** Data component ids (1.20.5+) or top-level NBT keys before that. */
    public static List<String> components(ItemStack st) {
        List<String> out = new ArrayList<String>();
//#if >=1.20.5
        for (DataComponentType<?> t : st.getComponents().keySet()) {
            Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(t);
            if (id != null) out.add(id.toString());
        }
//#else
        if (st.getTag() != null) out.addAll(st.getTag().getAllKeys());
//#endif
        Collections.sort(out);
        return out;
    }

    /** {nutrition, saturation} of a food item, else null. */
    public static float[] food(ItemStack st) {
//#if >=1.20.5
        net.minecraft.world.food.FoodProperties f = st.get(DataComponents.FOOD);
        return f == null ? null : new float[] { f.nutrition(), f.saturation() };
//#else
        net.minecraft.world.food.FoodProperties f = st.getItem().getFoodProperties();
        // Saturation as 1.20.5 states it: nutrition x modifier x 2.
        return f == null ? null : new float[] { f.getNutrition(), f.getNutrition() * f.getSaturationModifier() * 2f };
//#endif
    }

    public static String rarity(ItemStack st) {
//#if >=1.20.5
        return st.getRarity().getSerializedName();
//#else
        return st.getRarity().name().toLowerCase(java.util.Locale.ROOT);
//#endif
    }

    /** Attack damage in the main hand: 1 (the fist) plus the item's additive attack damage modifiers. */
    public static float attackDamage(ItemStack it) {
        final float[] dmg = { 1f };
//#if >=1.20.5
        it.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
            if (attr.value() == Attributes.ATTACK_DAMAGE.value() && mod.operation() == AttributeModifier.Operation.ADD_VALUE) dmg[0] += (float) mod.amount();
        });
//#else
        for (AttributeModifier mod : it.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
            if (mod.getOperation() == AttributeModifier.Operation.ADDITION) dmg[0] += (float) mod.getAmount();
        }
//#endif
        return dmg[0];
    }
}
