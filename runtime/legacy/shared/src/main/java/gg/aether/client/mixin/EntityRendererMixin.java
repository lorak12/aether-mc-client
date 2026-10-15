package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import net.minecraft.client.render.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Screenshot mode: no nametags. */
@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin {
//#if >=1.8
    @Inject(method = "hasLabel", at = @At("RETURN"), cancellable = true)
    private void aether$names(CallbackInfoReturnable<Boolean> cir) {
        if (AetherClient.fx().hideNametags) cir.setReturnValue(false);
    }
//#else
    // 1.7.10: every label (players, mobs, item frames) is drawn through this one method.
    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"), cancellable = true)
    private void aether$names(net.minecraft.entity.Entity entity, String text, double x, double y, double z, int maxDistance,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (AetherClient.fx().hideNametags) ci.cancel();
    }
//#endif
}
