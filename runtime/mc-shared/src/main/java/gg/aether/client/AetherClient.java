package gg.aether.client;

import gg.aether.core.AetherPlugin;
import gg.aether.core.ConfigStore;
import gg.aether.core.Events;
import gg.aether.core.Mod;
import gg.aether.core.ModManager;
import gg.aether.core.Overrides;
import gg.aether.core.GameState;
import gg.aether.core.ui.ClientGui;
import gg.aether.mc.Compat;
import gg.aether.mc.McEvents;
import gg.aether.mc.McGameState;
import gg.aether.mods.BuiltinMods;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
//#if >=26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#elif >=1.20
import net.minecraft.client.gui.GuiGraphics;
//#else
import com.mojang.blaze3d.vertex.PoseStack;
//#endif
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point and glue: owns the mod manager, feeds it game ticks and input, and draws its HUD. Mixins call in here. */
public final class AetherClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("aether");

    /** Enabled on first launch (no config yet); everything else is one click away in the menu. */
    private static final Set<String> DEFAULT_ON = new HashSet<String>(Arrays.asList(
            "fps", "cps", "coords", "direction", "potions", "keystrokes", "armor", "zoom", "freelook", "durabilitywarn", "deathcoords"));

    private static final ModManager MM = new ModManager();
    private static final McGameState STATE = new McGameState();
    private static ConfigStore store;
    private static String version = "dev";

    private static float prevFov = 1f, curFov = 1f;
    private static CameraType savedCamera;
    private static float lastHealth = -1;
    private static Object lastPlayer;
    private static int ticks;

    public static ModManager mods() { return MM; }
    public static Overrides fx() { return MM.overrides(); }
    public static GameState state() { return STATE; }

    @Override public void onInitializeClient() {
        BuiltinMods.registerAll(MM);
        gg.aether.mc.McPayload.init();
        loadPlugins();
        MM.setDataDir(configDir().resolve("data"));
        MM.setViewOpener(AetherClient::openView);
        MM.setHost(new gg.aether.mc.McHost(FabricLoader.getInstance().getModContainer("aether").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev")));
        MM.cosmetics().load(configDir().resolve("cosmetics"));
        version = FabricLoader.getInstance().getModContainer("aether").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        store = new ConfigStore(configDir().resolve("config.json"));
        if (!store.load(MM)) {
            for (Mod m : MM.all()) MM.setEnabled(m.id, DEFAULT_ON.contains(m.id));
            save();
        }
        int on = 0;
        for (Mod m : MM.all()) if (m.enabled()) on++;
        LOG.info("Aether loaded {} mods ({} enabled), {} plugin(s). {} opens the menu.", MM.all().size(), on, MM.plugins().size(), MM.menuKeyName());
        for (String f : MM.failures()) LOG.warn("Aether: {}", f);
    }

    static Path configDir() { return FabricLoader.getInstance().getConfigDir().resolve("aether"); }

    /** Third-party mods: Fabric mods with an "aether" entrypoint, plus ServiceLoader providers on the classpath. */
    private static void loadPlugins() {
        try {
            MM.loadPlugins(FabricLoader.getInstance().getEntrypoints("aether", AetherPlugin.class));
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Aether: an \"aether\" entrypoint failed to load", e);
        }
        try {
            MM.loadPlugins(ServiceLoader.load(AetherPlugin.class, AetherClient.class.getClassLoader()));
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Aether: a ServiceLoader plugin failed to load", e);
        }
    }

    /** Copy a profile file to config/aether/exports and show it in the file manager. */
    static String exportProfile(gg.aether.core.Profiles.Profile p) {
        try {
            Path out = ConfigStore.exportProfile(MM, p, configDir().resolve("exports"));
            MM.host().revealFile(out);
            return "Exported to config/aether/exports/" + out.getFileName();
        } catch (IOException e) {
            LOG.warn("Aether export failed", e);
            return "Export failed: " + e.getMessage();
        }
    }

    static void openProfilesFolder() {
        try {
            Files.createDirectories(store.profilesDir());
        } catch (IOException ignored) { }
        MM.host().openFile(store.profilesDir());
    }

    public static void save() {
        if (store == null || DevAutotest.ACTIVE) return; // the autotest must not overwrite the player's layout
        try {
            store.save(MM);
        } catch (IOException e) {
            LOG.warn("Could not save Aether config", e);
        }
    }

    /** End of every client tick (20/s), in menus too. */
    public static void tick(Minecraft mc) {
        if (mc.player != null) {
            if (mc.player != lastPlayer) {
                lastPlayer = mc.player;
                lastHealth = mc.player.getHealth();
            }
            float hp = mc.player.getHealth();
            if (hp < lastHealth) MM.events().post(new Events.Hurt(lastHealth - hp, System.currentTimeMillis()));
            lastHealth = hp;
        } else {
            lastPlayer = null;
        }
        McEvents.tick(mc);
        gg.aether.mc.McSession.tick(mc);
        MM.tick(STATE);
        gg.aether.mc.McRender.afterTick(mc, fx());
        // Statistics / playtime / waypoints change constantly; persist them once a minute rather than only on exit.
        if (++ticks % 1200 == 0 && MM.dataDirty()) save();
        if (DevAutotest.ACTIVE) DevAutotest.tick(mc);
        if (mc.level == null && gg.aether.mc.McAudit.tick()) mc.stop();
        prevFov = curFov;
        curFov = fx().fovMultiplier;

        // Freelook's third-person switch: remember the player's own view and put it back afterwards.
        boolean third = fx().forceThirdPerson && mc.player != null;
        if (third && savedCamera == null) {
            savedCamera = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        } else if (!third && savedCamera != null) {
            mc.options.setCameraType(savedCamera);
            savedCamera = null;
        }
    }

    /** FOV multiplier interpolated between ticks so smooth zoom stays smooth at any frame rate. */
    public static float fovMultiplier(float partialTick) {
        return prevFov + (curFov - prevFov) * Math.max(0f, Math.min(1f, partialTick));
    }

    /** Draws the Aether HUD and toasts into the vanilla HUD pass. */
//#if >=26.1
    public static void renderHud(GuiGraphicsExtractor g) {
//#elif >=1.20
    public static void renderHud(GuiGraphics g) {
//#else
    public static void renderHud(PoseStack pose) {
        LegacyGuiGraphics g = new LegacyGuiGraphics(pose);
//#endif
        if (MM.editing()) return; // the HUD editor draws the widgets itself
        GuiRenderer r = new GuiRenderer(g);
        MM.renderHud(r, STATE);
        MM.renderNotifications(r, STATE);
    }

    /** Show a mod's full-screen view (world map...). */
    public static void openView(gg.aether.core.ui.View view) {
        final Minecraft mc = Minecraft.getInstance();
        final AetherScreen screen = new AetherScreen(STATE);
//#if >=26.2
        view.setCloser(() -> { if (mc.gui.screen() == screen) mc.gui.setScreen(null); });
//#else
        view.setCloser(() -> { if (mc.screen == screen) mc.setScreen(null); });
//#endif
        screen.setGui(view);
//#if >=26.2
        mc.gui.setScreen(screen);
//#else
        mc.setScreen(screen);
//#endif
    }

    /** 3D overlays, called while the frame's gizmos are collected. */
    public static void renderWorld(gg.aether.mc.McWorldRenderer r) {
        MM.renderWorld(r, STATE);
    }

    public static ClientGui openMenu(boolean hudEditor) {
        final Minecraft mc = Minecraft.getInstance();
        final AetherScreen screen = new AetherScreen(STATE);
        ClientGui gui = new ClientGui(MM, menuHost(screen, () -> Compat.setScreen(mc, null)));
        screen.setGui(hudEditor ? gui.openHudEditor() : gui);
        Compat.setScreen(mc, screen);
        return gui;
    }

    /** What the Aether menu needs from the game; {@code onClose} runs when it closes itself. */
    private static ClientGui.Host menuHost(final AetherScreen screen, final Runnable onClose) {
        final Minecraft mc = Minecraft.getInstance();
        return new ClientGui.Host() {
            @Override public void close() { if (Compat.screen(mc) == screen) onClose.run(); }
            @Override public void save() { AetherClient.save(); }
            @Override public String exportProfile(gg.aether.core.Profiles.Profile p) { return AetherClient.exportProfile(p); }
            @Override public int rescanProfiles() { return store == null ? 0 : store.rescan(MM); }
            @Override public void openProfilesFolder() { AetherClient.openProfilesFolder(); }
        };
    }

    // ---- main menu --------------------------------------------------------------------------------------------------

    /**
     * Called by the setScreen mixin: shows the Aether main menu instead of vanilla's title screen (also when the game
     * falls back to the title screen with no world). Returns true if it replaced the screen.
     */
    public static boolean replaceTitleScreen(net.minecraft.client.gui.screens.Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        boolean title = screen instanceof net.minecraft.client.gui.screens.TitleScreen || (screen == null && mc.level == null);
        if (!title || !MM.client().customMainMenu.on()) return false;
        Compat.setScreen(mc, mainMenu());
        return true;
    }

    public static net.minecraft.client.gui.screens.Screen mainMenu() {
        final Minecraft mc = Minecraft.getInstance();
        final AetherScreen screen = new AetherScreen(STATE);
        screen.setGui(new gg.aether.core.ui.MainMenuView(MM, new gg.aether.mc.McMenuHost(screen, version) {
            @Override public void clientSettings() { openFromMainMenu("settings"); }
            @Override public void wardrobe() { openFromMainMenu("wardrobe"); }
            @Override public void store() { openFromMainMenu("shop"); }
            @Override public void useVanillaMenu() {
                MM.client().customMainMenu.set(false);
                save();
                Compat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen());
            }
        }, gg.aether.core.ui.MenuFeed.preview()));
        return screen;
    }

    /** The Aether menu over the main menu (settings, wardrobe); closing it returns to the main menu. */
    private static void openFromMainMenu(String page) {
        final Minecraft mc = Minecraft.getInstance();
        final AetherScreen screen = new AetherScreen(STATE);
        screen.setGui(new ClientGui(MM, menuHost(screen, () -> Compat.setScreen(mc, mainMenu()))).fromMainMenu().openPage(page));
        Compat.setScreen(mc, screen);
    }

    /** Vanilla title screen's "Aether menu" button: turn the custom menu back on. */
    public static void useAetherMenu() {
        MM.client().customMainMenu.set(true);
        save();
        Compat.setScreen(Minecraft.getInstance(), mainMenu());
    }

    /** Raw GLFW key event. Returns true if Aether consumed it. */
    public static boolean key(Minecraft mc, int code, int action) {
        if (action != 0 && action != 1) return false; // auto-repeat (2 under GLFW, -1 under SDL)
        boolean down = action == 1;
//#if >=26.2
        if (mc.gui.screen() != null) {
//#else
        if (mc.screen != null) {
//#endif
            // Typing in chat or a menu must not trigger hotkeys, but releases still end held ones (zoom).
            if (!down) MM.key(code, false);
            return false;
        }
        if (down && MM.opensMenu(code)) {
            openMenu(false).openedByMenuKey();
            return true;
        }
        MM.key(code, down);
        return false;
    }
}
