package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
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
    private void aether$outline(CallbackInfo ci) {
        if (AetherClient.fx().hideBlockOutline) ci.cancel();
    }
}
