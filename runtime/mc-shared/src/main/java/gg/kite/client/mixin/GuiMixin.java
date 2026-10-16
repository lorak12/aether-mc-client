//#if >=1.20.5 && <26.1
package gg.kite.client.mixin;

import gg.kite.core.Events;
import gg.kite.client.KiteClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Kite HUD in the vanilla HUD pass and hides vanilla pieces that mods replace. */
@Mixin(Gui.class)
abstract class GuiMixin {
    /** Screenshot mode: no vanilla HUD at all (our own still draws unless it is hidden too). */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void kite$hideAll(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (!KiteClient.fx().hideVanillaHud) return;
        KiteClient.renderHud(g);
        ci.cancel();
    }

    /** Just below chat, and only when F1 has not hidden the GUI. */
    @Inject(method = "renderChat", at = @At("HEAD"))
    private void kite$hud(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) { KiteClient.renderHud(g); }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void kite$crosshair(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (KiteClient.fx().hideVanillaCrosshair) ci.cancel();
    }

    @Inject(method = "renderOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void kite$actionBar(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (KiteClient.fx().hideVanillaActionBar) ci.cancel();
    }

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void kite$actionBarText(Component text, boolean animate, CallbackInfo ci) {
        KiteClient.mods().events().post(new Events.ActionBar(text.getString(), System.currentTimeMillis()));
    }

//#if >=1.21.6
    @Inject(method = "renderBossOverlay", at = @At("HEAD"), cancellable = true)
    private void kite$hideBossOverlay(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (KiteClient.fx().hideVanillaBossBar) ci.cancel();
    }
//#endif

    @Inject(method = "renderTitle", at = @At("HEAD"), cancellable = true)
    private void kite$hideTitle(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (KiteClient.fx().hideVanillaTitle) ci.cancel();
    }

    @Inject(method = "renderScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void kite$hideScoreboardSidebar(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (KiteClient.fx().hideVanillaScoreboard) ci.cancel();
    }

    @Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
    private void kite$hideEffects(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (KiteClient.fx().hideVanillaEffects) ci.cancel();
    }

    @Inject(method = "renderItemHotbar", at = @At("HEAD"), cancellable = true)
    private void kite$hideItemHotbar(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (KiteClient.fx().hideVanillaHotbar) ci.cancel();
    }

    @Inject(method = "renderTabList", at = @At("HEAD"), cancellable = true)
    private void kite$hideTabList(GuiGraphics g, DeltaTracker dt, CallbackInfo ci) {
        if (KiteClient.fx().hideVanillaTabList) ci.cancel();
    }
}
//#endif
