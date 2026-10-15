package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import gg.aether.mc.Gfx;
import net.minecraft.client.render.item.HeldItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** First-person overlays (fire, water) and the item swap animation. */
@Mixin(HeldItemRenderer.class)
abstract class HeldItemRendererMixin {
//#if <1.12
    @Shadow private float equipProgress;
    @Shadow private float lastEquipProgress;
//#else
    @Shadow private float field_13533;
    @Shadow private float lastEquipProgress;
    @Shadow private float field_13534;
    @Shadow private float field_13535;
//#endif

//#if <1.12
    @Inject(method = "renderFireOverlay(F)V", at = @At("HEAD"), cancellable = true)
    private void aether$fire(float tickDelta, CallbackInfo ci) {
//#else
    @Inject(method = "method_14680", at = @At("HEAD"), cancellable = true)
    private void aether$fire(CallbackInfo ci) {
//#endif
        if (AetherClient.fx().hideFireOverlay) {
            ci.cancel();
            return;
        }
        float offset = AetherClient.fx().fireOverlayOffset;
        if (offset != 0f) Gfx.translate(0f, -offset, 0f);
    }

    /** The fire overlay moved down must not move whatever is drawn after it. */
//#if <1.12
    @Inject(method = "renderFireOverlay(F)V", at = @At("RETURN"))
    private void aether$fireEnd(float tickDelta, CallbackInfo ci) {
//#else
    @Inject(method = "method_14680", at = @At("RETURN"))
    private void aether$fireEnd(CallbackInfo ci) {
//#endif
        float offset = AetherClient.fx().fireOverlayOffset;
        if (offset != 0f && !AetherClient.fx().hideFireOverlay) Gfx.translate(0f, offset, 0f);
    }

    @Inject(method = "renderUnderwaterOverlay", at = @At("HEAD"), cancellable = true)
    private void aether$water(float tickDelta, CallbackInfo ci) {
        if (AetherClient.fx().hideWaterOverlay) ci.cancel();
    }

    /** No equip animation: the held item is always fully raised. */
    @Inject(method = "updateHeldItems", at = @At("RETURN"))
    private void aether$noEquip(CallbackInfo ci) {
        if (!AetherClient.fx().noEquipAnimation) return;
//#if <1.12
        equipProgress = 1f;
        lastEquipProgress = 1f;
//#else
        field_13533 = 1f;
        lastEquipProgress = 1f;
        field_13534 = 1f;
        field_13535 = 1f;
//#endif
    }
}
