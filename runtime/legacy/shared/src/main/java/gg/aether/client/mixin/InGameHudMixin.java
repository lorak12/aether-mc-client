package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import gg.aether.mc.McEvents;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Draws the Aether HUD in the vanilla HUD pass and hides vanilla pieces that mods replace. */
@Mixin(InGameHud.class)
abstract class InGameHudMixin {
    /** Screenshot mode: no vanilla HUD at all (our own still draws unless it is hidden too). */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void aether$hideAll(float tickDelta, CallbackInfo ci) {
        if (!AetherClient.fx().hideVanillaHud) return;
        AetherClient.renderHud();
        ci.cancel();
    }

    /** Just below chat. */
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/ChatHud;render(I)V"))
    private void aether$hud(float tickDelta, CallbackInfo ci) { AetherClient.renderHud(); }

//#if >=1.12
    /** The crosshair (and attack indicator). */
    @Inject(method = "method_12164", at = @At("HEAD"), cancellable = true)
    private void aether$crosshair(CallbackInfo ci) { if (AetherClient.fx().hideVanillaCrosshair) ci.cancel(); }

    /** Status effect icons (top right, 1.9+). */
    @Inject(method = "method_12165", at = @At("HEAD"), cancellable = true)
    private void aether$effects(CallbackInfo ci) { if (AetherClient.fx().hideVanillaEffects) ci.cancel(); }
//#else
    @Inject(method = "showCrosshair", at = @At("RETURN"), cancellable = true)
    private void aether$crosshair(CallbackInfoReturnable<Boolean> cir) {
        if (AetherClient.fx().hideVanillaCrosshair) cir.setReturnValue(false);
    }

    @Inject(method = "renderBossBar", at = @At("HEAD"), cancellable = true)
    private void aether$bossBar(CallbackInfo ci) { if (AetherClient.fx().hideVanillaBossBar) ci.cancel(); }
//#endif

    @Inject(method = "renderScoreboardObjective", at = @At("HEAD"), cancellable = true)
    private void aether$scoreboard(CallbackInfo ci) { if (AetherClient.fx().hideVanillaScoreboard) ci.cancel(); }

    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void aether$hotbar(CallbackInfo ci) { if (AetherClient.fx().hideVanillaHotbar) ci.cancel(); }

    @Inject(method = "setOverlayMessage(Ljava/lang/String;Z)V", at = @At("HEAD"), cancellable = true)
    private void aether$actionBar(String text, boolean tinted, CallbackInfo ci) {
        McEvents.actionBar(text);
        if (AetherClient.fx().hideVanillaActionBar) ci.cancel();
    }
}
