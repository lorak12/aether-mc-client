package gg.aether.mc;

import gg.aether.core.WorldRenderer;
//#if >=1.21.11
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * {@link WorldRenderer} on vanilla's gizmo system (the one F3 debug renderers use), so the same code draws on every
 * render backend. Must be called while the game collects the frame's gizmos (DebugRenderer.emitGizmos).
 */
public final class McWorldRenderer implements WorldRenderer {
    private static final float TEXT_SCALE = TextGizmo.Style.whiteAndCentered().scale();

    private final double cx, cy, cz;

    public McWorldRenderer(double cx, double cy, double cz) {
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
    }

    @Override public double cameraX() { return cx; }
    @Override public double cameraY() { return cy; }
    @Override public double cameraZ() { return cz; }

    private static void top(GizmoProperties p, boolean throughWalls) { if (throughWalls && p != null) p.setAlwaysOnTop(); }

    @Override public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb, float width, boolean throughWalls) {
        top(Gizmos.line(new Vec3(x1, y1, z1), new Vec3(x2, y2, z2), argb, width), throughWalls);
    }

    @Override public void box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int stroke, float width, int fill, boolean throughWalls) {
        GizmoStyle style;
        boolean hasStroke = (stroke >>> 24) != 0, hasFill = (fill >>> 24) != 0;
        if (hasStroke && hasFill) style = GizmoStyle.strokeAndFill(stroke, width, fill);
        else if (hasStroke) style = GizmoStyle.stroke(stroke, width);
        else if (hasFill) style = GizmoStyle.fill(fill);
        else return;
        top(Gizmos.cuboid(new AABB(minX, minY, minZ, maxX, maxY, maxZ), style), throughWalls);
    }

    @Override public void face(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, String side, int fill, boolean throughWalls) {
        Direction dir = Direction.byName(side);
        if ((fill >>> 24) == 0) return;
        if (dir == null) { box(minX, minY, minZ, maxX, maxY, maxZ, 0, 1, fill, throughWalls); return; }
        // One quad, wound like the matching face of a cuboid gizmo (a flat cuboid would draw two coincident ones).
        top(Gizmos.rect(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ), dir, GizmoStyle.fill(fill)), throughWalls);
    }

    @Override public void text(String text, double x, double y, double z, int argb, float scale, boolean throughWalls) {
        top(Gizmos.billboardText(text, new Vec3(x, y, z), TextGizmo.Style.forColorAndCentered(argb).withScale(TEXT_SCALE * scale)), throughWalls);
    }
}
//#else
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;

/**
 * {@link WorldRenderer} before 1.21.11's gizmos: lines and boxes into the debug renderer's buffers, text through its
 * floating-text helper. Called at the end of DebugRenderer.render (camera-relative pose). Lines and boxes always
 * respect depth here; only text can show through walls.
 */
public final class McWorldRenderer implements WorldRenderer {
    private static final float TEXT_SCALE = 0.02f;

    private final PoseStack pose;
    private final MultiBufferSource buffers;
    private final double cx, cy, cz;

    public McWorldRenderer(PoseStack pose, MultiBufferSource buffers, double cx, double cy, double cz) {
        this.pose = pose;
        this.buffers = buffers;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
    }

    @Override public double cameraX() { return cx; }
    @Override public double cameraY() { return cy; }
    @Override public double cameraZ() { return cz; }

    @Override public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb, float width, boolean throughWalls) {
        float dx = (float) (x2 - x1), dy = (float) (y2 - y1), dz = (float) (z2 - z1);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0) return;
        dx /= len;
        dy /= len;
        dz /= len;
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        PoseStack.Pose p = pose.last();
        McDraw.lineVertex(vc, p, (float) (x1 - cx), (float) (y1 - cy), (float) (z1 - cz), argb, dx, dy, dz);
        McDraw.lineVertex(vc, p, (float) (x2 - cx), (float) (y2 - cy), (float) (z2 - cz), argb, dx, dy, dz);
    }

    @Override public void box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int stroke, float width, int fill, boolean throughWalls) {
        double x0 = minX - cx, y0 = minY - cy, z0 = minZ - cz, x1 = maxX - cx, y1 = maxY - cy, z1 = maxZ - cz;
        if ((fill >>> 24) != 0) {
//#if >=1.19.4
            DebugRenderer.renderFilledBox(pose, buffers, x0, y0, z0, x1, y1, z1,
                    ((fill >> 16) & 0xFF) / 255f, ((fill >> 8) & 0xFF) / 255f, (fill & 0xFF) / 255f, (fill >>> 24) / 255f);
//#else
            DebugRenderer.renderFilledBox(minX, minY, minZ, maxX, maxY, maxZ,
                    ((fill >> 16) & 0xFF) / 255f, ((fill >> 8) & 0xFF) / 255f, (fill & 0xFF) / 255f, (fill >>> 24) / 255f);
//#endif
        }
        if ((stroke >>> 24) != 0) McDraw.lineBox(pose, buffers.getBuffer(RenderType.lines()), x0, y0, z0, x1, y1, z1, stroke);
    }

    @Override public void text(String text, double x, double y, double z, int argb, float scale, boolean throughWalls) {
        McDraw.floatingText(pose, buffers, text, x, y, z, argb, TEXT_SCALE * scale, throughWalls);
    }
}
//#endif
