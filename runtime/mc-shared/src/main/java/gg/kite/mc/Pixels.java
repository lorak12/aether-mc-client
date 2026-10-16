package gg.kite.mc;

import com.mojang.blaze3d.platform.NativeImage;

/** NativeImage pixel writes in ARGB. Before 1.21.2 NativeImage stored ABGR (setPixelRGBA), so red and blue swap. */
public final class Pixels {
    private Pixels() {}

    public static void set(NativeImage img, int x, int y, int argb) {
//#if >=1.21.2
        img.setPixel(x, y, argb);
//#else
        img.setPixelRGBA(x, y, (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16));
//#endif
    }
}
