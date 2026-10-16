package gg.kite.client.mixin;

import gg.kite.client.KiteClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import gg.kite.mc.McRender;
import net.minecraft.world.entity.Entity;

//#if >=26.1
/** Drives mod ticks and saves the config on exit. */
//#else
/** Drives mod ticks, swaps in the Kite main menu and saves the config on exit. */
//#endif
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void kite$tick(CallbackInfo ci) { KiteClient.tick((Minecraft) (Object) this); }

    /** Mob Highlight: glowing outline for the entities the mod picks. */
    @Inject(method = "shouldEntityAppearGlowing", at = @At("RETURN"), cancellable = true)
    private void kite$glow(Entity e, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && McRender.highlight(e) >= 0) cir.setReturnValue(true);
    }

//#if <26.2
    /** Swaps vanilla's title screen for the Kite main menu while it is turned on. */
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void kite$mainMenu(net.minecraft.client.gui.screens.Screen screen, CallbackInfo ci) {
        if (KiteClient.replaceTitleScreen(screen)) ci.cancel();
    }

//#endif
    @Inject(method = "stop", at = @At("HEAD"))
    private void kite$stop(CallbackInfo ci) { KiteClient.save(); }
}
