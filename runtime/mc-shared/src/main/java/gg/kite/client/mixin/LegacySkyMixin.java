//#if <1.21.2
package gg.kite.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
//#if >=1.21
import com.mojang.blaze3d.vertex.MeshData;
//#else
import com.mojang.blaze3d.vertex.BufferBuilder;
//#endif
import gg.kite.client.KiteClient;
import gg.kite.core.Overrides;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sky Customization before 1.21.2, where LevelRenderer.renderSky draws the sky inline: hide stars, sun or moon. The sun
 * and moon are told apart by the texture bound just before their draw.
 */
@Mixin(LevelRenderer.class)
abstract class LegacySkyMixin {
    @Unique private static Identifier kite$skyTexture;

    @Inject(method = "renderSky", at = @At("HEAD"))
    private void kite$start(CallbackInfo ci) { kite$skyTexture = null; }

//#if >=1.17
    @Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V"))
    private void kite$texture(int unit, Identifier texture) {
        kite$skyTexture = texture;
        RenderSystem.setShaderTexture(unit, texture);
    }
//#else
    @Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;bind(Lnet/minecraft/resources/ResourceLocation;)V"))
    private void kite$texture(net.minecraft.client.renderer.texture.TextureManager textures, Identifier texture) {
        kite$skyTexture = texture;
        textures.bind(texture);
    }
//#endif

//#if >=1.21
    @Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V"))
    private void kite$draw(MeshData mesh) {
        if (kite$hidden()) mesh.close();
        else BufferUploader.drawWithShader(mesh);
    }
//#elif >=1.19
    @Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V"))
    private void kite$draw(BufferBuilder.RenderedBuffer mesh) {
        if (kite$hidden()) mesh.release();
        else BufferUploader.drawWithShader(mesh);
    }
//#else
    // 1.18: the builder is ended by the caller and uploaded here.
    @Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/BufferUploader;end(Lcom/mojang/blaze3d/vertex/BufferBuilder;)V"))
    private void kite$draw(BufferBuilder builder) {
        if (!kite$hidden()) BufferUploader.end(builder);
    }
//#endif

    /** The draw that is about to happen is the sun or moon and that is hidden. */
    @Unique
    private static boolean kite$hidden() {
        Identifier t = kite$skyTexture;
        Overrides fx = KiteClient.fx();
        return t != null && ((fx.hideSun && t.getPath().endsWith("/sun.png")) || (fx.hideMoon && t.getPath().endsWith("/moon_phases.png")));
    }

    @Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getStarBrightness(F)F"))
    private float kite$stars(ClientLevel level, float partialTick) {
        return KiteClient.fx().hideStars ? 0f : level.getStarBrightness(partialTick);
    }
}
//#endif
