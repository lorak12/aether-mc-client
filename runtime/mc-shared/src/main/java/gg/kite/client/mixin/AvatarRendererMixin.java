package gg.kite.client.mixin;

import gg.kite.mc.CosmeticLayer;
//#if >=1.17
import net.minecraft.client.renderer.entity.EntityRendererProvider;
//#endif
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
//#if >=1.21.2
import gg.kite.mc.McCosmetics;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cosmetics: a render layer for model cosmetics on every player renderer, and cosmetic capes on the skin. Before 1.21.2
 * there is no render state to extract into: the layer reads the entity, and AbstractClientPlayerMixin swaps the cape.
 */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin {
//#if >=1.17
    @Inject(method = "<init>", at = @At("TAIL"))
//#else
    @Inject(method = "<init>(Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;Z)V", at = @At("TAIL"))
//#endif
//#if >=1.17
    private void kite$cosmeticLayer(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
//#else
    private void kite$cosmeticLayer(net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher, boolean slim, CallbackInfo ci) {
//#endif
//#if >=1.21.9
        ((LivingEntityRendererAccessor) this).kite$addLayer(new CosmeticLayer((AvatarRenderer<?>) (Object) this));
//#else
        ((LivingEntityRendererAccessor) this).kite$addLayer(new CosmeticLayer((AvatarRenderer) (Object) this));
//#endif
    }

//#if >=1.21.2
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
    private void kite$cosmetics(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        McCosmetics.extract(avatar, state);
    }
//#endif
}
