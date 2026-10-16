package gg.kite.client.mixin;

import gg.kite.mc.McRender;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#if <1.19.3
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//#endif

/** While options.txt is written, options report the player's real values, never Kite's overrides. */
@Mixin(Options.class)
abstract class OptionsMixin {
    @Inject(method = "save", at = @At("HEAD"))
    private void kite$saveStart(CallbackInfo ci) { McRender.setSaving(true); }

    @Inject(method = "save", at = @At("RETURN"))
    private void kite$saveEnd(CallbackInfo ci) { McRender.setSaving(false); }

//#if <1.19.3
    /** Volume scale (Streamer / Recording mods): before 1.19.3 volumes are not OptionInstances. */
    @Inject(method = "getSoundSourceVolume", at = @At("RETURN"), cancellable = true)
    private void kite$volume(net.minecraft.sounds.SoundSource source, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(McRender.volume(source, cir.getReturnValue()));
    }
//#endif
}
