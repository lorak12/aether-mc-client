package gg.kite.mc;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Textures and the version line for the shared GuiRenderers. */
public final class McUi {
    private McUi() {}

    private static final Map<Identifier, int[]> SIZES = new HashMap<Identifier, int[]>();

    /**
     * Make sure texture {@code id} is loaded and return its {width, height}; null if missing. Kite's own textures
     * (namespace "kite") are read from the jar, since the jar is not a resource pack without Fabric API; others
     * come from the resource manager. Render thread only.
     */
    public static int[] texture(Identifier id) {
        if (id == null) return null;
        if (SIZES.containsKey(id)) return SIZES.get(id);
        int[] size = null;
        if ("kite".equals(id.getNamespace())) {
            try (InputStream in = McUi.class.getResourceAsStream("/assets/kite/" + id.getPath())) {
                if (in != null) {
                    NativeImage img = NativeImage.read(in);
                    size = new int[] { img.getWidth(), img.getHeight() };
                    net.minecraft.client.renderer.texture.DynamicTexture tex = Compat.smoothTexture("kite " + id.getPath(), img);
                    Minecraft.getInstance().getTextureManager().register(id, tex);
                    tex.upload();
                }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger("kite").warn("Could not load {}", id, e);
            }
        } else {
            size = pngSize(id);
        }
        SIZES.put(id, size);
        return size;
    }

    /** PNG size from its header in the resource packs. */
    private static int[] pngSize(Identifier id) {
        try {
//#if >=1.19
            java.util.Optional<net.minecraft.server.packs.resources.Resource> res = Minecraft.getInstance().getResourceManager().getResource(id);
            if (!res.isPresent()) return null;
            try (InputStream in = res.get().open()) {
//#else
            try (InputStream in = Minecraft.getInstance().getResourceManager().getResource(id).getInputStream()) {
//#endif
                byte[] head = new byte[24];
                int n = 0;
                while (n < head.length) {
                    int r = in.read(head, n, head.length - n);
                    if (r < 0) break;
                    n += r;
                }
                if (n == 24 && head[12] == 'I' && head[13] == 'H' && head[14] == 'D' && head[15] == 'R') return new int[] { readInt(head, 16), readInt(head, 20) };
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static int readInt(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    /** "Kite Client 1.21.11 (v0.1.0-123)": the build number comes from fabric.mod.json's "kite:build". */
    public static String versionLine(String clientVersion) {
        String mc = net.minecraft.SharedConstants.getCurrentVersion().name();
        String build = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("kite")
                .map(c -> c.getMetadata().getCustomValue("kite:build"))
                .map(v -> v.getType() == net.fabricmc.loader.api.metadata.CustomValue.CvType.STRING ? v.getAsString() : "")
                .orElse("");
        return "Kite Client " + mc + " (v" + clientVersion + (build.isEmpty() ? "" : "-" + build) + ")";
    }
}
