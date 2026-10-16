package gg.kite.client.mixin;

import gg.kite.client.KiteClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
//#if >=26.3
import net.minecraft.world.item.component.SwingAnimation;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Animations mod: the local player's arm swing speed (visual only; attack timing is server-side). */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
//#if >=26.3
    @Inject(method = "getModifiedSwingDuration", at = @At("RETURN"), cancellable = true)
    private void kite$swing(SwingAnimation anim, CallbackInfoReturnable<Integer> cir) {
//#else
    @Inject(method = "getCurrentSwingDuration", at = @At("RETURN"), cancellable = true)
    private void kite$swing(CallbackInfoReturnable<Integer> cir) {
//#endif
        float m = KiteClient.fx().swingSpeed;
        if (m != 1f && (Object) this == Minecraft.getInstance().player) cir.setReturnValue(Math.max(1, Math.round(cir.getReturnValue() * m)));
    }
}
