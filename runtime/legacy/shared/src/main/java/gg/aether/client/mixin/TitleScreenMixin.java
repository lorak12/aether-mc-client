package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla's title screen (custom main menu turned off) gets a button back to the Aether menu. */
@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    @Unique private static final int AETHER_BUTTON = 0xAE7;

    @Inject(method = "init", at = @At("TAIL"))
    private void aether$menuButton(CallbackInfo ci) {
        buttons.add(new ButtonWidget(AETHER_BUTTON, width - 84, 4, 80, 20, "Aether menu"));
    }

    @Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
    private void aether$clicked(ButtonWidget button, CallbackInfo ci) {
        if (button.id != AETHER_BUTTON) return;
        AetherClient.useAetherMenu();
        ci.cancel();
    }
}
