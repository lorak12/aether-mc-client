package gg.kite.core.ui;

import gg.kite.core.UiRenderer;
import java.util.HashMap;
import java.util.Map;

/**
 * The Kite mark (brand/kite-mark.svg) in colour on a transparent background, rasterised with anti-aliasing at the exact
 * pixel size it is drawn at, so it stays sharp at every GUI scale: the logo in the menus and the badge before client
 * users' names.
 */
public final class KiteMark {
    private KiteMark() {}

    // brand/kite-mark.svg (32x32) on Icons' 24x24 grid.
    private static final String OUTLINE = "F M12 2.25 L21 9.75 L12 21.75 L3 9.75 Z";
    private static final String[] FACES = {
        "F M12 2.25 L21 9.75 L12 12 Z", "F M12 2.25 L3 9.75 L12 12 Z", "F M3 9.75 L12 12 L12 21.75 Z", "F M21 9.75 L12 12 L12 21.75 Z",
    };
    private static final int[] FACE_RGB = { 0xA78BFA, 0x8B5CF6, 0x7C3AED, 0x6D28D9 };
    private static final String TAIL = "M12 21.75 L9.75 23.625 M12 21.75 L14.25 23.625";
    private static final int TAIL_RGB = 0x8B5CF6;
    private static final float TAIL_STROKE = 1.2f;

    private static final Map<Integer, int[]> CACHE = new HashMap<Integer, int[]>();

    /** Draw the mark in a {@code size} square at (x, y). False when the renderer cannot draw images. */
    public static boolean draw(UiRenderer r, float x, float y, float size) {
        int px = Math.max(8, Math.min(512, Math.round(size * Math.max(0.25f, r.pixelScale()))));
        return r.image("kite:kite/" + px, px, px, pixels(px), 1, x, y, size, size);
    }

    /** ARGB pixels ({@code px * px}) of the mark. Cached. */
    public static synchronized int[] pixels(int px) {
        int[] p = CACHE.get(px);
        if (p == null) {
            p = render(px);
            CACHE.put(px, p);
        }
        return p;
    }

    /**
     * Alpha from the whole silhouette and colour from the faces' coverage, so the faces' shared edges don't show seams
     * of background; the tail goes on top.
     */
    static int[] render(int px) {
        int[] outline = Icons.rasterize(new String[] { OUTLINE }, px);
        int[][] faces = new int[FACES.length][];
        for (int i = 0; i < FACES.length; i++) faces[i] = Icons.rasterize(new String[] { FACES[i] }, px);
        int[] tail = Icons.rasterize(new String[] { TAIL }, px, TAIL_STROKE);
        int[] out = new int[px * px];
        for (int i = 0; i < out.length; i++) {
            float a = (outline[i] >>> 24) / 255f, r = 0, g = 0, b = 0, sum = 0;
            for (int f = 0; f < faces.length; f++) {
                float c = (faces[f][i] >>> 24) / 255f;
                r += c * (FACE_RGB[f] >> 16 & 0xFF);
                g += c * (FACE_RGB[f] >> 8 & 0xFF);
                b += c * (FACE_RGB[f] & 0xFF);
                sum += c;
            }
            if (sum > 0) {
                r /= sum;
                g /= sum;
                b /= sum;
            } else {
                a = 0;
            }
            // Tail over the kite.
            float ta = (tail[i] >>> 24) / 255f;
            float oa = ta + a * (1 - ta);
            if (oa <= 0) continue;
            r = ((TAIL_RGB >> 16 & 0xFF) * ta + r * a * (1 - ta)) / oa;
            g = ((TAIL_RGB >> 8 & 0xFF) * ta + g * a * (1 - ta)) / oa;
            b = ((TAIL_RGB & 0xFF) * ta + b * a * (1 - ta)) / oa;
            out[i] = Math.round(oa * 255) << 24 | Math.round(r) << 16 | Math.round(g) << 8 | Math.round(b);
        }
        return out;
    }
}
