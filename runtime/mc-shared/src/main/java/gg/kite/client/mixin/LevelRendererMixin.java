package gg.kite.client.mixin;

import gg.kite.client.KiteClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block Overlay replaces the vanilla block outline with its own. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
//#if >=26.2
    @Inject(method = "submitBlockOutline", at = @At("HEAD"), cancellable = true)
//#elif >=1.21.2
    @Inject(method = "renderBlockOutline", at = @At("HEAD"), cancellable = true)
//#else
    @Inject(method = "renderHitOutline", at = @At("HEAD"), cancellable = true)
//#endif
    private void kite$outline(CallbackInfo ci) {
        if (KiteClient.fx().hideBlockOutline) ci.cancel();
    }
}
