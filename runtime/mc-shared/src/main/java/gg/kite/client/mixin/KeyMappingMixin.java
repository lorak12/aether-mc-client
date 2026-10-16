package gg.kite.client.mixin;

import gg.kite.core.Overrides;
import gg.kite.client.KiteClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Toggle sprint / sneak: the sprint and sneak keys read as held while a mod forces them. */
@Mixin(KeyMapping.class)
abstract class KeyMappingMixin {
    @Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
    private void kite$forced(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        Overrides fx = KiteClient.fx();
//#if >=26.2
        if (mc.gui.screen() != null && !(fx.forceSprintInScreens && (Object) this == mc.options.keySprint)) return;
//#else
        if (mc.screen != null && !(fx.forceSprintInScreens && (Object) this == mc.options.keySprint)) return;
//#endif
        Object self = this;
        if ((fx.forceSprint && self == mc.options.keySprint) || (fx.forceSneak && self == mc.options.keyShift)) cir.setReturnValue(true);
    }
}
