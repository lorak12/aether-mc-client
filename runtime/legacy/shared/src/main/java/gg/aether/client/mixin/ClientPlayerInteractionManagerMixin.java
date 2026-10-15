package gg.aether.client.mixin;

import gg.aether.mc.McEvents;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hits: reach (eye to the nearest point of the target's hitbox, as servers measure it) and criticals. */
@Mixin(ClientPlayerInteractionManager.class)
abstract class ClientPlayerInteractionManagerMixin {
    @Inject(method = "attackEntity", at = @At("HEAD"))
    private void aether$attack(PlayerEntity player, Entity target, CallbackInfo ci) {
        double ex = player.x, ey = player.y + player.getEyeHeight(), ez = player.z;
        Box bb = target.getBoundingBox();
        double dx = ex - Math.max(bb.minX, Math.min(bb.maxX, ex));
        double dy = ey - Math.max(bb.minY, Math.min(bb.maxY, ey));
        double dz = ez - Math.max(bb.minZ, Math.min(bb.maxZ, ez));
        boolean crit = player.fallDistance > 0 && !player.onGround && !player.isClimbing() && !player.isTouchingWater() && !gg.aether.mc.LegacyCompat.riding(player);
        gg.aether.core.Overrides fx = gg.aether.client.AetherClient.fx();
        for (int i = 0; i < fx.extraCrits; i++) player.addCritParticles(target);
        if (fx.alwaysMagicCrits) player.addEnchantedHitParticles(target);
        McEvents.attacked(target, Math.sqrt(dx * dx + dy * dy + dz * dz), crit);
    }
}
