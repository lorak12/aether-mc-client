package gg.kite.client.mixin;

import gg.kite.client.KiteClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla's title screen (custom main menu turned off) gets a button back to the Kite menu. */
@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    @Unique private static final int KITE_BUTTON = 0xAE7;

    @Inject(method = "init", at = @At("TAIL"))
    private void kite$menuButton(CallbackInfo ci) {
        buttons.add(new ButtonWidget(KITE_BUTTON, width - 84, 4, 80, 20, "Kite menu"));
    }

    @Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
    private void kite$clicked(ButtonWidget button, CallbackInfo ci) {
        if (button.id != KITE_BUTTON) return;
        KiteClient.useKiteMenu();
        ci.cancel();
    }
}
