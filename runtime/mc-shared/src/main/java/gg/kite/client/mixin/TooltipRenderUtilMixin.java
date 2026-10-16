//#if >=1.19.3
package gg.kite.client.mixin;

import gg.kite.mc.McItems;
import gg.kite.client.GuiRenderer;
//#if >=26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#elif >=1.20
import net.minecraft.client.gui.GuiGraphics;
//#endif
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tooltips mod: flat background / border colours instead of the vanilla frame sprites. */
@Mixin(TooltipRenderUtil.class)
abstract class TooltipRenderUtilMixin {
//#if <1.20
    @Inject(method = "renderTooltipBackground", at = @At("HEAD"), cancellable = true)
    private static void kite$background(TooltipRenderUtil.BlitPainter painter,
            org.joml.Matrix4f matrix, com.mojang.blaze3d.vertex.BufferBuilder buffer,
            int x, int y, int w, int h, int z, CallbackInfo ci) {
        gg.kite.core.Overrides fx = gg.kite.client.KiteClient.fx();
        if (fx.tooltipBackground == null) return;
        int bx = x - 3, by = y - 3, bw = w + 6, bh = h + 6;
        painter.blit(matrix, buffer, bx, by, bx + bw, by + bh, z, fx.tooltipBackground, fx.tooltipBackground);
        if (fx.tooltipBorder != null) {
            int c = fx.tooltipBorder;
            painter.blit(matrix, buffer, bx, by, bx + bw, by + 1, z, c, c);
            painter.blit(matrix, buffer, bx, by + bh - 1, bx + bw, by + bh, z, c, c);
            painter.blit(matrix, buffer, bx, by + 1, bx + 1, by + bh - 1, z, c, c);
            painter.blit(matrix, buffer, bx + bw - 1, by + 1, bx + bw, by + bh - 1, z, c, c);
        }
        ci.cancel();
    }
//#else
//#if >=26.1
    @Inject(method = "extractTooltipBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIILnet/minecraft/resources/Identifier;)V", at = @At("HEAD"), cancellable = true)
    private static void kite$background(GuiGraphicsExtractor g, int x, int y, int w, int h, Identifier style, CallbackInfo ci) {
//#elif >=1.21.6
    @Inject(method = "renderTooltipBackground(Lnet/minecraft/client/gui/GuiGraphics;IIIILnet/minecraft/resources/Identifier;)V", at = @At("HEAD"), cancellable = true)
    private static void kite$background(GuiGraphics g, int x, int y, int w, int h, Identifier style, CallbackInfo ci) {
//#elif >=1.21.2
    @Inject(method = "renderTooltipBackground(Lnet/minecraft/client/gui/GuiGraphics;IIIIILnet/minecraft/resources/Identifier;)V", at = @At("HEAD"), cancellable = true)
    private static void kite$background(GuiGraphics g, int x, int y, int w, int h, int z, Identifier style, CallbackInfo ci) {
//#else
    // No tooltip styles before 1.21.2.
    @Inject(method = "renderTooltipBackground(Lnet/minecraft/client/gui/GuiGraphics;IIIII)V", at = @At("HEAD"), cancellable = true)
    private static void kite$background(GuiGraphics g, int x, int y, int w, int h, int z, CallbackInfo ci) {
        Identifier style = null;
//#endif
        if (McItems.tooltipBackground(new GuiRenderer(g), x, y, w, h, style != null)) ci.cancel();
    }
//#endif
}
//#endif
