//#if >=26.1
package gg.aether.client.mixin;

import gg.aether.core.Events;
import gg.aether.client.AetherClient;
import net.minecraft.client.DeltaTracker;
//#if >=26.2
import net.minecraft.client.gui.Hud;
//#else
import net.minecraft.client.gui.Gui;
//#endif
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Aether HUD in the vanilla HUD pass and hides vanilla pieces that mods replace. */
//#if >=26.2
@Mixin(Hud.class)
//#else
@Mixin(Gui.class)
//#endif
abstract class HudMixin {
    /** Screenshot mode: no vanilla HUD at all (our own still draws unless it is hidden too). */
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void aether$hideAll(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (!AetherClient.fx().hideVanillaHud) return;
        AetherClient.renderHud(g);
        ci.cancel();
    }

    /** Just below chat, and only when F1 has not hidden the GUI. */
    @Inject(method = "extractChat", at = @At("HEAD"))
    private void aether$hud(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) { AetherClient.renderHud(g); }

    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void aether$crosshair(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaCrosshair) ci.cancel();
    }

    @Inject(method = "extractOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void aether$actionBar(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaActionBar) ci.cancel();
    }

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void aether$actionBarText(Component text, boolean animate, CallbackInfo ci) {
        AetherClient.mods().events().post(new Events.ActionBar(text.getString(), System.currentTimeMillis()));
    }

    @Inject(method = "extractBossOverlay", at = @At("HEAD"), cancellable = true)
    private void aether$hideBossOverlay(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaBossBar) ci.cancel();
    }

    @Inject(method = "extractTitle", at = @At("HEAD"), cancellable = true)
    private void aether$hideTitle(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaTitle) ci.cancel();
    }

    @Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void aether$hideScoreboardSidebar(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaScoreboard) ci.cancel();
    }

    @Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
    private void aether$hideEffects(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaEffects) ci.cancel();
    }

    @Inject(method = "extractItemHotbar", at = @At("HEAD"), cancellable = true)
    private void aether$hideItemHotbar(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaHotbar) ci.cancel();
    }

    @Inject(method = "extractTabList", at = @At("HEAD"), cancellable = true)
    private void aether$hideTabList(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaTabList) ci.cancel();
    }
}
//#endif
