package gg.kite.client.mixin;

import gg.kite.mc.McEvents;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AdvancementToast.class)
abstract class AdvancementToastMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void kite$earned(AdvancementHolder holder, CallbackInfo ci) {
//#if >=26.3
        holder.value().display().ifPresent(d -> McEvents.advancement(d.title()));
//#elif >=1.20.2
        holder.value().display().ifPresent(d -> McEvents.advancement(d.getTitle()));
//#else
        if (holder.getDisplay() != null) McEvents.advancement(holder.getDisplay().getTitle());
//#endif
    }
}
