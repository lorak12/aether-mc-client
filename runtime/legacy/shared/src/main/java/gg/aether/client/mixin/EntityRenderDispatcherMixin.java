package gg.aether.client.mixin;

import gg.aether.mc.LegacyRender;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entity Hider: entities of hidden types are not rendered. */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
//#if >=1.8
    @Inject(method = "shouldRender(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/CameraView;DDD)Z", at = @At("HEAD"), cancellable = true)
    private void aether$hide(Entity entity, net.minecraft.client.render.CameraView camera, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (LegacyRender.hidden(entity)) cir.setReturnValue(false);
    }
//#else
    @Inject(method = "renderEntity(Lnet/minecraft/entity/Entity;F)Z", at = @At("HEAD"), cancellable = true)
    private void aether$hide(Entity entity, float tickDelta, CallbackInfoReturnable<Boolean> cir) {
        if (LegacyRender.hidden(entity)) cir.setReturnValue(false);
    }
//#endif
}
