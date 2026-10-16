package gg.kite.client.mixin;

// shouldInstantlyReplaceVisibleItem arrived in 1.21.4; 1.21.3 and older have no such method, so the whole mixin is
// left out there (an empty file, so it's not listed in kite.mixins.json).
//#if >=1.21.4
import gg.kite.client.KiteClient;
//#if >=26.3
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
//#else
import net.minecraft.client.renderer.ItemInHandRenderer;
//#endif
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Animations mod: no lower/raise animation when switching items. */
//#if >=26.3
@Mixin(FirstPersonHandsAndItems.class)
//#else
@Mixin(ItemInHandRenderer.class)
//#endif
abstract class HandsMixin {
    @Inject(method = "shouldInstantlyReplaceVisibleItem", at = @At("RETURN"), cancellable = true)
//#if >=26.3
    private void kite$instant(ItemStack from, ItemStack to, LocalPlayer player, CallbackInfoReturnable<Boolean> cir) {
//#else
    private void kite$instant(ItemStack from, ItemStack to, CallbackInfoReturnable<Boolean> cir) {
//#endif
        if (KiteClient.fx().noEquipAnimation) cir.setReturnValue(true);
    }
}
//#endif
