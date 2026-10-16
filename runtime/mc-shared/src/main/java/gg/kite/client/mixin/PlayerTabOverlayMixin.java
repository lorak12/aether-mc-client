package gg.kite.client.mixin;

import gg.kite.mc.McRender;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Name replacements (streamer mode, friend nicknames) and the client badge in the vanilla player list. */
@Mixin(PlayerTabOverlay.class)
abstract class PlayerTabOverlayMixin {
    @Inject(method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;", at = @At("RETURN"), cancellable = true)
    private void kite$name(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(McRender.tabName(info, cir.getReturnValue()));
    }
}
