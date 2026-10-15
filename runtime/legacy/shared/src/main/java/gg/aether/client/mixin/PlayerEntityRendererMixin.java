package gg.aether.client.mixin;

import gg.aether.mc.LegacyCosmetics;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#if <1.8
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Redirect;
//#endif

/** Cosmetics on every player: a render layer from 1.8 on, a hook into the special-renders pass on 1.7.10. */
@Mixin(PlayerEntityRenderer.class)
abstract class PlayerEntityRendererMixin {
//#if >=1.8
    @Inject(method = "<init>(Lnet/minecraft/client/render/entity/EntityRenderDispatcher;Z)V", at = @At("TAIL"))
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void aether$cosmeticFeature(net.minecraft.client.render.entity.EntityRenderDispatcher dispatcher, boolean slim, CallbackInfo ci) {
        ((java.util.List) ((LivingEntityRendererAccessor) this).aether$features()).add(new gg.aether.client.CosmeticFeature((PlayerEntityRenderer) (Object) this));
    }
//#else
    @Shadow private net.minecraft.client.render.entity.model.BiPedModel field_2133;

    @Inject(method = "method_1582", at = @At("TAIL"))
    private void aether$cosmetics(AbstractClientPlayerEntity player, float tickDelta, CallbackInfo ci) {
        float limb = gg.aether.mc.LegacyCompat.limbAmount(player, tickDelta), age = player.ticksAlive + tickDelta;
        LegacyCosmetics.drawWavyCape(player, field_2133, tickDelta, age);
        LegacyCosmetics.draw(player, field_2133, limb, tickDelta, age);
    }

    /** The cloth cape replaces vanilla's. */
    @Redirect(method = "method_1582", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/AbstractClientPlayerEntity;canRenderCapeTexture()Z"))
    private boolean aether$vanillaCape(AbstractClientPlayerEntity player) {
        return player.canRenderCapeTexture() && LegacyCosmetics.wavyCape(player) == null;
    }
//#endif
}
