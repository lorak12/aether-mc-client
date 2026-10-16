//#if >=26.1
package gg.kite.client.mixin;

import gg.kite.core.Events;
import gg.kite.client.KiteClient;
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

/** Draws the Kite HUD in the vanilla HUD pass and hides vanilla pieces that mods replace. */
//#if >=26.2
@Mixin(Hud.class)
//#else
@Mixin(Gui.class)
//#endif
abstract class HudMixin {
    /** Screenshot mode: no vanilla HUD at all (our own still draws unless it is hidden too). */
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void kite$hideAll(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (!KiteClient.fx().hideVanillaHud) return;
        KiteClient.renderHud(g);
        ci.cancel();
    }

    /** Just below chat, and only when F1 has not hidden the GUI. */
    @Inject(method = "extractChat", at = @At("HEAD"))
    private void kite$hud(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) { KiteClient.renderHud(g); }

    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void kite$crosshair(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (KiteClient.fx().hideVanillaCrosshair) ci.cancel();
    }

    @Inject(method = "extractOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void kite$actionBar(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (KiteClient.fx().hideVanillaActionBar) ci.cancel();
    }

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void kite$actionBarText(Component text, boolean animate, CallbackInfo ci) {
        KiteClient.mods().events().post(new Events.ActionBar(text.getString(), System.currentTimeMillis()));
    }

    @Inject(method = "extractBossOverlay", at = @At("HEAD"), cancellable = true)
    private void kite$hideBossOverlay(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (KiteClient.fx().hideVanillaBossBar) ci.cancel();
    }

    @Inject(method = "extractTitle", at = @At("HEAD"), cancellable = true)
    private void kite$hideTitle(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (KiteClient.fx().hideVanillaTitle) ci.cancel();
    }

    @Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void kite$hideScoreboardSidebar(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (KiteClient.fx().hideVanillaScoreboard) ci.cancel();
    }

    @Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
    private void kite$hideEffects(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (KiteClient.fx().hideVanillaEffects) ci.cancel();
    }

    @Inject(method = "extractItemHotbar", at = @At("HEAD"), cancellable = true)
    private void kite$hideItemHotbar(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (KiteClient.fx().hideVanillaHotbar) ci.cancel();
    }

    @Inject(method = "extractTabList", at = @At("HEAD"), cancellable = true)
    private void kite$hideTabList(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        if (KiteClient.fx().hideVanillaTabList) ci.cancel();
    }
}
//#endif
