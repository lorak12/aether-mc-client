package gg.aether.client.mixin;

import gg.aether.mc.McRender;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mob Highlight: outline colour (entities that glow on their own keep their team colour). */
@Mixin(Entity.class)
abstract class EntityMixin {
    @Shadow public abstract boolean isCurrentlyGlowing();

    @Inject(method = "getTeamColor", at = @At("RETURN"), cancellable = true)
    private void aether$color(CallbackInfoReturnable<Integer> cir) {
        if (isCurrentlyGlowing()) return;
        int c = McRender.highlight((Entity) (Object) this);
        if (c >= 0) cir.setReturnValue(c);
    }
}
