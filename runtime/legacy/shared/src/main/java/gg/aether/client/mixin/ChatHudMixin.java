package gg.aether.client.mixin;

import gg.aether.mc.McEvents;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chat events; mods can drop lines. */
@Mixin(ChatHud.class)
abstract class ChatHudMixin {
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), cancellable = true)
    private void aether$chat(Text message, CallbackInfo ci) {
        if (message != null && !McEvents.chat(message.asUnformattedString(), false)) ci.cancel();
    }
}
