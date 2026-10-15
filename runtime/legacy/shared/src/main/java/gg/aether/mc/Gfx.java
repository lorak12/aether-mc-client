package gg.aether.mc;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
//#if >=1.8
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
//#else
import net.minecraft.client.render.Tessellator;
//#endif

/**
 * Fixed-function GL and quad drawing for the legacy adapters. 1.8+ goes through GlStateManager (it caches state, so
 * raw calls would desync it); 1.7.10 has no such layer and the Tessellator is a singleton with no vertex formats.
 */
public final class Gfx {
    private Gfx() {}

    // ---- state ------------------------------------------------------------------------------------------------------

//#if >=1.8
    public static void enableBlend() { GlStateManager.enableBlend(); }
    public static void disableBlend() { GlStateManager.disableBlend(); }
    public static void enableTexture() { GlStateManager.enableTexture(); }
    public static void disableTexture() { GlStateManager.disableTexture(); }
    public static void enableAlphaTest() { GlStateManager.enableAlphaTest(); }
    public static void disableAlphaTest() { GlStateManager.disableAlphaTest(); }
    public static void enableDepthTest() { GlStateManager.enableDepthTest(); }
    public static void disableDepthTest() { GlStateManager.disableDepthTest(); }
    public static void enableRescaleNormal() { GlStateManager.enableRescaleNormal(); }
    public static void disableRescaleNormal() { GlStateManager.disableRescaleNormal(); }
    public static void enableColorMaterial() { GlStateManager.enableColorMaterial(); }
    public static void blendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) { GlStateManager.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha); }
    public static void shadeModel(int mode) { GlStateManager.shadeModel(mode); }
    public static void color(float r, float g, float b, float a) { GlStateManager.color(r, g, b, a); }
    public static void pushMatrix() { GlStateManager.pushMatrix(); }
    public static void popMatrix() { GlStateManager.popMatrix(); }
    public static void translate(float x, float y, float z) { GlStateManager.translate(x, y, z); }
    public static void scale(float x, float y, float z) { GlStateManager.scale(x, y, z); }
    public static void rotate(float angle, float x, float y, float z) { GlStateManager.rotate(angle, x, y, z); }
    /** Texture unit 1 (the lightmap) and back to unit 0, as vanilla does after drawing an entity in a GUI. */
    public static void lightmapUnitTextureOff() {
        GlStateManager.activeTexture(com.mojang.blaze3d.platform.GLX.lightmapTextureUnit);
        GlStateManager.disableTexture();
        GlStateManager.activeTexture(com.mojang.blaze3d.platform.GLX.textureUnit);
    }
//#else
    public static void enableBlend() { GL11.glEnable(GL11.GL_BLEND); }
    public static void disableBlend() { GL11.glDisable(GL11.GL_BLEND); }
    public static void enableTexture() { GL11.glEnable(GL11.GL_TEXTURE_2D); }
    public static void disableTexture() { GL11.glDisable(GL11.GL_TEXTURE_2D); }
    public static void enableAlphaTest() { GL11.glEnable(GL11.GL_ALPHA_TEST); }
    public static void disableAlphaTest() { GL11.glDisable(GL11.GL_ALPHA_TEST); }
    public static void enableDepthTest() { GL11.glEnable(GL11.GL_DEPTH_TEST); }
    public static void disableDepthTest() { GL11.glDisable(GL11.GL_DEPTH_TEST); }
    public static void enableRescaleNormal() { GL11.glEnable(GL12.GL_RESCALE_NORMAL); }
    public static void disableRescaleNormal() { GL11.glDisable(GL12.GL_RESCALE_NORMAL); }
    public static void enableColorMaterial() { GL11.glEnable(GL11.GL_COLOR_MATERIAL); }
    public static void blendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) { GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha); }
    public static void shadeModel(int mode) { GL11.glShadeModel(mode); }
    public static void color(float r, float g, float b, float a) { GL11.glColor4f(r, g, b, a); }
    public static void pushMatrix() { GL11.glPushMatrix(); }
    public static void popMatrix() { GL11.glPopMatrix(); }
    public static void translate(float x, float y, float z) { GL11.glTranslatef(x, y, z); }
    public static void scale(float x, float y, float z) { GL11.glScalef(x, y, z); }
    public static void rotate(float angle, float x, float y, float z) { GL11.glRotatef(angle, x, y, z); }
    public static void lightmapUnitTextureOff() {
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }
//#endif

    // ---- quads ------------------------------------------------------------------------------------------------------

    private static boolean textured;
//#if >=1.8
    private static Tessellator tess;
    private static BufferBuilder buf;

    /** Start a batch of quads, position + colour or position + texture + colour. */
    public static void begin(boolean withTexture) {
        textured = withTexture;
        tess = Tessellator.getInstance();
        buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, withTexture ? VertexFormats.POSITION_TEXTURE_COLOR : VertexFormats.POSITION_COLOR);
    }

    public static void vertex(float x, float y, float u, float v, int r, int g, int b, int a) {
        if (textured) buf.vertex(x, y, 0).texture(u, v).color(r, g, b, a).next();
        else buf.vertex(x, y, 0).color(r, g, b, a).next();
    }

    public static void draw() { tess.draw(); }
//#else
    private static Tessellator tess;

    public static void begin(boolean withTexture) {
        textured = withTexture;
        tess = Tessellator.INSTANCE;
        tess.begin(GL11.GL_QUADS);
    }

    public static void vertex(float x, float y, float u, float v, int r, int g, int b, int a) {
        tess.color(r, g, b, a);
        if (textured) tess.texture(u, v);
        tess.vertex(x, y, 0);
    }

    public static void draw() { tess.end(); }
//#endif
}
