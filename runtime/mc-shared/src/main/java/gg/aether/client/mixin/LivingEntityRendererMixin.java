package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Screenshot mode: no nametags on players and mobs (they override the base check). */
@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
//#if >=1.21.2
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("RETURN"), cancellable = true)
    private void aether$names(LivingEntity e, double distSq, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(gg.aether.mc.McRender.showName(e, distSq, cir.getReturnValueZ()));
    }
//#else
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("RETURN"), cancellable = true)
    private void aether$names(LivingEntity e, CallbackInfoReturnable<Boolean> cir) {
        double distSq = net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().distanceToSqr(e);
        cir.setReturnValue(gg.aether.mc.McRender.showName(e, distSq, cir.getReturnValueZ()));
    }
//#endif
}
