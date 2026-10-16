package gg.kite.client.mixin;

import gg.kite.mc.McEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Item pickups and server time updates (TPS). Handlers run first on the network thread, then again on the client thread. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void kite$pickup(ClientboundTakeItemEntityPacket p, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) McEvents.pickup(mc, p.getItemId(), p.getPlayerId(), p.getAmount());
    }

//#if <1.20.2
    /** Server API before 1.20.2: custom payloads arrive as raw buffers here. */
    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void kite$payload(net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket p, CallbackInfo ci) {
        if (gg.kite.mc.McPayload.handle(p.getIdentifier(), p.getData())) ci.cancel();
    }

//#endif
    @Inject(method = "handleSetTime", at = @At("HEAD"))
    private void kite$time(ClientboundSetTimePacket p, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) McEvents.timeUpdate(p.gameTime());
    }
}
