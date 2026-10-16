//#if >=1.21.4
package gg.kite.client.mixin;

import gg.kite.mc.ItemStackSize;
import net.minecraft.client.renderer.entity.state.ItemClusterRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Item Physics: remembers the stack size so flat items can pile up thicker as the stack grows. */
@Mixin(ItemClusterRenderState.class)
abstract class ItemClusterRenderStateMixin implements ItemStackSize {
    @Unique private int kite$stackSize;

    @Inject(method = "extractItemGroupRenderState", at = @At("RETURN"))
    private void kite$extract(Entity entity, ItemStack stack, ItemModelResolver resolver, CallbackInfo ci) {
        kite$stackSize = stack.getCount();
    }

    @Override public int kite$stackSize() { return kite$stackSize; }
}
//#endif
