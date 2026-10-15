package gg.aether.mc;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

/**
 * Textures for the legacy GuiRenderer: Aether's own images from the jar (the jar is no resource pack), dynamic images
 * (minimap, icons) re-uploaded only on a new version, and font atlases. Render thread only.
 */
public final class McTextures {
    private McTextures() {}

    private static final class Tex {
        NativeImageBackedTexture tex;
        Identifier id;
        int w, h;
        long version = Long.MIN_VALUE;
    }

    /** Most recently used last; beyond MAX the oldest textures are released. */
    private static final int MAX = 512;
    private static final LinkedHashMap<String, Tex> DYNAMIC = new LinkedHashMap<String, Tex>(64, 0.75f, true);
    private static final Map<Identifier, int[]> SIZES = new HashMap<Identifier, int[]>();
    /** Textures sampled linearly (drawn scaled: fonts, logo). */
    private static final java.util.Set<Identifier> SMOOTH = new java.util.HashSet<Identifier>();

    static String idPath(String key) {
        String readable = key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
        if (readable.length() > 60) readable = readable.substring(readable.length() - 60);
        return "dynamic/" + readable + "_" + Integer.toHexString(key.hashCode());
    }

    /** Make sure texture {@code key} holds {@code argb} ({@code w x h}); returns its id. */
    public static Identifier prepare(String key, int w, int h, int[] argb, long version) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Tex t = DYNAMIC.get(key);
        if (t == null || t.w != w || t.h != h) {
            if (t != null) mc.getTextureManager().close(t.id);
            t = new Tex();
            t.w = w;
            t.h = h;
            t.id = new Identifier("aether", idPath(key));
            t.tex = new NativeImageBackedTexture(w, h);
            mc.getTextureManager().loadTexture(t.id, t.tex);
            SMOOTH.add(t.id);
            DYNAMIC.put(key, t);
            while (DYNAMIC.size() > MAX) {
                java.util.Iterator<Map.Entry<String, Tex>> it = DYNAMIC.entrySet().iterator();
                Tex old = it.next().getValue();
                it.remove();
                mc.getTextureManager().close(old.id);
            }
        }
        if (t.version != version && argb.length >= w * h) {
            System.arraycopy(argb, 0, t.tex.getPixels(), 0, w * h);
            t.tex.upload();
            t.version = version;
        }
        return t.id;
    }

    /** Register an image (font atlas) under {@code id}, sampled linearly. */
    public static void register(Identifier id, BufferedImage img) {
        NativeImageBackedTexture tex = new NativeImageBackedTexture(img);
        MinecraftClient.getInstance().getTextureManager().loadTexture(id, tex);
        SMOOTH.add(id);
        SIZES.put(id, new int[] { img.getWidth(), img.getHeight() });
    }

    /**
     * {width, height} of a texture, loading Aether's own ("aether:textures/...") from the jar on first use; null if
     * missing. Other namespaces come from the resource packs.
     */
    public static int[] size(Identifier id) {
        if (SIZES.containsKey(id)) return SIZES.get(id);
        int[] size = null;
        try {
            if ("aether".equals(id.getNamespace())) {
                try (InputStream in = McTextures.class.getResourceAsStream("/assets/aether/" + id.getPath())) {
                    if (in != null) {
                        BufferedImage img = TextureUtil.create(in);
                        register(id, img);
                        size = new int[] { img.getWidth(), img.getHeight() };
                    }
                }
            } else {
                try (InputStream in = MinecraftClient.getInstance().getResourceManager().getResource(id).getInputStream()) {
                    BufferedImage img = TextureUtil.create(in);
                    size = new int[] { img.getWidth(), img.getHeight() };
                }
            }
        } catch (Exception e) {
            size = null;
        }
        SIZES.put(id, size);
        return size;
    }

    /** Bind for drawing; false if unknown. */
    public static boolean bind(Identifier id) {
        if (id == null) return false;
        MinecraftClient.getInstance().getTextureManager().bindTexture(id);
        int filter = SMOOTH.contains(id) ? GL11.GL_LINEAR : GL11.GL_NEAREST;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        return true;
    }
}
