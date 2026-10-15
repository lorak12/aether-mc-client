package gg.aether.client.mixin;

import gg.aether.core.Overrides;
import gg.aether.client.AetherClient;
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
    private void aether$forced(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        Overrides fx = AetherClient.fx();
//#if >=26.2
        if (mc.gui.screen() != null && !(fx.forceSprintInScreens && (Object) this == mc.options.keySprint)) return;
//#else
        if (mc.screen != null && !(fx.forceSprintInScreens && (Object) this == mc.options.keySprint)) return;
//#endif
        Object self = this;
        if ((fx.forceSprint && self == mc.options.keySprint) || (fx.forceSneak && self == mc.options.keyShift)) cir.setReturnValue(true);
    }
}
