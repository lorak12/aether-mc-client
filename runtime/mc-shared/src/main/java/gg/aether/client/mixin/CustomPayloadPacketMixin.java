//#if >=1.20.2 && <1.20.5
package gg.aether.client.mixin;

import gg.aether.mc.McPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Server API (1.20.2-1.20.4): read aether:client as raw bytes instead of a DiscardedPayload that drops them. */
@Mixin(ClientboundCustomPayloadPacket.class)
abstract class CustomPayloadPacketMixin {
    @Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
    private static void aether$read(Identifier id, FriendlyByteBuf buf, CallbackInfoReturnable<CustomPacketPayload> cir) {
        CustomPacketPayload p = McPayload.read(id, buf);
        if (p != null) cir.setReturnValue(p);
    }
}
//#endif
