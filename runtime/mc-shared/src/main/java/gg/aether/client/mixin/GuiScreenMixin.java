//#if >=26.2
package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Swaps vanilla's title screen for the Aether main menu while it is turned on. */
@Mixin(Gui.class)
abstract class GuiScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void aether$mainMenu(Screen screen, CallbackInfo ci) {
        if (AetherClient.replaceTitleScreen(screen)) ci.cancel();
    }
}
//#endif
