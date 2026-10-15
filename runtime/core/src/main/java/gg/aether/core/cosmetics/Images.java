package gg.aether.core.cosmetics;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.Node;

/**
 * Turns pictures people have lying around (PNG, JPG, BMP, animated GIF) into what the renderer takes: a PNG, with
 * animation frames stacked vertically. {@link #pictureCape} lays any picture out as a cape texture.
 */
final class Images {
    private Images() {}

    /** Most frames kept from a GIF; longer ones are sampled evenly (and each frame shows longer). */
    static final int MAX_FRAMES = 256;
    /** Upper bound on the converted texture (all frames), in pixels: keeps memory sane for long GIFs. */
    static final long MAX_PIXELS = 24L * 1024 * 1024;
    /** Upper bound on the decoded GIF frames together, before conversion. */
    static final long SOURCE_PIXELS = 16L * 1024 * 1024;

    /** A decoded picture: one or more equally sized frames and how long each shows. */
    static final class Picture {
        final List<BufferedImage> frames;
        final int frameTimeMs;

        Picture(List<BufferedImage> frames, int frameTimeMs) {
            this.frames = frames;
            this.frameTimeMs = frameTimeMs;
        }

        int width() { return frames.get(0).getWidth(); }
        int height() { return frames.get(0).getHeight(); }
        boolean animated() { return frames.size() > 1; }
    }

    static boolean isImageName(String file) {
        String f = file.toLowerCase(Locale.ROOT);
        return f.endsWith(".png") || f.endsWith(".jpg") || f.endsWith(".jpeg") || f.endsWith(".gif") || f.endsWith(".bmp");
    }

    static boolean isGif(byte[] b) { return b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F'; }

    /** Decode {@code bytes}; GIFs keep every frame, composited the way browsers show them. */
    static Picture read(byte[] bytes) throws IOException {
        if (isGif(bytes)) return readGif(bytes);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) throw new IOException("not a picture this client can read (use PNG, JPG or GIF)");
        List<BufferedImage> one = new ArrayList<BufferedImage>();
        one.add(argb(img));
        return new Picture(one, 100);
    }

    private static Picture readGif(byte[] bytes) throws IOException {
        Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("gif");
        if (!it.hasNext()) throw new IOException("no GIF decoder");
        ImageReader reader = it.next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(in, false);
            int w = -1, h = -1;
            IIOMetadata sm = reader.getStreamMetadata();
            if (sm != null) {
                Node screen = child(sm.getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
                if (screen != null) {
                    w = intAttr(screen, "logicalScreenWidth", -1);
                    h = intAttr(screen, "logicalScreenHeight", -1);
                }
            }
            // Keep at most MAX_FRAMES (evenly spread) and shrink them to a total pixel budget while decoding, so a long,
            // large GIF never sits in memory at full size.
            int total = reader.getNumImages(true);
            if (total <= 0) throw new IOException("the GIF has no frames");
            int keep = Math.min(total, MAX_FRAMES), kept = 0, nextKept = 0;
            long perFrame = Math.max(64 * 64, SOURCE_PIXELS / keep);
            List<BufferedImage> frames = new ArrayList<BufferedImage>();
            long delays = 0;
            BufferedImage canvas = null;
            for (int i = 0; i < total; i++) {
                BufferedImage raw = reader.read(i);
                Node meta = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                Node desc = child(meta, "ImageDescriptor"), gce = child(meta, "GraphicControlExtension");
                int fx = desc == null ? 0 : intAttr(desc, "imageLeftPosition", 0), fy = desc == null ? 0 : intAttr(desc, "imageTopPosition", 0);
                if (canvas == null) {
                    if (w <= 0 || h <= 0) {
                        w = raw.getWidth() + fx;
                        h = raw.getHeight() + fy;
                    }
                    canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                }
                String disposal = gce == null ? "none" : attr(gce, "disposalMethod", "none");
                // Browsers show 0 and 1 hundredths as 100 ms.
                int delay = gce == null ? 10 : intAttr(gce, "delayTime", 10);
                delays += (delay <= 1 ? 10 : delay) * 10L;
                BufferedImage before = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;
                Graphics2D g = canvas.createGraphics();
                g.drawImage(raw, fx, fy, null);
                g.dispose();
                if (i == nextKept) {
                    frames.add(shrink(canvas, perFrame));
                    kept++;
                    nextKept = (int) ((long) kept * total / keep);
                }
                if ("restoreToBackgroundColor".equals(disposal)) {
                    Graphics2D c = canvas.createGraphics();
                    c.setComposite(AlphaComposite.Clear);
                    c.fillRect(fx, fy, raw.getWidth(), raw.getHeight());
                    c.dispose();
                } else if (before != null) {
                    canvas = before;
                }
            }
            if (frames.isEmpty()) throw new IOException("the GIF has no frames");
            // The whole loop takes as long as before, spread over the frames kept.
            int frameTime = (int) Math.max(20, delays / frames.size());
            return new Picture(frames, frameTime);
        } finally {
            reader.dispose();
        }
    }

    /** True when every frame has the 2:1 cape layout (64x32, 128x64...): used as a cape texture as it is. */
    static boolean capeLayout(Picture p) { return p.width() == p.height() * 2 && p.width() >= 64 && p.width() % 64 == 0; }

    /**
     * Lay a picture out as a cape: it fills the outside (cropped to the 10:16 panel, centred), shows darker and
     * mirrored on the inside (as if seen through), covers the elytra and tints the edges with its average colour.
     */
    static Picture pictureCape(Picture p) {
        int frames = p.frames.size();
        // Resolution: about as sharp as the picture, at most 1024x512 per frame, and the strip within MAX_PIXELS.
        int k = Math.max(1, Math.min(16, Math.round(p.height() / 16f)));
        while (k > 1 && (long) frames * 64 * k * 32 * k > MAX_PIXELS) k--;
        List<BufferedImage> out = new ArrayList<BufferedImage>();
        for (BufferedImage src : p.frames) out.add(capeFrame(src, k));
        return new Picture(out, p.frameTimeMs);
    }

    private static BufferedImage capeFrame(BufferedImage src, int k) {
        BufferedImage cape = new BufferedImage(64 * k, 32 * k, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = cape.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int avg = shade(average(src), 0.8f);
        g.setColor(new java.awt.Color(avg, true));
        g.fillRect(0, 0, 22 * k, 17 * k); // edges first; the panels cover the rest
        cover(g, src, 1 * k, 1 * k, 10 * k, 16 * k, false);
        BufferedImage inside = new BufferedImage(10 * k, 16 * k, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gi = inside.createGraphics();
        gi.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        cover(gi, src, 0, 0, 10 * k, 16 * k, true);
        gi.dispose();
        darken(inside, 0.6f);
        g.drawImage(inside, 12 * k, 1 * k, null);
        // Elytra: one wing's box UV spans 22,0 .. 46,22 (both wings share it).
        cover(g, src, 22 * k, 0, 24 * k, 22 * k, false);
        g.dispose();
        return cape;
    }

    /** Draw {@code src} into the box, scaled to fill it and cropped in the middle ("cover"). */
    private static void cover(Graphics2D g, BufferedImage src, int x, int y, int w, int h, boolean mirror) {
        float s = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        int cw = Math.max(1, Math.round(w / s)), ch = Math.max(1, Math.round(h / s));
        int sx = (src.getWidth() - cw) / 2, sy = (src.getHeight() - ch) / 2;
        if (mirror) g.drawImage(src, x + w, y, x, y + h, sx, sy, sx + cw, sy + ch, null);
        else g.drawImage(src, x, y, x + w, y + h, sx, sy, sx + cw, sy + ch, null);
    }

    static int average(BufferedImage img) {
        long r = 0, gr = 0, b = 0, n = 0;
        int step = Math.max(1, Math.max(img.getWidth(), img.getHeight()) / 64);
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                int c = img.getRGB(x, y);
                if ((c >>> 24) < 128) continue;
                r += (c >> 16) & 255;
                gr += (c >> 8) & 255;
                b += c & 255;
                n++;
            }
        }
        if (n == 0) return 0xFF404040;
        return 0xFF000000 | (int) (r / n) << 16 | (int) (gr / n) << 8 | (int) (b / n);
    }

    private static int shade(int c, float f) {
        int r = Math.round(((c >> 16) & 255) * f), g = Math.round(((c >> 8) & 255) * f), b = Math.round((c & 255) * f);
        return (c & 0xFF000000) | r << 16 | g << 8 | b;
    }

    private static void darken(BufferedImage img, float f) {
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) img.setRGB(x, y, shade(img.getRGB(x, y), f));
    }

    /** Frames stacked top to bottom in one image. */
    static BufferedImage strip(Picture p) {
        int w = p.width(), h = p.height();
        BufferedImage out = new BufferedImage(w, h * p.frames.size(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        for (int i = 0; i < p.frames.size(); i++) g.drawImage(p.frames.get(i), 0, i * h, null);
        g.dispose();
        return out;
    }

    /** Shrink frames so the strip stays within {@link #MAX_PIXELS} (long GIFs used as model textures). */
    static Picture fit(Picture p) {
        long px = (long) p.width() * p.height() * p.frames.size();
        if (px <= MAX_PIXELS) return p;
        double s = Math.sqrt(MAX_PIXELS / (double) px);
        int w = Math.max(1, (int) (p.width() * s)), h = Math.max(1, (int) (p.height() * s));
        List<BufferedImage> out = new ArrayList<BufferedImage>();
        for (BufferedImage f : p.frames) {
            BufferedImage r = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = r.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(f, 0, 0, w, h, null);
            g.dispose();
            out.add(r);
        }
        return new Picture(out, p.frameTimeMs);
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", out)) throw new IOException("no PNG encoder");
        return out.toByteArray();
    }

    private static BufferedImage argb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_ARGB) return img;
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    /** A copy of {@code img}, scaled down to at most {@code maxPixels} pixels. */
    private static BufferedImage shrink(BufferedImage img, long maxPixels) {
        long px = (long) img.getWidth() * img.getHeight();
        if (px <= maxPixels) return copy(img);
        double s = Math.sqrt(maxPixels / (double) px);
        int w = Math.max(1, (int) (img.getWidth() * s)), h = Math.max(1, (int) (img.getHeight() * s));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static BufferedImage copy(BufferedImage img) {
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    private static Node child(Node n, String name) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) if (name.equals(c.getNodeName())) return c;
        return null;
    }

    private static String attr(Node n, String name, String def) {
        Node a = n.getAttributes() == null ? null : n.getAttributes().getNamedItem(name);
        return a == null ? def : a.getNodeValue();
    }

    private static int intAttr(Node n, String name, int def) {
        try {
            return Integer.parseInt(attr(n, name, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
