package gg.aether.client.mixin;

import gg.aether.mc.LegacyPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server plugin messages on the aether:client channel. */
@Mixin(ClientPlayNetworkHandler.class)
abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onCustomPayload", at = @At("HEAD"), cancellable = true)
    private void aether$payload(CustomPayloadS2CPacket packet, CallbackInfo ci) {
//#if >=1.8
        // Packets arrive on the network thread first and are re-run on the client thread by vanilla: handle that run only.
        if (!MinecraftClient.getInstance().isOnThread()) return;
//#endif
        if (LegacyPayload.handle(packet)) ci.cancel();
    }
}
