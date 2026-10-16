package gg.kite.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
//#if >=1.21.2
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//#else
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
//#endif

/** Player render layer for model cosmetics (wings, hats...); added to every player renderer by the adapters. */
//#if >=1.21.2
public final class CosmeticLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    public CosmeticLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) { super(parent); }

//#if >=1.21.9
    @Override public void submit(PoseStack pose, SubmitNodeCollector nodes, int light, AvatarRenderState state, float yRot, float xRot) {
//#else
    @Override public void render(PoseStack pose, SubmitNodeCollector nodes, int light, AvatarRenderState state, float yRot, float xRot) {
//#endif
        McCosmetics.submit(pose, nodes, light, state, getParentModel());
    }
}
//#else
// Before 1.21.2 layers render from the entity; PlayerState gathers what a render state would hold.
public final class CosmeticLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    public CosmeticLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) { super(parent); }

    @Override public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
            float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        McCosmetics.submit(pose, buffers, light, PlayerState.of(player, partialTick), getParentModel());
    }
}
//#endif
