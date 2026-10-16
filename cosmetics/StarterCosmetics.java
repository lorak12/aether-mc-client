import gg.kite.core.cosmetics.CosmeticModel;
import gg.kite.core.cosmetics.Mesh;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Generates the built-in starter cosmetics (cosmetic.json + texture.png per folder, plus index.json) into the
 * runtime's resources. Models are defined here with their painters, so each texture is painted exactly where the
 * box-UV layout puts its cube faces. With --preview, also renders every cosmetic on a plain player for review.
 *
 * <pre>
 * cd runtime && ./gradlew.bat :core:compileJava
 * java -cp runtime/core/build/classes/java/main cosmetics/StarterCosmetics.java runtime/core/src/main/resources/kite/cosmetics [--preview out]
 * </pre>
 */
public final class StarterCosmetics {
    // ---- colour + noise helpers ---------------------------------------------------------------------------------

    static int argb(int a, double r, double g, double b) {
        return (a << 24) | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    static int clamp255(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }
    static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
    static double smooth(double e0, double e1, double x) {
        double t = clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    static int rgb(int hex) { return 0xFF000000 | hex; }

    static int mix(int c1, int c2, double t) {
        t = clamp(t);
        int a = clamp255(((c1 >>> 24) & 255) * (1 - t) + ((c2 >>> 24) & 255) * t);
        return argb(a, ((c1 >> 16) & 255) * (1 - t) + ((c2 >> 16) & 255) * t, ((c1 >> 8) & 255) * (1 - t) + ((c2 >> 8) & 255) * t,
                (c1 & 255) * (1 - t) + (c2 & 255) * t);
    }

    static int shade(int c, double f) {
        return argb((c >>> 24) & 255, ((c >> 16) & 255) * f, ((c >> 8) & 255) * f, (c & 255) * f);
    }

    static int add(int c, double r, double g, double b) {
        return argb((c >>> 24) & 255, ((c >> 16) & 255) + r, ((c >> 8) & 255) + g, (c & 255) + b);
    }

    static int alpha(int c, double a) { return (clamp255(a * 255) << 24) | (c & 0xFFFFFF); }

    static double hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0xFFFFFF;
    }

    /** Value noise, periodic with period px / py (0 = not periodic). */
    static double noise(double x, double y, int px, int py, int seed) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        double sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
        double a = hash(wrap(x0, px), wrap(y0, py), seed), b = hash(wrap(x0 + 1, px), wrap(y0, py), seed);
        double c = hash(wrap(x0, px), wrap(y0 + 1, py), seed), d = hash(wrap(x0 + 1, px), wrap(y0 + 1, py), seed);
        return (a + (b - a) * sx) * (1 - sy) + (c + (d - c) * sx) * sy;
    }

    static int wrap(int v, int p) { return p <= 0 ? v : ((v % p) + p) % p; }

    static double fbm(double x, double y, int px, int py, int seed, int octaves) {
        double sum = 0, amp = 0.5, norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += amp * noise(x, y, px, py, seed + i * 31);
            norm += amp;
            x *= 2;
            y *= 2;
            px *= 2;
            py *= 2;
            amp *= 0.5;
        }
        return sum / norm;
    }

    static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double t = clamp(((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy));
        double qx = ax + t * dx - px, qy = ay + t * dy - py;
        return Math.sqrt(qx * qx + qy * qy);
    }

    // ---- models -----------------------------------------------------------------------------------------------------

    static final int DOWN = 0, UP = 1, WEST = 2, NORTH = 3, EAST = 4, SOUTH = 5;

    /** Where a texel lies on its cube: face, position 0..1 along each cube axis, cube size in pixels. */
    static final class P {
        int face;
        double ax, ay, az, w, h, d;
        /** Texel position in UV units (for noise that should flow across faces). */
        double tu, tv;
        /** Pixel coordinate along the face (width axis / height axis) in model pixels. */
        double fu() { return face == WEST || face == EAST ? (face == WEST ? 1 - az : az) * d : face == SOUTH ? (1 - ax) * w : ax * w; }
        double fv() { return face == DOWN || face == UP ? (1 - az) * d : ay * h; }
    }

    interface Paint { int at(P p); }

    static final class CubeRec {
        final double u, v, w, h, d;
        final Paint paint;
        CubeRec(double u, double v, double w, double h, double d, Paint paint) {
            this.u = u; this.v = v; this.w = w; this.h = h; this.d = d; this.paint = paint;
        }
    }

    static final class Model {
        final int uvW, uvH, scale;
        final List<Map<String, Object>> parts = new ArrayList<>();
        final List<CubeRec> cubes = new ArrayList<>();
        int shelfX, shelfY, shelfH;

        Model(int uvW, int uvH, int scale) {
            this.uvW = uvW;
            this.uvH = uvH;
            this.scale = scale;
        }

        Map<String, Object> part(String bone, double[] pivot, double[] rotation) {
            Map<String, Object> p = newPart(pivot, rotation);
            p.put("bone", bone);
            Map<String, Object> ordered = new LinkedHashMap<>();
            ordered.put("bone", bone);
            ordered.putAll(p);
            parts.add(ordered);
            return ordered;
        }

        Map<String, Object> child(Map<String, Object> parent, double[] pivot, double[] rotation) {
            Map<String, Object> p = newPart(pivot, rotation);
            @SuppressWarnings("unchecked")
            List<Object> children = (List<Object>) parent.computeIfAbsent("children", k -> new ArrayList<Object>());
            children.add(p);
            return p;
        }

        private static Map<String, Object> newPart(double[] pivot, double[] rotation) {
            Map<String, Object> p = new LinkedHashMap<>();
            if (pivot != null) p.put("pivot", nums(pivot));
            if (rotation != null) p.put("rotation", nums(rotation));
            return p;
        }

        /** Adds a cube; extra = alternating key/value pairs ("inflate", 0.2, "glow", true, "mirror", true). */
        void cube(Map<String, Object> part, double[] from, double[] size, Paint paint, Object... extra) {
            double w = size[0], h = size[1], d = size[2];
            int fw = (int) Math.ceil(2 * (d + w)), fh = (int) Math.ceil(d + h);
            if (shelfX + fw > uvW) {
                shelfX = 0;
                shelfY += shelfH;
                shelfH = 0;
            }
            if (shelfY + fh > uvH || fw > uvW) throw new IllegalStateException("texture space " + uvW + "x" + uvH + " is full");
            int u = shelfX, v = shelfY;
            shelfX += fw;
            shelfH = Math.max(shelfH, fh);
            cubes.add(new CubeRec(u, v, w, h, d, paint));
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("from", nums(from));
            c.put("size", nums(size));
            c.put("uv", Arrays.asList(u, v));
            for (int i = 0; i + 1 < extra.length; i += 2) c.put((String) extra[i], extra[i + 1]);
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) part.computeIfAbsent("cubes", k -> new ArrayList<Object>());
            list.add(c);
        }

        BufferedImage paint() {
            int W = uvW * scale, H = uvH * scale;
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
            for (CubeRec c : cubes) {
                double u = c.u, v = c.v, w = c.w, h = c.h, d = c.d;
                double[][] rects = {
                        { u + d, v, u + d + w, v + d }, { u + d + w, v, u + d + w + w, v + d }, { u, v + d, u + d, v + d + h },
                        { u + d, v + d, u + d + w, v + d + h }, { u + d + w, v + d, u + d + w + d, v + d + h }, { u + d + w + d, v + d, u + d + w + d + w, v + d + h },
                };
                for (int f = 0; f < 6; f++) {
                    double[] r = rects[f];
                    if (r[2] - r[0] <= 0 || r[3] - r[1] <= 0) continue;
                    int x0 = (int) Math.floor(r[0] * scale), x1 = (int) Math.ceil(r[2] * scale), y0 = (int) Math.floor(r[1] * scale), y1 = (int) Math.ceil(r[3] * scale);
                    for (int py = y0; py < y1; py++) {
                        for (int px = x0; px < x1; px++) {
                            double fx = clamp((px + 0.5 - r[0] * scale) / ((r[2] - r[0]) * scale));
                            double fy = clamp((py + 0.5 - r[1] * scale) / ((r[3] - r[1]) * scale));
                            P p = new P();
                            p.face = f;
                            p.w = w;
                            p.h = h;
                            p.d = d;
                            p.tu = (px + 0.5) / scale;
                            p.tv = (py + 0.5) / scale;
                            switch (f) {
                                case DOWN: p.ax = fx; p.ay = 0; p.az = 1 - fy; break;
                                case UP: p.ax = fx; p.ay = 1; p.az = 1 - fy; break;
                                case WEST: p.ax = 0; p.ay = fy; p.az = 1 - fx; break;
                                case NORTH: p.ax = fx; p.ay = fy; p.az = 0; break;
                                case EAST: p.ax = 1; p.ay = fy; p.az = fx; break;
                                default: p.ax = 1 - fx; p.ay = fy; p.az = 1; break;
                            }
                            if (px >= 0 && py >= 0 && px < W && py < H) img.setRGB(px, py, c.paint.at(p));
                        }
                    }
                }
            }
            return img;
        }
    }

    static List<Object> nums(double[] a) {
        List<Object> out = new ArrayList<>();
        for (double v : a) out.add(v == Math.rint(v) ? (Object) (long) v : (Object) v);
        return out;
    }

    static double[] v(double... a) { return a; }

    static Map<String, Object> anim(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1] instanceof double[] ? nums((double[]) kv[i + 1]) : kv[i + 1]);
        return m;
    }

    // ---- paints -----------------------------------------------------------------------------------------------------

    /** Solid material with fine grain; faces pointing up are lighter, the bottom darker. */
    static Paint material(int base, double grain, int seed) {
        return p -> {
            double n = (noise(p.tu * 16, p.tv * 16, 0, 0, seed) - 0.5) * grain;
            double f = p.face == DOWN ? 1.12 : p.face == UP ? 0.7 : 1.0;
            return add(shade(base, f), n * 255, n * 255, n * 255);
        };
    }

    static Paint felt(int base) {
        return p -> {
            double n = fbm(p.tu * 6, p.tv * 6, 0, 0, 7, 3) - 0.5;
            double f = p.face == DOWN ? 1.25 : p.face == UP ? 0.8 : 1.0;
            return add(shade(base, f), n * 40, n * 40, n * 46);
        };
    }

    static Paint gold() {
        return p -> {
            double t = p.face == DOWN ? 0.05 : p.face == UP ? 0.95 : p.ay;
            int c = mix(rgb(0xFFF1A8), rgb(0xC88A1C), t * 0.9);
            double glint = Math.pow(Math.max(0, Math.sin((p.fu() + p.fv()) * 1.7)), 12) * 60;
            double n = (noise(p.tu * 24, p.tv * 24, 0, 0, 3) - 0.5) * 18;
            // Darker rim along the cube's edges for a bevelled look.
            double edge = Math.min(Math.min(p.fu(), p.face == WEST || p.face == EAST ? p.d - p.fu() : p.w - p.fu()), Math.min(p.fv(), (p.face == DOWN || p.face == UP ? p.d : p.h) - p.fv()));
            double rim = edge < 0.18 ? 0.8 : 1.0;
            return shade(add(c, glint + n, glint * 0.9 + n, glint * 0.5 + n), rim);
        };
    }

    static Paint gem(int color) {
        return p -> {
            double cx = p.fu() / Math.max(0.01, p.face == WEST || p.face == EAST ? p.d : p.w), cy = p.fv() / Math.max(0.01, p.face == DOWN || p.face == UP ? p.d : p.h);
            double r = Math.hypot(cx - 0.35, cy - 0.35);
            int c = mix(add(color, 90, 90, 90), shade(color, 0.55), smooth(0, 0.8, r));
            if (r < 0.14) c = mix(c, rgb(0xFFFFFF), 0.8);
            return c;
        };
    }

    /** A fabric with a repeating paisley-dot print. */
    static Paint bandana(int base, int ink, int dots) {
        return p -> {
            double x = p.fu() * 2, y = p.fv() * 2;
            if (p.face == DOWN || p.face == UP) { x = p.ax * p.w * 2; y = p.az * p.d * 2; }
            int c = base;
            double n = fbm(p.tu * 8, p.tv * 8, 0, 0, 11, 2) - 0.5;
            c = add(c, n * 30, n * 20, n * 20);
            double gx = ((x % 3) + 3) % 3, gy = ((y + (Math.floor(x / 3) % 2) * 1.5) % 3 + 3) % 3;
            double r = Math.hypot(gx - 1.5, gy - 1.5);
            if (r < 0.55) c = dots;
            else if (r < 0.8) c = ink;
            double gx2 = ((x + 1.5) % 3 + 3) % 3, gy2 = ((y + 1.5 + (Math.floor((x + 1.5) / 3) % 2) * 1.5) % 3 + 3) % 3;
            if (Math.hypot(gx2 - 1.5, gy2 - 1.5) < 0.28) c = dots;
            // Hemmed edges.
            if (p.face != DOWN && p.face != UP && (p.fv() < 0.12 || p.fv() > p.h - 0.12)) c = shade(base, 0.7);
            return c;
        };
    }

    static Paint fur(int base, int tip) {
        return p -> {
            double n = fbm(p.tu * 20, p.tv * 20, 0, 0, 5, 2);
            double t = p.face == DOWN ? 0.1 : p.ay;
            int c = mix(tip, base, 0.35 + 0.65 * t);
            return add(c, (n - 0.5) * 36, (n - 0.5) * 36, (n - 0.5) * 36);
        };
    }

    // ---- wing silhouettes (painted on flat panels, see-through outside the shape) ----------------------------------

    /**
     * Angel wing on a panel whose outer tip is at ax = 0 and root at ax = 1: a curved leading edge, then rows of
     * rounded feathers getting longer towards the tip.
     */
    static Paint angelWing(boolean coverts) {
        return p -> {
            if (p.face != NORTH && p.face != SOUTH) return rgb(0xEDEFF7);
            double r = 1 - p.ax, y = p.ay; // r: 0 root .. 1 tip
            // Leading edge: rises from the shoulder to the wrist, then falls gently to the tip.
            double top = coverts ? 0.04 + 0.1 * r : 0.3 - 0.26 * Math.sin(Math.PI * Math.min(1, r / 0.7) * 0.5) + 0.18 * Math.max(0, r - 0.7) / 0.3;
            if (!coverts && r < 0.05) top += (0.05 - r) * 3;
            int n = coverts ? 6 : 8;
            double col = r * n;
            int i = (int) Math.min(n - 1, Math.floor(col));
            double f = col - i;
            double len = coverts ? 0.45 + 0.35 * (i / (double) (n - 1)) : 0.55 + 0.42 * Math.pow(i / (double) (n - 1), 0.8);
            double tip = len - 0.07 * (1 - Math.sqrt(Math.max(0, 1 - Math.pow(2 * f - 1, 2))));
            if (y < top || y > tip) return 0;
            double along = (y - top) / Math.max(0.01, tip - top);
            int c = mix(rgb(0xFFFFFF), rgb(coverts ? 0xDDE2F2 : 0xC9CFE3), along * 0.9);
            double edge = Math.min(f, 1 - f);
            if (edge < 0.08) c = shade(c, 0.86);
            if (Math.abs(f - 0.5) < 0.035 && along > 0.1) c = shade(c, 0.92);
            if (y - top < 0.025) c = shade(c, 0.9);
            double n2 = (noise(p.tu * 20, p.tv * 20, 0, 0, 9) - 0.5) * 10;
            return add(c, n2, n2, n2 + 3);
        };
    }

    static Paint dragonWing() {
        // Finger tips along the bottom edge (r, y), bones radiate from the wrist.
        final double wr = 0.62, wy = 0.06;
        final double[][] tips = { { 0.0, 0.55 }, { 0.22, 0.92 }, { 0.5, 1.0 }, { 0.78, 0.9 }, { 1.0, 0.55 } };
        return p -> {
            if (p.face != NORTH && p.face != SOUTH) return rgb(0x2A0E12);
            double r = 1 - p.ax, y = p.ay;
            if (y < 0.03 + 0.05 * Math.abs(r - wr)) return 0;
            // Scalloped trailing edge between neighbouring finger tips.
            double bottom = 0;
            for (int k = 0; k + 1 < tips.length; k++) {
                double[] a = tips[k], b = tips[k + 1];
                if (r >= a[0] && r <= b[0]) {
                    double t = (r - a[0]) / (b[0] - a[0]);
                    double straight = a[1] + (b[1] - a[1]) * t;
                    bottom = straight - 0.16 * Math.sin(Math.PI * t) * (b[0] - a[0]) * 2.2;
                }
            }
            if (y > bottom) return 0;
            double bone = 1;
            for (double[] t : tips) bone = Math.min(bone, segDist(r, y, wr, wy, t[0], t[1]));
            bone = Math.min(bone, segDist(r, y, 1.0, 0.04, wr, wy));
            double vein = fbm(p.tu * 3, p.tv * 3, 0, 0, 21, 3);
            int c = mix(rgb(0x5A0C18), rgb(0xB8233A), 0.35 + 0.65 * y);
            c = mix(c, rgb(0x2B070C), smooth(0.5, 0.56, vein) * 0.45);
            if (bone < 0.022) c = mix(rgb(0x2A0E12), rgb(0x4A1A20), bone / 0.022);
            else if (bone < 0.05) c = shade(c, 0.75);
            return c;
        };
    }

    static Paint fairyWing(boolean upper) {
        return p -> {
            if (p.face != NORTH && p.face != SOUTH) return 0;
            double r = 1 - p.ax, y = p.ay;
            // Teardrop along the panel: pinched at the root, widest two thirds out, rounded tip.
            double half = 0.5 * Math.pow(Math.sin(Math.PI * Math.pow(r, upper ? 0.62 : 0.7)), 0.8);
            double dy = Math.abs(y - 0.5);
            if (half <= 0 || dy > half) return 0;
            double d = Math.max(dy / half, Math.pow(r, 6));
            int c = mix(rgb(0x8FF0FF), rgb(0xFF9AE6), r * 0.9 + (upper ? 0 : 0.2));
            double a = 0.45 + 0.4 * smooth(0.75, 1.0, d);
            // Veins fanning out from the root.
            double ang = Math.atan2(y - 0.5, r + 0.05) * 7;
            if (Math.abs(Math.sin(ang)) < 0.09 && d < 0.95) { c = mix(c, rgb(0xFFFFFF), 0.7); a = 0.85; }
            if (d > 0.93) { c = mix(c, rgb(0xFFFFFF), 0.6); a = 0.95; }
            double sparkle = hash((int) (p.tu * 4), (int) (p.tv * 4), 77);
            if (sparkle > 0.985) { c = rgb(0xFFFFFF); a = 1; }
            return alpha(c, a);
        };
    }

    static Paint featherMini() {
        return p -> {
            if (p.face != NORTH && p.face != SOUTH) return rgb(0xF1F2F8);
            double r = 1 - p.ax, y = p.ay;
            int n = 3;
            double col = r * n;
            int i = (int) Math.min(n - 1, Math.floor(col));
            double f = col - i;
            double top = 0.05 + 0.15 * r;
            double len = 0.5 + 0.45 * (i / (double) (n - 1));
            double tip = len - 0.12 * (1 - Math.sqrt(Math.max(0, 1 - Math.pow(2 * f - 1, 2))));
            if (y < top || y > tip) return 0;
            int c = mix(rgb(0xFFFFFF), rgb(0xD5DAEA), (y - top) / (tip - top));
            if (Math.min(f, 1 - f) < 0.1) c = shade(c, 0.85);
            return c;
        };
    }

    // ---- capes ------------------------------------------------------------------------------------------------------

    /** Cape art: colour at (x, y) of the outside (0..1 across, 0..1 down) at animation time t (0..1). */
    interface CapeArt { int at(double x, double y, double t); }

    /** One 64x32-layout frame at {@code scale}: outside, darker inside, trimmed edges and matching elytra. */
    static BufferedImage capeFrame(CapeArt art, int trim, int scale, double t) {
        int W = 64 * scale, H = 32 * scale;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < H; py++) {
            for (int px = 0; px < W; px++) {
                double u = (px + 0.5) / scale, v = (py + 0.5) / scale;
                int c = 0;
                if (u >= 1 && u < 11 && v >= 1 && v < 17) { // outside
                    c = art.at((u - 1) / 10, (v - 1) / 16, t);
                    double bx = Math.min(u - 1, 11 - u), by = Math.min(v - 1, 17 - v);
                    if (Math.min(bx, by) < 0.45) c = mix(c, trim, 0.85);
                } else if (u >= 12 && u < 22 && v >= 1 && v < 17) { // inside, mirrored
                    c = shade(art.at((22 - u) / 10, (v - 1) / 16, t), 0.45);
                } else if (u < 22 && v < 17 && !(u < 1 && v < 1) && !(u >= 21 && v < 1)) { // edges
                    c = trim;
                } else if (u >= 22 && u < 46 && v < 22) { // elytra: wing outside at (24,2)-(34,22)
                    double ex = clamp((u - 24) / 10), ey = clamp((v - 2) / 20);
                    c = art.at(ex, ey * 0.95, t);
                    if (u < 24 || u >= 34 || v < 2) c = shade(c, 0.7);
                }
                img.setRGB(px, py, c);
            }
        }
        return img;
    }

    static BufferedImage strip(CapeArt art, int trim, int scale, int frames) {
        BufferedImage first = capeFrame(art, trim, scale, 0);
        BufferedImage out = new BufferedImage(first.getWidth(), first.getHeight() * frames, BufferedImage.TYPE_INT_ARGB);
        for (int f = 0; f < frames; f++) {
            BufferedImage fr = f == 0 ? first : capeFrame(art, trim, scale, f / (double) frames);
            out.getGraphics().drawImage(fr, 0, f * first.getHeight(), null);
        }
        return out;
    }

    static int starfield(double x, double y, double t, int seed, int density) {
        double gx = x * density, gy = y * density * 1.6;
        int cx = (int) Math.floor(gx), cy = (int) Math.floor(gy);
        double best = 0;
        for (int oy = -1; oy <= 1; oy++) {
            for (int ox = -1; ox <= 1; ox++) {
                int sx = cx + ox, sy = cy + oy;
                double h = hash(sx, sy, seed);
                if (h < 0.72) continue;
                double px = sx + hash(sx, sy, seed + 1), py = sy + hash(sx, sy, seed + 2);
                double d = Math.hypot(gx - px, gy - py);
                double tw = t < 0 ? 1 : 0.55 + 0.45 * Math.sin(2 * Math.PI * (t + hash(sx, sy, seed + 3)));
                double size = 0.05 + 0.1 * hash(sx, sy, seed + 4);
                best = Math.max(best, tw * Math.max(0, 1 - d / size) + tw * 0.25 * Math.max(0, 1 - d / (size * 3)));
            }
        }
        return clamp255(best * 255);
    }

    /** The kite's four facets as triangles on the mark's 32-unit grid (light top right, darkest bottom right). */
    static final double[][][] KITE_FACETS = {
        {{16, 3}, {28, 13}, {16, 16}},
        {{16, 3}, {4, 13}, {16, 16}},
        {{4, 13}, {16, 16}, {16, 29}},
        {{28, 13}, {16, 16}, {16, 29}},
    };
    static final int[] KITE_COLORS = { rgb(0xA78BFA), rgb(0x8B5CF6), rgb(0x7C3AED), rgb(0x6D28D9) };

    static final CapeArt KITE = (x, y, t) -> {
        int c = mix(rgb(0x6C8CFF), rgb(0xB36CFF), y);
        c = mix(c, rgb(0x0B0D12), 0.35 + 0.25 * Math.abs(x - 0.5));
        double n = fbm(x * 6, y * 9, 0, 0, 3, 4) - 0.5;
        c = add(c, n * 30, n * 25, n * 40);
        // The brand kite (brand/kite-mark.svg, on its 32-unit grid): four flat facets and a tail, with a soft glow
        // outside. Mark units are scaled to cape units by s.
        double s = 0.27;
        double kx = 16 + (x * 10 - 5) / s, ky = 16 + (y * 16 - 7.6) / s;
        double facet = Double.MAX_VALUE;
        int face = 0;
        for (int i = 0; i < KITE_FACETS.length; i++) {
            double fd = triSd(KITE_FACETS[i], kx, ky);
            if (fd < facet) { facet = fd; face = i; }
        }
        double tail = Math.min(segDist(kx, ky, 16, 29, 13, 31.5), segDist(kx, ky, 16, 29, 19, 31.5)) - 0.8;
        double d = Math.min(facet, tail) * s;
        int glyph = tail < facet ? rgb(0x8B5CF6) : KITE_COLORS[face];
        double cover = 1 - smooth(-0.03, 0.03, d);
        double g = Math.max(0, 1 - d / 0.12) * (1 - cover);
        return add(mix(c, glyph, cover), 55 * g, 55 * g, 85 * g);
    };

    /** Signed distance from (x, y) to a triangle, negative inside: the largest signed distance to its three edges. */
    static double triSd(double[][] t, double x, double y) {
        double cx = (t[0][0] + t[1][0] + t[2][0]) / 3, cy = (t[0][1] + t[1][1] + t[2][1]) / 3;
        double d = -Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            double[] a = t[i], b = t[(i + 1) % 3];
            double ex = b[0] - a[0], ey = b[1] - a[1], len = Math.hypot(ex, ey);
            double nx = ey / len, ny = -ex / len;
            if ((cx - a[0]) * nx + (cy - a[1]) * ny > 0) { nx = -nx; ny = -ny; }
            d = Math.max(d, (x - a[0]) * nx + (y - a[1]) * ny);
        }
        return d;
    }

    static final CapeArt MIDNIGHT = (x, y, t) -> {
        int c = mix(rgb(0x060A1C), rgb(0x1E1450), y);
        double n = fbm(x * 3, y * 5, 0, 0, 12, 4);
        c = mix(c, rgb(0x2A2270), smooth(0.55, 0.8, n) * 0.5);
        int s = starfield(x, y, -1, 5, 14);
        c = add(c, s, s, s * 1.05);
        // Crescent moon.
        double mx = (x - 0.68) * 10, my = (y - 0.2) * 16;
        double moon = Math.hypot(mx, my), cut = Math.hypot(mx - 0.9, my - 0.5);
        if (moon < 1.9 && cut > 1.6) c = mix(rgb(0xFFF4D6), rgb(0xE6D6A8), moon / 1.9);
        else if (moon < 3.2) c = add(c, 30 * (1 - moon / 3.2), 28 * (1 - moon / 3.2), 20 * (1 - moon / 3.2));
        return c;
    };

    static final CapeArt AURORA = (x, y, t) -> {
        int c = mix(rgb(0x03101A), rgb(0x061F2A), y);
        int s = starfield(x, y, t, 9, 12);
        c = add(c, s * 0.6, s * 0.6, s * 0.7);
        double tt = 2 * Math.PI * t;
        for (int band = 0; band < 3; band++) {
            double phase = band * 2.1;
            double center = 0.36 + band * 0.17 + 0.07 * Math.sin(x * 4 + tt + phase) + 0.025 * Math.sin(x * 9 - tt * 2 + phase);
            double dist = y - center;
            // A bright lower hem that fades slowly upwards, like hanging curtains of light.
            double curtain = dist < 0 ? Math.exp(dist / 0.16) : Math.exp(-dist * dist / 0.0015);
            double rays = 0.45 + 0.55 * Math.pow(0.5 + 0.5 * Math.sin(x * 17 + band * 2 + 1.5 * Math.sin(tt + band)), 2);
            int col = band == 0 ? rgb(0x39FFB0) : band == 1 ? rgb(0x2FD3FF) : rgb(0xB56CFF);
            c = mix(c, col, clamp(curtain * rays * 0.85));
        }
        return c;
    };

    static final CapeArt EMBER = (x, y, t) -> {
        // Flames rise: noise scrolls upwards and loops every cycle.
        double heat = 1 - y;
        double n = fbm(x * 4, y * 4 + t * 4, 4, 4, 17, 4);
        double flame = clamp((1 - heat) * 1.25 - 0.35 + (n - 0.5) * 0.9);
        int c;
        if (flame < 0.25) c = mix(rgb(0x120405), rgb(0x3A0A08), flame / 0.25);
        else if (flame < 0.55) c = mix(rgb(0x3A0A08), rgb(0xD8360C), (flame - 0.25) / 0.3);
        else if (flame < 0.8) c = mix(rgb(0xD8360C), rgb(0xFF9A1F), (flame - 0.55) / 0.25);
        else c = mix(rgb(0xFF9A1F), rgb(0xFFF1B0), (flame - 0.8) / 0.2);
        // Embers drifting up.
        for (int i = 0; i < 9; i++) {
            double ex = hash(i, 1, 40), speed = 1 + (int) (hash(i, 2, 40) * 2);
            double ey = 1 - ((t * speed + hash(i, 3, 40)) % 1.0);
            double wob = 0.03 * Math.sin(2 * Math.PI * (t * speed) + i);
            double d = Math.hypot((x - ex - wob) * 10, (y - ey) * 16);
            if (d < 0.35) c = mix(c, rgb(0xFFD27A), 1 - d / 0.35);
        }
        return c;
    };

    static final CapeArt GALAXY = (x, y, t) -> {
        double px = (x - 0.5) * 10, py = (y - 0.45) * 16;
        double r = Math.hypot(px, py), a = Math.atan2(py, px);
        double spin = a - 2 * Math.PI * t - r * 0.45;
        double arms = Math.pow(0.5 + 0.5 * Math.cos(2 * spin), 3);
        double dust = fbm(x * 5 + Math.cos(2 * Math.PI * t) * 0.3, y * 8 + Math.sin(2 * Math.PI * t) * 0.3, 0, 0, 33, 4);
        double glow = Math.exp(-r / 2.6);
        int c = mix(rgb(0x05030F), rgb(0x1A0B3D), y);
        c = mix(c, rgb(0x6C3CFF), clamp(arms * Math.exp(-r / 5) * 0.9 * dust * 1.6));
        c = mix(c, rgb(0xFF5FCF), clamp(arms * Math.exp(-r / 3) * 0.5 * (1 - dust)));
        c = mix(c, rgb(0xFFF0FF), clamp(glow * 0.9));
        int s = starfield(x, y, t, 21, 16);
        return add(c, s, s * 0.9, s);
    };

    // ---- the starter set --------------------------------------------------------------------------------------------

    static final class Def {
        final Map<String, Object> json = new LinkedHashMap<>();
        BufferedImage texture;
        Model model;
    }

    static Def meta(String id, String name, String slot, String rarity, String description) {
        Def d = new Def();
        d.json.put("id", id);
        d.json.put("name", name);
        d.json.put("description", description);
        d.json.put("slot", slot);
        d.json.put("rarity", rarity);
        return d;
    }

    static Def cape(String id, String name, String rarity, String description, CapeArt art, int trim, int frames, int frameTime) {
        Def d = meta(id, name, "cape", rarity, description);
        if (frames > 1) {
            d.json.put("frames", frames);
            d.json.put("frameTime", frameTime);
            d.texture = strip(art, trim, 4, frames);
        } else {
            d.texture = capeFrame(art, trim, 8, 0);
        }
        return d;
    }

    static Def model(Def d, Model m) {
        d.model = m;
        d.json.put("textureSize", Arrays.asList(m.uvW, m.uvH));
        d.json.put("parts", m.parts);
        d.texture = m.paint();
        return d;
    }

    static List<Def> starters() {
        List<Def> out = new ArrayList<>();
        out.add(cape("cape-kite", "Kite", "rare", "The client's own cape, in the brand gradient.", KITE, rgb(0xC9D3FF), 1, 0));
        out.add(cape("cape-midnight", "Midnight", "rare", "A starry night sky with a crescent moon.", MIDNIGHT, rgb(0xB9C0D8), 1, 0));
        out.add(cape("cape-aurora", "Aurora", "epic", "Animated northern lights over a starry sky.", AURORA, rgb(0x2FD3B0), 24, 90));
        out.add(cape("cape-ember", "Ember", "epic", "Animated flames with drifting embers.", EMBER, rgb(0x2A0A06), 16, 70));
        out.add(cape("cape-galaxy", "Galaxy", "legendary", "An animated spiral galaxy with twinkling stars.", GALAXY, rgb(0xD9C8FF), 32, 110));

        { // Angel wings
            Def d = meta("wings-angel", "Angel Wings", "wings", "epic", "Feathered wings that flap as you move and beat hard in flight.");
            Model m = new Model(64, 64, 4);
            Map<String, Object> root = m.part("body", v(-1.5, 2, 2.4), v(0, 28, 12));
            root.put("mirror", true);
            root.put("animate", anim("rot", v(0, 18, 4), "speed", 0.09, "flySpeed", 3.0, "flyAmp", 1.7, "walkAmp", 0.3));
            m.cube(root, v(-17, -9, 0.4), v(17, 24, 0), angelWing(false));
            m.cube(root, v(-12, -7, 0.9), v(12, 12, 0), angelWing(true));
            out.add(model(d, m));
        }
        { // Dragon wings
            Def d = meta("wings-dragon", "Dragon Wings", "wings", "legendary", "Leathery wings with bone struts; slow, heavy beats.");
            Model m = new Model(64, 64, 4);
            Map<String, Object> root = m.part("body", v(-1.5, 2, 2.4), v(0, 30, 18));
            root.put("mirror", true);
            root.put("animate", anim("rot", v(0, 22, 6), "speed", 0.07, "flySpeed", 3.5, "flyAmp", 1.6));
            m.cube(root, v(-18, -1, 0.4), v(18, 22, 0), dragonWing());
            m.cube(root, v(-11, -1.5, -0.1), v(11, 1, 1), material(rgb(0x3A1418), 0.12, 4));
            Map<String, Object> claw = m.child(root, v(-11, -1, 0.4), v(0, 0, 35));
            m.cube(claw, v(-1, -2.5, -0.5), v(1, 2, 1), material(rgb(0xD9C9A8), 0.1, 6));
            out.add(model(d, m));
        }
        { // Fairy wings
            Def d = meta("wings-fairy", "Fairy Wings", "wings", "rare", "Shimmering see-through wings that flutter quickly.");
            d.json.put("translucent", true);
            Model m = new Model(64, 32, 4);
            Map<String, Object> root = m.part("body", v(-0.5, 4, 2.4), v(0, 32, 0));
            root.put("mirror", true);
            root.put("animate", anim("rot", v(0, 26, 0), "speed", 0.55, "flySpeed", 1.5));
            Map<String, Object> upper = m.child(root, v(0, 0, 0), v(0, 0, 32));
            m.cube(upper, v(-13, -4, 0), v(13, 8, 0), fairyWing(true));
            Map<String, Object> lower = m.child(root, v(0, 1, 0.3), v(0, 0, -38));
            m.cube(lower, v(-9, -2.5, 0), v(9, 5, 0), fairyWing(false));
            out.add(model(d, m));
        }
        { // Top hat
            Def d = meta("hat-tophat", "Top Hat", "hat", "rare", "A tall felt top hat with a violet band, worn at an angle.");
            Model m = new Model(64, 32, 4);
            Map<String, Object> p = m.part("head", v(0, -8.5, 0), v(0, 0, -6));
            m.cube(p, v(-5.5, -1, -5.5), v(11, 1, 11), felt(rgb(0x1B1A22)));
            m.cube(p, v(-4, -9, -4), v(8, 8, 8), felt(rgb(0x1E1D26)));
            m.cube(p, v(-4, -3, -4), v(8, 2, 8), material(rgb(0x7B5CFF), 0.08, 2), "inflate", 0.15);
            out.add(model(d, m));
        }
        { // Crown
            Def d = meta("hat-crown", "Crown", "hat", "epic", "A jewelled golden crown.");
            Model m = new Model(64, 32, 4);
            Map<String, Object> p = m.part("head", v(0, -8, 0), v(0, 0, 4));
            Paint g = gold();
            m.cube(p, v(-5, -3, -5), v(10, 3, 1), g);
            m.cube(p, v(-5, -3, 4), v(10, 3, 1), g);
            m.cube(p, v(-5, -3, -4), v(1, 3, 8), g);
            m.cube(p, v(4, -3, -4), v(1, 3, 8), g);
            double[][] spikes = { { -5, -5, -5 }, { 4, -5, -5 }, { -5, -5, 4 }, { 4, -5, 4 }, { -0.5, -6, -5 }, { -0.5, -6, 4 }, { -5, -5, -0.5 }, { 4, -5, -0.5 } };
            for (double[] s : spikes) m.cube(p, s, v(1, s[1] == -6 ? 3 : 2, 1), g);
            m.cube(p, v(-1, -2.5, -5.4), v(2, 2, 1), gem(rgb(0xE8193C)), "glow", true);
            m.cube(p, v(-1, -2.5, 4.4), v(2, 2, 1), gem(rgb(0x2E6BFF)), "glow", true);
            m.cube(p, v(-5.4, -2.5, -1), v(1, 2, 2), gem(rgb(0x18C47A)), "glow", true);
            m.cube(p, v(4.4, -2.5, -1), v(1, 2, 2), gem(rgb(0x18C47A)), "glow", true);
            out.add(model(d, m));
        }
        { // Halo
            Def d = meta("hat-halo", "Halo", "hat", "legendary", "A glowing halo that floats and slowly turns above your head.");
            Model m = new Model(32, 16, 8);
            Map<String, Object> p = m.part("head", v(0, -12, 0), v(-8, 0, 0));
            p.put("animate", anim("bob", 0.7, "spin", 1.2, "speed", 0.08));
            Paint glow = q -> mix(rgb(0xFFFBE6), rgb(0xFFD66B), Math.abs(q.ay - 0.5) * 1.6 + (q.face == UP ? 0.3 : 0));
            m.cube(p, v(-4, -0.5, -4), v(8, 1, 1), glow, "glow", true);
            m.cube(p, v(-4, -0.5, 3), v(8, 1, 1), glow, "glow", true);
            m.cube(p, v(-4, -0.5, -3), v(1, 1, 6), glow, "glow", true);
            m.cube(p, v(3, -0.5, -3), v(1, 1, 6), glow, "glow", true);
            out.add(model(d, m));
        }
        out.add(bandanaDef("hat-bandana", "Red Bandana", "common", rgb(0xC21F2C), rgb(0x2A0A0E), rgb(0xFFF3E6)));
        out.add(bandanaDef("hat-bandana-blue", "Blue Bandana", "common", rgb(0x1F4DB8), rgb(0x0A1433), rgb(0xEAF1FF)));
        { // Cat ears
            Def d = meta("ears-cat", "Cat Ears", "ears", "rare", "Soft black cat ears with pink insides; they twitch now and then.");
            Model m = new Model(32, 16, 8);
            Map<String, Object> p = m.part("head", v(-2.5, -8.2, -1), v(-8, 0, -14));
            p.put("mirror", true);
            p.put("animate", anim("rot", v(0, 0, 5), "speed", 0.06));
            Paint f = fur(rgb(0x221F28), rgb(0x3A3544));
            m.cube(p, v(-2, -1, 0), v(4, 1, 1), f);
            m.cube(p, v(-1.5, -2, 0), v(3, 1, 1), f);
            m.cube(p, v(-1, -3, 0), v(2, 1, 1), f);
            m.cube(p, v(-0.5, -4, 0), v(1, 1, 1), f);
            Paint pink = fur(rgb(0xF08FAE), rgb(0xFFC4D6));
            m.cube(p, v(-1, -2, -0.3), v(2, 2, 1), pink);
            m.cube(p, v(-0.5, -3, -0.3), v(1, 1, 1), pink);
            out.add(model(d, m));
        }
        { // Bunny ears
            Def d = meta("ears-bunny", "Bunny Ears", "ears", "rare", "Tall white bunny ears with floppy tips.");
            Model m = new Model(32, 32, 8);
            Map<String, Object> p = m.part("head", v(-2, -8.2, 0), v(-6, 0, -10));
            p.put("mirror", true);
            p.put("animate", anim("rot", v(5, 0, 3), "speed", 0.05, "walkAmp", 1.0));
            Paint f = fur(rgb(0xE9E4E0), rgb(0xFFFFFF));
            Paint pink = fur(rgb(0xF2A6BC), rgb(0xFFD0DD));
            m.cube(p, v(-1, -6, 0), v(2, 6, 1), f);
            m.cube(p, v(-0.5, -5.5, -0.3), v(1, 5, 1), pink);
            Map<String, Object> tip = m.child(p, v(0, -6, 0.5), v(-28, 0, 0));
            tip.put("animate", anim("rot", v(8, 0, 0), "speed", 0.07, "phase", 1.0, "walkAmp", 1.5));
            m.cube(tip, v(-1, -3, -0.5), v(2, 3, 1), f);
            m.cube(tip, v(-0.5, -2.5, -0.8), v(1, 2, 1), pink);
            out.add(model(d, m));
        }
        { // Shades
            Def d = meta("face-shades", "Shades", "face", "common", "Dark sunglasses with a mirrored shine.");
            Model m = new Model(32, 16, 8);
            Paint frame = material(rgb(0x121218), 0.05, 8);
            Paint lens = q -> {
                if (q.face != NORTH) return rgb(0x121218);
                double x = q.fu() / q.w, y = q.fv() / q.h;
                int c = mix(rgb(0x2B1B4A), rgb(0x07060C), y);
                if (Math.abs((x + y * 0.6) - 0.45) < 0.08) c = mix(c, rgb(0xFFFFFF), 0.55);
                if (x < 0.07 || x > 0.93 || y < 0.12 || y > 0.88) c = rgb(0x121218);
                return c;
            };
            Map<String, Object> side = m.part("head", v(0, 0, 0), null);
            side.put("mirror", true);
            m.cube(side, v(-3.6, -5, -5.1), v(3, 2, 1), lens);
            m.cube(side, v(-4.7, -5, -4.6), v(1, 1, 5), frame);
            Map<String, Object> mid = m.part("head", v(0, 0, 0), null);
            m.cube(mid, v(-0.6, -5, -5), v(1, 1, 1), frame, "inflate", 0.05);
            out.add(model(d, m));
        }
        { // Winged boots
            Def d = meta("boots-winged", "Winged Boots", "boots", "epic", "White leather boots with little wings that flap as you run.");
            Model m = new Model(64, 32, 4);
            Map<String, Object> leg = m.part("right_leg", v(0, 0, 0), null);
            leg.put("mirror", true);
            Paint shell = q -> {
                int c = q.face == UP ? rgb(0x6B5A48) : rgb(0xEDEBF5);
                if (q.face != UP && q.face != DOWN && q.ay < 0.16) c = mix(rgb(0xFFE08A), rgb(0xD1A13A), q.ay / 0.16);
                if (q.face != UP && q.face != DOWN && q.ay > 0.86) c = rgb(0x8A7A66);
                double n = (noise(q.tu * 20, q.tv * 20, 0, 0, 2) - 0.5) * 14;
                return add(c, n, n, n);
            };
            m.cube(leg, v(-2, 8, -2), v(4, 4, 4), shell, "inflate", 0.4);
            Map<String, Object> wing = m.child(leg, v(-2.5, 9, 1), v(0, 30, 15));
            wing.put("animate", anim("rot", v(0, 16, 0), "speed", 0.25, "walkAmp", 1.5));
            m.cube(wing, v(-6, -4, 0), v(6, 5, 0), featherMini());
            out.add(model(d, m));
        }
        { // Frost boots
            Def d = meta("boots-frost", "Frost Boots", "boots", "rare", "Ice-blue boots with a frosted cuff and glowing crystals.");
            Model m = new Model(64, 32, 4);
            Map<String, Object> leg = m.part("right_leg", v(0, 0, 0), null);
            leg.put("mirror", true);
            Paint ice = q -> {
                int c = mix(rgb(0xA7E4FF), rgb(0x3C7FD6), q.face == DOWN ? 0 : q.face == UP ? 1 : q.ay);
                double n = fbm(q.tu * 10, q.tv * 10, 0, 0, 13, 3);
                if (n > 0.62) c = mix(c, rgb(0xFFFFFF), (n - 0.62) * 2);
                return c;
            };
            Paint frost = q -> add(rgb(0xF4FBFF), -hash((int) (q.tu * 8), (int) (q.tv * 8), 5) * 40, -hash((int) (q.tu * 8), (int) (q.tv * 8), 5) * 20, 0);
            Paint crystal = q -> mix(rgb(0xE6FDFF), rgb(0x5FD8FF), q.face == DOWN ? 0 : q.ay);
            m.cube(leg, v(-2, 8, -2), v(4, 4, 4), ice, "inflate", 0.4);
            m.cube(leg, v(-2, 7, -2), v(4, 1, 4), frost, "inflate", 0.7);
            m.cube(leg, v(-3.2, 5.5, -0.5), v(1, 2, 1), crystal, "glow", true);
            m.cube(leg, v(-3.0, 6.2, -2), v(1, 1, 1), crystal, "glow", true);
            m.cube(leg, v(-0.5, 5.3, -3.2), v(1, 2, 1), crystal, "glow", true);
            out.add(model(d, m));
        }
        { // Backpack
            Def d = meta("back-explorer", "Explorer Pack", "back", "common", "A canvas backpack with a bedroll, for long journeys.");
            Model m = new Model(64, 64, 4);
            Map<String, Object> p = m.part("body", v(0, 0, 0), null);
            Paint canvas = q -> {
                int c = rgb(0x8A5A33);
                double n = noise(q.tu * 16, q.tv * 16, 0, 0, 3);
                c = add(c, (n - 0.5) * 24, (n - 0.5) * 20, (n - 0.5) * 14);
                double weave = ((int) (q.tu * 8) + (int) (q.tv * 8)) % 2 == 0 ? 6 : -6;
                c = add(c, weave, weave, weave);
                if (q.face == SOUTH && Math.abs(q.ax - 0.5) < 0.04) c = rgb(0x5A3A20); // seam
                return c;
            };
            Paint leather = material(rgb(0x4A2E1A), 0.1, 6);
            Paint flap = q -> {
                int c = shade(rgb(0x6E4526), q.face == DOWN ? 1.15 : 1);
                if (q.face == SOUTH && q.ay > 0.6 && Math.abs(q.ax - 0.5) < 0.1) c = rgb(0xE0B84A); // buckle
                return c;
            };
            Paint roll = q -> {
                int c = mix(rgb(0x4E7D46), rgb(0x2F5329), q.face == UP ? 1 : q.ay);
                double sx = q.ax * q.w;
                if (Math.abs(sx - 2) < 0.4 || Math.abs(sx - q.w + 2) < 0.4) c = rgb(0x3B2413); // straps
                if (q.face == WEST || q.face == EAST) {
                    double r = Math.hypot(q.az - 0.5, q.ay - 0.5);
                    c = ((int) (r * 8)) % 2 == 0 ? rgb(0x5E9254) : rgb(0x3C6634); // rolled spiral
                }
                return c;
            };
            m.cube(p, v(-3.5, 2, 2.4), v(7, 8, 3), canvas);
            m.cube(p, v(-3.5, 1.5, 2.4), v(7, 3, 3), flap, "inflate", 0.2);
            m.cube(p, v(-4.5, -0.3, 3.2), v(9, 2, 2), roll);
            m.cube(p, v(-4.5, 5, 2.9), v(1, 4, 2), canvas);
            m.cube(p, v(3.5, 5, 2.9), v(1, 4, 2), canvas);
            m.cube(p, v(-3, 0, -2.6), v(1, 6, 1), leather);
            m.cube(p, v(2, 0, -2.6), v(1, 6, 1), leather);
            m.cube(p, v(-3, -0.4, -2.4), v(1, 1, 5), leather);
            m.cube(p, v(2, -0.4, -2.4), v(1, 1, 5), leather);
            out.add(model(d, m));
        }
        return out;
    }

    static Def bandanaDef(String id, String name, String rarity, int base, int ink, int dots) {
        Def d = meta(id, name, "hat", rarity, "A patterned bandana tied around the head; the tails sway as you move.");
        Model m = new Model(64, 32, 8);
        Paint cloth = bandana(base, ink, dots);
        Map<String, Object> p = m.part("head", v(0, 0, 0), null);
        m.cube(p, v(-4, -6.5, -4), v(8, 2, 8), cloth, "inflate", 0.62);
        m.cube(p, v(-1, -6.8, 4.4), v(2, 2, 1), cloth, "inflate", 0.1);
        Map<String, Object> t1 = m.child(p, v(-0.5, -5.1, 5), v(25, 0, -14));
        t1.put("animate", anim("rot", v(10, 0, 6), "speed", 0.12, "walkAmp", 2.0));
        m.cube(t1, v(-1, 0, 0), v(2, 5, 1), cloth);
        Map<String, Object> t2 = m.child(p, v(0.5, -5.1, 5), v(18, 0, 16));
        t2.put("animate", anim("rot", v(10, 0, -6), "speed", 0.12, "phase", 1.3, "walkAmp", 2.0));
        m.cube(t2, v(-1, 0, 0), v(2, 4, 1), cloth);
        return model(d, m);
    }

    // ---- output -----------------------------------------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: StarterCosmetics <out dir> [--preview <dir>]");
            System.exit(2);
        }
        Path out = Paths.get(args[0]);
        Path preview = args.length >= 3 && args[1].equals("--preview") ? Paths.get(args[2]) : null;
        List<Def> defs = starters();
        List<Object> ids = new ArrayList<>();
        for (Def d : defs) {
            String id = (String) d.json.get("id");
            Path dir = out.resolve(id);
            Files.createDirectories(dir);
            if (d.model != null) {
                ImageIO.write(Preview.icon(d), "png", dir.resolve("icon.png").toFile());
                d.json.put("iconImage", "icon.png");
            }
            Files.write(dir.resolve("cosmetic.json"), (pretty(d.json, 0) + "\n").getBytes(StandardCharsets.UTF_8));
            ImageIO.write(d.texture, "png", dir.resolve("texture.png").toFile());
            ids.add(id);
            System.out.printf("%-18s %4dx%-5d %s%n", id, d.texture.getWidth(), d.texture.getHeight(), d.model == null ? "cape" : d.model.cubes.size() + " cubes");
        }
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("cosmetics", ids);
        Files.write(out.resolve("index.json"), (pretty(index, 0) + "\n").getBytes(StandardCharsets.UTF_8));
        if (preview != null) Preview.renderAll(defs, preview);
    }

    /** JSON with one key per line; short number lists stay on one line. */
    static String pretty(Object o, int indent) {
        String pad = "  ".repeat(indent);
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (m.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(pad).append("  \"").append(e.getKey()).append("\": ").append(pretty(e.getValue(), indent + 1));
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            return sb.append(pad).append("}").toString();
        }
        if (o instanceof List) {
            List<?> l = (List<?>) o;
            boolean flat = true;
            for (Object x : l) flat &= !(x instanceof Map) && !(x instanceof List);
            if (flat && (l.isEmpty() || l.get(0) instanceof Number)) {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < l.size(); i++) sb.append(i > 0 ? ", " : "").append(pretty(l.get(i), 0));
                return sb.append("]").toString();
            }
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < l.size(); i++) sb.append(pad).append("  ").append(pretty(l.get(i), indent + 1)).append(i + 1 < l.size() ? ",\n" : "\n");
            return sb.append(pad).append("]").toString();
        }
        if (o instanceof String) return "\"" + ((String) o).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (o instanceof Double) {
            double dv = (Double) o;
            return dv == Math.rint(dv) ? String.valueOf((long) dv) : String.valueOf(Math.round(dv * 1000) / 1000.0);
        }
        return String.valueOf(o);
    }

    // ---- preview renderer -------------------------------------------------------------------------------------------

    /** Orthographic software renderer: the cosmetic on a grey player, seen from front-left and back-right. */
    static final class Preview {
        static final int W = 280, H = 300;
        static final double SCALE = 6.5;

        static void renderAll(List<Def> defs, Path dir) throws IOException {
            Files.createDirectories(dir);
            int cols = 4;
            List<BufferedImage> sheets = new ArrayList<>();
            for (Def d : defs) {
                BufferedImage sheet = new BufferedImage(W * 2, H + 14, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = sheet.createGraphics();
                g.setColor(new java.awt.Color(0x1B1E27));
                g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
                double[] yaws = { 30, 150 };
                for (int i = 0; i < 2; i++) g.drawImage(render(d, yaws[i], 0.0), i * W, 14, null);
                g.setColor(java.awt.Color.WHITE);
                g.drawString((String) d.json.get("id"), 6, 11);
                g.dispose();
                ImageIO.write(sheet, "png", dir.resolve(d.json.get("id") + ".png").toFile());
                sheets.add(sheet);
            }
            int rows = (sheets.size() + cols - 1) / cols;
            BufferedImage all = new BufferedImage(cols * W * 2 / 2, rows * (H + 14) / 2, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = all.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (int i = 0; i < sheets.size(); i++) g.drawImage(sheets.get(i), (i % cols) * W, (i / cols) * (H + 14) / 2, W, (H + 14) / 2, null);
            g.dispose();
            ImageIO.write(all, "png", dir.resolve("_all.png").toFile());
        }

        /** Menu thumbnail: the model alone, seen from where it looks best, cropped and fitted into 128x128. */
        static BufferedImage icon(Def d) {
            String slot = (String) d.json.get("slot");
            boolean back = slot.equals("wings") || slot.equals("back");
            BufferedImage full = render(d, back ? 155 : 28, 0, false);
            int x0 = W, y0 = H, x1 = -1, y1 = -1;
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                if ((full.getRGB(x, y) >>> 24) == 0) continue;
                x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
            }
            BufferedImage out = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
            if (x1 < 0) return out;
            int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
            double s = Math.min(120.0 / bw, 120.0 / bh);
            int dw = (int) Math.round(bw * s), dh = (int) Math.round(bh * s);
            java.awt.Graphics2D g = out.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    s < 1 ? java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR : java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(full, (128 - dw) / 2, (128 - dh) / 2, (128 - dw) / 2 + dw, (128 - dh) / 2 + dh, x0, y0, x1 + 1, y1 + 1, null);
            g.dispose();
            return out;
        }

        static final double[][] BONES = { { 0, 0, 0 }, { 0, 0, 0 }, { -5, 2, 0 }, { 5, 2, 0 }, { -1.9, 12, 0 }, { 1.9, 12, 0 } };

        static BufferedImage render(Def d, double yawDeg, double age) { return render(d, yawDeg, age, true); }

        static BufferedImage render(Def d, double yawDeg, double age, boolean withPlayer) {
            int[] color = new int[W * H];
            double[] depth = new double[W * H];
            Arrays.fill(depth, Double.MAX_VALUE);
            Arrays.fill(color, withPlayer ? 0xFF1B1E27 : 0);
            double yaw = Math.toRadians(yawDeg);
            // Player: head, body, arms, legs (vanilla sizes, outer layer ignored).
            double[][] boxes = { { -4, -8, -4, 8, 8, 8, 0xC89F7F }, { -4, 0, -2, 8, 12, 4, 0x3F6FA8 }, { -8, -2, -2, 4, 12, 4, 0xC89F7F },
                    { 4, -2, -2, 4, 12, 4, 0xC89F7F }, { -3.9, 12, -2, 4, 12, 4, 0x3A3F63 }, { -0.1, 12, -2, 4, 12, 4, 0x3A3F63 } };
            if (withPlayer) for (double[] b : boxes) box(color, depth, yaw, b);
            if (d.model != null) {
                String json = gg.kite.core.Json.write(d.json);
                Object parts = gg.kite.core.Json.parseObject(json).get("parts");
                CosmeticModel model = CosmeticModel.parse(parts, d.model.uvW, d.model.uvH);
                Mesh mesh = model.mesh(new CosmeticModel.Motion((float) age, 0, false));
                for (CosmeticModel.Bone bone : CosmeticModel.Bone.values()) {
                    for (boolean glow : new boolean[] { false, true }) {
                        Mesh.Layer l = mesh.layer(bone, glow);
                        if (l != null) quads(color, depth, yaw, l, BONES[bone.ordinal()], d.texture, glow);
                    }
                }
            } else {
                // Cape: vanilla cape box, 2 px behind the body, turned to face backwards and tilted out.
                Map<String, Object> cube = new LinkedHashMap<>();
                cube.put("from", nums(v(-5, 0, -1)));
                cube.put("size", nums(v(10, 16, 1)));
                cube.put("uv", Arrays.asList(0, 0));
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("bone", "body");
                part.put("pivot", nums(v(0, 0, 2)));
                part.put("rotation", nums(v(-8, 180, 0)));
                part.put("cubes", Arrays.asList(cube));
                CosmeticModel model = CosmeticModel.parse(gg.kite.core.Json.parseObject(gg.kite.core.Json.write(Map.of("p", Arrays.asList(part)))).get("p"), 64, 32);
                BufferedImage frame = d.texture.getSubimage(0, 0, d.texture.getWidth(), d.texture.getWidth() / 2);
                quads(color, depth, yaw, model.mesh(new CosmeticModel.Motion()).layer(CosmeticModel.Bone.BODY, false), BONES[1], frame, false);
            }
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, W, H, color, 0, W);
            return img;
        }

        static void box(int[] color, double[] depth, double yaw, double[] b) {
            double x0 = b[0], y0 = b[1], z0 = b[2], x1 = x0 + b[3], y1 = y0 + b[4], z1 = z0 + b[5];
            double[][] c = new double[8][];
            for (int i = 0; i < 8; i++) c[i] = new double[] { (i & 1) != 0 ? x1 : x0, (i & 2) != 0 ? y1 : y0, (i & 4) != 0 ? z1 : z0 };
            int[][] faces = { { 0, 1, 3, 2 }, { 4, 5, 7, 6 }, { 0, 4, 6, 2 }, { 1, 5, 7, 3 }, { 0, 1, 5, 4 }, { 2, 3, 7, 6 } };
            double[][] normals = { { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 }, { 0, -1, 0 }, { 0, 1, 0 } };
            for (int f = 0; f < 6; f++) {
                double[][] q = { c[faces[f][0]], c[faces[f][1]], c[faces[f][2]], c[faces[f][3]] };
                int col = 0xFF000000 | (int) b[6];
                tri(color, depth, yaw, q[0], q[1], q[2], null, null, null, null, col, normals[f], false);
                tri(color, depth, yaw, q[0], q[2], q[3], null, null, null, null, col, normals[f], false);
            }
        }

        static void quads(int[] color, double[] depth, double yaw, Mesh.Layer l, double[] bone, BufferedImage tex, boolean glow) {
            float[] dt = l.data();
            for (int q = 0; q < l.quads(); q++) {
                int o = q * Mesh.STRIDE;
                double[] n = { dt[o], dt[o + 1], dt[o + 2] };
                double[][] p = new double[4][], uv = new double[4][];
                for (int k = 0; k < 4; k++) {
                    int b = o + 3 + k * 5;
                    p[k] = new double[] { dt[b] + bone[0], dt[b + 1] + bone[1], dt[b + 2] + bone[2] };
                    uv[k] = new double[] { dt[b + 3], dt[b + 4] };
                }
                tri(color, depth, yaw, p[0], p[1], p[2], uv[0], uv[1], uv[2], tex, 0, n, glow);
                tri(color, depth, yaw, p[0], p[2], p[3], uv[0], uv[2], uv[3], tex, 0, n, glow);
            }
        }

        static double[] project(double[] p, double yaw) {
            double c = Math.cos(yaw), s = Math.sin(yaw);
            double x = p[0] * c - p[2] * s, z = p[0] * s + p[2] * c;
            return new double[] { W / 2.0 + x * SCALE, (p[1] + 20) * SCALE, z };
        }

        static void tri(int[] color, double[] depth, double yaw, double[] a, double[] b, double[] cc, double[] ua, double[] ub, double[] uc,
                        BufferedImage tex, int flat, double[] normal, boolean glow) {
            double[] A = project(a, yaw), B = project(b, yaw), C = project(cc, yaw);
            // Light from the camera's upper left.
            double cs = Math.cos(yaw), sn = Math.sin(yaw);
            double nx = normal[0] * cs - normal[2] * sn, nz = normal[0] * sn + normal[2] * cs, ny = normal[1];
            double light = glow ? 1 : 0.55 + 0.45 * Math.abs(-0.4 * nx - 0.6 * ny - 0.7 * nz);
            double minX = Math.max(0, Math.floor(Math.min(A[0], Math.min(B[0], C[0])))), maxX = Math.min(W - 1, Math.ceil(Math.max(A[0], Math.max(B[0], C[0]))));
            double minY = Math.max(0, Math.floor(Math.min(A[1], Math.min(B[1], C[1])))), maxY = Math.min(H - 1, Math.ceil(Math.max(A[1], Math.max(B[1], C[1]))));
            double area = (B[0] - A[0]) * (C[1] - A[1]) - (B[1] - A[1]) * (C[0] - A[0]);
            if (Math.abs(area) < 1e-9) return;
            for (int y = (int) minY; y <= maxY; y++) {
                for (int x = (int) minX; x <= maxX; x++) {
                    double px = x + 0.5, py = y + 0.5;
                    double w0 = ((B[0] - px) * (C[1] - py) - (B[1] - py) * (C[0] - px)) / area;
                    double w1 = ((C[0] - px) * (A[1] - py) - (C[1] - py) * (A[0] - px)) / area;
                    double w2 = 1 - w0 - w1;
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                    double z = w0 * A[2] + w1 * B[2] + w2 * C[2];
                    int i = y * W + x;
                    if (z >= depth[i]) continue;
                    int col = flat;
                    if (tex != null) {
                        double u = w0 * ua[0] + w1 * ub[0] + w2 * uc[0], v = w0 * ua[1] + w1 * ub[1] + w2 * uc[1];
                        int tx = (int) Math.min(tex.getWidth() - 1, Math.max(0, Math.floor(u * tex.getWidth())));
                        int ty = (int) Math.min(tex.getHeight() - 1, Math.max(0, Math.floor(v * tex.getHeight())));
                        col = tex.getRGB(tx, ty);
                    }
                    int a8 = (col >>> 24) & 255;
                    if (a8 < 26) continue;
                    int shaded = shade(col, light) | 0xFF000000;
                    if (a8 < 250) {
                        color[i] = mix(color[i], shaded, a8 / 255.0);
                        continue; // translucent: no depth write
                    }
                    depth[i] = z;
                    color[i] = shaded;
                }
            }
        }
    }
}
