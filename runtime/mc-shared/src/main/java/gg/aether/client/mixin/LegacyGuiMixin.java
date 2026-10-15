//#if <1.20.5
package gg.aether.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import gg.aether.core.Events;
import gg.aether.client.AetherClient;
import net.minecraft.client.gui.Gui;
//#if >=1.20
import net.minecraft.client.gui.GuiGraphics;
//#else
import com.mojang.blaze3d.vertex.PoseStack;
//#endif
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GuiMixin before 1.20.5, where Gui.render draws most of the HUD inline: the Aether HUD goes in right before the chat,
 * and the action bar, title and tab list are hidden at their reads / calls inside render.
 */
@Mixin(Gui.class)
abstract class LegacyGuiMixin {
    /** Screenshot mode: no vanilla HUD at all (our own still draws unless it is hidden too). */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
//#if >=1.20
    private void aether$hideAll(GuiGraphics g, float partialTick, CallbackInfo ci) {
//#else
    private void aether$hideAll(PoseStack g, float partialTick, CallbackInfo ci) {
//#endif
        if (!AetherClient.fx().hideVanillaHud) return;
        AetherClient.renderHud(g);
        ci.cancel();
    }

    /** Just below chat. */
//#if >=1.20
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lnet/minecraft/client/gui/GuiGraphics;III)V"))
    private void aether$hud(GuiGraphics g, float partialTick, CallbackInfo ci) { AetherClient.renderHud(g); }
//#else
//#if >=1.19.3
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lcom/mojang/blaze3d/vertex/PoseStack;III)V"))
//#else
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lcom/mojang/blaze3d/vertex/PoseStack;I)V"))
//#endif
    private void aether$hud(PoseStack g, float partialTick, CallbackInfo ci) { AetherClient.renderHud(g); }
//#endif

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
//#if >=1.20
    private void aether$crosshair(GuiGraphics g, CallbackInfo ci) {
//#else
    private void aether$crosshair(PoseStack g, CallbackInfo ci) {
//#endif
        if (AetherClient.fx().hideVanillaCrosshair) ci.cancel();
    }

    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/Gui;overlayMessageString:Lnet/minecraft/network/chat/Component;"))
    private Component aether$actionBar(Component text) { return AetherClient.fx().hideVanillaActionBar ? null : text; }

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void aether$actionBarText(Component text, boolean animate, CallbackInfo ci) {
        AetherClient.mods().events().post(new Events.ActionBar(text.getString(), System.currentTimeMillis()));
    }

    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/Gui;title:Lnet/minecraft/network/chat/Component;"))
    private Component aether$title(Component title) { return AetherClient.fx().hideVanillaTitle ? null : title; }

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void aether$hideScoreboardSidebar(CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaScoreboard) ci.cancel();
    }

    @Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
    private void aether$hideEffects(CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaEffects) ci.cancel();
    }

    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void aether$hideItemHotbar(CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaHotbar) ci.cancel();
    }

//#if >=1.20
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;render(Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V"))
    private void aether$hideTabList(PlayerTabOverlay tab, GuiGraphics g, int width, Scoreboard scoreboard, Objective objective, Operation<Void> original) {
//#else
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;render(Lcom/mojang/blaze3d/vertex/PoseStack;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V"))
    private void aether$hideTabList(PlayerTabOverlay tab, PoseStack g, int width, Scoreboard scoreboard, Objective objective, Operation<Void> original) {
//#endif
        if (!AetherClient.fx().hideVanillaTabList) original.call(tab, g, width, scoreboard, objective);
    }
}
//#endif
