package gg.aether.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import gg.aether.client.AetherClient;
import gg.aether.core.Events;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mod ticks, the Aether main menu, saving on exit, and the LWJGL 2 input loops (keys, clicks, scroll). */
@Mixin(MinecraftClient.class)
abstract class MinecraftClientMixin {
    @Shadow public Screen currentScreen;

    @Inject(method = "tick", at = @At("TAIL"))
    private void aether$tick(CallbackInfo ci) { AetherClient.tick((MinecraftClient) (Object) this); }

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void aether$mainMenu(Screen screen, CallbackInfo ci) {
        if (AetherClient.replaceTitleScreen(screen)) ci.cancel();
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void aether$stop(CallbackInfo ci) { AetherClient.save(); }

    /** Every keyboard event, in game and in screens (screens call it too). */
    @Inject(method = "handleKeyInput", at = @At("HEAD"))
    private void aether$key(CallbackInfo ci) {
        if (Keyboard.isRepeatEvent()) return;
        AetherClient.key((MinecraftClient) (Object) this, Keyboard.getEventKey(), Keyboard.getEventKeyState());
    }

    /** In-game mouse buttons (the loop only runs with no screen open): clicks for CPS. */
//#if >=1.12
    @ModifyExpressionValue(method = "tickMouse", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getEventButtonState()Z", ordinal = 0, remap = false))
//#else
    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getEventButtonState()Z", ordinal = 0, remap = false))
//#endif
    private boolean aether$click(boolean pressed) {
        int button = Mouse.getEventButton();
        if (pressed && button >= 0 && currentScreen == null) AetherClient.mods().events().post(new Events.Click(button, System.currentTimeMillis()));
        return pressed;
    }

    /** The wheel scrolls the hotbar; while zoomed it changes the zoom level instead. */
//#if >=1.12
    @ModifyExpressionValue(method = "tickMouse", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getEventDWheel()I", remap = false))
//#else
    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getEventDWheel()I", remap = false))
//#endif
    private int aether$scroll(int wheel) {
        if (wheel == 0 || currentScreen != null) return wheel;
        AetherClient.mods().events().post(new Events.Scroll(wheel > 0 ? 1f : -1f));
        return AetherClient.fx().fovMultiplier < 0.999f ? 0 : wheel;
    }
}
