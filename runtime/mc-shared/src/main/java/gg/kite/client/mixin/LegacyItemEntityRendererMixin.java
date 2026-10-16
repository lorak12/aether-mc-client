//#if <1.21.2
package gg.kite.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.kite.client.KiteClient;
import gg.kite.mc.ItemPhysics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
//#if >=1.17
import net.minecraft.client.renderer.entity.EntityRendererProvider;
//#endif
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
//#if >=1.19.4
import net.minecraft.world.item.ItemDisplayContext;
//#else
import net.minecraft.client.renderer.block.model.ItemTransforms;
//#endif
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item Physics before 1.21.2, where the item entity renders straight from the entity (ItemEntityRendererMixin covers the
 * render-state versions): lie-flat physics, the vanilla path's bob / spin / size, and the shadow.
 */
@Mixin(ItemEntityRenderer.class)
abstract class LegacyItemEntityRendererMixin extends EntityRenderer<ItemEntity> {
    private static final String RENDER = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

    @Shadow @Final private RandomSource random;
    @Shadow @Final private ItemRenderer itemRenderer;

//#if >=1.17
    protected LegacyItemEntityRendererMixin(EntityRendererProvider.Context ctx) { super(ctx); }
//#else
    protected LegacyItemEntityRendererMixin(net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher) { super(dispatcher); }
//#endif

    /** sin() drives the bob: -1 makes the bob offset 0. */
    @Redirect(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;sin(F)F"))
    private float kite$bob(float x) { return KiteClient.fx().itemBob ? Mth.sin(x) : -1f; }

    @Redirect(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;getSpin(F)F"))
    private float kite$spin(ItemEntity item, float partialTick) { return item.getSpin(partialTick) * KiteClient.fx().itemSpin; }

    /** Size on the vanilla path (Item Physics draws its own pose, scale included). */
//#if >=1.19.3
    @Inject(method = RENDER, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionf;)V", shift = At.Shift.AFTER))
//#else
    @Inject(method = RENDER, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lcom/mojang/math/Quaternion;)V", shift = At.Shift.AFTER))
//#endif
    private void kite$scale(ItemEntity item, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        float s = KiteClient.fx().itemScale;
        if (s != 1f) pose.scale(s, s, s);
    }

    @Inject(method = RENDER, at = @At("HEAD"), cancellable = true)
    private void kite$physics(ItemEntity item, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        ItemStack stack = item.getItem();
        if (!KiteClient.fx().itemPhysics || stack.isEmpty()) return;
//#if >=1.20
        BakedModel model = itemRenderer.getModel(stack, item.level(), null, item.getId());
//#elif >=1.17
        BakedModel model = itemRenderer.getModel(stack, item.level, null, item.getId());
//#else
        BakedModel model = itemRenderer.getModel(stack, item.level, null);
//#endif
        ci.cancel();
        boolean block = model.isGui3d();
//#if >=1.19.4
        float[] box = ItemPhysics.box(model.getTransforms().getTransform(ItemDisplayContext.GROUND), block);
//#else
        float[] box = ItemPhysics.box(model.getTransforms().getTransform(ItemTransforms.TransformType.GROUND), block);
//#endif
        ItemPhysics.lay(pose, random, item.getAge() + partialTick, item.bobOffs, ItemPhysics.copies(stack.getCount(), block),
            ItemPhysics.seed(stack), box, block,
//#if >=1.19.4
            () -> itemRenderer.render(stack, ItemDisplayContext.GROUND, false, pose, buffers, light, OverlayTexture.NO_OVERLAY, model));
//#else
            () -> itemRenderer.render(stack, ItemTransforms.TransformType.GROUND, false, pose, buffers, light, OverlayTexture.NO_OVERLAY, model));
//#endif
        super.render(item, yaw, partialTick, pose, buffers, light);
    }

//#if >=1.20.5
    @Override protected float getShadowRadius(ItemEntity item) {
        return KiteClient.fx().hideItemShadows ? 0f : super.getShadowRadius(item);
    }
//#else
    /** The dispatcher reads shadowRadius after render, so setting it here applies to this frame (vanilla: 0.15). */
    @Inject(method = RENDER, at = @At("HEAD"))
    private void kite$shadow(ItemEntity item, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        shadowRadius = KiteClient.fx().hideItemShadows ? 0f : 0.15f;
    }
//#endif
}
//#endif
