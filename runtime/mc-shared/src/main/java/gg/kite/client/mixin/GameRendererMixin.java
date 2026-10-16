package gg.kite.client.mixin;

//#if >=26.3
import com.mojang.renderpearl.api.textures.GpuTextureView;
//#endif
import gg.kite.client.KiteClient;
//#if <26.1
import net.minecraft.client.Camera;
//#endif
import net.minecraft.client.renderer.GameRenderer;
//#if >=26.1
import net.minecraft.client.renderer.state.level.CameraRenderState;
//#if >=26.3
import net.minecraft.client.renderer.state.level.PlayerRenderState;
//#endif
//#elif >=1.19.3
import org.joml.Matrix4f;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#if <26.1
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//#endif

//#if >=26.1
/** Hiding the hand. */
//#else
/** Zoom (FOV) and hiding the hand. */
//#endif
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
//#if <26.1
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
//#if <1.20
    private void kite$fov(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
        float m = KiteClient.fovMultiplier(partialTick);
        if (m != 1f) cir.setReturnValue(cir.getReturnValue() * m);
    }
//#else
    private void kite$fov(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
        float m = KiteClient.fovMultiplier(partialTick);
        if (m != 1f) cir.setReturnValue(cir.getReturnValue() * m);
    }
//#endif

//#endif
    // No target arguments: the signature differs between versions.
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void kite$hand(CallbackInfo ci) {
        if (KiteClient.fx().hideHand) ci.cancel();
    }
}
