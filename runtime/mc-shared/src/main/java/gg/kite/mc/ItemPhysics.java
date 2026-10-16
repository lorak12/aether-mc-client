package gg.kite.mc;

import com.mojang.blaze3d.vertex.PoseStack;
//#if >=1.19.3
import com.mojang.math.Axis;
//#else
import com.mojang.math.Vector3f;
//#endif
import gg.kite.client.KiteClient;
import gg.kite.core.Overrides;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
//#if >=1.21.5
import net.minecraft.client.renderer.item.ItemStackRenderState;
//#else
import net.minecraft.client.renderer.block.model.ItemTransform;
//#endif

/**
 * Item Physics placement, shared by the item entity renderer mixins (render states from 1.21.2, the entity before).
 * Block models sit on the ground as small cubes and flat items lie on their back, piled from more layers as the stack
 * grows, both turning around Y only.
 */
public final class ItemPhysics {
    private ItemPhysics() {}

    /** Vanilla's own split between "3D model" and "flat sprite" (model depth in blocks, ground transform applied). */
    public static final float FLAT_DEPTH = 0.0625f;
    /** Gap that keeps stacked copies and the ground from z-fighting. */
    private static final float GAP = 0.002f;

    /**
     * Draws {@code count} copies resting on the entity's position (y = 0 is the ground the item lies on). {@code box}
     * is the model's bounding box with the ground display transform applied: {minX, minY, minZ, maxX, maxY, maxZ}.
     */
    public static void lay(PoseStack pose, RandomSource random, float age, float bobOffset, int count, int seed, float[] box, boolean block, Runnable draw) {
        Overrides fx = KiteClient.fx();
        float cx = (box[0] + box[3]) / 2f, cy = (box[1] + box[4]) / 2f, cz = (box[2] + box[5]) / 2f;
        // Flat items lie on their back, so their depth (1px) becomes their height.
        float height = block ? box[4] - box[1] : box[5] - box[2];
        pose.pushPose();
        if (fx.itemBob) pose.translate(0f, Mth.sin(age / 10f + bobOffset) * 0.1f + 0.1f, 0f);
        // bobOffset is a random angle per entity, so with spin 0 every item keeps its own fixed yaw.
        rotate(pose, 1, bobOffset + age / 20f * fx.itemSpin);
        if (fx.itemScale != 1f) pose.scale(fx.itemScale, fx.itemScale, fx.itemScale);
        random.setSeed(seed);
        for (int i = 0; i < Math.max(1, count); i++) {
            pose.pushPose();
            if (i > 0) {
                // Blocks scatter next to the first one. Flat items are layers piled right on top of each other, each
                // shifted and turned a little so the pile reads as separate items.
                float spread = block ? 0.15f : 0.04f;
                float dx = (random.nextFloat() * 2f - 1f) * spread, dz = (random.nextFloat() * 2f - 1f) * spread;
                pose.translate(dx, block ? i * GAP : i * (height + GAP), dz);
                rotate(pose, 1, (random.nextFloat() * 2f - 1f) * (block ? 0.6f : 0.3f));
            }
            if (block) {
                pose.translate(-cx, GAP - box[1], -cz);
            } else {
                pose.translate(0f, height / 2f + GAP, 0f);
                rotate(pose, 0, (float) (-Math.PI / 2));
                pose.translate(-cx, -cy, -cz);
            }
            draw.run();
            pose.popPose();
        }
        pose.popPose();
    }

    private static void rotate(PoseStack pose, int axis, float radians) {
//#if >=26.3
        pose.rotate(axis == 0 ? Axis.XP : Axis.YP, radians);
//#elif >=1.19.3
        pose.mulPose((axis == 0 ? Axis.XP : Axis.YP).rotation(radians));
//#else
        pose.mulPose((axis == 0 ? Vector3f.XP : Vector3f.YP).rotation(radians));
//#endif
    }

    /** Blocks: vanilla's copy count (1-5). Flat items: one 1px layer per stack-size tier, see Overrides.itemLayers. */
    public static int copies(int stackSize, boolean block) {
        if (!block) return Overrides.itemLayers(stackSize);
        return stackSize <= 1 ? 1 : stackSize <= 16 ? 2 : stackSize <= 32 ? 3 : stackSize <= 48 ? 4 : 5;
    }

    /** Vanilla's per-stack random seed, so copies scatter the same way every frame. */
    public static int seed(net.minecraft.world.item.ItemStack st) {
//#if >=1.20.5 && <1.21.4
        return net.minecraft.client.renderer.entity.ItemEntityRenderer.getSeedForItemStack(st);
//#else
        return st.isEmpty() ? 187 : net.minecraft.world.item.Item.getId(st.getItem()) + st.getDamageValue();
//#endif
    }

//#if >=1.21.5
    public static float[] box(ItemStackRenderState item) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        item.visitExtents(v -> {
            b[0] = Math.min(b[0], v.x()); b[1] = Math.min(b[1], v.y()); b[2] = Math.min(b[2], v.z());
            b[3] = Math.max(b[3], v.x()); b[4] = Math.max(b[4], v.y()); b[5] = Math.max(b[5], v.z());
        });
        // No extents (some special models): draw it as-is, standing on its origin.
        return b[0] > b[3] ? new float[]{0f, 0f, 0f, 0f, 0f, FLAT_DEPTH * 2f} : b;
    }
//#else
    /** No model extents before 1.21.5: a full-size model (cube or 16x16x1 sprite) through the ground transform. */
    public static float[] box(ItemTransform t, boolean block) {
        float hx = 0.5f * t.scale.x(), hy = 0.5f * t.scale.y(), hz = block ? 0.5f * t.scale.z() : t.scale.z() / 32f;
        float x = t.translation.x(), y = t.translation.y(), z = t.translation.z();
        return new float[]{x - hx, y - hy, z - hz, x + hx, y + hy, z + hz};
    }
//#endif
}
