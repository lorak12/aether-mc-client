//#if >=1.21.2
package gg.kite.client.mixin;

import gg.kite.client.KiteClient;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sky Customization: hide stars, sun or moon. Handlers take no target arguments, so they fit every version's signature. */
@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {
    @Inject(method = "renderStars", at = @At("HEAD"), cancellable = true)
    private void kite$stars(CallbackInfo ci) { if (KiteClient.fx().hideStars) ci.cancel(); }

    @Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
    private void kite$sun(CallbackInfo ci) { if (KiteClient.fx().hideSun) ci.cancel(); }

    @Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
    private void kite$moon(CallbackInfo ci) { if (KiteClient.fx().hideMoon) ci.cancel(); }
}
//#endif
