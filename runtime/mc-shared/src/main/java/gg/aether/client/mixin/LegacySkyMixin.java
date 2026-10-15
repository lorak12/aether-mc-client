//#if <1.21.2
package gg.aether.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
//#if >=1.21
import com.mojang.blaze3d.vertex.MeshData;
//#else
import com.mojang.blaze3d.vertex.BufferBuilder;
//#endif
import gg.aether.client.AetherClient;
import gg.aether.core.Overrides;
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
    @Unique private static Identifier aether$skyTexture;

    @Inject(method = "renderSky", at = @At("HEAD"))
    private void aether$start(CallbackInfo ci) { aether$skyTexture = null; }

//#if >=1.17
    @Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V"))
    private void aether$texture(int unit, Identifier texture) {
        aether$skyTexture = texture;
        RenderSystem.setShaderTexture(unit, texture);
    }
//#else
    @Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;bind(Lnet/minecraft/resources/ResourceLocation;)V"))
    private void aether$texture(net.minecraft.client.renderer.texture.TextureManager textures, Identifier texture) {
        aether$skyTexture = texture;
        textures.bind(texture);
    }
//#endif

//#if >=1.21
    @Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V"))
    private void aether$draw(MeshData mesh) {
        if (aether$hidden()) mesh.close();
        else BufferUploader.drawWithShader(mesh);
    }
//#elif >=1.19
    @Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V"))
    private void aether$draw(BufferBuilder.RenderedBuffer mesh) {
        if (aether$hidden()) mesh.release();
        else BufferUploader.drawWithShader(mesh);
    }
//#else
    // 1.18: the builder is ended by the caller and uploaded here.
    @Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/BufferUploader;end(Lcom/mojang/blaze3d/vertex/BufferBuilder;)V"))
    private void aether$draw(BufferBuilder builder) {
        if (!aether$hidden()) BufferUploader.end(builder);
    }
//#endif

    /** The draw that is about to happen is the sun or moon and that is hidden. */
    @Unique
    private static boolean aether$hidden() {
        Identifier t = aether$skyTexture;
        Overrides fx = AetherClient.fx();
        return t != null && ((fx.hideSun && t.getPath().endsWith("/sun.png")) || (fx.hideMoon && t.getPath().endsWith("/moon_phases.png")));
    }

    @Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getStarBrightness(F)F"))
    private float aether$stars(ClientLevel level, float partialTick) {
        return AetherClient.fx().hideStars ? 0f : level.getStarBrightness(partialTick);
    }
}
//#endif
