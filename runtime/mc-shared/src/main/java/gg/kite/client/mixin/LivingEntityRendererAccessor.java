package gg.kite.client.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the cosmetics mixin add a render layer (addLayer is protected). */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererAccessor {
    @SuppressWarnings("rawtypes")
    @Invoker("addLayer")
    boolean kite$addLayer(RenderLayer layer);
}
