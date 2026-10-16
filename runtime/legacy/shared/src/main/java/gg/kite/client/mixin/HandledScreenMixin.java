package gg.kite.client.mixin;

import gg.kite.client.GuiRenderer;
import gg.kite.client.LegacyKeys;
import gg.kite.mc.McItems;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Container Search: a search box above chests / inventories that dims non-matching slots. */
@Mixin(HandledScreen.class)
abstract class HandledScreenMixin {
    @Shadow protected int x;
    @Shadow protected int y;
    @Shadow protected int backgroundWidth;
    @Shadow public ScreenHandler screenHandler;

    @Inject(method = "render", at = @At("TAIL"))
    private void kite$search(int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
        McItems.renderSearch(new GuiRenderer(), this, x, y, backgroundWidth, screenHandler.slots);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void kite$click(int mouseX, int mouseY, int button, CallbackInfo ci) {
        if (McItems.click(this, mouseX, mouseY, button)) ci.cancel();
    }

    /** Typing in the search box: letters must not close the screen or move items. */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void kite$key(char c, int code, CallbackInfo ci) {
        int glfw = LegacyKeys.toGlfw(code);
        boolean used = glfw >= 0 && McItems.key(this, glfw);
        if (c >= 32 && c != 127) used |= McItems.character(this, c);
        if (used) ci.cancel();
    }
}
