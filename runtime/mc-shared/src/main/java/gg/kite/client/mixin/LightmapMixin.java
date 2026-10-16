//#if >=26.1
package gg.kite.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import gg.kite.client.KiteClient;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Fullbright: replaces the gamma option value the lightmap state is extracted from. */
@Mixin(LightmapRenderStateExtractor.class)
abstract class LightmapMixin {
    // OptionInstance.get() calls in extract: hideLightningFlash (0), gamma (1), darknessEffectScale (2).
    @ModifyExpressionValue(method = "extract",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;", ordinal = 1))
    private Object kite$gamma(Object gamma) {
        Double g = KiteClient.fx().gamma;
        return g == null ? gamma : g;
    }
}
//#endif
