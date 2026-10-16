package gg.kite.client.mixin;

import gg.kite.client.KiteClient;
import gg.kite.core.Overrides;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Toggle sprint / sneak: the sprint and sneak keys read as held while a mod forces them. */
@Mixin(KeyBinding.class)
abstract class KeyBindingMixin {
    @Inject(method = "isPressed", at = @At("HEAD"), cancellable = true)
    private void kite$forced(CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null) return;
        Overrides fx = KiteClient.fx();
        Object self = this;
        if (mc.currentScreen != null && !(fx.forceSprintInScreens && self == mc.options.sprintKey)) return;
        if ((fx.forceSprint && self == mc.options.sprintKey) || (fx.forceSneak && self == mc.options.sneakKey)) cir.setReturnValue(true);
    }
}
