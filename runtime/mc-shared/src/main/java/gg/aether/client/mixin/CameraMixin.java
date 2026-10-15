package gg.aether.client.mixin;

import gg.aether.core.Overrides;
import gg.aether.client.AetherClient;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
//#if >=26.1
import org.spongepowered.asm.mixin.injection.ModifyArg;
//#endif
import org.spongepowered.asm.mixin.injection.Inject;
//#if >=26.1
import org.spongepowered.asm.mixin.injection.Redirect;
//#endif
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//#if <26.1
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
//#endif

//#if >=26.1
/** Freelook (camera yaw/pitch offsets on top of the player's rotation) and zoom (FOV). */
//#else
/** Freelook: the camera adds the mod's yaw/pitch offsets on top of the player's rotation. */
//#endif
@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow protected abstract void setRotation(float yaw, float pitch);

//#if >=26.1
    @Redirect(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
//#else
    @Redirect(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
//#endif
    private void aether$rotate(Camera self, float yaw, float pitch) {
        Overrides fx = AetherClient.fx();
        if (fx.freelook) {
            yaw += fx.cameraYawOffset;
            pitch = Math.max(-90f, Math.min(90f, pitch + fx.cameraPitchOffset));
        }
        setRotation(yaw, pitch);
    }

//#if >=26.1
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void aether$fov(float partialTick, CallbackInfoReturnable<Float> cir) {
        float m = AetherClient.fovMultiplier(partialTick);
        if (m != 1f) cir.setReturnValue(cir.getReturnValue() * m);
    }

//#endif
    /** Camera mod: third-person distance (vanilla 4 blocks). */
//#if >=26.1
    @ModifyArg(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
//#elif >=1.21
    @ModifyArg(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
//#endif
//#if >=1.21
    private float aether$distance(float distance) {
        Float d = AetherClient.fx().cameraDistance;
        return d == null ? distance : distance * d / 4f;
    }

    /** Camera mod: no collision, the camera goes through blocks. */
    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void aether$noClip(float distance, CallbackInfoReturnable<Float> cir) {
        if (AetherClient.fx().noCameraClip) cir.setReturnValue(distance);
    }
//#else
    // getMaxZoom works in doubles before 1.21.
    @ModifyArg(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(D)D"))
    private double aether$distance(double distance) {
        Float d = AetherClient.fx().cameraDistance;
        return d == null ? distance : distance * d / 4f;
    }

    /** Camera mod: no collision, the camera goes through blocks. */
    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void aether$noClip(double distance, CallbackInfoReturnable<Double> cir) {
        if (AetherClient.fx().noCameraClip) cir.setReturnValue(distance);
    }
//#endif
}
