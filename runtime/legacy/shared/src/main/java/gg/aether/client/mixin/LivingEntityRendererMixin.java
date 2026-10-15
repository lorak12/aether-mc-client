//#if >=1.8
package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Whether a nametag is shown at all: screenshot mode hides them, "show own nametag" adds the player's in third person. */
@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
    @Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;)Z", at = @At("RETURN"), cancellable = true)
    private void aether$names(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        gg.aether.core.Overrides fx = AetherClient.fx();
        if (fx.hideNametags) {
            cir.setReturnValue(false);
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (fx.showOwnNametag && entity == mc.player && mc.options.perspective != 0 && !entity.isInvisible()) cir.setReturnValue(true);
    }
}
//#endif
