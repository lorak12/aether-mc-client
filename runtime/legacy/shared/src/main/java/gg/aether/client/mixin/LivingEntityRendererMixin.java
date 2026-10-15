package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Screenshot mode: no nametags on players and mobs (they override the base check). */
@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
    @Inject(method = "hasLabel", at = @At("RETURN"), cancellable = true)
    private void aether$names(CallbackInfoReturnable<Boolean> cir) {
        if (AetherClient.fx().hideNametags) cir.setReturnValue(false);
    }
}
