package gg.aether.client.mixin;

//#if >=1.21.2
import com.mojang.blaze3d.platform.FramerateLimitTracker;
//#endif
import gg.aether.client.AetherClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FPS limiter / dynamic FPS. Vanilla's own throttling (minimized, long AFK) still wins when it is lower. Before 1.21.2
 * the limit came from Minecraft.getFramerateLimit.
 */
//#if >=1.21.2
@Mixin(FramerateLimitTracker.class)
//#else
@Mixin(net.minecraft.client.Minecraft.class)
//#endif
abstract class FramerateLimitTrackerMixin {
    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void aether$limit(CallbackInfoReturnable<Integer> cir) {
        Integer limit = AetherClient.fx().fpsLimit;
        if (limit == null) return;
//#if >=1.21.5
        boolean throttled = ((FramerateLimitTracker) (Object) this).getThrottleReason() != FramerateLimitTracker.FramerateThrottleReason.NONE;
//#else
        // No throttle reason before 1.21.5: vanilla returning less than the player's limit means it is throttling.
//#if >=1.19
        boolean throttled = cir.getReturnValue() < net.minecraft.client.Minecraft.getInstance().options.framerateLimit().get();
//#else
        boolean throttled = cir.getReturnValue() < net.minecraft.client.Minecraft.getInstance().options.framerateLimit;
//#endif
//#endif
        cir.setReturnValue(throttled ? Math.min(cir.getReturnValue(), limit) : Math.max(1, limit));
    }
}
