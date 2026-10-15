package gg.aether.mc;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** Dynamic GUI textures for UiRenderer.image (minimap, world map): one per key, re-uploaded only on a new version. */
public final class McTextures {
    private McTextures() {}

    private static final class Tex {
        DynamicTexture tex;
        Identifier id;
        int w, h;
        long version = Long.MIN_VALUE;
    }

    /** Most recently used last; beyond MAX the oldest textures are released (screenshot thumbnails add up). */
    private static final int MAX = 512;
    private static final LinkedHashMap<String, Tex> TEXTURES = new LinkedHashMap<String, Tex>(64, 0.75f, true);

    /** Texture id for a key: readable prefix plus a hash, so keys differing only in case or symbols never share one. */
    static String idPath(String key) {
        String readable = key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
        if (readable.length() > 60) readable = readable.substring(readable.length() - 60);
        return "dynamic/" + readable + "_" + Integer.toHexString(key.hashCode());
    }

    /** Make sure texture {@code key} holds {@code argb} ({@code w x h}); returns its id. Render thread only. */
    public static Identifier prepare(String key, int w, int h, int[] argb, long version) {
        Minecraft mc = Minecraft.getInstance();
        Tex t = TEXTURES.get(key);
        if (t == null || t.w != w || t.h != h) {
            if (t != null) mc.getTextureManager().release(t.id);
            t = new Tex();
            t.w = w;
            t.h = h;
            t.id = Identifier.fromNamespaceAndPath("aether", idPath(key));
            final String label = "aether " + key;
            t.tex = Compat.dynamicTexture(label, new NativeImage(w, h, false));
            mc.getTextureManager().register(t.id, t.tex);
            TEXTURES.put(key, t);
            while (TEXTURES.size() > MAX) {
                java.util.Iterator<Map.Entry<String, Tex>> it = TEXTURES.entrySet().iterator();
                Tex old = it.next().getValue();
                it.remove();
                mc.getTextureManager().release(old.id);
            }
        }
        if (t.version != version && argb.length >= w * h) {
            NativeImage img = t.tex.getPixels();
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) Pixels.set(img, x, y, argb[y * w + x]);
            t.tex.upload();
            t.version = version;
        }
        return t.id;
    }
}
