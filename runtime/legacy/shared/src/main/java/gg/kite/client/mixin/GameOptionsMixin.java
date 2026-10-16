package gg.kite.client.mixin;

import gg.kite.mc.LegacyRender;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.sound.SoundCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While options.txt is written, options hold the player's real values, never Kite's overrides; plus the volume scales. */
@Mixin(GameOptions.class)
abstract class GameOptionsMixin {
    @Inject(method = "save", at = @At("HEAD"))
    private void kite$saveStart(CallbackInfo ci) { LegacyRender.setSaving(true); }

    @Inject(method = "save", at = @At("RETURN"))
    private void kite$saveEnd(CallbackInfo ci) { LegacyRender.setSaving(false); }

    @Inject(method = "getSoundVolume", at = @At("RETURN"), cancellable = true)
    private void kite$volume(SoundCategory category, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(LegacyRender.volume(category, cir.getReturnValueF()));
    }
}
