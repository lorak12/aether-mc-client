//#if <1.21.6
package gg.kite.client.mixin;

import gg.kite.client.KiteClient;
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Boss bar mod: hides vanilla's boss bars (1.21.6+ hides them through GuiMixin.renderBossOverlay). */
@Mixin(BossHealthOverlay.class)
abstract class BossHealthOverlayMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void kite$hide(CallbackInfo ci) { if (KiteClient.fx().hideVanillaBossBar) ci.cancel(); }
}
//#endif
