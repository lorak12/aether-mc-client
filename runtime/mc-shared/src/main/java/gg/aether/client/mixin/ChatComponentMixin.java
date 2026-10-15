package gg.aether.client.mixin;

import gg.aether.mc.McEvents;
import java.util.List;
//#if <26.1
import net.minecraft.client.GuiMessage;
//#if >=1.19.1
import net.minecraft.client.GuiMessageTag;
//#endif
//#endif
import net.minecraft.client.gui.Font;
//#if >=26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#elif >=1.20
import net.minecraft.client.gui.GuiGraphics;
//#endif
import net.minecraft.client.gui.components.ChatComponent;
//#if >=26.1
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
//#endif
import net.minecraft.network.chat.Component;
//#if >=1.19.1
import net.minecraft.network.chat.MessageSignature;
//#endif
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat events plus the Chat mod: dropped lines, timestamps, name replacement, "(xN)" stacking, mention colour bars,
 * chat tabs, longer history and hiding the chat.
 */
@Mixin(ChatComponent.class)
abstract class ChatComponentMixin {
    @Shadow @Final private List<GuiMessage> allMessages;
    @Shadow private void refreshTrimmedMessages() { throw new AssertionError(); }
//#if >=26.1
    @Shadow private void addMessage(Component msg, MessageSignature sig, GuiMessageSource source, GuiMessageTag tag) { throw new AssertionError(); }
//#elif >=1.19.1
    @Shadow public abstract void addMessage(Component msg, MessageSignature sig, GuiMessageTag tag);
//#else
    @Shadow public abstract void addMessage(Component msg);
//#endif
    @Shadow public abstract boolean isChatFocused();

    @Unique private static boolean aether$adding;

//#if >=26.1
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V", at = @At("HEAD"), cancellable = true)
    private void aether$chat(Component msg, MessageSignature sig, GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
//#elif >=1.19.1
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V", at = @At("HEAD"), cancellable = true)
    private void aether$chat(Component msg, MessageSignature sig, GuiMessageTag tag, CallbackInfo ci) {
//#else
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true)
    private void aether$chat(Component msg, CallbackInfo ci) {
//#endif
        if (aether$adding) return;
//#if >=26.1
        McEvents.ChatResult r = McEvents.receive(msg, source != GuiMessageSource.PLAYER);
//#elif >=1.19.1
        McEvents.ChatResult r = McEvents.receive(msg, sig == null);
//#else
        McEvents.ChatResult r = McEvents.receive(msg, false);
//#endif
        if (r == null) {
            ci.cancel();
            return;
        }
        if (r.message == msg && r.highlight == null) return;
        ci.cancel();
        if (r.replacePrevious && !allMessages.isEmpty()) {
            allMessages.remove(0);
            refreshTrimmedMessages();
        }
//#if >=1.19.1
        GuiMessageTag t = r.highlight == null ? tag
                : new GuiMessageTag(r.highlight, tag == null ? null : tag.icon(), tag == null ? null : tag.text(), tag == null ? "Aether" : tag.logTag());
//#endif
        aether$adding = true;
        try {
//#if >=26.1
            addMessage(r.message, sig, source, t);
//#elif >=1.19.1
            addMessage(r.message, sig, t);
//#else
            addMessage(r.message);
//#endif
        } finally {
            aether$adding = false;
        }
    }

//#if >=1.20.5
    /** Chat tabs: lines outside the current tab stay in history but are not displayed. */
    @Inject(method = "addMessageToDisplayQueue", at = @At("HEAD"), cancellable = true)
    private void aether$tab(GuiMessage m, CallbackInfo ci) {
        if (!McEvents.chatVisible(m.content())) ci.cancel();
    }

    @ModifyConstant(method = { "addMessageToQueue", "addMessageToDisplayQueue" }, constant = @Constant(intValue = 100))
    private int aether$history(int vanilla) { return McEvents.chatHistory(vanilla); }
//#else
    // Before 1.20.5 one private addMessage both wraps the message into display lines and keeps the history.
//#if >=1.19.1
    private static final String ADD = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V";
//#else
    private static final String ADD = "addMessage(Lnet/minecraft/network/chat/Component;IIZ)V";
//#endif

    /** Chat tabs: lines outside the current tab stay in history but are not displayed (wrapped into no lines). */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = ADD, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/ComponentRenderUtils;wrapComponents(Lnet/minecraft/network/chat/FormattedText;ILnet/minecraft/client/gui/Font;)Ljava/util/List;"))
    private List<net.minecraft.util.FormattedCharSequence> aether$tab(net.minecraft.network.chat.FormattedText text, int width, Font font,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<List<net.minecraft.util.FormattedCharSequence>> original) {
        if (text instanceof Component && !McEvents.chatVisible((Component) text)) return new java.util.ArrayList<net.minecraft.util.FormattedCharSequence>();
        return original.call(text, width, font);
    }

    @ModifyConstant(method = ADD, constant = @Constant(intValue = 100))
    private int aether$history(int vanilla) { return McEvents.chatHistory(vanilla); }
//#endif

//#if >=26.1
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V", at = @At("HEAD"), cancellable = true)
    private void aether$hide(GuiGraphicsExtractor g, Font font, int tick, int mx, int my, ChatComponent.DisplayMode mode, boolean b, CallbackInfo ci) {
//#else
    // Every overload: the signature changes between versions and all of them draw the chat.
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void aether$hide(CallbackInfo ci) {
//#endif
        if (McEvents.hideChat() && !isChatFocused()) ci.cancel();
    }
}
