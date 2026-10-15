package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Animation Tweaks: the player's arm swing is faster or slower (vanilla names this "mining speed multiplier"). */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
    @Inject(method = "getMiningSpeedMultiplier", at = @At("RETURN"), cancellable = true)
    private void aether$swing(CallbackInfoReturnable<Integer> cir) {
        float speed = AetherClient.fx().swingSpeed;
        if (speed == 1f || (Object) this != MinecraftClient.getInstance().player) return;
        cir.setReturnValue(Math.max(1, Math.round(cir.getReturnValueI() * speed)));
    }
}
