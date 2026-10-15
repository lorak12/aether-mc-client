//#if >=1.12
package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import net.minecraft.client.gui.hud.BossBarHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Boss bar mod: hides vanilla's boss bars (1.9+ draws them in their own HUD part). */
@Mixin(BossBarHud.class)
abstract class BossBarHudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void aether$hide(CallbackInfo ci) { if (AetherClient.fx().hideVanillaBossBar) ci.cancel(); }
}
//#endif
