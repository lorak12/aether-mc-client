//#if >=1.20.2
package gg.aether.client.mixin;

import gg.aether.mc.McPayload;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server API: aether:client messages go to the mods instead of the "unknown payload" path. */
@Mixin(ClientCommonPacketListenerImpl.class)
abstract class ClientCommonPacketListenerImplMixin {
    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V", at = @At("HEAD"), cancellable = true)
    private void aether$payload(ClientboundCustomPayloadPacket p, CallbackInfo ci) {
        if (McPayload.handle(p.payload())) ci.cancel();
    }
}
//#endif
