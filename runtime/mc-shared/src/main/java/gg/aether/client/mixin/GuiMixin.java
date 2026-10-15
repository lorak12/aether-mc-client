//#if >=1.20.5 && <26.1
package gg.aether.client.mixin;

import gg.aether.core.Events;
import gg.aether.client.AetherClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Aether HUD in the vanilla HUD pass and hides vanilla pieces that mods replace. */
@Mixin(Gui.class)
abstract class GuiMixin {
    /** Screenshot mode: no vanilla HUD at all (our own still draws unless it is hidden too). */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void aether$hideAll(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (!AetherClient.fx().hideVanillaHud) return;
        AetherClient.renderHud(g);
        ci.cancel();
    }

    /** Just below chat, and only when F1 has not hidden the GUI. */
    @Inject(method = "renderChat", at = @At("HEAD"))
    private void aether$hud(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) { AetherClient.renderHud(g); }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void aether$crosshair(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaCrosshair) ci.cancel();
    }

    @Inject(method = "renderOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void aether$actionBar(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaActionBar) ci.cancel();
    }

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void aether$actionBarText(Component text, boolean animate, CallbackInfo ci) {
        AetherClient.mods().events().post(new Events.ActionBar(text.getString(), System.currentTimeMillis()));
    }

//#if >=1.21.6
    @Inject(method = "renderBossOverlay", at = @At("HEAD"), cancellable = true)
    private void aether$hideBossOverlay(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaBossBar) ci.cancel();
    }
//#endif

    @Inject(method = "renderTitle", at = @At("HEAD"), cancellable = true)
    private void aether$hideTitle(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaTitle) ci.cancel();
    }

    @Inject(method = "renderScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void aether$hideScoreboardSidebar(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaScoreboard) ci.cancel();
    }

    @Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
    private void aether$hideEffects(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaEffects) ci.cancel();
    }

    @Inject(method = "renderItemHotbar", at = @At("HEAD"), cancellable = true)
    private void aether$hideItemHotbar(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaHotbar) ci.cancel();
    }

    @Inject(method = "renderTabList", at = @At("HEAD"), cancellable = true)
    private void aether$hideTabList(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaTabList) ci.cancel();
    }
}
//#endif
