package gg.kite.client.mixin;

import gg.kite.mc.McItems;
import java.util.List;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Item Information / Tooltips: extra tooltip lines from Events.Tooltip. */
@Mixin(ItemStack.class)
abstract class ItemStackMixin {
//#if >=1.12
    @Inject(method = "getTooltip", at = @At("RETURN"), cancellable = true)
    private void kite$tooltip(PlayerEntity player, net.minecraft.client.TooltipContext ctx, CallbackInfoReturnable<List<String>> cir) {
        List<String> out = McItems.tooltip((ItemStack) (Object) this, ctx.isAdvanced(), cir.getReturnValue());
        if (out != null) cir.setReturnValue(out);
    }
//#else
    @Inject(method = "getTooltip", at = @At("RETURN"), cancellable = true)
    private void kite$tooltip(PlayerEntity player, boolean advanced, CallbackInfoReturnable<List<String>> cir) {
        List<String> out = McItems.tooltip((ItemStack) (Object) this, advanced, cir.getReturnValue());
        if (out != null) cir.setReturnValue(out);
    }
//#endif
}
