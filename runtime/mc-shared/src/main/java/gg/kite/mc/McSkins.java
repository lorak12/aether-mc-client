package gg.kite.mc;

import gg.kite.core.SkinFaces;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;

/**
 * Skins outside a world: account faces for the main menu's account switcher, and the signed-in player's skin for the
 * shop preview when there is no player entity.
 */
public final class McSkins {
    private McSkins() {}

    /** Faces of vanilla's default skins by account uuid (null: couldn't be read). Render thread. */
    private static final Map<String, int[]> DEFAULT_FACES = new HashMap<String, int[]>();

    /**
     * An account's face (8x8 ARGB with the hat layer): its Mojang skin, else the default skin vanilla gives that uuid
     * (offline accounts); null while the lookup runs. Render thread.
     */
    public static int[] face(String uuid) {
        int[] f = SkinFaces.face(uuid);
        return f == SkinFaces.NONE ? defaultFace(uuid) : f;
    }

    private static int[] defaultFace(String uuid) {
        if (uuid == null) return null;
        if (DEFAULT_FACES.containsKey(uuid)) return DEFAULT_FACES.get(uuid);
        int[] face = null;
//#if >=1.21.9
        try {
            net.minecraft.resources.Identifier tex = net.minecraft.client.resources.DefaultPlayerSkin.get(McSession.uuid(uuid)).body().texturePath();
            java.util.Optional<net.minecraft.server.packs.resources.Resource> res = Minecraft.getInstance().getResourceManager().getResource(tex);
            if (res.isPresent()) {
                try (java.io.InputStream in = res.get().open()) {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(in);
                    if (img != null) face = SkinFaces.face(img);
                }
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("kite").debug("No default skin face for {}", uuid, e);
        }
//#endif
        DEFAULT_FACES.put(uuid, face);
        return face;
    }

//#if >=1.21.9
    /** The signed-in account's skin: their Mojang skin once it has loaded, vanilla's default for them until then. */
    public static net.minecraft.world.entity.player.PlayerSkin localSkin() {
        Minecraft mc = Minecraft.getInstance();
        com.mojang.authlib.GameProfile profile = null;
        java.util.concurrent.CompletableFuture<?> lookup = Compat.profileFuture(mc);
        // getGameProfile() waits for the startup profile lookup; only ask once it is done.
        if (lookup != null && lookup.isDone() && !lookup.isCompletedExceptionally()) profile = mc.getGameProfile();
        if (profile == null) profile = new com.mojang.authlib.GameProfile(mc.getUser().getProfileId(), mc.getUser().getName());
        java.util.Optional<net.minecraft.world.entity.player.PlayerSkin> skin = mc.getSkinManager().get(profile).getNow(null);
        return skin != null && skin.isPresent() ? skin.get() : net.minecraft.client.resources.DefaultPlayerSkin.get(profile);
    }
//#endif
}
