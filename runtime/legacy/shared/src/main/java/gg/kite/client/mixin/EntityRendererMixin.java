package gg.kite.client.mixin;

import gg.kite.mc.LegacyRender;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nametags: every label (players, mobs, item frames) is drawn through renderLabelIfPresent, so hiding them (screenshot
 * mode, distance limit, NPC filter), the client badge, the health suffix and streamer name replacement all hook there.
 */
@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin {
    @Shadow protected abstract void renderLabelIfPresent(Entity entity, String text, double x, double y, double z, int maxDistance);

    @Unique private boolean kite$busy;

    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"), cancellable = true)
    private void kite$label(Entity entity, String text, double x, double y, double z, int maxDistance, CallbackInfo ci) {
        if (kite$busy) return;
        String shown = LegacyRender.nameTag(entity, text);
        if (shown == null) {
            ci.cancel();
            return;
        }
        if (shown.equals(text)) return;
        ci.cancel();
        kite$busy = true;
        try {
            renderLabelIfPresent(entity, shown, x, y, z, maxDistance);
        } finally {
            kite$busy = false;
        }
    }
}
