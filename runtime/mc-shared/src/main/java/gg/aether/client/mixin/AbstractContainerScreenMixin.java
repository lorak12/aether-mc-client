package gg.aether.client.mixin;

import gg.aether.mc.McItems;
import gg.aether.client.GuiRenderer;
//#if >=26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#elif >=1.20
import net.minecraft.client.gui.GuiGraphics;
//#else
import com.mojang.blaze3d.vertex.PoseStack;
import gg.aether.client.LegacyGuiGraphics;
//#endif
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
//#if >=1.21.9
import net.minecraft.client.input.MouseButtonEvent;
//#endif
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Container Search: a search box above chests / inventories that dims non-matching slots. */
@Mixin(AbstractContainerScreen.class)
abstract class AbstractContainerScreenMixin {
    @Shadow protected int leftPos;
    @Shadow protected int topPos;
//#if >=26.1
    @Shadow @Final protected int imageWidth;
//#else
    @Shadow protected int imageWidth;
//#endif
    @Shadow @Final protected AbstractContainerMenu menu;

//#if >=26.1
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("TAIL"))
    private void aether$search(GuiGraphicsExtractor g, int mx, int my, float dt, CallbackInfo ci) {
//#elif >=1.20
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"))
    private void aether$search(GuiGraphics g, int mx, int my, float dt, CallbackInfo ci) {
//#else
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", at = @At("TAIL"))
    private void aether$search(PoseStack pose, int mx, int my, float dt, CallbackInfo ci) {
        LegacyGuiGraphics g = new LegacyGuiGraphics(pose);
//#endif
        McItems.renderSearch(new GuiRenderer(g), this, leftPos, topPos, imageWidth, menu.slots);
    }

//#if >=1.21.9
    @Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z", at = @At("HEAD"), cancellable = true)
    private void aether$click(MouseButtonEvent e, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (McItems.click(this, e.x(), e.y(), e.button())) cir.setReturnValue(true);
    }
//#else
    @Inject(method = "mouseClicked(DDI)Z", at = @At("HEAD"), cancellable = true)
    private void aether$click(double x, double y, int button, CallbackInfoReturnable<Boolean> cir) {
        if (McItems.click(this, x, y, button)) cir.setReturnValue(true);
    }
//#endif
}
