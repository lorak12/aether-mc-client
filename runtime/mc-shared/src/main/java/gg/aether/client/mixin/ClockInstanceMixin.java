//#if >=26.1
package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//#if >=26.3
/** Time changer: the client's world clocks report the chosen time of day, keeping the day count (moon phase). */
@Mixin(targets = "net.minecraft.client.ClientClockManager$ClientClockInstance")
abstract class ClockInstanceMixin {
    @Inject(method = "totalTicks", at = @At("RETURN"), cancellable = true)
    private void aether$time(CallbackInfoReturnable<Long> cir) {
        Integer tod = AetherClient.fx().timeOfDay;
        if (tod == null) return;
        long t = cir.getReturnValue();
        cir.setReturnValue(t - Math.floorMod(t, 24000L) + tod);
    }

    @Inject(method = "partialTick", at = @At("RETURN"), cancellable = true)
    private void aether$still(CallbackInfoReturnable<Float> cir) {
        if (AetherClient.fx().timeOfDay != null) cir.setReturnValue(0f);
    }
}
//#else
/** Time changer: 26.1-26.2 clocks are plain fields, read through ClientClockManager.getTotalTicks. */
@Mixin(net.minecraft.client.ClientClockManager.class)
abstract class ClockInstanceMixin {
    @Inject(method = "getTotalTicks", at = @At("RETURN"), cancellable = true)
    private void aether$time(net.minecraft.core.Holder<net.minecraft.world.clock.WorldClock> clock, CallbackInfoReturnable<Long> cir) {
        Integer tod = AetherClient.fx().timeOfDay;
        if (tod == null) return;
        long t = cir.getReturnValue();
        cir.setReturnValue(t - Math.floorMod(t, 24000L) + tod);
    }
}
//#endif
//#endif
