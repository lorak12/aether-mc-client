package gg.kite.client.mixin;

import gg.kite.client.KiteClient;
import gg.kite.core.Overrides;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Weather and time changers, client world only (the integrated server's world is untouched). */
@Mixin(World.class)
abstract class WorldMixin {
    @Inject(method = "getRainGradient", at = @At("RETURN"), cancellable = true)
    private void kite$rain(float delta, CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof ClientWorld)) return;
        Overrides.Weather w = KiteClient.fx().weather;
        if (w != Overrides.Weather.VANILLA) cir.setReturnValue(w == Overrides.Weather.CLEAR ? 0f : KiteClient.fx().weatherIntensity);
    }

    @Inject(method = "getThunderGradient", at = @At("RETURN"), cancellable = true)
    private void kite$thunder(float delta, CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof ClientWorld)) return;
        Overrides.Weather w = KiteClient.fx().weather;
        if (w != Overrides.Weather.VANILLA) cir.setReturnValue(w == Overrides.Weather.THUNDER ? 1f : 0f);
    }

    /** The sky angle (sun, moon, light) from the client-side time of day, keeping the day count. */
    @ModifyArg(method = "getSkyAngle", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/dimension/Dimension;getSkyAngle(JF)F"), index = 0)
    private long kite$time(long time) {
        Integer tod = KiteClient.fx().timeOfDay;
        if (tod == null || !((Object) this instanceof ClientWorld)) return time;
        return time - Math.floorMod(time, 24000L) + tod;
    }
}
