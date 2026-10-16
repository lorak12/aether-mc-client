package gg.kite.client.mixin;

import gg.kite.mc.McRender;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entity Rendering mod: hide armor stands, item frames, paintings, dropped items... */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
//#if >=26.3
    private <E extends Entity> void kite$hidden(E entity, Frustum frustum, double x, double y, double z, float partialTick, CallbackInfoReturnable<Boolean> cir) {
//#else
    private <E extends Entity> void kite$hidden(E entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
//#endif
        if (McRender.hidden(entity)) cir.setReturnValue(false);
    }
//#if <1.21.9

    /** Hitboxes mod: vanilla's hitbox renderer on without flipping the player's F3 + B state (1.21.9+: DebugEntriesMixin). */
    @Inject(method = "shouldRenderHitBoxes", at = @At("RETURN"), cancellable = true)
    private void kite$hitboxes(CallbackInfoReturnable<Boolean> cir) {
        if (gg.kite.client.KiteClient.fx().showHitboxes) cir.setReturnValue(true);
    }
//#endif
}
