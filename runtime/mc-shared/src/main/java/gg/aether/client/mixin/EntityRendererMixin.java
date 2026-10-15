package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.network.chat.Component;
//#if <1.21.2
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//#endif

/** Screenshot mode: no nametags. */
@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin {
//#if >=1.21.2
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Entity;D)Z", at = @At("HEAD"), cancellable = true)
    private void aether$names(Entity e, double distSq, CallbackInfoReturnable<Boolean> cir) {
//#else
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void aether$names(Entity e, CallbackInfoReturnable<Boolean> cir) {
//#endif
        if (AetherClient.fx().hideNametags) cir.setReturnValue(false);
    }

    /** Nametags mod: health after the name. */
//#if >=1.21.2
    @Inject(method = "getNameTag(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/network/chat/Component;", at = @At("RETURN"), cancellable = true)
    private void aether$nameTag(Entity e, CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(gg.aether.mc.McRender.nameTag(e, cir.getReturnValue()));
    }
//#else
    // No getNameTag before 1.21.2: render passes entity.getDisplayName() straight to renderNameTag.
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getDisplayName()Lnet/minecraft/network/chat/Component;"))
    private Component aether$nameTag(Entity e, Operation<Component> original) {
        return gg.aether.mc.McRender.nameTag(e, original.call(e));
    }
//#endif
}
