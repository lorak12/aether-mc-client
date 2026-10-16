//#if >=1.8
package gg.kite.client;

import gg.kite.mc.LegacyCosmetics;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;

/** The cosmetics layer on every player renderer: cloth cape first, then the worn models. */
public final class CosmeticFeature implements FeatureRenderer<AbstractClientPlayerEntity> {
    private final PlayerEntityRenderer renderer;

    public CosmeticFeature(PlayerEntityRenderer renderer) { this.renderer = renderer; }

    @Override public void render(AbstractClientPlayerEntity player, float limbAngle, float limbDistance, float tickDelta, float age,
            float headYaw, float headPitch, float scale) {
        net.minecraft.client.render.entity.model.BiPedModel model = renderer.getModel();
        LegacyCosmetics.drawWavyCape(player, model, tickDelta, age);
        LegacyCosmetics.draw(player, model, limbDistance, tickDelta, age);
    }

    /** Render the hurt flash on cosmetics too. */
    @Override public boolean combineTextures() { return true; }
}
//#endif
