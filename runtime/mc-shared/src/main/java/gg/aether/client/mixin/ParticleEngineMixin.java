package gg.aether.client.mixin;

import gg.aether.core.Overrides;
import gg.aether.client.AetherClient;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Particles mod: multiplier and per-type filters. */
@Mixin(ParticleEngine.class)
abstract class ParticleEngineMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void aether$add(Particle p, CallbackInfo ci) {
        Overrides fx = AetherClient.fx();
        if (fx.particleKeep < 1f && ThreadLocalRandom.current().nextFloat() >= fx.particleKeep) ci.cancel();
        else if (p instanceof TerrainParticle && fx.hiddenParticles.contains("minecraft:block")) ci.cancel();
    }

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void aether$create(ParticleOptions opts, double x, double y, double z, double dx, double dy, double dz, CallbackInfoReturnable<Particle> cir) {
        Overrides fx = AetherClient.fx();
        if (!fx.hiddenParticles.isEmpty() && fx.hiddenParticles.contains(String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(opts.getType())))) cir.setReturnValue(null);
    }
}
