package gg.aether.client.mixin;

import gg.aether.mc.McItems;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Item Information / Tooltips: extra tooltip lines from Events.Tooltip. */
@Mixin(ItemStack.class)
abstract class ItemStackMixin {
//#if >=1.20.5
    @Inject(method = "getTooltipLines(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;)Ljava/util/List;", at = @At("RETURN"), cancellable = true)
    private void aether$tooltip(Item.TooltipContext ctx, Player player, TooltipFlag flag, CallbackInfoReturnable<List<Component>> cir) {
//#else
    @Inject(method = "getTooltipLines(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;)Ljava/util/List;", at = @At("RETURN"), cancellable = true)
    private void aether$tooltip(Player player, TooltipFlag flag, CallbackInfoReturnable<List<Component>> cir) {
//#endif
        List<Component> out = McItems.tooltip((ItemStack) (Object) this, flag.isAdvanced(), cir.getReturnValue());
        if (out != null) cir.setReturnValue(out);
    }
}
