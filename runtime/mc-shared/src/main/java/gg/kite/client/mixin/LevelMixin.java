package gg.kite.client.mixin;

import gg.kite.core.Overrides;
import gg.kite.client.KiteClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Weather changer, client level only (the integrated server's level is untouched). */
@Mixin(Level.class)
abstract class LevelMixin {
    @Inject(method = "getRainLevel", at = @At("RETURN"), cancellable = true)
    private void kite$rain(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof ClientLevel)) return;
        Overrides.Weather w = KiteClient.fx().weather;
        if (w == Overrides.Weather.VANILLA) return;
        cir.setReturnValue(w == Overrides.Weather.CLEAR ? 0f : KiteClient.fx().weatherIntensity);
    }

    @Inject(method = "getThunderLevel", at = @At("RETURN"), cancellable = true)
    private void kite$thunder(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof ClientLevel)) return;
        Overrides.Weather w = KiteClient.fx().weather;
        if (w == Overrides.Weather.VANILLA) return;
        cir.setReturnValue(w == Overrides.Weather.THUNDER ? 1f : 0f);
    }
}
