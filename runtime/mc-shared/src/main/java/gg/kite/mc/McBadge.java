//#if >=1.21.11
package gg.kite.mc;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import gg.kite.core.cosmetics.Cosmetics;
import gg.kite.core.ui.KiteMark;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.PlainTextRenderable;
import net.minecraft.client.gui.font.SingleSpriteSource;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * The client badge before client users' names (nametags, tab list): the Kite mark as a glyph of its own font,
 * "kite:badge", so it sits in the text like any character (same line, scale and shadow, no background). Vanilla only
 * builds fonts from resource packs, which the jar is not without Fabric API, so FontProviderMixin hands out
 * {@link #SOURCE} for that font. The texture is the mark rasterised at 8 x the GUI scale: pixel for pixel in the tab
 * list, and linearly filtered on nametags out in the world.
 */
public final class McBadge {
    private McBadge() {}

    public static final FontDescription FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("kite", "badge"));
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("kite", "badge/kite");
    private static final GlyphRenderTypes TYPES = GlyphRenderTypes.createForColorTexture(TEXTURE);
    /** 8 units wide like a player head glyph, plus vanilla's 1-unit gap after a character. */
    private static final GlyphInfo INFO = GlyphInfo.simple(9f);

    /** Pixel size of the uploaded texture; 0 before the first upload. Render thread. */
    private static int size;

    public static final GlyphSource SOURCE = new SingleSpriteSource(new BakedGlyph() {
        @Override public GlyphInfo info() { return INFO; }

        @Override public TextRenderable.Styled createGlyph(float x, float y, int color, int shadowColor, Style style, float boldOffset, float shadowOffset) {
            upload();
            return new Glyph(x, y, color, shadowColor, shadowOffset, style);
        }
    });

    /** The badge followed by a space, white so the mark keeps its colours (the glyph's colour tints it). */
    public static MutableComponent badge() {
        return Component.empty()
                .append(Component.literal(Cosmetics.BADGE).withStyle(Style.EMPTY.withFont(FONT).withColor(0xFFFFFF)))
                .append(Component.literal(" "));
    }

    /** (Re)build the texture when the GUI scale changed. */
    private static void upload() {
        Minecraft mc = Minecraft.getInstance();
        int want = Math.max(16, Math.min(64, 8 * (int) Math.ceil(mc.getWindow().getGuiScale())));
        if (want == size) return;
        int[] argb = KiteMark.pixels(want);
        NativeImage img = new NativeImage(want, want, false);
        for (int y = 0; y < want; y++) for (int x = 0; x < want; x++) Pixels.set(img, x, y, argb[y * want + x]);
        net.minecraft.client.renderer.texture.DynamicTexture tex = Compat.smoothTexture("kite badge", img);
        mc.getTextureManager().register(TEXTURE, tex);
        tex.upload();
        size = want;
    }

    /** One drawn badge: an 8x8 quad on the text line, like vanilla's player head glyphs. */
    private static final class Glyph implements PlainTextRenderable {
        private final float x, y, shadowOffset;
        private final int color, shadowColor;
        private final Style style;

        Glyph(float x, float y, int color, int shadowColor, float shadowOffset, Style style) {
            this.x = x;
            this.y = y;
            this.color = color;
            this.shadowColor = shadowColor;
            this.shadowOffset = shadowOffset;
            this.style = style;
        }

//#if >=26.1
        @Override public void renderSprite(org.joml.Matrix4fc pose, VertexConsumer buffer, int light, float offsetX, float offsetY, float z, int color) {
//#else
        @Override public void renderSprite(org.joml.Matrix4f pose, VertexConsumer buffer, int light, float offsetX, float offsetY, float z, int color) {
//#endif
            float l = offsetX + left(), r = offsetX + right(), t = offsetY + top(), b = offsetY + bottom();
            buffer.addVertex(pose, l, t, z).setUv(0f, 0f).setColor(color).setLight(light);
            buffer.addVertex(pose, l, b, z).setUv(0f, 1f).setColor(color).setLight(light);
            buffer.addVertex(pose, r, b, z).setUv(1f, 1f).setColor(color).setLight(light);
            buffer.addVertex(pose, r, t, z).setUv(1f, 0f).setColor(color).setLight(light);
        }

        @Override public float x() { return x; }
        @Override public float y() { return y; }
        @Override public int color() { return color; }
        @Override public int shadowColor() { return shadowColor; }
        @Override public float shadowOffset() { return shadowOffset; }
        @Override public Style style() { return style; }

        @Override public RenderType renderType(Font.DisplayMode mode) { return TYPES.select(mode); }
//#if >=26.3
        @Override public com.mojang.renderpearl.api.pipeline.RenderPipeline guiPipeline() { return TYPES.guiPipeline(); }
        @Override public com.mojang.renderpearl.api.textures.GpuTextureView textureView() { return Minecraft.getInstance().getTextureManager().getTexture(TEXTURE).getTextureView(); }
//#else
        @Override public com.mojang.blaze3d.pipeline.RenderPipeline guiPipeline() { return TYPES.guiPipeline(); }
        @Override public com.mojang.blaze3d.textures.GpuTextureView textureView() { return Minecraft.getInstance().getTextureManager().getTexture(TEXTURE).getTextureView(); }
//#endif
    }
}
//#endif
