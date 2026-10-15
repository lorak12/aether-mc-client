package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import gg.aether.core.Events;
import gg.aether.core.Overrides;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Zoom (FOV), hiding the hand, mouse look (sensitivity, freelook) and the freelook camera. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void aether$fov(float tickDelta, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        float m = AetherClient.fovMultiplier(tickDelta);
        if (m != 1f) cir.setReturnValue(cir.getReturnValue() * m);
    }

    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void aether$hand(CallbackInfo ci) { if (AetherClient.fx().hideHand) ci.cancel(); }

    /** Mouse look: raw movement goes to mods; freelook turns the camera instead of the player. */
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/ClientPlayerEntity;increaseTransforms(FF)V"))
    private void aether$turn(ClientPlayerEntity player, float yaw, float pitch) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.mouse.x != 0 || mc.mouse.y != 0) AetherClient.mods().events().post(new Events.MouseMove(mc.mouse.x, -mc.mouse.y));
        Overrides fx = AetherClient.fx();
        if (fx.freelook) return;
        player.increaseTransforms(yaw * fx.sensitivityMultiplier, pitch * fx.sensitivityMultiplier);
    }

    @Unique private float aether$yaw, aether$pitch, aether$prevYaw, aether$prevPitch;

    /** Freelook: the camera is set up from the view entity's rotation; offset it just for that. */
    @Inject(method = "transformCamera", at = @At("HEAD"))
    private void aether$cameraStart(float tickDelta, CallbackInfo ci) {
        Overrides fx = AetherClient.fx();
        Entity e = MinecraftClient.getInstance().getCameraEntity();
        if (!fx.freelook || e == null) return;
        aether$yaw = e.yaw;
        aether$pitch = e.pitch;
        aether$prevYaw = e.prevYaw;
        aether$prevPitch = e.prevPitch;
        e.yaw += fx.cameraYawOffset;
        e.prevYaw += fx.cameraYawOffset;
        e.pitch = Math.max(-90f, Math.min(90f, e.pitch + fx.cameraPitchOffset));
        e.prevPitch = Math.max(-90f, Math.min(90f, e.prevPitch + fx.cameraPitchOffset));
    }

    @Inject(method = "transformCamera", at = @At("RETURN"))
    private void aether$cameraEnd(float tickDelta, CallbackInfo ci) {
        Entity e = MinecraftClient.getInstance().getCameraEntity();
        if (!AetherClient.fx().freelook || e == null) return;
        e.yaw = aether$yaw;
        e.pitch = aether$pitch;
        e.prevYaw = aether$prevYaw;
        e.prevPitch = aether$prevPitch;
    }
}
