package gg.aether.client;

import gg.aether.core.UiRenderer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
//#if >=26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#elif >=1.20
import net.minecraft.client.gui.GuiGraphics;
//#endif
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * {@link UiRenderer} on the version's GUI graphics: GUI-scaled coordinates, the GUI pose for push/scale. The few
 * calls that changed between versions (blit, pose matrices, draw layers, the entity preview) are the helpers at the end.
 */
public final class GuiRenderer implements UiRenderer {
    private static final Map<String, ItemStack> ITEMS = new HashMap<String, ItemStack>();
    /** Anti-aliased disc whose quarters are the rounded corners (see {@link #rect}). */
    private static Identifier CORNER;

//#if >=26.1
    private final GuiGraphicsExtractor g;
//#elif >=1.20
    private final GuiGraphics g;
//#else
    private final LegacyGuiGraphics g;
//#endif
    private final net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
    /** Face for text()/textWidth(); see {@link gg.aether.mc.McUi}. */
    private UiRenderer.Font face = UiRenderer.Font.VANILLA;

//#if >=26.1
    public GuiRenderer(GuiGraphicsExtractor g) { this.g = g; }
//#elif >=1.20
    public GuiRenderer(GuiGraphics g) { this.g = g; }
//#else
    public GuiRenderer(LegacyGuiGraphics g) { this.g = g; }
//#endif

    @Override public int screenWidth() { return g.guiWidth(); }
    @Override public int screenHeight() { return g.guiHeight(); }

    @Override public void rect(float x, float y, float w, float h, int argb, float cornerRadius) {
        if ((argb >>> 24) == 0 || w <= 0 || h <= 0) return;
        int x0 = Math.round(x), y0 = Math.round(y), x1 = Math.round(x + w), y1 = Math.round(y + h);
        int r = (int) Math.min(cornerRadius, Math.min(x1 - x0, y1 - y0) / 2f);
        if (r < 1) {
            g.fill(x0, y0, x1, y1, argb);
            return;
        }
        // Smooth corners: quarter-discs of an anti-aliased, linearly filtered texture, tinted to the fill colour. The
        // strips between them don't overlap the corners, so translucent colours stay even.
        if (CORNER == null) CORNER = Identifier.tryParse("aether:textures/gui/corner.png");
        int[] size = r >= 2 ? gg.aether.mc.McUi.texture(CORNER) : null;
        if (size != null) {
            int half = size[0] / 2;
            g.fill(x0 + r, y0, x1 - r, y0 + r, argb);
            g.fill(x0 + r, y1 - r, x1 - r, y1, argb);
            g.fill(x0, y0 + r, x1, y1 - r, argb);
            blit(CORNER, x0, y0, 0f, 0f, r, r, half, half, size[0], size[1], argb);
            blit(CORNER, x1 - r, y0, half, 0f, r, r, half, half, size[0], size[1], argb);
            blit(CORNER, x0, y1 - r, 0f, half, r, r, half, half, size[0], size[1], argb);
            blit(CORNER, x1 - r, y1 - r, half, half, r, r, half, half, size[0], size[1], argb);
            return;
        }
        // Fallback: per-row insets.
        for (int i = 0; i < r; i++) {
            double d = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(r * r - d * d));
            g.fill(x0 + inset, y0 + i, x1 - inset, y0 + i + 1, argb);
            g.fill(x0 + inset, y1 - i - 1, x1 - inset, y1 - i, argb);
        }
        g.fill(x0, y0 + r, x1, y1 - r, argb);
    }

    @Override public void text(String s, float x, float y, int argb, boolean shadow) {
        if ((argb >>> 24) < 4) return; // vanilla treats alpha < 4 as opaque
        gg.aether.mc.McFonts.Face f = gg.aether.mc.McFonts.face(face);
        if (f == null || !f.supports(s)) {
//#if >=26.1
            g.text(font, s, Math.round(x), Math.round(y), argb, shadow);
//#else
            g.drawString(font, s, Math.round(x), Math.round(y), argb, shadow);
//#endif
            return;
        }
        // Vanilla's shadow: a quarter as bright, one unit down-right.
        if (shadow) glyphs(f, s, x + 1, y + 1, (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2));
        glyphs(f, s, x, y, argb);
    }

    /** One tinted quad per character from the face's atlas, positioned at atlas resolution for sub-pixel accuracy. */
    private void glyphs(gg.aether.mc.McFonts.Face f, String s, float x, float y, int argb) {
        int os = f.oversample;
        pushPose();
        translate(x - f.pad, y + gg.aether.mc.McFonts.BASELINE - f.ascent);
        scale(1f / os);
        float pen = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != ' ') blit(f.texture, Math.round(pen * os), 0, f.u(c), f.v(c), f.cellW, f.cellH, f.cellW, f.cellH, f.atlasW, f.atlasH, argb);
            pen += f.advance(c);
        }
        popPose();
    }

    @Override public int textWidth(String s) {
        gg.aether.mc.McFonts.Face f = gg.aether.mc.McFonts.face(face);
        return f == null || !f.supports(s) ? font.width(s) : (int) Math.ceil(f.width(s));
    }

    @Override public void font(UiRenderer.Font f) { face = f == null ? UiRenderer.Font.VANILLA : f; }

    @Override public boolean texture(String id, float x, float y, float w, float h) {
        Identifier rid = Identifier.tryParse(id);
        int[] size = gg.aether.mc.McUi.texture(rid);
        if (size == null) return false;
        blit(rid, Math.round(x), Math.round(y), 0f, 0f, Math.round(w), Math.round(h), size[0], size[1], size[0], size[1], -1);
        return true;
    }

    @Override public int fontHeight() { return font.lineHeight; }

    @Override public void item(String itemId, float x, float y) {
        ItemStack st = ITEMS.get(itemId);
        if (st == null) {
            Identifier id = Identifier.tryParse(itemId);
            try {
                st = id == null ? ItemStack.EMPTY : new ItemStack(gg.aether.mc.Compat.item(id));
            } catch (RuntimeException e) {
                // Item components aren't bound until resources finish loading, but the title screen already draws
                // under the loading overlay. Skip the icon for now (not cached) and try again next frame.
                return;
            }
            ITEMS.put(itemId, st);
        }
//#if >=26.1
        if (!st.isEmpty()) g.fakeItem(st, Math.round(x), Math.round(y));
//#else
        if (!st.isEmpty()) g.renderFakeItem(st, Math.round(x), Math.round(y));
//#endif
    }

    @Override public void push(float tx, float ty, float s) {
        pushPose();
        translate(tx, ty);
        scale(s);
    }

    @Override public void pop() { popPose(); }

    @Override public void clip(float x, float y, float w, float h) {
        g.enableScissor(Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h));
    }

    @Override public void unclip() { g.disableScissor(); }

    @Override public boolean cosmeticIcon(gg.aether.core.cosmetics.Cosmetic c, float x, float y, float w, float h) {
        gg.aether.mc.McCosmetics.Icon ic = gg.aether.mc.McCosmetics.icon(c);
        if (ic == null || ic.w <= 0 || ic.h <= 0) return false;
        float s = Math.min(w / ic.w, h / ic.h);
        int dw = Math.round(ic.w * s), dh = Math.round(ic.h * s);
        blit(ic.id, Math.round(x + (w - dw) / 2f), Math.round(y + (h - dh) / 2f), ic.u, ic.v, dw, dh, ic.w, ic.h, ic.texW, ic.texH, -1);
        return true;
    }

    @Override public boolean image(String key, int w, int h, int[] argb, long version, float x, float y, float dw, float dh) {
        return image(key, w, h, argb, version, x, y, dw, dh, -1);
    }

    @Override public boolean image(String key, int w, int h, int[] argb, long version, float x, float y, float dw, float dh, int tint) {
        if (w <= 0 || h <= 0) return false;
        Identifier id = gg.aether.mc.McTextures.prepare(key, w, h, argb, version);
        blit(id, Math.round(x), Math.round(y), 0f, 0f, Math.round(dw), Math.round(dh), w, h, w, h, tint);
        return true;
    }

    /** Account faces are 8x8 pixel art: drawn as a nearest-filtered image, so every skin pixel stays a sharp square. */
    @Override public boolean head(String playerName, String uuid, float x, float y, float size) {
        int[] face = gg.aether.mc.McSkins.face(uuid);
        if (face == null || face.length != 64) return false;
        return image("aether:face/" + uuid, 8, 8, face, System.identityHashCode(face), x, y, size, size);
    }

    @Override public float pixelScale() { return (float) Minecraft.getInstance().getWindow().getGuiScale() * poseScale(); }

    @Override public boolean player(float x, float y, float w, float h, float yaw) { return player(x, y, w, h, yaw, 0); }

    @Override public boolean player(float x, float y, float w, float h, float yaw, float pitch) {
        Minecraft mc = Minecraft.getInstance();
//#if >=1.21.9
        // No world (the shop from the main menu): a free-standing player render state with the account's skin.
        if (mc.player == null && mc.getUser() == null) return false;
//#else
        if (mc.player == null) return false;
//#endif
        // The entity is drawn in screen space: map the box through the current (possibly scaled) pose.
        float[] a = toScreen(x, y), b = toScreen(x + w, y + h);
        int x0 = Math.round(a[0]), y0 = Math.round(a[1]), x1 = Math.round(b[0]), y1 = Math.round(b[1]);
        // Room for hats above and wings to the sides.
        float scale = Math.min((y1 - y0) / 2.3f, (x1 - x0) / 2.6f);
//#if >=1.19.3
        org.joml.Quaternionf rotation = new org.joml.Quaternionf().rotateZ((float) Math.PI);
        // Looking down a little by default; the wardrobe tilts it further (top-down) or up from below.
        org.joml.Quaternionf camera = new org.joml.Quaternionf().rotateX((float) Math.toRadians(Math.max(-80, Math.min(89, 8 + pitch))));
        rotation.mul(camera);
//#endif
//#if >=1.21.6
//#if >=1.21.9
        net.minecraft.client.renderer.entity.state.EntityRenderState st = mc.player != null ? gg.aether.mc.McCosmetics.previewState(mc.player, yaw)
                : gg.aether.mc.McCosmetics.menuPreviewState(yaw);
//#else
        net.minecraft.client.renderer.entity.state.EntityRenderState st = gg.aether.mc.McCosmetics.previewState(mc.player, yaw);
//#endif
        org.joml.Vector3f offset = new org.joml.Vector3f(0, st.boundingBoxHeight / 2f + 0.0625f, 0);
//#if >=26.1
        g.entity(st, scale, offset, rotation, camera, x0, y0, x1, y1);
//#else
        g.submitEntityRenderState(st, scale, offset, rotation, camera, x0, y0, x1, y1);
//#endif
//#else
        // Before 1.21.6 the GUI draws a live entity: turn the player for the preview, then put everything back.
        net.minecraft.world.entity.LivingEntity e = mc.player;
        float body = e.yBodyRot, yRot = gg.aether.mc.Compat.yRot(e), xRot = gg.aether.mc.Compat.xRot(e), headO = e.yHeadRotO, head = e.yHeadRot;
        e.yBodyRot = 180f + yaw;
        gg.aether.mc.Compat.setRot(e, 180f + yaw, 0);
        e.yHeadRot = gg.aether.mc.Compat.yRot(e);
        e.yHeadRotO = gg.aether.mc.Compat.yRot(e);
//#if >=1.19.3
        org.joml.Vector3f offset = new org.joml.Vector3f(0, e.getBbHeight() / 2f + 0.0625f, 0);
//#endif
        g.enableScissor(x0, y0, x1, y1);
//#if >=1.20
        gg.aether.mc.Compat.renderEntityInInventory(g, (x0 + x1) / 2f, (y0 + y1) / 2f, scale, offset, rotation, camera, e);
//#elif >=1.19.4
        net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventory(g.pose(), (x0 + x1) / 2,
                Math.round((y0 + y1) / 2f + offset.y() * scale), Math.round(scale), rotation, camera, e);
//#else
        net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventory((x0 + x1) / 2,
                Math.round((y0 + y1) / 2f + (e.getBbHeight() / 2f + 0.0625f) * scale), Math.round(scale), yaw, pitch, e);
//#endif
        g.disableScissor();
        e.yBodyRot = body;
        gg.aether.mc.Compat.setRot(e, yRot, xRot);
        e.yHeadRotO = headO;
        e.yHeadRot = head;
//#endif
        return true;
    }

    // ---- version helpers ------------------------------------------------------------------------------------------

    /** Whole-texture-region blit, tinted (ARGB; -1 = untinted). */
    private void blit(Identifier tex, int x, int y, float u, float v, int w, int h, int regionW, int regionH, int texW, int texH, int tint) {
//#if >=1.21.6
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w, h, regionW, regionH, texW, texH, tint);
//#elif >=1.21.2
        g.blit(net.minecraft.client.renderer.RenderType::guiTextured, tex, x, y, u, v, w, h, regionW, regionH, texW, texH, tint);
//#else
        // No tinted blit before 1.21.2: tint through the shader colour, blended for translucent textures.
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
//#if >=1.17
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(((tint >> 16) & 0xFF) / 255f, ((tint >> 8) & 0xFF) / 255f, (tint & 0xFF) / 255f, (tint >>> 24) / 255f);
        g.blit(tex, x, y, w, h, u, v, regionW, regionH, texW, texH);
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
//#else
        com.mojang.blaze3d.systems.RenderSystem.color4f(((tint >> 16) & 0xFF) / 255f, ((tint >> 8) & 0xFF) / 255f, (tint & 0xFF) / 255f, (tint >>> 24) / 255f);
        g.blit(tex, x, y, w, h, u, v, regionW, regionH, texW, texH);
        com.mojang.blaze3d.systems.RenderSystem.color4f(1f, 1f, 1f, 1f);
//#endif
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
//#endif
    }

    /** Start a new layer: everything drawn after it covers everything before, text included. */
    @Override public void layer() {
//#if >=1.21.6
        g.nextStratum();
//#else
        // Draw what is queued now; later draws land on top (painter's order at equal depth).
        g.flush();
//#endif
    }

//#if >=1.21.6
    private void pushPose() { g.pose().pushMatrix(); }
    private void popPose() { g.pose().popMatrix(); }
    private void translate(float x, float y) { g.pose().translate(x, y); }
    private void scale(float s) { g.pose().scale(s, s); }
    private float poseScale() { return g.pose().m00(); }

    private float[] toScreen(float x, float y) {
        org.joml.Vector2f v = g.pose().transformPosition(new org.joml.Vector2f(x, y));
        return new float[] { v.x, v.y };
    }
//#elif >=1.19.3
    private void pushPose() { g.pose().pushPose(); }
    private void popPose() { g.pose().popPose(); }
    private void translate(float x, float y) { g.pose().translate(x, y, 0f); }
    private void scale(float s) { g.pose().scale(s, s, 1f); }
    private float poseScale() { return g.pose().last().pose().m00(); }

    private float[] toScreen(float x, float y) {
        org.joml.Vector4f v = g.pose().last().pose().transform(new org.joml.Vector4f(x, y, 0f, 1f));
        return new float[] { v.x, v.y };
    }
//#else
    private void pushPose() { g.pose().pushPose(); }
    private void popPose() { g.pose().popPose(); }
    private void translate(float x, float y) { g.pose().translate(x, y, 0f); }
    private void scale(float s) { g.pose().scale(s, s, 1f); }
    private float poseScale() { float[] a = toScreen(0f, 0f), b = toScreen(1f, 0f); return Math.abs(b[0] - a[0]); }

    private float[] toScreen(float x, float y) {
        com.mojang.math.Vector4f v = new com.mojang.math.Vector4f(x, y, 0f, 1f);
        v.transform(g.pose().last().pose());
        return new float[] { v.x(), v.y() };
    }
//#endif
}
