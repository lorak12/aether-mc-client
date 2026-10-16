//#if >=1.12
package gg.kite.client.mixin;

import gg.kite.client.KiteClient;
import net.minecraft.client.gui.hud.BossBarHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Boss bar mod: hides vanilla's boss bars (1.9+ draws them in their own HUD part). */
@Mixin(BossBarHud.class)
abstract class BossBarHudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void kite$hide(CallbackInfo ci) { if (KiteClient.fx().hideVanillaBossBar) ci.cancel(); }
}
//#endif
