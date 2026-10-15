//#if >=1.8
package gg.aether.client.mixin;

import gg.aether.mc.LegacyCosmetics;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.feature.CapeFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wavy capes: vanilla's flat cape is skipped for players whose cape is drawn as cloth. */
@Mixin(CapeFeatureRenderer.class)
abstract class CapeFeatureRendererMixin {
    @Inject(method = "render(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFFFFFF)V", at = @At("HEAD"), cancellable = true)
    private void aether$cloth(AbstractClientPlayerEntity player, float limbAngle, float limbDistance, float tickDelta, float age,
            float headYaw, float headPitch, float scale, CallbackInfo ci) {
        if (LegacyCosmetics.wavyCape(player) != null) ci.cancel();
    }
}
//#endif
