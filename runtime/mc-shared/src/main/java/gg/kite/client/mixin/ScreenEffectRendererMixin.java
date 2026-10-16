package gg.kite.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.kite.core.Overrides;
import gg.kite.client.KiteClient;
//#if >=26.2
import net.minecraft.client.renderer.SubmitNodeCollector;
//#if >=26.3
import net.minecraft.client.renderer.state.level.PlayerRenderState;
//#endif
//#else
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
//#endif
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Camera mod: hide or lower the fire overlay, hide the underwater overlay. */
@Mixin(ScreenEffectRenderer.class)
abstract class ScreenEffectRendererMixin {
//#if >=26.2
    @Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
    private static void kite$fire(PoseStack pose, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
//#elif >=1.21.9
    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void kite$fire(PoseStack pose, MultiBufferSource buffers, TextureAtlasSprite sprite, CallbackInfo ci) {
//#elif >=1.21.4
    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void kite$fire(PoseStack pose, MultiBufferSource buffers, CallbackInfo ci) {
//#else
    // 1.21.3 and older: renderFire(Minecraft, PoseStack); the Minecraft argument is unused here.
    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void kite$fire(Minecraft mc, PoseStack pose, CallbackInfo ci) {
//#endif
        Overrides fx = KiteClient.fx();
        if (fx.hideFireOverlay) ci.cancel();
        else if (fx.fireOverlayOffset > 0) pose.translate(0f, -fx.fireOverlayOffset, 0f);
    }

//#if >=26.2
    @Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
//#else
    @Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
//#endif
    private static void kite$water(CallbackInfo ci) {
        if (KiteClient.fx().hideWaterOverlay) ci.cancel();
    }
}
