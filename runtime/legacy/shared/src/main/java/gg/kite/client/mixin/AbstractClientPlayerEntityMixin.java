package gg.kite.client.mixin;

import gg.kite.mc.LegacyCosmetics;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cosmetic capes: the cape texture vanilla's cape drawing binds (Legacy Yarn names it getSkinId) and whether there is one. */
@Mixin(AbstractClientPlayerEntity.class)
abstract class AbstractClientPlayerEntityMixin {
    @Inject(method = "getSkinId()Lnet/minecraft/util/Identifier;", at = @At("RETURN"), cancellable = true)
    private void kite$cape(CallbackInfoReturnable<Identifier> cir) {
        Identifier vanilla = cir.getReturnValue();
        Identifier id = LegacyCosmetics.capeTexture((AbstractClientPlayerEntity) (Object) this, vanilla);
        if (id != vanilla) cir.setReturnValue(id);
    }

    @Inject(method = "canRenderCapeTexture", at = @At("RETURN"), cancellable = true)
    private void kite$hasCape(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && LegacyCosmetics.hasCosmeticCape((AbstractClientPlayerEntity) (Object) this)) cir.setReturnValue(true);
    }
}
