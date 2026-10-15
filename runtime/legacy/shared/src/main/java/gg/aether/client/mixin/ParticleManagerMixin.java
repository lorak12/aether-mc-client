//#if >=1.8
package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import gg.aether.core.Overrides;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.particle.ParticleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Particle filter: types a mod hides never spawn, and the rest thin out by the keep chance. */
@Mixin(ParticleManager.class)
abstract class ParticleManagerMixin {
    @Inject(method = "addParticle(IDDDDDD[I)Lnet/minecraft/client/particle/Particle;", at = @At("HEAD"), cancellable = true)
    private void aether$filter(int id, double x, double y, double z, double vx, double vy, double vz, int[] args, CallbackInfoReturnable<Particle> cir) {
        Overrides fx = AetherClient.fx();
        if (fx.hiddenParticles.isEmpty() && fx.particleKeep >= 1f) return;
        ParticleType type = ParticleType.getById(id);
        if (type != null && fx.hiddenParticles.contains("minecraft:" + type.getName())) {
            cir.setReturnValue(null);
            return;
        }
        if (fx.particleKeep < 1f && Math.random() >= fx.particleKeep) cir.setReturnValue(null);
    }
}
//#endif
