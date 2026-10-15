package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import gg.aether.mc.McEvents;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//#if <1.8
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//#endif

/** Draws the Aether HUD in the vanilla HUD pass and hides vanilla pieces that mods replace. */
@Mixin(InGameHud.class)
abstract class InGameHudMixin {
    /** Screenshot mode: no vanilla HUD at all (our own still draws unless it is hidden too). */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
//#if >=1.8
    private void aether$hideAll(float tickDelta, CallbackInfo ci) {
//#else
    private void aether$hideAll(float tickDelta, boolean screenOpen, int mouseX, int mouseY, CallbackInfo ci) {
//#endif
        if (!AetherClient.fx().hideVanillaHud) return;
        AetherClient.renderHud();
        ci.cancel();
    }

    /** Just below chat. */
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/ChatHud;render(I)V"))
//#if >=1.8
    private void aether$hud(float tickDelta, CallbackInfo ci) { AetherClient.renderHud(); }
//#else
    private void aether$hud(float tickDelta, boolean screenOpen, int mouseX, int mouseY, CallbackInfo ci) { AetherClient.renderHud(); }
//#endif

//#if >=1.12
    /** The crosshair (and attack indicator). */
    @Inject(method = "method_12164", at = @At("HEAD"), cancellable = true)
    private void aether$crosshair(CallbackInfo ci) { if (AetherClient.fx().hideVanillaCrosshair) ci.cancel(); }

    /** Status effect icons (top right, 1.9+). */
    @Inject(method = "method_12165", at = @At("HEAD"), cancellable = true)
    private void aether$effects(CallbackInfo ci) { if (AetherClient.fx().hideVanillaEffects) ci.cancel(); }
//#elif >=1.8
    @Inject(method = "showCrosshair", at = @At("RETURN"), cancellable = true)
    private void aether$crosshair(CallbackInfoReturnable<Boolean> cir) {
        if (AetherClient.fx().hideVanillaCrosshair) cir.setReturnValue(false);
    }

    @Inject(method = "renderBossBar", at = @At("HEAD"), cancellable = true)
    private void aether$bossBar(CallbackInfo ci) { if (AetherClient.fx().hideVanillaBossBar) ci.cancel(); }
//#endif

//#if >=1.8
    @Inject(method = "renderScoreboardObjective", at = @At("HEAD"), cancellable = true)
    private void aether$scoreboard(CallbackInfo ci) { if (AetherClient.fx().hideVanillaScoreboard) ci.cancel(); }

    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void aether$hotbar(CallbackInfo ci) { if (AetherClient.fx().hideVanillaHotbar) ci.cancel(); }
//#else
    // 1.7.10 draws most of the HUD inside render(): the sidebar has its own method, the hotbar frame and crosshair are
    // the first three drawTexture calls of the pass (frame, selected slot, crosshair).
    @Inject(method = "method_4943", at = @At("HEAD"), cancellable = true)
    private void aether$scoreboard(CallbackInfo ci) { if (AetherClient.fx().hideVanillaScoreboard) ci.cancel(); }

    @Inject(method = "renderActionBar", at = @At("HEAD"), cancellable = true)
    private void aether$hotbarItem(CallbackInfo ci) { if (AetherClient.fx().hideVanillaHotbar) ci.cancel(); }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTexture(IIIIII)V", ordinal = 0))
    private void aether$hotbarFrame(InGameHud hud, int x, int y, int u, int v, int w, int h, Operation<Void> original) {
        if (!AetherClient.fx().hideVanillaHotbar) original.call(hud, x, y, u, v, w, h);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTexture(IIIIII)V", ordinal = 1))
    private void aether$hotbarSlot(InGameHud hud, int x, int y, int u, int v, int w, int h, Operation<Void> original) {
        if (!AetherClient.fx().hideVanillaHotbar) original.call(hud, x, y, u, v, w, h);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTexture(IIIIII)V", ordinal = 2))
    private void aether$crosshair(InGameHud hud, int x, int y, int u, int v, int w, int h, Operation<Void> original) {
        if (!AetherClient.fx().hideVanillaCrosshair) original.call(hud, x, y, u, v, w, h);
    }
//#endif

    @Inject(method = "setOverlayMessage(Ljava/lang/String;Z)V", at = @At("HEAD"), cancellable = true)
    private void aether$actionBar(String text, boolean tinted, CallbackInfo ci) {
        McEvents.actionBar(text);
        if (AetherClient.fx().hideVanillaActionBar) ci.cancel();
    }
}
