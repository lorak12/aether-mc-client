package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla's title screen (custom main menu turned off) gets a button back to the Aether menu. */
@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void aether$menuButton(CallbackInfo ci) {
//#if >=1.19.3
        addRenderableWidget(Button.builder(Component.literal("Kite menu"), b -> AetherClient.useAetherMenu()).bounds(width - 84, 4, 80, 20).build());
//#elif >=1.17
        addRenderableWidget(new Button(width - 84, 4, 80, 20, Component.literal("Kite menu"), b -> AetherClient.useAetherMenu()));
//#else
        addButton(new Button(width - 84, 4, 80, 20, Component.literal("Kite menu"), b -> AetherClient.useAetherMenu()));
//#endif
    }
}
