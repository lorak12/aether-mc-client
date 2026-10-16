package gg.kite.client.mixin;

import gg.kite.client.KiteClient;
import gg.kite.mc.McEvents;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//#if >=1.8
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
//#endif
//#if <1.8
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//#endif

/** Draws the Kite HUD in the vanilla HUD pass and hides vanilla pieces that mods replace. */
@Mixin(InGameHud.class)
abstract class InGameHudMixin {
    /** Screenshot mode: no vanilla HUD at all (our own still draws unless it is hidden too). */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
//#if >=1.8
    private void kite$hideAll(float tickDelta, CallbackInfo ci) {
//#else
    private void kite$hideAll(float tickDelta, boolean screenOpen, int mouseX, int mouseY, CallbackInfo ci) {
//#endif
        if (!KiteClient.fx().hideVanillaHud) return;
        KiteClient.renderHud();
        ci.cancel();
    }

    /** Just below chat. */
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/ChatHud;render(I)V"))
//#if >=1.8
    private void kite$hud(float tickDelta, CallbackInfo ci) { KiteClient.renderHud(); }
//#else
    private void kite$hud(float tickDelta, boolean screenOpen, int mouseX, int mouseY, CallbackInfo ci) { KiteClient.renderHud(); }
//#endif

//#if >=1.12
    /** The crosshair (and attack indicator). */
    @Inject(method = "method_12164", at = @At("HEAD"), cancellable = true)
    private void kite$crosshair(CallbackInfo ci) { if (KiteClient.fx().hideVanillaCrosshair) ci.cancel(); }

    /** Status effect icons (top right, 1.9+). */
    @Inject(method = "method_12165", at = @At("HEAD"), cancellable = true)
    private void kite$effects(CallbackInfo ci) { if (KiteClient.fx().hideVanillaEffects) ci.cancel(); }
//#elif >=1.8
    @Inject(method = "showCrosshair", at = @At("RETURN"), cancellable = true)
    private void kite$crosshair(CallbackInfoReturnable<Boolean> cir) {
        if (KiteClient.fx().hideVanillaCrosshair) cir.setReturnValue(false);
    }

    @Inject(method = "renderBossBar", at = @At("HEAD"), cancellable = true)
    private void kite$bossBar(CallbackInfo ci) { if (KiteClient.fx().hideVanillaBossBar) ci.cancel(); }
//#endif

//#if >=1.8
    @Inject(method = "renderScoreboardObjective", at = @At("HEAD"), cancellable = true)
    private void kite$scoreboard(CallbackInfo ci) { if (KiteClient.fx().hideVanillaScoreboard) ci.cancel(); }

    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void kite$hotbar(CallbackInfo ci) { if (KiteClient.fx().hideVanillaHotbar) ci.cancel(); }
//#else
    // 1.7.10 draws most of the HUD inside render(): the sidebar has its own method, the hotbar frame and crosshair are
    // the first three drawTexture calls of the pass (frame, selected slot, crosshair).
    @Inject(method = "method_4943", at = @At("HEAD"), cancellable = true)
    private void kite$scoreboard(CallbackInfo ci) { if (KiteClient.fx().hideVanillaScoreboard) ci.cancel(); }

    @Inject(method = "renderActionBar", at = @At("HEAD"), cancellable = true)
    private void kite$hotbarItem(CallbackInfo ci) { if (KiteClient.fx().hideVanillaHotbar) ci.cancel(); }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTexture(IIIIII)V", ordinal = 0))
    private void kite$hotbarFrame(InGameHud hud, int x, int y, int u, int v, int w, int h, Operation<Void> original) {
        if (!KiteClient.fx().hideVanillaHotbar) original.call(hud, x, y, u, v, w, h);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTexture(IIIIII)V", ordinal = 1))
    private void kite$hotbarSlot(InGameHud hud, int x, int y, int u, int v, int w, int h, Operation<Void> original) {
        if (!KiteClient.fx().hideVanillaHotbar) original.call(hud, x, y, u, v, w, h);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTexture(IIIIII)V", ordinal = 2))
    private void kite$crosshair(InGameHud hud, int x, int y, int u, int v, int w, int h, Operation<Void> original) {
        if (!KiteClient.fx().hideVanillaCrosshair) original.call(hud, x, y, u, v, w, h);
    }
//#endif

//#if >=1.8
    /** Title and subtitle: every read of the remaining time is 0 while a mod draws them itself. */
    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/hud/InGameHud;titleTotalTicks:I"))
    private int kite$title(int ticks) { return KiteClient.fx().hideVanillaTitle ? 0 : ticks; }
//#endif

    @Inject(method = "setOverlayMessage(Ljava/lang/String;Z)V", at = @At("HEAD"), cancellable = true)
    private void kite$actionBar(String text, boolean tinted, CallbackInfo ci) {
        McEvents.actionBar(text);
        if (KiteClient.fx().hideVanillaActionBar) ci.cancel();
    }
}
