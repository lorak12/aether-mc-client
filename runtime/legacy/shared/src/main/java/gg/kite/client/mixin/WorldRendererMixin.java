package gg.kite.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import gg.kite.client.KiteClient;
import gg.kite.core.Overrides;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sky Customization (hide stars, sun, moon) and Block Overlay's hidden vanilla outline. The sun and moon are told apart
 * by the texture bound just before their quad; hiding draws that quad with the colour mask off.
 */
@Mixin(WorldRenderer.class)
abstract class WorldRendererMixin {
//#if >=1.8
    private static final String RENDER_SKY = "renderSky(FI)V";
//#else
    private static final String RENDER_SKY = "renderSky(F)V";
//#endif

    @Unique private static Identifier kite$skyTexture;

    @Inject(method = RENDER_SKY, at = @At("HEAD"))
//#if >=1.8
    private void kite$start(float tickDelta, int pass, CallbackInfo ci) { kite$skyTexture = null; }
//#else
    private void kite$start(float tickDelta, CallbackInfo ci) { kite$skyTexture = null; }
//#endif

    @Inject(method = "drawBlockOutline", at = @At("HEAD"), cancellable = true)
    private void kite$outline(PlayerEntity player, BlockHitResult hit, int pass, float tickDelta, CallbackInfo ci) {
        if (KiteClient.fx().hideBlockOutline) ci.cancel();
    }

    @WrapOperation(method = RENDER_SKY, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/texture/TextureManager;bindTexture(Lnet/minecraft/util/Identifier;)V"))
    private void kite$texture(TextureManager textures, Identifier texture, Operation<Void> original) {
        kite$skyTexture = texture;
        original.call(textures, texture);
    }

    /** Star brightness 0 hides the stars. */
    @WrapOperation(method = RENDER_SKY, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;method_3707(F)F"))
    private float kite$stars(net.minecraft.client.world.ClientWorld world, float tickDelta, Operation<Float> original) {
        return KiteClient.fx().hideStars ? 0f : original.call(world, tickDelta);
    }

//#if >=1.8
    @WrapOperation(method = RENDER_SKY, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Tessellator;draw()V"))
    private void kite$draw(Tessellator tessellator, Operation<Void> original) {
        boolean hide = kite$hidden();
        kite$skyTexture = null;
        if (hide) GL11.glColorMask(false, false, false, false);
        original.call(tessellator);
        if (hide) GL11.glColorMask(true, true, true, true);
    }
//#else
    @WrapOperation(method = RENDER_SKY, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Tessellator;end()I"))
    private int kite$draw(Tessellator tessellator, Operation<Integer> original) {
        boolean hide = kite$hidden();
        kite$skyTexture = null;
        if (hide) GL11.glColorMask(false, false, false, false);
        int n = original.call(tessellator);
        if (hide) GL11.glColorMask(true, true, true, true);
        return n;
    }
//#endif

    /** The draw that is about to happen is the sun or moon and that is hidden. */
    @Unique
    private static boolean kite$hidden() {
        Identifier t = kite$skyTexture;
        Overrides fx = KiteClient.fx();
        return t != null && ((fx.hideSun && t.getPath().endsWith("/sun.png")) || (fx.hideMoon && t.getPath().endsWith("/moon_phases.png")));
    }
}
