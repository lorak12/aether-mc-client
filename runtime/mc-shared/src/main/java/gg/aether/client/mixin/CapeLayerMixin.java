package gg.aether.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.aether.mc.McCosmetics;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
//#if >=1.21.2
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//#else
import gg.aether.mc.PlayerState;
import net.minecraft.client.player.AbstractClientPlayer;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wavy capes: skip vanilla's stiff cape when the cosmetic layer draws it as cloth. */
@Mixin(CapeLayer.class)
abstract class CapeLayerMixin {
//#if >=1.21.9
    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At("HEAD"), cancellable = true)
    private void aether$wavyCape(PoseStack pose, SubmitNodeCollector nodes, int light, AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
        if (McCosmetics.wavyCape(state) != null) ci.cancel();
    }
//#elif >=1.21.2
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At("HEAD"), cancellable = true)
    private void aether$wavyCape(PoseStack pose, SubmitNodeCollector nodes, int light, AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
        if (McCosmetics.wavyCape(state) != null) ci.cancel();
    }
//#else
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At("HEAD"), cancellable = true)
    private void aether$wavyCape(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, int light, AbstractClientPlayer player,
            float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (McCosmetics.wavyCape(PlayerState.of(player, partialTick)) != null) ci.cancel();
    }
//#endif
}
