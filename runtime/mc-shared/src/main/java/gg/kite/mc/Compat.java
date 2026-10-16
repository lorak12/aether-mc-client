package gg.kite.mc;

import gg.kite.core.GameState;
import gg.kite.core.ModManager;
import gg.kite.client.KiteClient;
import gg.kite.client.mixin.BossOverlayAccessor;
import gg.kite.client.mixin.FoodDataAccessor;
import gg.kite.client.mixin.HudStateAccessor;
import gg.kite.client.mixin.TabOverlayAccessor;
import java.util.Collection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodData;

//#if >=26.1
/** The few calls that differ between Minecraft versions, for the shared adapter code in gg.kite.mc (26_3 build). */
//#else
/** The few calls that differ between Minecraft versions, for the shared adapter code in gg.kite.mc (1_21_11 build). */
//#endif
public final class Compat {
    private Compat() {}

    public static ModManager mods() { return KiteClient.mods(); }
    public static GameState state() { return KiteClient.state(); }

    /** Entity type used to test "could a mob spawn here" (zombie: the common hostile rules). */
//#if >=26.2
    public static net.minecraft.world.entity.EntityType<?> spawnProbe() { return net.minecraft.world.entity.EntityTypes.ZOMBIE; }
//#else
    public static net.minecraft.world.entity.EntityType<?> spawnProbe() { return net.minecraft.world.entity.EntityType.ZOMBIE; }
//#endif

//#if >=26.2
    public static net.minecraft.client.gui.components.ChatComponent chat(Minecraft mc) { return mc.gui == null ? null : mc.gui.hud.getChat(); }
//#else
    public static net.minecraft.client.gui.components.ChatComponent chat(Minecraft mc) { return mc.gui == null ? null : mc.gui.getChat(); }
//#endif

    /** A texture sampled with linear filtering (font atlases, the logo: they are drawn scaled down). */
    public static net.minecraft.client.renderer.texture.DynamicTexture smoothTexture(String label, com.mojang.blaze3d.platform.NativeImage img) {
//#if >=1.21.5
        return new net.minecraft.client.renderer.texture.DynamicTexture(() -> label, img) {
//#else
        return new net.minecraft.client.renderer.texture.DynamicTexture(img) {
//#endif
            {
//#if >=26.3
                sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.LINEAR);
//#elif >=1.21.11
                sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.LINEAR);
//#else
                setFilter(true, false);
//#endif
            }
        };
    }

//#if >=26.2
    public static void setScreen(Minecraft mc, Screen s) { mc.gui.setScreen(s); }
//#else
    public static void setScreen(Minecraft mc, Screen s) { mc.setScreen(s); }
//#endif

//#if >=26.2
    public static Screen screen(Minecraft mc) { return mc.gui.screen(); }
//#else
    public static Screen screen(Minecraft mc) { return mc.screen; }
//#endif

//#if >=26.1
    public static long dayTime(ClientLevel level) { return level.getOverworldClockTime(); }
//#else
    public static long dayTime(ClientLevel level) { return level.getDayTime(); }
//#endif

    public static float exhaustion(FoodData food) { return ((FoodDataAccessor) food).kite$exhaustion(); }

    public static Collection<LerpingBossEvent> bossEvents(Minecraft mc) {
//#if >=26.2
        return ((BossOverlayAccessor) mc.gui.hud.getBossOverlay()).kite$events().values();
//#else
        return ((BossOverlayAccessor) mc.gui.getBossOverlay()).kite$events().values();
//#endif
    }

    public static GameState.Title title(Minecraft mc) {
//#if >=26.2
        HudStateAccessor h = (HudStateAccessor) mc.gui.hud;
//#else
        HudStateAccessor h = (HudStateAccessor) mc.gui;
//#endif
        int t = h.kite$titleTime();
        Component title = h.kite$title();
        if (t <= 0 || title == null) return null;
        int in = h.kite$fadeIn(), stay = h.kite$stay(), out = h.kite$fadeOut();
        float alpha = 1f;
        if (t > out + stay && in > 0) alpha = (in + stay + out - t) / (float) in;
        else if (t < out && out > 0) alpha = t / (float) out;
        Component sub = h.kite$subtitle();
        return new GameState.Title(title.getString(), sub == null ? "" : sub.getString(), Math.max(0f, Math.min(1f, alpha)));
    }

    public static String tabHeader(Minecraft mc) {
//#if >=26.2
        Component c = ((TabOverlayAccessor) mc.gui.hud.getTabList()).kite$header();
//#else
        Component c = ((TabOverlayAccessor) mc.gui.getTabList()).kite$header();
//#endif
        return c == null ? "" : c.getString();
    }

    public static String tabFooter(Minecraft mc) {
//#if >=26.2
        Component c = ((TabOverlayAccessor) mc.gui.hud.getTabList()).kite$footer();
//#else
        Component c = ((TabOverlayAccessor) mc.gui.getTabList()).kite$footer();
//#endif
        return c == null ? "" : c.getString();
    }

    public static java.util.Map<net.minecraft.advancements.AdvancementHolder, net.minecraft.advancements.AdvancementProgress> advancementProgress(Minecraft mc) {
        net.minecraft.client.multiplayer.ClientPacketListener c = mc.getConnection();
//#if >=26.3
        return c == null ? null : c.getAdvancements().progress();
//#else
        return c == null ? null : ((gg.kite.client.mixin.ClientAdvancementsAccessor) c.getAdvancements()).kite$progress();
//#endif
    }

    public static net.minecraft.client.renderer.texture.DynamicTexture overlayTexture(Minecraft mc) {
        return ((gg.kite.client.mixin.OverlayTextureAccessor) mc.gameRenderer.overlayTexture()).kite$texture();
    }

    public static int refreshRate(Minecraft mc) {
        try {
//#if >=26.2
            return Math.round(mc.getWindow().findBestMonitor().currentMode().getRefreshRate());
//#else
            return mc.getWindow().getRefreshRate();
//#endif
        } catch (RuntimeException e) {
            return 60;
        }
    }

//#if >=26.2
    public static String sectionStats(Minecraft mc) { return mc.levelExtractor.sectionStatistics(); }
    public static String entityStats(Minecraft mc) { return mc.levelExtractor.entityStatistics(); }
//#else
    public static String sectionStats(Minecraft mc) { return mc.levelRenderer.getSectionStatistics(); }
    public static String entityStats(Minecraft mc) { return mc.levelRenderer.getEntityStatistics(); }
//#endif

    public static String gpuName() {
        try {
//#if >=26.2
            return com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().name();
//#else
//#if >=1.21.5
            return com.mojang.blaze3d.systems.RenderSystem.getDevice().getRenderer();
//#else
            return com.mojang.blaze3d.platform.GlUtil.getRenderer();
//#endif
//#endif
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Opaque-or-transparent entity texture drawn from both sides (cosmetic models). */
    public static net.minecraft.client.renderer.rendertype.RenderType cutoutNoCull(net.minecraft.resources.Identifier texture) {
//#if >=26.1
        return net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(texture);
//#else
        return net.minecraft.client.renderer.rendertype.RenderTypes.entityCutoutNoCull(texture);
//#endif
    }

    // ---- account (McSession) ----------------------------------------------------------------------------------------

    /** Item for a registry id; AIR if unknown. */
    public static net.minecraft.world.item.Item item(net.minecraft.resources.Identifier id) {
//#if >=1.21.2
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id);
//#else
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
//#endif
    }

//#if >=1.20 && <1.21.6
    /** The inventory screen's entity preview at (x, y), {@code scale} pixels per block (entity drawn live). */
    public static void renderEntityInInventory(net.minecraft.client.gui.GuiGraphics g, float x, float y, float scale, org.joml.Vector3f offset,
            org.joml.Quaternionf rotation, org.joml.Quaternionf camera, net.minecraft.world.entity.LivingEntity e) {
//#if >=1.20.5
        net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventory(g, x, y, scale, offset, rotation, camera, e);
//#elif >=1.20.2
        net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventory(g, x, y, Math.round(scale), offset, rotation, camera, e);
//#else
        net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventory(g, Math.round(x), Math.round(y + offset.y() * scale), Math.round(scale), rotation, camera, e);
//#endif
    }
//#endif

    /** Leave the world like the pause menu's Disconnect (autotest). */
    public static void leaveWorld(Minecraft mc) {
//#if >=1.21.6
        mc.level.disconnect(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
        mc.disconnectWithSavingScreen();
//#else
        if (mc.level != null) mc.level.disconnect();
        mc.disconnect();
//#endif
    }

    /** Save a screenshot named {@code name} into the game's screenshots folder (autotest). */
    public static void screenshot(Minecraft mc, String name, java.util.function.Consumer<net.minecraft.network.chat.Component> done) {
//#if >=26.2
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, done);
//#elif >=1.21.6
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), 1, done);
//#elif >=1.17
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), done);
//#else
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.getMainRenderTarget(), done);
//#endif
    }

    /** A texture for {@code img}; the label names it in GPU debuggers (1.21.5+). */
    public static net.minecraft.client.renderer.texture.DynamicTexture dynamicTexture(final String label, com.mojang.blaze3d.platform.NativeImage img) {
//#if >=1.21.5
        return new net.minecraft.client.renderer.texture.DynamicTexture(() -> label, img);
//#else
        return new net.minecraft.client.renderer.texture.DynamicTexture(img);
//#endif
    }

    public static int selectedSlot(net.minecraft.world.entity.player.Player p) {
//#if >=1.21.5
        return p.getInventory().getSelectedSlot();
//#else
        return p.getInventory().selected;
//#endif
    }

    public static void setSelectedSlot(net.minecraft.world.entity.player.Player p, int slot) {
//#if >=1.21.5
        p.getInventory().setSelectedSlot(slot);
//#else
        p.getInventory().selected = slot;
//#endif
    }

    /** Signed in without a Mojang session (offline / developer account). */
    public static boolean offlineSession(Minecraft mc) {
//#if >=1.21.9
        return mc.isOfflineDeveloperMode();
//#elif >=1.18
        return mc.getUser().getType() != net.minecraft.client.User.Type.MSA;
//#elif >=1.17
        return mc.getUser().getType() != net.minecraft.client.User.Type.MOJANG;
//#else
        return mc.getUser().getAccessToken().length() < 20; // a real session token is a long JWT; dev / offline ones are "0" or empty
//#endif
    }

    public static net.minecraft.client.User newUser(String name, java.util.UUID id, String token) {
//#if >=1.21.9
        return new net.minecraft.client.User(name, id, token, java.util.Optional.<String>empty(), java.util.Optional.<String>empty());
//#elif >=1.20.2
        return new net.minecraft.client.User(name, id, token, java.util.Optional.<String>empty(), java.util.Optional.<String>empty(), net.minecraft.client.User.Type.MSA);
//#elif >=1.18
        return new net.minecraft.client.User(name, id.toString().replace("-", ""), token, java.util.Optional.<String>empty(), java.util.Optional.<String>empty(), net.minecraft.client.User.Type.MSA);
//#else
        return new net.minecraft.client.User(name, id.toString().replace("-", ""), token, "mojang");
//#endif
    }

    /** Re-apply a sound category's volume after the option override (volume scale) changed. */
    public static void refreshVolume(Minecraft mc, net.minecraft.sounds.SoundSource source) {
//#if >=1.21.9
        mc.getSoundManager().refreshCategoryVolume(source);
//#else
        mc.getSoundManager().updateSourceVolume(source, mc.options.getSoundSourceVolume(source));
//#endif
    }

    /** Minecraft's lookup of the signed-in player's own profile (skin, cape), or null. */
//#if >=1.20.2
    public static java.util.concurrent.CompletableFuture<?> profileFuture(Minecraft mc) { return ((gg.kite.client.mixin.MinecraftSessionAccessor) mc).kite$profileFuture(); }
//#else
    // Before 1.20.2 Minecraft keeps the own profile's properties (skin textures) in a map filled on first use.
    public static java.util.concurrent.CompletableFuture<?> profileFuture(Minecraft mc) {
        return java.util.concurrent.CompletableFuture.completedFuture(((gg.kite.client.mixin.MinecraftSessionAccessor) mc).kite$profileProperties());
    }
//#endif

    /** The signed-in account's profile id (User.getProfileId from 1.19.1; a plain uuid string before). */
    public static java.util.UUID userId(net.minecraft.client.User user) {
//#if >=1.19.1
        return user.getProfileId();
//#else
        return com.mojang.util.UUIDTypeAdapter.fromString(user.getUuid());
//#endif
    }

    /** A vertex's colour as ARGB (VertexConsumer.color(int) arrived in 1.18). */
//#if <1.21
    public static com.mojang.blaze3d.vertex.VertexConsumer argb(com.mojang.blaze3d.vertex.VertexConsumer vc, int argb) {
//#if >=1.18
        return vc.color(argb);
//#else
        return vc.color(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24);
//#endif
    }
//#endif

    // Entity rotation and the world's lowest block: fields / constants before 1.17.
    public static float yRot(net.minecraft.world.entity.Entity e) { return e.getYRot(); }
    public static float xRot(net.minecraft.world.entity.Entity e) { return e.getXRot(); }
    public static void setRot(net.minecraft.world.entity.Entity e, float yRot, float xRot) {
//#if >=1.17
        e.setYRot(yRot);
        e.setXRot(xRot);
//#else
        e.yRot = yRot;
        e.xRot = xRot;
//#endif
    }

    public static int minY(net.minecraft.world.level.LevelReader level) {
//#if >=1.21.2
        return level.getMinY();
//#elif >=1.17
        return level.getMinBuildHeight();
//#else
        return 0;
//#endif
    }

    /** {@code c} in colour {@code rgb} (Component.withColor before 1.20.3 took a Style). */
    public static net.minecraft.network.chat.MutableComponent color(net.minecraft.network.chat.MutableComponent c, int rgb) {
//#if >=1.20.3
        return c.withColor(rgb);
//#else
        return c.withStyle(s -> s.withColor(net.minecraft.network.chat.TextColor.fromRgb(rgb)));
//#endif
    }

    /** Look the signed-in player's profile up again (with textures); completes with null when it fails. */
    public static java.util.concurrent.CompletableFuture<?> fetchProfile(Minecraft mc) {
//#if >=1.20.2
        final java.util.UUID id = mc.getUser().getProfileId();
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return mc.services().sessionService().fetchProfile(id, true);
            } catch (RuntimeException e) {
                return null;
            }
        }, net.minecraft.util.Util.nonCriticalIoPool());
//#else
        final com.mojang.authlib.GameProfile profile = mc.getUser().getGameProfile();
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return mc.getMinecraftSessionService().fillProfileProperties(profile, true).getProperties();
            } catch (RuntimeException e) {
                return null;
            }
        }, net.minecraft.Util.backgroundExecutor());
//#endif
    }

    public static void setProfile(Minecraft mc, Object profileResult) {
//#if >=26.3
        ((gg.kite.client.mixin.MinecraftSessionAccessor) mc).kite$setProfileFuture(java.util.concurrent.CompletableFuture.completedFuture((com.mojang.authlib.services.ProfileResult) profileResult));
//#elif >=1.20.2
        ((gg.kite.client.mixin.MinecraftSessionAccessor) mc).kite$setProfileFuture(java.util.concurrent.CompletableFuture.completedFuture((com.mojang.authlib.yggdrasil.ProfileResult) profileResult));
//#else
        com.mojang.authlib.properties.PropertyMap own = ((gg.kite.client.mixin.MinecraftSessionAccessor) mc).kite$profileProperties();
        own.clear();
        if (profileResult instanceof com.mojang.authlib.properties.PropertyMap) own.putAll((com.mojang.authlib.properties.PropertyMap) profileResult);
//#endif
    }

    /** True if a profile lookup result carries skin textures. */
    public static boolean hasTextures(Object profileResult) {
//#if >=26.3
        return profileResult instanceof com.mojang.authlib.services.ProfileResult && (((com.mojang.authlib.services.ProfileResult) profileResult).profile().properties().containsKey("textures"));
//#elif >=1.20.2
        return profileResult instanceof com.mojang.authlib.yggdrasil.ProfileResult && (((com.mojang.authlib.yggdrasil.ProfileResult) profileResult).profile().properties().containsKey("textures"));
//#else
        return profileResult instanceof com.mojang.authlib.properties.PropertyMap && ((com.mojang.authlib.properties.PropertyMap) profileResult).containsKey("textures");
//#endif
    }

    /**
     * Sign in as {@code user} without restarting: the account, its services (privileges, chat signing keys, reporting,
     * telemetry) and its profile lookup. Render thread, main menu only.
     */
    public static void switchUser(Minecraft mc, net.minecraft.client.User user, boolean online) {
        gg.kite.client.mixin.MinecraftSessionAccessor a = (gg.kite.client.mixin.MinecraftSessionAccessor) mc;
//#if >=26.3
        final com.mojang.authlib.minecraft.UserApiService api = online ? com.mojang.authlib.services.MinecraftServicesDiscoveryService.create(mc.getProxy(), true).createUserApiService(user.getAccessToken()) : com.mojang.authlib.minecraft.UserApiService.OFFLINE;
//#elif >=1.20.3
        final com.mojang.authlib.minecraft.UserApiService api = online ? new com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService(mc.getProxy()).createUserApiService(user.getAccessToken()) : com.mojang.authlib.minecraft.UserApiService.OFFLINE;
//#elif >=1.18
        // authlib before 6 throws when the token is refused; vanilla then falls back to the offline services too.
        com.mojang.authlib.minecraft.UserApiService created = com.mojang.authlib.minecraft.UserApiService.OFFLINE;
        if (online) {
            try {
                created = new com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService(mc.getProxy()).createUserApiService(user.getAccessToken());
            } catch (com.mojang.authlib.exceptions.AuthenticationException e) {
                org.apache.logging.log4j.LogManager.getLogger("kite").warn("Could not sign in to Minecraft services", e);
            }
        }
        final com.mojang.authlib.minecraft.UserApiService api = created;
//#endif
        a.kite$setUser(user);
//#if >=1.18
        a.kite$setUserApiService(api);
//#endif
//#if >=1.20.2
        a.kite$setProfileFuture(online ? fetchProfile(mc) : java.util.concurrent.CompletableFuture.completedFuture(null));
//#else
        a.kite$profileProperties().clear();
        if (online) fetchProfile(mc).thenAccept(p -> mc.execute(() -> setProfile(mc, p)));
//#endif
//#if >=1.20.3
        a.kite$setUserPropertiesFuture(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return api.fetchProperties();
            } catch (Exception e) {
                return com.mojang.authlib.minecraft.UserApiService.OFFLINE_PROPERTIES;
            }
        }, net.minecraft.util.Util.nonCriticalIoPool()));
//#endif
//#if >=1.19.3
        a.kite$setProfileKeyPairManager(net.minecraft.client.multiplayer.ProfileKeyPairManager.create(api, user, mc.gameDirectory.toPath()));
//#elif >=1.19
        a.kite$setProfileKeyPairManager(new net.minecraft.client.multiplayer.ProfileKeyPairManager(api, userId(user), mc.gameDirectory.toPath()));
//#endif
//#if >=1.19.1
        a.kite$setReportingContext(net.minecraft.client.multiplayer.chat.report.ReportingContext.create(net.minecraft.client.multiplayer.chat.report.ReportEnvironment.local(), api));
//#endif
//#if >=1.19.3
        net.minecraft.client.telemetry.ClientTelemetryManager old = a.kite$telemetryManager();
        a.kite$setTelemetryManager(new net.minecraft.client.telemetry.ClientTelemetryManager(mc, api, user));
        if (old != null) old.close();
//#endif
    }
}
