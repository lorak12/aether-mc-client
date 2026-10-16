//#if >=1.8
package gg.kite.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.GlStateManager;
import gg.kite.client.KiteClient;
import gg.kite.core.Overrides;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Item Physics, bobbing, spin, scale and shadows for dropped items. The vanilla pose is set up in one method
 * (method_10221: bob, spin, layer offset) that returns how many layers the stack is drawn as.
 */
@Mixin(ItemEntityRenderer.class)
abstract class ItemEntityRendererMixin extends EntityRenderer<ItemEntity> {
    private static final String POSE = "method_10221(Lnet/minecraft/entity/ItemEntity;DDDFLnet/minecraft/client/render/model/BakedModel;)I";

    protected ItemEntityRendererMixin(EntityRenderDispatcher dispatcher) { super(dispatcher); }

    /** No shadow under items when hidden. */
    @Inject(method = "render(Lnet/minecraft/entity/ItemEntity;DDDFF)V", at = @At("HEAD"))
    private void kite$shadow(ItemEntity item, double x, double y, double z, float yaw, float tickDelta, CallbackInfo ci) {
        shadowSize = KiteClient.fx().hideItemShadows ? 0f : 0.15f;
    }

    /** Item Physics: the item lies where it fell instead of floating, with a fixed random heading. */
    @Inject(method = POSE, at = @At("HEAD"), cancellable = true)
    private void kite$physics(ItemEntity item, double x, double y, double z, float tickDelta, BakedModel model, CallbackInfoReturnable<Integer> cir) {
        if (!KiteClient.fx().itemPhysics || item.getItemStack() == null || item.getItemStack().getItem() == null) return;
        int count = gg.kite.mc.LegacyCompat.count(item.getItemStack());
        int layers = count > 48 ? 5 : count > 32 ? 4 : count > 16 ? 3 : count > 1 ? 2 : 1;
        boolean block = model.hasDepth();
        float yaw = Math.floorMod(item.getEntityId() * 47, 360);
        if (block) {
            float k = model.getTransformation().ground.scale.y;
            GlStateManager.translate((float) x, (float) y + 0.25f * k, (float) z);
            GlStateManager.rotate(yaw, 0f, 1f, 0f);
        } else {
            GlStateManager.translate((float) x, (float) y + 0.02f + 0.0234375f * (layers - 1), (float) z);
            GlStateManager.rotate(yaw, 0f, 1f, 0f);
            GlStateManager.rotate(-90f, 1f, 0f, 0f);
        }
        float s = KiteClient.fx().itemScale;
        if (s != 1f) GlStateManager.scale(s, s, s);
        GlStateManager.color(1f, 1f, 1f, 1f);
        cir.setReturnValue(layers);
    }

    /** sin() drives the bob: -1 makes the bob offset 0. */
    @WrapOperation(method = POSE, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/MathHelper;sin(F)F"))
    private float kite$bob(float x, Operation<Float> original) {
        return KiteClient.fx().itemBob ? original.call(x) : -1f;
    }

    /** Spin: the heading rotation of the vanilla pose. */
    @WrapOperation(method = POSE, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;rotate(FFFF)V", ordinal = 0))
    private void kite$spin(float angle, float x, float y, float z, Operation<Void> original) {
        original.call(angle * KiteClient.fx().itemSpin, x, y, z);
    }

    /** Size on the vanilla path (Item Physics draws its own pose and keeps its own size). */
    @Inject(method = POSE, at = @At("RETURN"))
    private void kite$scale(ItemEntity item, double x, double y, double z, float tickDelta, BakedModel model, CallbackInfoReturnable<Integer> cir) {
        float s = KiteClient.fx().itemScale;
        if (s != 1f) GlStateManager.scale(s, s, s);
    }
}
//#endif
