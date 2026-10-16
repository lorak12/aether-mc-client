//#if <1.21.4
package gg.kite.client.mixin;

import gg.kite.client.KiteClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Animations mod before 1.21.4 (HandsMixin from then on): no lower/raise animation when switching items. Showing the held
 * stacks before vanilla compares them makes the hands count as unchanged, so they never dip.
 */
@Mixin(ItemInHandRenderer.class)
abstract class LegacyHandsMixin {
    @Shadow private ItemStack mainHandItem;
    @Shadow private ItemStack offHandItem;

    @Inject(method = "tick", at = @At("HEAD"))
    private void kite$instant(CallbackInfo ci) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !KiteClient.fx().noEquipAnimation) return;
        mainHandItem = p.getMainHandItem();
        offHandItem = p.getOffhandItem();
    }
}
//#endif
