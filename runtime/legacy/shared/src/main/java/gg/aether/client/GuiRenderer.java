package gg.aether.client;

import gg.aether.core.UiRenderer;
import gg.aether.mc.Gfx;
import gg.aether.mc.LegacyCompat;
import gg.aether.mc.McFonts;
import gg.aether.mc.McTextures;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.util.Window;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

/**
 * {@link UiRenderer} on legacy OpenGL (1.7.10 - 1.12.2): immediate-mode quads through the Tessellator, the GL matrix
 * stack for push/scale and GL scissor for clipping. Coordinates are GUI-scaled, like vanilla screens.
 */
public final class GuiRenderer implements UiRenderer {
    private static final Map<String, ItemStack> ITEMS = new HashMap<String, ItemStack>();

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final TextRenderer font = mc.textRenderer;
//#if >=1.8
    private final Window window = new Window(mc);
//#else
    private final Window window = new Window(mc, mc.width, mc.height);
//#endif
    private UiRenderer.Font face = UiRenderer.Font.VANILLA;
    /** Current push scale, for pixelScale() and scissor. */
    private float scale = 1f, tx, ty;
    private final java.util.ArrayDeque<float[]> stack = new java.util.ArrayDeque<float[]>();

    @Override public int screenWidth() { return window.getWidth(); }
    @Override public int screenHeight() { return window.getHeight(); }

    private static void begin() {
        Gfx.enableBlend();
        Gfx.disableTexture();
        Gfx.blendFuncSeparate(770, 771, 1, 0);
        Gfx.disableAlphaTest();
        Gfx.shadeModel(GL11.GL_SMOOTH);
    }

    private static void end() {
        Gfx.shadeModel(GL11.GL_FLAT);
        Gfx.enableAlphaTest();
        Gfx.enableTexture();
        Gfx.disableBlend();
        Gfx.color(1f, 1f, 1f, 1f);
    }

    private static void quad(float x0, float y0, float x1, float y1, int argb) {
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, bl = argb & 0xFF;
        Gfx.vertex(x0, y1, 0, 0, r, g, bl, a);
        Gfx.vertex(x1, y1, 0, 0, r, g, bl, a);
        Gfx.vertex(x1, y0, 0, 0, r, g, bl, a);
        Gfx.vertex(x0, y0, 0, 0, r, g, bl, a);
    }

    @Override public void rect(float x, float y, float w, float h, int argb, float cornerRadius) {
        if ((argb >>> 24) == 0 || w <= 0 || h <= 0) return;
        float x0 = Math.round(x), y0 = Math.round(y), x1 = Math.round(x + w), y1 = Math.round(y + h);
        int r = (int) Math.min(cornerRadius, Math.min(x1 - x0, y1 - y0) / 2f);
        begin();
        Gfx.begin(false);
        if (r < 1) {
            quad(x0, y0, x1, y1, argb);
        } else {
            // Rounded corners as per-row insets; rows never overlap, so translucent colours stay even.
            for (int i = 0; i < r; i++) {
                double d = r - i - 0.5;
                int inset = (int) Math.round(r - Math.sqrt(r * r - d * d));
                quad(x0 + inset, y0 + i, x1 - inset, y0 + i + 1, argb);
                quad(x0 + inset, y1 - i - 1, x1 - inset, y1 - i, argb);
            }
            quad(x0, y0 + r, x1, y1 - r, argb);
        }
        Gfx.draw();
        end();
    }

    @Override public void text(String s, float x, float y, int argb, boolean shadow) {
        if ((argb >>> 24) < 4) return; // vanilla treats alpha < 4 as opaque
        McFonts.Face f = McFonts.face(face);
        if (f == null || !f.supports(s)) {
            Gfx.enableBlend();
            LegacyCompat.drawText(font, s, x, y, argb, shadow);
            return;
        }
        // Vanilla's shadow: a quarter as bright, one unit down-right.
        if (shadow) glyphs(f, s, x + 1, y + 1, (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2));
        glyphs(f, s, x, y, argb);
    }

    /** One tinted quad per character from the face's atlas. */
    private void glyphs(McFonts.Face f, String s, float x, float y, int argb) {
        if (!McTextures.bind(f.texture)) return;
        float ox = x - f.pad, oy = y + McFonts.BASELINE - f.ascent, os = f.oversample;
        float cw = f.cellW / os, ch = f.cellH / os;
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, bl = argb & 0xFF;
        Gfx.enableBlend();
        Gfx.blendFuncSeparate(770, 771, 1, 0);
        Gfx.disableAlphaTest();
        Gfx.begin(true);
        float pen = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != ' ') {
                float u0 = f.u(c) / (float) f.atlasW, v0 = f.v(c) / (float) f.atlasH;
                float u1 = (f.u(c) + f.cellW) / (float) f.atlasW, v1 = (f.v(c) + f.cellH) / (float) f.atlasH;
                float px = ox + pen;
                Gfx.vertex(px, oy + ch, u0, v1, r, g, bl, a);
                Gfx.vertex(px + cw, oy + ch, u1, v1, r, g, bl, a);
                Gfx.vertex(px + cw, oy, u1, v0, r, g, bl, a);
                Gfx.vertex(px, oy, u0, v0, r, g, bl, a);
            }
            pen += f.advance(c);
        }
        Gfx.draw();
        Gfx.enableAlphaTest();
        Gfx.disableBlend();
    }

    @Override public int textWidth(String s) {
        McFonts.Face f = McFonts.face(face);
        return f == null || !f.supports(s) ? font.getStringWidth(s) : (int) Math.ceil(f.width(s));
    }

    @Override public int fontHeight() { return font.fontHeight; }

    @Override public void font(UiRenderer.Font f) { face = f == null ? UiRenderer.Font.VANILLA : f; }

    /** Textured quad of the currently bound texture, multiplied by {@code tint}. */
    private static void texQuad(float x, float y, float w, float h, float u0, float v0, float u1, float v1, int tint) {
        int a = tint >>> 24, r = (tint >> 16) & 0xFF, g = (tint >> 8) & 0xFF, bl = tint & 0xFF;
        Gfx.enableBlend();
        Gfx.blendFuncSeparate(770, 771, 1, 0);
        Gfx.disableAlphaTest();
        Gfx.begin(true);
        Gfx.vertex(x, y + h, u0, v1, r, g, bl, a);
        Gfx.vertex(x + w, y + h, u1, v1, r, g, bl, a);
        Gfx.vertex(x + w, y, u1, v0, r, g, bl, a);
        Gfx.vertex(x, y, u0, v0, r, g, bl, a);
        Gfx.draw();
        Gfx.enableAlphaTest();
        Gfx.disableBlend();
    }

    @Override public boolean cosmeticIcon(gg.aether.core.cosmetics.Cosmetic c, float x, float y, float w, float h) {
        gg.aether.mc.LegacyCosmetics.Icon ic = gg.aether.mc.LegacyCosmetics.icon(c);
        if (ic == null || ic.w <= 0 || ic.h <= 0 || !McTextures.bind(ic.id)) return false;
        float s = Math.min(w / ic.w, h / ic.h);
        int dw = Math.round(ic.w * s), dh = Math.round(ic.h * s);
        texQuad(Math.round(x + (w - dw) / 2f), Math.round(y + (h - dh) / 2f), dw, dh,
                (float) ic.u / ic.texW, (float) ic.v / ic.texH, (float) (ic.u + ic.w) / ic.texW, (float) (ic.v + ic.h) / ic.texH, -1);
        return true;
    }

    /** Account faces are 8x8 pixel art: drawn as a nearest-filtered image, so every skin pixel stays a sharp square. */
    @Override public boolean head(String playerName, String uuid, float x, float y, float size) {
        int[] face = gg.aether.core.SkinFaces.face(uuid);
        if (face == null || face.length != 64) return false;
        return image("aether:face/" + uuid, 8, 8, face, System.identityHashCode(face), x, y, size, size);
    }

    @Override public boolean texture(String id, float x, float y, float w, float h) {
        Identifier rid = new Identifier(id);
        if (McTextures.size(rid) == null || !McTextures.bind(rid)) return false;
        texQuad(Math.round(x), Math.round(y), Math.round(w), Math.round(h), 0, 0, 1, 1, -1);
        return true;
    }

    @Override public void item(String itemId, float x, float y) {
        ItemStack st = ITEMS.get(itemId);
        if (st == null) {
            Item item = gg.aether.mc.McItems.item(itemId);
            if (item == null) return;
            st = new ItemStack(item);
            ITEMS.put(itemId, st);
        }
        Gfx.enableRescaleNormal();
        DiffuseLighting.enable();
        Gfx.enableDepthTest();
        gg.aether.mc.LegacyCompat.renderGuiItem(mc, st, Math.round(x), Math.round(y));
        DiffuseLighting.disable();
        Gfx.disableRescaleNormal();
        Gfx.disableDepthTest();
    }

    @Override public void push(float x, float y, float s) {
        stack.push(new float[] { scale, tx, ty });
        tx += x * scale;
        ty += y * scale;
        scale *= s;
        Gfx.pushMatrix();
        Gfx.translate(x, y, 0f);
        Gfx.scale(s, s, 1f);
    }

    @Override public void pop() {
        Gfx.popMatrix();
        float[] p = stack.pop();
        scale = p[0];
        tx = p[1];
        ty = p[2];
    }

    @Override public void clip(float x, float y, float w, float h) {
        // GL scissor works in window pixels from the bottom-left; (x, y, w, h) is in the current push transform.
        int f = window.getScaleFactor();
        int sx = Math.round((tx + x * scale) * f), sy = Math.round((ty + y * scale) * f);
        int sw = Math.round(w * scale * f), sh = Math.round(h * scale * f);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(sx, mc.height - sy - sh, Math.max(0, sw), Math.max(0, sh));
    }

    @Override public void unclip() { GL11.glDisable(GL11.GL_SCISSOR_TEST); }

    @Override public boolean image(String key, int w, int h, int[] argb, long version, float x, float y, float dw, float dh) {
        return image(key, w, h, argb, version, x, y, dw, dh, -1);
    }

    @Override public boolean image(String key, int w, int h, int[] argb, long version, float x, float y, float dw, float dh, int tint) {
        if (w <= 0 || h <= 0) return false;
        Identifier id = McTextures.prepare(key, w, h, argb, version);
        if (!McTextures.bind(id)) return false;
        texQuad(Math.round(x), Math.round(y), Math.round(dw), Math.round(dh), 0, 0, 1, 1, tint);
        return true;
    }

    @Override public float pixelScale() { return window.getScaleFactor() * scale; }

    @Override public boolean player(float x, float y, float w, float h, float yaw) {
        if (mc.player == null) return false;
        float size = Math.min(h / 2.3f, w / 2.6f);
        AetherScreen.drawPlayer(Math.round(x + w / 2), Math.round(y + h - h * 0.08f), Math.round(size), yaw);
        return true;
    }
}
