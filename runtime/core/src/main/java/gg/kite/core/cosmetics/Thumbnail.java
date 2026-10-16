package gg.kite.core.cosmetics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Menu thumbnails for imported models: the mesh drawn alone (flat shaded, textured) from where it looks best, cropped
 * and fitted into a square. The built-in set renders its icons the same way (cosmetics/StarterCosmetics.java).
 */
final class Thumbnail {
    private Thumbnail() {}

    static final int SIZE = 128;
    private static final int W = 280, H = 300;
    private static final double SCALE = 6.5;

    /** {@code texture}: one frame of the model's texture. */
    static BufferedImage render(CosmeticModel model, BufferedImage texture, Slot slot) {
        boolean back = slot == Slot.WINGS || slot == Slot.BACK || slot == Slot.CAPE;
        double yaw = Math.toRadians(back ? 155 : 28);
        int[] color = new int[W * H];
        double[] depth = new double[W * H];
        Arrays.fill(depth, Double.MAX_VALUE);
        Mesh mesh = model.mesh(new CosmeticModel.Motion());
        // Fit the model into the canvas first: imported meshes can be any size.
        double[] box = { Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
        for (CosmeticModel.Bone bone : CosmeticModel.Bone.values()) {
            for (boolean glow : new boolean[] { false, true }) {
                Mesh.Layer l = mesh.layer(bone, glow);
                if (l == null) continue;
                float[] d = l.data();
                float[] bp = ObjModel.BONE_PIVOTS[bone.ordinal()];
                for (int q = 0; q < l.quads(); q++) {
                    for (int k = 0; k < 4; k++) {
                        int b = q * Mesh.STRIDE + 3 + k * 5;
                        double[] p = rotate(new double[] { d[b] + bp[0], d[b + 1] + bp[1], d[b + 2] + bp[2] }, yaw);
                        box[0] = Math.min(box[0], p[0]);
                        box[1] = Math.min(box[1], p[1]);
                        box[2] = Math.max(box[2], p[0]);
                        box[3] = Math.max(box[3], p[1]);
                    }
                }
            }
        }
        if (box[0] > box[2]) return new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        double fit = Math.min((W - 8) / Math.max(1e-3, box[2] - box[0]), (H - 8) / Math.max(1e-3, box[3] - box[1]));
        double s = Math.min(SCALE * 4, fit), ox = W / 2.0 - (box[0] + box[2]) / 2 * s, oy = H / 2.0 - (box[1] + box[3]) / 2 * s;
        for (CosmeticModel.Bone bone : CosmeticModel.Bone.values()) {
            for (boolean glow : new boolean[] { false, true }) {
                Mesh.Layer l = mesh.layer(bone, glow);
                if (l != null) quads(color, depth, yaw, s, ox, oy, l, ObjModel.BONE_PIVOTS[bone.ordinal()], texture, glow);
            }
        }
        BufferedImage full = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        full.setRGB(0, 0, W, H, color, 0, W);
        return crop(full);
    }

    private static BufferedImage crop(BufferedImage full) {
        int x0 = W, y0 = H, x1 = -1, y1 = -1;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if ((full.getRGB(x, y) >>> 24) == 0) continue;
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                x1 = Math.max(x1, x);
                y1 = Math.max(y1, y);
            }
        }
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        if (x1 < 0) return out;
        int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
        double s = Math.min((SIZE - 8.0) / bw, (SIZE - 8.0) / bh);
        int dw = (int) Math.round(bw * s), dh = (int) Math.round(bh * s);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(full, (SIZE - dw) / 2, (SIZE - dh) / 2, (SIZE - dw) / 2 + dw, (SIZE - dh) / 2 + dh, x0, y0, x1 + 1, y1 + 1, null);
        g.dispose();
        return out;
    }

    private static double[] rotate(double[] p, double yaw) {
        double c = Math.cos(yaw), s = Math.sin(yaw);
        return new double[] { p[0] * c - p[2] * s, p[1], p[0] * s + p[2] * c };
    }

    private static void quads(int[] color, double[] depth, double yaw, double s, double ox, double oy, Mesh.Layer l, float[] bone, BufferedImage tex, boolean glow) {
        float[] d = l.data();
        for (int q = 0; q < l.quads(); q++) {
            int o = q * Mesh.STRIDE;
            double[] n = rotate(new double[] { d[o], d[o + 1], d[o + 2] }, yaw);
            double[][] p = new double[4][], uv = new double[4][];
            for (int k = 0; k < 4; k++) {
                int b = o + 3 + k * 5;
                double[] r = rotate(new double[] { d[b] + bone[0], d[b + 1] + bone[1], d[b + 2] + bone[2] }, yaw);
                p[k] = new double[] { ox + r[0] * s, oy + r[1] * s, r[2] };
                uv[k] = new double[] { d[b + 3], d[b + 4] };
            }
            double light = glow ? 1 : 0.55 + 0.45 * Math.abs(-0.4 * n[0] - 0.6 * n[1] - 0.7 * n[2]);
            tri(color, depth, p[0], p[1], p[2], uv[0], uv[1], uv[2], tex, light);
            tri(color, depth, p[0], p[2], p[3], uv[0], uv[2], uv[3], tex, light);
        }
    }

    private static void tri(int[] color, double[] depth, double[] a, double[] b, double[] c, double[] ua, double[] ub, double[] uc, BufferedImage tex, double light) {
        double area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        if (Math.abs(area) < 1e-9) return;
        int minX = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0])))), maxX = (int) Math.min(W - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1])))), maxY = (int) Math.min(H - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5, py = y + 0.5;
                double w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) / area;
                double w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                double z = w0 * a[2] + w1 * b[2] + w2 * c[2];
                int i = y * W + x;
                if (z >= depth[i]) continue;
                int col = 0xFFFFFFFF;
                if (tex != null) {
                    double u = w0 * ua[0] + w1 * ub[0] + w2 * uc[0], v = w0 * ua[1] + w1 * ub[1] + w2 * uc[1];
                    int tx = (int) Math.min(tex.getWidth() - 1, Math.max(0, Math.floor(u * tex.getWidth())));
                    int ty = (int) Math.min(tex.getHeight() - 1, Math.max(0, Math.floor(v * tex.getHeight())));
                    col = tex.getRGB(tx, ty);
                }
                int a8 = (col >>> 24) & 255;
                if (a8 < 26) continue;
                int shaded = 0xFF000000 | shade(col, light);
                if (a8 < 250) {
                    color[i] = mix(color[i], shaded, a8 / 255.0);
                    continue;
                }
                depth[i] = z;
                color[i] = shaded;
            }
        }
    }

    private static int shade(int c, double f) {
        int r = (int) Math.min(255, ((c >> 16) & 255) * f), g = (int) Math.min(255, ((c >> 8) & 255) * f), b = (int) Math.min(255, (c & 255) * f);
        return r << 16 | g << 8 | b;
    }

    private static int mix(int c1, int c2, double t) {
        int out = 0;
        for (int sh = 0; sh <= 24; sh += 8) {
            int a = (c1 >>> sh) & 255, b = (c2 >>> sh) & 255;
            out |= ((int) Math.round(a + (b - a) * t) & 255) << sh;
        }
        return out;
    }
}
