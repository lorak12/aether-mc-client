//#if >=1.21.2
package gg.kite.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import gg.kite.core.Overrides;
import gg.kite.client.KiteClient;
import gg.kite.mc.ItemStackSize;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#if >=1.21.9
import net.minecraft.client.renderer.SubmitNodeCollector;
//#else
import net.minecraft.client.renderer.MultiBufferSource;
//#endif
//#if >=1.21.5
import net.minecraft.client.renderer.item.ItemStackRenderState;
//#elif >=1.21.4
import net.minecraft.client.renderer.block.model.ItemTransform;
//#else
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
//#endif

/**
 * Item Physics. With "lie flat" on, vanilla's hovering, bobbing, spinning item is replaced: block models sit on the
 * ground as small cubes and flat items lie on their back, piled from more layers as the stack grows, both turning around Y
 * only. Stacks show extra copies piled on top / scattered next to the first instead of sinking into the floor. Also
 * the vanilla path's bob / spin / size and the shadow.
 */
@Mixin(ItemEntityRenderer.class)
abstract class ItemEntityRendererMixin extends EntityRenderer<ItemEntity, ItemEntityRenderState> {
//#if >=26.1
    private static final String SUBMIT = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";
//#elif >=1.21.9
    private static final String SUBMIT = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V";
//#else
    private static final String SUBMIT = "render(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V";
//#endif
    @Shadow @Final private RandomSource random;
//#if <1.21.4
    @Shadow @Final private ItemRenderer itemRenderer;
//#endif

    protected ItemEntityRendererMixin(EntityRendererProvider.Context ctx) { super(ctx); }

    /** sin() drives the bob: -1 makes the bob offset 0. */
//#if >=1.21.11
    @Redirect(method = SUBMIT, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;sin(D)F"))
    private float kite$bob(double x) { return KiteClient.fx().itemBob ? Mth.sin(x) : -1f; }
//#else
    @Redirect(method = SUBMIT, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;sin(F)F"))
    private float kite$bob(float x) { return KiteClient.fx().itemBob ? Mth.sin(x) : -1f; }
//#endif

    @Redirect(method = SUBMIT, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;getSpin(FF)F"))
    private float kite$spin(float age, float offset) { return ItemEntity.getSpin(age, offset) * KiteClient.fx().itemSpin; }

    /** Size on the vanilla path (Item Physics draws its own pose, scale included). */
//#if >=26.3
    @Inject(method = SUBMIT, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotate(Lcom/mojang/math/Axis;F)V", shift = At.Shift.AFTER))
//#else
    @Inject(method = SUBMIT, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionfc;)V", shift = At.Shift.AFTER))
//#endif
//#if >=26.1
    private void kite$scale(ItemEntityRenderState state, PoseStack pose, SubmitNodeCollector collector, net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo ci) {
//#elif >=1.21.9
    private void kite$scale(ItemEntityRenderState state, PoseStack pose, SubmitNodeCollector collector, net.minecraft.client.renderer.state.CameraRenderState camera, CallbackInfo ci) {
//#else
    private void kite$scale(ItemEntityRenderState state, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
//#endif
        float s = KiteClient.fx().itemScale;
        if (s != 1f) pose.scale(s, s, s);
    }

//#if >=1.21.9
    @Inject(method = SUBMIT, at = @At("HEAD"), cancellable = true)
//#if >=26.1
    private void kite$physics(ItemEntityRenderState state, PoseStack pose, SubmitNodeCollector collector, net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo ci) {
//#else
    private void kite$physics(ItemEntityRenderState state, PoseStack pose, SubmitNodeCollector collector, net.minecraft.client.renderer.state.CameraRenderState camera, CallbackInfo ci) {
//#endif
        if (!KiteClient.fx().itemPhysics || state.item.isEmpty()) return;
        ci.cancel();
        float[] box = gg.kite.mc.ItemPhysics.box(state.item);
        boolean block = box[5] - box[2] > gg.kite.mc.ItemPhysics.FLAT_DEPTH;
        gg.kite.mc.ItemPhysics.lay(pose, random, state.ageInTicks, state.bobOffset, kite$copies(state, block), state.seed, box, block,
            () -> state.item.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor));
        super.submit(state, pose, collector, camera);
    }
//#elif >=1.21.4
    @Inject(method = SUBMIT, at = @At("HEAD"), cancellable = true)
    private void kite$physics(ItemEntityRenderState state, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (!KiteClient.fx().itemPhysics || state.item.isEmpty()) return;
        ci.cancel();
//#if >=1.21.5
        float[] box = gg.kite.mc.ItemPhysics.box(state.item);
        boolean block = box[5] - box[2] > gg.kite.mc.ItemPhysics.FLAT_DEPTH;
//#else
        boolean block = state.item.isGui3d();
        float[] box = gg.kite.mc.ItemPhysics.box(state.item.transform(), block);
//#endif
        gg.kite.mc.ItemPhysics.lay(pose, random, state.ageInTicks, state.bobOffset, kite$copies(state, block), state.seed, box, block,
            () -> state.item.render(pose, buffers, light, OverlayTexture.NO_OVERLAY));
        super.render(state, pose, buffers, light);
    }
//#else
    @Inject(method = SUBMIT, at = @At("HEAD"), cancellable = true)
    private void kite$physics(ItemEntityRenderState state, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        BakedModel model = state.itemModel;
        if (!KiteClient.fx().itemPhysics || model == null || state.item.isEmpty()) return;
        ci.cancel();
        boolean block = model.isGui3d();
        float[] box = gg.kite.mc.ItemPhysics.box(model.getTransforms().getTransform(ItemDisplayContext.GROUND), block);
        int count = gg.kite.mc.ItemPhysics.copies(state.item.getCount(), block);
        gg.kite.mc.ItemPhysics.lay(pose, random, state.ageInTicks, state.bobOffset, count, ItemEntityRenderer.getSeedForItemStack(state.item), box, block,
            () -> itemRenderer.render(state.item, ItemDisplayContext.GROUND, false, pose, buffers, light, OverlayTexture.NO_OVERLAY, model));
        super.render(state, pose, buffers, light);
    }
//#endif

//#if >=1.21.4
    /** Blocks: vanilla's copy count (1-5). Flat items: one 1px layer per stack-size tier, see Overrides.itemLayers. */
    private static int kite$copies(ItemEntityRenderState state, boolean block) {
        return block ? state.count : Overrides.itemLayers(((ItemStackSize) state).kite$stackSize());
    }
//#endif

//#if >=1.21.9
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V", at = @At("RETURN"))
    private void kite$shadow(ItemEntity item, ItemEntityRenderState state, float partialTick, CallbackInfo ci) {
        if (KiteClient.fx().hideItemShadows) state.shadowRadius = 0f;
    }
//#else
    /** The shadow radius is the renderer's own field here, read through getShadowRadius. */
    @Override protected float getShadowRadius(ItemEntityRenderState state) {
        return KiteClient.fx().hideItemShadows ? 0f : super.getShadowRadius(state);
    }
//#endif
}
//#endif
