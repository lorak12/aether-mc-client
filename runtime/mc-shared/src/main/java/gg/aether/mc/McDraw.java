//#if <1.21.11
package gg.aether.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.debug.DebugRenderer;

/** Vertex-level drawing whose API changed between versions (world overlays before 1.21.11's gizmos). */
final class McDraw {
    private McDraw() {}

    static void lineVertex(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, int argb, float nx, float ny, float nz) {
//#if >=1.21
        vc.addVertex(p, x, y, z).setColor(argb).setNormal(p, nx, ny, nz);
//#elif >=1.20.5
        vc.vertex(p, x, y, z).color(argb).normal(p, nx, ny, nz).endVertex();
//#else
        Compat.argb(vc.vertex(p.pose(), x, y, z), argb).normal(p.normal(), nx, ny, nz).endVertex();
//#endif
    }

    static void lineBox(PoseStack pose, VertexConsumer vc, double x0, double y0, double z0, double x1, double y1, double z1, int argb) {
//#if >=1.21.9
        ShapeRenderer.renderLineBox(pose.last(), vc, x0, y0, z0, x1, y1, z1,
//#else
        ShapeRenderer.renderLineBox(pose, vc, x0, y0, z0, x1, y1, z1,
//#endif
                ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, (argb >>> 24) / 255f);
    }

    /** World-space text facing the camera (the helper subtracts the camera itself). */
    static void floatingText(PoseStack pose, MultiBufferSource buffers, String text, double x, double y, double z, int argb, float scale, boolean throughWalls) {
//#if >=1.19.4
        DebugRenderer.renderFloatingText(pose, buffers, text, x, y, z, argb, scale, true, 0f, throughWalls);
//#else
        DebugRenderer.renderFloatingText(text, x, y, z, argb, scale, true, 0f, throughWalls);
//#endif
    }
}
//#endif
