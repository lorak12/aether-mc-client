//#if <26.1
package gg.kite.client.mixin;

import gg.kite.client.KiteClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Time changer: client-side time of day, keeping the day count (moon phase). */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientLevel$ClientLevelData")
abstract class ClientLevelDataMixin {
    @Inject(method = "getDayTime", at = @At("RETURN"), cancellable = true)
    private void kite$time(CallbackInfoReturnable<Long> cir) {
        Integer tod = KiteClient.fx().timeOfDay;
        if (tod == null) return;
        long t = cir.getReturnValue();
        cir.setReturnValue(t - Math.floorMod(t, 24000L) + tod);
    }
}
//#endif
