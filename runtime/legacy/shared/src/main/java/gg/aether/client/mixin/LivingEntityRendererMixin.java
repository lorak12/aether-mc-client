//#if >=1.8
package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Whether a nametag is shown at all: screenshot mode hides them, "show own nametag" adds the player's in third person. */
@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
    @Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;)Z", at = @At("RETURN"), cancellable = true)
    private void aether$names(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        gg.aether.core.Overrides fx = AetherClient.fx();
        if (fx.hideNametags) {
            cir.setReturnValue(false);
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (fx.showOwnNametag && entity == mc.player && mc.options.perspective != 0 && !entity.isInvisible()) cir.setReturnValue(true);
    }

    // The hurt flash is a texture-environment colour of four floats {r, g, b, 1 - alpha}: the first four put() calls of the
    // hurt branch (vanilla 1, 0, 0, 0.3).
    private static final String BRIGHTNESS = "method_10252(Lnet/minecraft/entity/LivingEntity;FZ)Z";
    private static final String PUT = "Ljava/nio/FloatBuffer;put(F)Ljava/nio/FloatBuffer;";

    @ModifyArg(method = BRIGHTNESS, at = @At(value = "INVOKE", target = PUT, ordinal = 0), index = 0)
    private float aether$hitRed(float v) { Integer c = AetherClient.fx().hitColor; return c == null ? v : ((c >> 16) & 0xFF) / 255f; }

    @ModifyArg(method = BRIGHTNESS, at = @At(value = "INVOKE", target = PUT, ordinal = 1), index = 0)
    private float aether$hitGreen(float v) { Integer c = AetherClient.fx().hitColor; return c == null ? v : ((c >> 8) & 0xFF) / 255f; }

    @ModifyArg(method = BRIGHTNESS, at = @At(value = "INVOKE", target = PUT, ordinal = 2), index = 0)
    private float aether$hitBlue(float v) { Integer c = AetherClient.fx().hitColor; return c == null ? v : (c & 0xFF) / 255f; }

    @ModifyArg(method = BRIGHTNESS, at = @At(value = "INVOKE", target = PUT, ordinal = 3), index = 0)
    private float aether$hitAlpha(float v) { Integer c = AetherClient.fx().hitColor; return c == null ? v : 1f - (c >>> 24) / 255f; }
}
//#endif
