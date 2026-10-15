//#if >=1.21.2
package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sky Customization: hide stars, sun or moon. Handlers take no target arguments, so they fit every version's signature. */
@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {
    @Inject(method = "renderStars", at = @At("HEAD"), cancellable = true)
    private void aether$stars(CallbackInfo ci) { if (AetherClient.fx().hideStars) ci.cancel(); }

    @Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
    private void aether$sun(CallbackInfo ci) { if (AetherClient.fx().hideSun) ci.cancel(); }

    @Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
    private void aether$moon(CallbackInfo ci) { if (AetherClient.fx().hideMoon) ci.cancel(); }
}
//#endif
