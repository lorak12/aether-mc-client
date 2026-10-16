package gg.kite.mc;

import gg.kite.core.UiRenderer;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import net.minecraft.util.Identifier;

/**
 * The Kite UI faces (Inter, Sora) as glyph atlases rasterized with Java2D from the TTFs in the jar. Legacy versions
 * have no TTF font providers at all. Glyphs are
 * rendered {@code oversample}x larger than drawn and sampled linearly, so text stays crisp at any GUI scale.
 */
public final class McFonts {
    private McFonts() {}

    /** Latin-1 printable range; anything else falls back to the vanilla font. */
    static final char FIRST = 32, LAST = 255;

    /** One face at one size: the atlas texture plus per-character metrics, in GUI units. */
    public static final class Face {
        public final Identifier texture;
        /** Atlas pixels per GUI unit. */
        public final int oversample;
        public int atlasW, atlasH, cellW, cellH;
        /** Distance from a cell's top to the baseline, GUI units. */
        public float ascent;
        final float[] advance = new float[LAST + 1];
        final int[] u = new int[LAST + 1], v = new int[LAST + 1];
        final boolean[] has = new boolean[LAST + 1];
        /** Left padding inside a cell, GUI units. */
        public float pad;

        Face(Identifier texture, int oversample) {
            this.texture = texture;
            this.oversample = oversample;
        }

        public boolean supports(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c > LAST || !has[c]) return false;
            }
            return true;
        }

        public float width(String s) {
            float w = 0;
            for (int i = 0; i < s.length(); i++) w += advance[s.charAt(i)];
            return w;
        }

        public float advance(char c) { return advance[c]; }
        public int u(char c) { return u[c]; }
        public int v(char c) { return v[c]; }
    }

    private static Face ui, display;
    private static boolean uiFailed, displayFailed;

    /** The face for {@code f}, built on first use (render thread); null for vanilla or if it could not be loaded. */
    public static Face face(UiRenderer.Font f) {
        if (f == UiRenderer.Font.UI) {
            if (ui == null && !uiFailed) {
                ui = build("/assets/kite/font/inter-medium.ttf", "ui", 9.5f, 5);
                uiFailed = ui == null;
            }
            return ui;
        }
        if (f == UiRenderer.Font.DISPLAY) {
            if (display == null && !displayFailed) {
                display = build("/assets/kite/font/sora-bold.ttf", "display", 9.5f, 10);
                displayFailed = display == null;
            }
            return display;
        }
        return null;
    }

    private static Face build(String resource, String name, float em, int oversample) {
        try (InputStream in = McFonts.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException(resource + " missing from the jar");
            Font font = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(em * oversample);
            FontRenderContext frc = new FontRenderContext(null, true, true);
            Face face = new Face(new Identifier("kite", "font_atlas/" + name), oversample);

            // Cell size from the real glyph bounds.
            int padPx = 2;
            float maxRight = 0, ascent = 0, descent = 0;
            for (char c = FIRST; c <= LAST; c++) {
                if (!font.canDisplay(c) || Character.isISOControl(c)) continue;
                GlyphVector gv = font.createGlyphVector(frc, String.valueOf(c));
                Rectangle2D b = gv.getVisualBounds();
                maxRight = Math.max(maxRight, (float) Math.max(b.getMaxX(), gv.getGlyphMetrics(0).getAdvance()));
                ascent = Math.max(ascent, (float) -b.getMinY());
                descent = Math.max(descent, (float) b.getMaxY());
            }
            int cellW = (int) Math.ceil(maxRight) + padPx * 2, cellH = (int) Math.ceil(ascent + descent) + padPx * 2;
            int cols = 16, count = LAST - FIRST + 1, rows = (count + cols - 1) / cols;
            int w = cols * cellW, h = rows * cellH;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(java.awt.Color.WHITE);
            g.setFont(font);
            for (char c = FIRST; c <= LAST; c++) {
                int i = c - FIRST, cx = (i % cols) * cellW, cy = (i / cols) * cellH;
                face.u[c] = cx;
                face.v[c] = cy;
                if (c == ' ' || c == ' ') {
                    face.has[c] = true;
                    face.advance[c] = (float) font.createGlyphVector(frc, " ").getGlyphMetrics(0).getAdvance() / oversample;
                    continue;
                }
                if (!font.canDisplay(c) || Character.isISOControl(c)) continue;
                GlyphVector gv = font.createGlyphVector(frc, String.valueOf(c));
                g.drawGlyphVector(gv, cx + padPx, cy + padPx + ascent);
                face.advance[c] = gv.getGlyphMetrics(0).getAdvance() / oversample;
                face.has[c] = true;
            }
            g.dispose();

            // White glyphs: the colour comes from the vertex tint.
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) img.setRGB(x, y, ((img.getRGB(x, y) >>> 24) << 24) | 0xFFFFFF);
            }
            McTextures.register(face.texture, img);
            face.atlasW = w;
            face.atlasH = h;
            face.cellW = cellW;
            face.cellH = cellH;
            face.ascent = (padPx + ascent) / oversample;
            face.pad = padPx / (float) oversample;
            return face;
        } catch (Throwable t) {
            Log.warn("Kite font " + name + " unavailable, using Minecraft's", t);
            return null;
        }
    }

    /** Vanilla draws text with its baseline 7 units below y; Kite faces line up with that. */
    public static final float BASELINE = 7.5f;
}
