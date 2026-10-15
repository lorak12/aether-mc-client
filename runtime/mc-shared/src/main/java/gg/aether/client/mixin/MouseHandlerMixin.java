package gg.aether.client.mixin;

import gg.aether.core.Events;
import gg.aether.core.Overrides;
import gg.aether.client.AetherClient;
//#if >=26.3
import gg.aether.client.SdlInput;
//#endif
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
//#if >=1.21.9
import net.minecraft.client.input.MouseButtonInfo;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clicks (CPS), scroll (zoom level), and mouse look (freelook, zoom sensitivity). */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

//#if >=1.21.9
    @Inject(method = "onButton", at = @At("HEAD"))
    private void aether$click(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
//#if >=26.3
        int button = SdlInput.toCoreButton(info.button());
        if (action == 1 && button >= 0 && Minecraft.getInstance().gui.screen() == null) {
            AetherClient.mods().events().post(new Events.Click(button, System.currentTimeMillis()));
//#elif >=26.2
        if (action == 1 && Minecraft.getInstance().gui.screen() == null) {
            AetherClient.mods().events().post(new Events.Click(info.button(), System.currentTimeMillis()));
//#else
        if (action == 1 && Minecraft.getInstance().screen == null) {
            AetherClient.mods().events().post(new Events.Click(info.button(), System.currentTimeMillis()));
//#endif
        }
    }
//#else
    @Inject(method = "onPress", at = @At("HEAD"))
    private void aether$click(long window, int button, int action, int mods, CallbackInfo ci) {
        if (action == 1 && Minecraft.getInstance().screen == null) {
            AetherClient.mods().events().post(new Events.Click(button, System.currentTimeMillis()));
        }
    }
//#endif

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void aether$scroll(long window, double dx, double dy, CallbackInfo ci) {
//#if >=26.2
        if (Minecraft.getInstance().gui.screen() != null || dy == 0) return;
//#else
        if (Minecraft.getInstance().screen != null || dy == 0) return;
//#endif
        AetherClient.mods().events().post(new Events.Scroll((float) dy));
        // While zoomed the wheel changes the zoom level, not the hotbar slot.
        if (AetherClient.fx().fovMultiplier < 0.999f) ci.cancel();
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"))
//#if >=1.20.5
    private void aether$turn(double movementTime, CallbackInfo ci) {
//#else
    private void aether$turn(CallbackInfo ci) {
//#endif
//#if >=26.2
        if (Minecraft.getInstance().gui.screen() != null) return;
//#else
        if (Minecraft.getInstance().screen != null) return;
//#endif
        if (accumulatedDX != 0 || accumulatedDY != 0) {
            AetherClient.mods().events().post(new Events.MouseMove((float) accumulatedDX, (float) accumulatedDY));
        }
        Overrides fx = AetherClient.fx();
        if (fx.freelook) {
            // The camera turns (see CameraMixin), the player does not.
            accumulatedDX = 0;
            accumulatedDY = 0;
        } else if (fx.sensitivityMultiplier != 1f) {
            accumulatedDX *= fx.sensitivityMultiplier;
            accumulatedDY *= fx.sensitivityMultiplier;
        }
    }
}
