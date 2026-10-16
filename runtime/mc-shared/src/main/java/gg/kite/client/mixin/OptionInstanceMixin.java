//#if >=1.19
package gg.kite.client.mixin;

import gg.kite.mc.McRender;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla option overrides (FOV, bobbing, particles, clouds, render distance...) without touching options.txt. */
@Mixin(OptionInstance.class)
abstract class OptionInstanceMixin {
    @Inject(method = "get", at = @At("RETURN"), cancellable = true)
    private void kite$override(CallbackInfoReturnable<Object> cir) {
        Object v = cir.getReturnValue();
        Object o = McRender.option((OptionInstance<?>) (Object) this, v);
        if (o != v) cir.setReturnValue(o);
    }
}
//#endif
