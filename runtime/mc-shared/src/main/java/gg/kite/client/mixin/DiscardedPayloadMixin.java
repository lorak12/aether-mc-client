//#if >=1.20.5
package gg.kite.client.mixin;

import gg.kite.mc.McPayload;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Server API: keep the bytes of kite:client (and write minecraft:register) instead of discarding them. */
@Mixin(DiscardedPayload.class)
abstract class DiscardedPayloadMixin {
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Inject(method = "codec(Lnet/minecraft/resources/Identifier;I)Lnet/minecraft/network/codec/StreamCodec;", at = @At("HEAD"), cancellable = true)
    private static void kite$codec(Identifier id, int max, CallbackInfoReturnable<StreamCodec> cir) {
        StreamCodec c = McPayload.codecFor(id);
        if (c != null) cir.setReturnValue(c);
    }
}
//#endif
