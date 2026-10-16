package gg.kite.client.mixin;

import gg.kite.client.KiteClient;
import gg.kite.mc.McWorldRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#if <1.21.11
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
//#endif
//#if <1.21.9
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
//#endif

/** World overlays (chunk borders, light levels, waypoints...) drawn alongside vanilla's debug renderers. */
@Mixin(DebugRenderer.class)
abstract class DebugRendererMixin {
//#if >=1.21.11
    /** Emitted as gizmos while the frame's gizmos are collected. */
    @Inject(method = "emitGizmos", at = @At("TAIL"))
    private void kite$world(Frustum frustum, double camX, double camY, double camZ, float partialTick, CallbackInfo ci) {
        KiteClient.renderWorld(new McWorldRenderer(camX, camY, camZ));
    }
//#elif >=1.21.9
    /** Drawn into the debug buffers in the opaque pass (the translucent pass calls render again with true). */
    @Inject(method = "render", at = @At("TAIL"))
    private void kite$world(PoseStack pose, Frustum frustum, MultiBufferSource.BufferSource buffers, double camX, double camY, double camZ,
            boolean translucent, CallbackInfo ci) {
        if (!translucent) KiteClient.renderWorld(new McWorldRenderer(pose, buffers, camX, camY, camZ));
    }
//#elif >=1.21.2
    @Inject(method = "render", at = @At("TAIL"))
    private void kite$world(PoseStack pose, Frustum frustum, MultiBufferSource.BufferSource buffers, double camX, double camY, double camZ, CallbackInfo ci) {
        KiteClient.renderWorld(new McWorldRenderer(pose, buffers, camX, camY, camZ));
    }
//#else
    @Inject(method = "render", at = @At("TAIL"))
    private void kite$world(PoseStack pose, MultiBufferSource.BufferSource buffers, double camX, double camY, double camZ, CallbackInfo ci) {
        KiteClient.renderWorld(new McWorldRenderer(pose, buffers, camX, camY, camZ));
    }
//#endif
//#if <1.21.9

    /** Chunk Borders mod: vanilla's chunk border renderer on without flipping the player's F3 + G state. */
    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/debug/DebugRenderer;renderChunkborder:Z"))
    private boolean kite$chunkBorders(boolean shown) { return shown || KiteClient.fx().showChunkBorders; }
//#endif
}
