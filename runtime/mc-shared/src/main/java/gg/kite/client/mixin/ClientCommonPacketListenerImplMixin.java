//#if >=1.20.2
package gg.kite.client.mixin;

import gg.kite.mc.McPayload;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server API: kite:client messages go to the mods instead of the "unknown payload" path. */
@Mixin(ClientCommonPacketListenerImpl.class)
abstract class ClientCommonPacketListenerImplMixin {
    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V", at = @At("HEAD"), cancellable = true)
    private void kite$payload(ClientboundCustomPayloadPacket p, CallbackInfo ci) {
        if (McPayload.handle(p.payload())) ci.cancel();
    }
}
//#endif
