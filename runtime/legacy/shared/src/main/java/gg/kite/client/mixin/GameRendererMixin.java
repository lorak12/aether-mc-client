package gg.kite.client.mixin;

import gg.kite.client.KiteClient;
import gg.kite.core.Events;
import gg.kite.core.Overrides;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Zoom (FOV), hiding the hand, mouse look (sensitivity, freelook) and the freelook camera. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void kite$fov(float tickDelta, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        float m = KiteClient.fovMultiplier(tickDelta);
        if (m != 1f) cir.setReturnValue(cir.getReturnValue() * m);
    }

    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void kite$hand(CallbackInfo ci) { if (KiteClient.fx().hideHand) ci.cancel(); }

    /** Mouse look: raw movement goes to mods; freelook turns the camera instead of the player. */
//#if >=1.8
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/ClientPlayerEntity;increaseTransforms(FF)V"))
//#else
    @Redirect(method = "renderGui", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/ControllablePlayerEntity;increaseTransforms(FF)V"))
//#endif
//#if >=1.8
    private void kite$turn(ClientPlayerEntity player, float yaw, float pitch) {
//#else
    private void kite$turn(net.minecraft.entity.player.ControllablePlayerEntity player, float yaw, float pitch) {
//#endif
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.mouse.x != 0 || mc.mouse.y != 0) KiteClient.mods().events().post(new Events.MouseMove(mc.mouse.x, -mc.mouse.y));
        Overrides fx = KiteClient.fx();
        if (fx.freelook) return;
        player.increaseTransforms(yaw * fx.sensitivityMultiplier, pitch * fx.sensitivityMultiplier);
    }

    /** Third-person camera distance (vanilla 4): the distance is the fourth double of the camera setup. */
    @ModifyVariable(method = "transformCamera(F)V", at = @At(value = "STORE", ordinal = 0), ordinal = 3)
    private double kite$distance(double distance) {
        Float d = KiteClient.fx().cameraDistance;
        return d == null ? distance : d;
    }

    /** Camera clip: the third-person camera ray-traces against blocks; without the trace it passes through them. */
    @WrapOperation(method = "transformCamera(F)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/world/ClientWorld;rayTrace(Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/hit/BlockHitResult;"))
    private net.minecraft.util.hit.BlockHitResult kite$clip(net.minecraft.client.world.ClientWorld world, net.minecraft.util.math.Vec3d from,
            net.minecraft.util.math.Vec3d to, Operation<net.minecraft.util.hit.BlockHitResult> original) {
        return KiteClient.fx().noCameraClip ? null : original.call(world, from, to);
    }

    @Unique private float kite$yaw, kite$pitch, kite$prevYaw, kite$prevPitch;

    /** Freelook: the camera is set up from the view entity's rotation; offset it just for that. */
    @Inject(method = "transformCamera", at = @At("HEAD"))
    private void kite$cameraStart(float tickDelta, CallbackInfo ci) {
        Overrides fx = KiteClient.fx();
        Entity e = MinecraftClient.getInstance().getCameraEntity();
        if (!fx.freelook || e == null) return;
        kite$yaw = e.yaw;
        kite$pitch = e.pitch;
        kite$prevYaw = e.prevYaw;
        kite$prevPitch = e.prevPitch;
        e.yaw += fx.cameraYawOffset;
        e.prevYaw += fx.cameraYawOffset;
        e.pitch = Math.max(-90f, Math.min(90f, e.pitch + fx.cameraPitchOffset));
        e.prevPitch = Math.max(-90f, Math.min(90f, e.prevPitch + fx.cameraPitchOffset));
    }

    @Inject(method = "transformCamera", at = @At("RETURN"))
    private void kite$cameraEnd(float tickDelta, CallbackInfo ci) {
        Entity e = MinecraftClient.getInstance().getCameraEntity();
        if (!KiteClient.fx().freelook || e == null) return;
        e.yaw = kite$yaw;
        e.pitch = kite$pitch;
        e.prevYaw = kite$prevYaw;
        e.prevPitch = kite$prevPitch;
    }
}
