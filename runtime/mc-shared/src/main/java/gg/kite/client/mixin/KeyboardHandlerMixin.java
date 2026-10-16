package gg.kite.client.mixin;

import gg.kite.client.KiteClient;
//#if >=26.3
import gg.kite.client.SdlInput;
//#endif
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
//#if >=1.21.9
import net.minecraft.client.input.KeyEvent;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds raw GLFW key events to mod hotkeys and opens the menu (Right Shift); container search typing. */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
//#if >=1.21.9
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void kite$key(long window, int action, KeyEvent event, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        // Container search box: typing must not close the screen or move items.
//#if >=26.3
        if (action != 0 && gg.kite.mc.McItems.key(mc.gui.screen(), gg.kite.client.SdlInput.toGlfw(event.key()))) {
//#elif >=26.2
        if (action != 0 && gg.kite.mc.McItems.key(mc.gui.screen(), event.key())) {
//#else
        if (action != 0 && gg.kite.mc.McItems.key(mc.screen, event.key())) {
//#endif
            ci.cancel();
            return;
        }
//#if >=26.3
        if (KiteClient.key(Minecraft.getInstance(), SdlInput.toGlfw(event.key()), action)) ci.cancel();
//#else
        if (KiteClient.key(Minecraft.getInstance(), event.key(), action)) ci.cancel();
//#endif
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void kite$char(long window, net.minecraft.client.input.CharacterEvent event, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
//#if >=26.2
        if (gg.kite.mc.McItems.character(mc.gui.screen(), event.codepoint())) ci.cancel();
//#else
        if (gg.kite.mc.McItems.character(mc.screen, event.codepoint())) ci.cancel();
//#endif
    }
//#else
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void kite$key(long window, int key, int scancode, int action, int mods, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        // Container search box: typing must not close the screen or move items.
        if (action != 0 && gg.kite.mc.McItems.key(mc.screen, key)) {
            ci.cancel();
            return;
        }
        if (KiteClient.key(mc, key, action)) ci.cancel();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void kite$char(long window, int codepoint, int mods, CallbackInfo ci) {
        if (gg.kite.mc.McItems.character(Minecraft.getInstance().screen, codepoint)) ci.cancel();
    }
//#endif
}
