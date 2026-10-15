package gg.aether.client;

import gg.aether.core.AetherPlugin;
import gg.aether.core.ConfigStore;
import gg.aether.core.Events;
import gg.aether.core.GameState;
import gg.aether.core.Mod;
import gg.aether.core.ModManager;
import gg.aether.core.Overrides;
import gg.aether.core.ui.ClientGui;
import gg.aether.mc.Log;
import gg.aether.mc.McAudit;
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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;

/**
 * Entry point and glue for the legacy adapters (1.7.10 - 1.12.2): owns the mod manager, feeds it game ticks and input,
 * and draws its HUD. Mixins call in here.
 */
public final class AetherClient implements ClientModInitializer {
    /** Enabled on first launch (no config yet); everything else is one click away in the menu. */
    private static final Set<String> DEFAULT_ON = new HashSet<String>(Arrays.asList(
            "fps", "cps", "coords", "direction", "potions", "keystrokes", "armor", "zoom", "freelook", "durabilitywarn", "deathcoords"));

    private static final ModManager MM = new ModManager();
    private static final McGameState STATE = new McGameState();
    private static ConfigStore store;
    private static String version = "dev";

    private static float prevFov = 1f, curFov = 1f;
    private static int savedPerspective = -1;
    private static float lastHealth = -1;
    private static Object lastPlayer;
    private static int ticks;

    public static ModManager mods() { return MM; }
    public static Overrides fx() { return MM.overrides(); }
    public static GameState state() { return STATE; }

    @Override public void onInitializeClient() {
        BuiltinMods.registerAll(MM);
        McEvents.init(MM);
        loadPlugins();
        version = FabricLoader.getInstance().getModContainer("aether").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        MM.setDataDir(configDir().resolve("data"));
        MM.setViewOpener(AetherClient::openView);
        MM.setHost(new gg.aether.mc.McHost(version));
        MM.cosmetics().load(configDir().resolve("cosmetics"));
        store = new ConfigStore(configDir().resolve("config.json"));
        if (!store.load(MM)) {
            for (Mod m : MM.all()) MM.setEnabled(m.id, DEFAULT_ON.contains(m.id));
            save();
        }
        int on = 0;
        for (Mod m : MM.all()) if (m.enabled()) on++;
        Log.info("Aether loaded " + MM.all().size() + " mods (" + on + " enabled), " + MM.plugins().size() + " plugin(s). "
                + MM.menuKeyName() + " opens the menu.");
        for (String f : MM.failures()) Log.warn("Aether: " + f);
    }

    static Path configDir() { return FabricLoader.getInstance().getConfigDir().resolve("aether"); }

    private static void loadPlugins() {
        try {
            MM.loadPlugins(FabricLoader.getInstance().getEntrypoints("aether", AetherPlugin.class));
        } catch (RuntimeException | LinkageError e) {
            Log.warn("Aether: an \"aether\" entrypoint failed to load", e);
        }
        try {
            MM.loadPlugins(ServiceLoader.load(AetherPlugin.class, AetherClient.class.getClassLoader()));
        } catch (RuntimeException | LinkageError e) {
            Log.warn("Aether: a ServiceLoader plugin failed to load", e);
        }
    }

    static String exportProfile(gg.aether.core.Profiles.Profile p) {
        try {
            Path out = ConfigStore.exportProfile(MM, p, configDir().resolve("exports"));
            MM.host().revealFile(out);
            return "Exported to config/aether/exports/" + out.getFileName();
        } catch (IOException e) {
            Log.warn("Aether export failed", e);
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
        if (store == null) return;
        try {
            store.save(MM);
        } catch (IOException e) {
            Log.warn("Could not save Aether config", e);
        }
    }

    /** End of every client tick (20/s), in menus too. */
    public static void tick(MinecraftClient mc) {
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
        McEvents.tick(mc, STATE.serverAddress());
        MM.tick(STATE);
        if (++ticks % 1200 == 0 && MM.dataDirty()) save();
        prevFov = curFov;
        curFov = fx().fovMultiplier;

        Overrides fx = fx();
        // Freelook's third-person switch: remember the player's own view and put it back afterwards.
        boolean third = fx.forceThirdPerson && mc.player != null;
        if (third && savedPerspective < 0) {
            savedPerspective = mc.options.perspective;
            mc.options.perspective = 1;
        } else if (!third && savedPerspective >= 0) {
            mc.options.perspective = savedPerspective;
            savedPerspective = -1;
        }
        // Vanilla option overrides (including Fullbright's gamma) go into the option fields; LegacyRender puts them back.
        gg.aether.mc.LegacyRender.afterTick(mc, fx);
        if (fx.selectSlot != null && mc.player != null && fx.selectSlot >= 0 && fx.selectSlot <= 8) mc.player.inventory.selectedSlot = fx.selectSlot;
        if (mc.world == null && mc.currentScreen != null && McAudit.tick()) mc.scheduleStop();
    }

    public static float fovMultiplier(float partialTick) {
        return prevFov + (curFov - prevFov) * Math.max(0f, Math.min(1f, partialTick));
    }

    /** Draws the Aether HUD and toasts into the vanilla HUD pass. */
    public static void renderHud() {
        if (MM.editing()) return;
        GuiRenderer r = new GuiRenderer();
        MM.renderHud(r, STATE);
        MM.renderNotifications(r, STATE);
    }

    public static void openView(gg.aether.core.ui.View view) {
        final MinecraftClient mc = MinecraftClient.getInstance();
        final AetherScreen screen = new AetherScreen(STATE);
        view.setCloser(() -> { if (mc.currentScreen == screen) mc.setScreen(null); });
        screen.setGui(view);
        mc.setScreen(screen);
    }

    public static ClientGui openMenu(boolean hudEditor) {
        final MinecraftClient mc = MinecraftClient.getInstance();
        final AetherScreen screen = new AetherScreen(STATE);
        ClientGui gui = new ClientGui(MM, menuHost(screen, () -> mc.setScreen(null)));
        screen.setGui(hudEditor ? gui.openHudEditor() : gui);
        mc.setScreen(screen);
        return gui;
    }

    private static ClientGui.Host menuHost(final AetherScreen screen, final Runnable onClose) {
        final MinecraftClient mc = MinecraftClient.getInstance();
        return new ClientGui.Host() {
            @Override public void close() { if (mc.currentScreen == screen) onClose.run(); }
            @Override public void save() { AetherClient.save(); }
            @Override public String exportProfile(gg.aether.core.Profiles.Profile p) { return AetherClient.exportProfile(p); }
            @Override public int rescanProfiles() { return store == null ? 0 : store.rescan(MM); }
            @Override public void openProfilesFolder() { AetherClient.openProfilesFolder(); }
        };
    }

    // ---- main menu --------------------------------------------------------------------------------------------------

    /** setScreen hook: show the Aether main menu instead of vanilla's title screen. True if it replaced the screen. */
    public static boolean replaceTitleScreen(Screen screen) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean title = screen instanceof TitleScreen || (screen == null && mc.world == null);
        if (!title || !MM.client().customMainMenu.on()) return false;
        mc.setScreen(mainMenu());
        return true;
    }

    public static Screen mainMenu() {
        final MinecraftClient mc = MinecraftClient.getInstance();
        final AetherScreen screen = new AetherScreen(STATE);
        screen.setGui(new gg.aether.core.ui.MainMenuView(MM, new gg.aether.mc.McMenuHost(screen, version) {
            @Override public void clientSettings() { openFromMainMenu("settings"); }
            @Override public void wardrobe() { openFromMainMenu("wardrobe"); }
            @Override public void store() { openFromMainMenu("shop"); }
            @Override public void useVanillaMenu() {
                MM.client().customMainMenu.set(false);
                save();
                mc.setScreen(new TitleScreen());
            }
        }, gg.aether.core.ui.MenuFeed.preview()));
        return screen;
    }

    private static void openFromMainMenu(String page) {
        final MinecraftClient mc = MinecraftClient.getInstance();
        final AetherScreen screen = new AetherScreen(STATE);
        screen.setGui(new ClientGui(MM, menuHost(screen, () -> mc.setScreen(mainMenu()))).fromMainMenu().openPage(page));
        mc.setScreen(screen);
    }

    /** Vanilla title screen's "Aether menu" button. */
    public static void useAetherMenu() {
        MM.client().customMainMenu.set(true);
        save();
        MinecraftClient.getInstance().setScreen(mainMenu());
    }

    /** An LWJGL 2 key event (MinecraftClient.handleKeyInput). */
    public static void key(MinecraftClient mc, int lwjglKey, boolean down) {
        int code = LegacyKeys.toGlfw(lwjglKey);
        if (code < 0) return;
        if (mc.currentScreen != null) {
            // Typing in chat or a menu must not trigger hotkeys, but releases still end held ones (zoom).
            if (!down) MM.key(code, false);
            return;
        }
        if (down && MM.opensMenu(code)) {
            openMenu(false).openedByMenuKey();
            return;
        }
        MM.key(code, down);
    }
}
