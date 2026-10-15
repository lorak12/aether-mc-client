package gg.aether.client.mixin;

import gg.aether.mc.McEvents;
import java.util.List;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat events plus the Chat mod: dropped lines, timestamps, name replacement, "(xN)" stacking, chat tabs, longer
 * history and hiding the chat.
 */
@Mixin(ChatHud.class)
abstract class ChatHudMixin {
    private static final String ADD = "addMessage(Lnet/minecraft/text/Text;IIZ)V";

    @Shadow public abstract void addMessage(Text message, int id);
    @Shadow public abstract void removeMessage(int id);
    @Shadow public abstract boolean isChatFocused();

    @Unique private boolean aether$hide;

    /** Every line from the server: events first, then the Chat mod's rebuilt line goes in instead of the original. */
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), cancellable = true)
    private void aether$chat(Text message, CallbackInfo ci) {
        if (message == null) return;
        McEvents.ChatResult r = McEvents.receive(message, false);
        if (r == null) {
            ci.cancel();
            return;
        }
        if (r.message == message && r.id == 0) return;
        ci.cancel();
        if (r.removeId != 0) removeMessage(r.removeId);
        addMessage(r.message, r.id);
    }

    /** Chat tabs: lines outside the current tab stay in history but are not displayed. */
    @Inject(method = ADD, at = @At("HEAD"))
    private void aether$tab(Text message, int id, int ticks, boolean ignoreLimit, CallbackInfo ci) {
        aether$hide = !McEvents.chatVisible(message);
    }

    @Redirect(method = ADD, at = @At(value = "INVOKE", target = "Ljava/util/List;add(ILjava/lang/Object;)V", ordinal = 0))
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void aether$visible(List list, int index, Object line) {
        if (!aether$hide) list.add(index, line);
    }

    @ModifyConstant(method = ADD, constant = @Constant(intValue = 100))
    private int aether$history(int vanilla) { return McEvents.chatHistory(vanilla); }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void aether$hide(int ticks, CallbackInfo ci) {
        if (McEvents.hideChat() && !isChatFocused()) ci.cancel();
    }
}
