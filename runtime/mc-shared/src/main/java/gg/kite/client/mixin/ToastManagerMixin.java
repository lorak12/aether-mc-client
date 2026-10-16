package gg.kite.client.mixin;

import gg.kite.mc.McRender;
//#if >=26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#elif >=1.20
import net.minecraft.client.gui.GuiGraphics;
//#else
import com.mojang.blaze3d.vertex.PoseStack;
//#endif
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Streamer / recording mode: no vanilla toasts on screen. */
@Mixin(ToastManager.class)
abstract class ToastManagerMixin {
//#if >=26.1
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true)
    private void kite$hide(GuiGraphicsExtractor g, CallbackInfo ci) {
//#elif >=1.20
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), cancellable = true)
    private void kite$hide(GuiGraphics g, CallbackInfo ci) {
//#else
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("HEAD"), cancellable = true)
    private void kite$hide(PoseStack pose, CallbackInfo ci) {
//#endif
        if (McRender.hideToasts()) ci.cancel();
    }
}
