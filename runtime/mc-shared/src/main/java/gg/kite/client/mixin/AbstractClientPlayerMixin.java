//#if <1.21.2
package gg.kite.client.mixin;

import gg.kite.mc.McCosmetics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cosmetic capes before 1.21.2: vanilla's cape (and elytra) layers read the cape from the player, so it is swapped in
 * here. With 1.21.2's render states this moved to McCosmetics.extract.
 */
@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerMixin {
//#if >=1.20.2
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void kite$cosmeticCape(CallbackInfoReturnable<net.minecraft.client.resources.PlayerSkin> cir) {
        Identifier cape = McCosmetics.capeTexture((AbstractClientPlayer) (Object) this);
        net.minecraft.client.resources.PlayerSkin s = cir.getReturnValue();
        if (cape == null || s == null) return;
        cir.setReturnValue(new net.minecraft.client.resources.PlayerSkin(s.texture(), s.textureUrl(), cape, s.elytraTexture(), s.model(), s.secure()));
    }
//#else
    @Inject(method = "getCloakTextureLocation", at = @At("RETURN"), cancellable = true)
    private void kite$cosmeticCape(CallbackInfoReturnable<Identifier> cir) {
        Identifier cape = McCosmetics.capeTexture((AbstractClientPlayer) (Object) this);
        if (cape != null) cir.setReturnValue(cape);
    }

    @Inject(method = "isCapeLoaded", at = @At("RETURN"), cancellable = true)
    private void kite$capeLoaded(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && McCosmetics.capeTexture((AbstractClientPlayer) (Object) this) != null) cir.setReturnValue(true);
    }
//#endif
}
//#endif
