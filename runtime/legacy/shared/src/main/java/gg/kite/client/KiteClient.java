package gg.kite.client;

import gg.kite.core.KitePlugin;
import gg.kite.core.ConfigStore;
import gg.kite.core.Events;
import gg.kite.core.GameState;
import gg.kite.core.Mod;
import gg.kite.core.ModManager;
import gg.kite.core.Overrides;
import gg.kite.core.ui.ClientGui;
import gg.kite.mc.Log;
import gg.kite.mc.McAudit;
import gg.kite.mc.McEvents;
import gg.kite.mc.McGameState;
import gg.kite.mods.BuiltinMods;
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
public final class KiteClient implements ClientModInitializer {
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
        version = FabricLoader.getInstance().getModContainer("kite").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        MM.setDataDir(configDir().resolve("data"));
        MM.setViewOpener(KiteClient::openView);
        MM.setHost(new gg.kite.mc.McHost(version));
        MM.cosmetics().load(configDir().resolve("cosmetics"));
        store = new ConfigStore(configDir().resolve("config.json"));
        if (!store.load(MM)) {
            for (Mod m : MM.all()) MM.setEnabled(m.id, DEFAULT_ON.contains(m.id));
            save();
        }
        int on = 0;
        for (Mod m : MM.all()) if (m.enabled()) on++;
        Log.info("Kite loaded " + MM.all().size() + " mods (" + on + " enabled), " + MM.plugins().size() + " plugin(s). "
                + MM.menuKeyName() + " opens the menu.");
        for (String f : MM.failures()) Log.warn("Kite: " + f);
    }

    static Path configDir() { return FabricLoader.getInstance().getConfigDir().resolve("kite"); }

    private static void loadPlugins() {
        try {
            MM.loadPlugins(FabricLoader.getInstance().getEntrypoints("kite", KitePlugin.class));
        } catch (RuntimeException | LinkageError e) {
            Log.warn("Kite: an \"kite\" entrypoint failed to load", e);
        }
        try {
            MM.loadPlugins(ServiceLoader.load(KitePlugin.class, KiteClient.class.getClassLoader()));
        } catch (RuntimeException | LinkageError e) {
            Log.warn("Kite: a ServiceLoader plugin failed to load", e);
        }
    }

    static String exportProfile(gg.kite.core.Profiles.Profile p) {
        try {
            Path out = ConfigStore.exportProfile(MM, p, configDir().resolve("exports"));
            MM.host().revealFile(out);
            return "Exported to config/kite/exports/" + out.getFileName();
        } catch (IOException e) {
            Log.warn("Kite export failed", e);
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
            Log.warn("Could not save Kite config", e);
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
        gg.kite.mc.LegacyRender.afterTick(mc, fx);
        if (fx.selectSlot != null && mc.player != null && fx.selectSlot >= 0 && fx.selectSlot <= 8) mc.player.inventory.selectedSlot = fx.selectSlot;
        if (mc.world == null && mc.currentScreen != null && McAudit.tick()) mc.scheduleStop();
    }

    public static float fovMultiplier(float partialTick) {
        return prevFov + (curFov - prevFov) * Math.max(0f, Math.min(1f, partialTick));
    }

    /** Draws the Kite HUD and toasts into the vanilla HUD pass. */
    public static void renderHud() {
        if (MM.editing()) return;
        GuiRenderer r = new GuiRenderer();
        MM.renderHud(r, STATE);
        MM.renderNotifications(r, STATE);
    }

    public static void openView(gg.kite.core.ui.View view) {
        final MinecraftClient mc = MinecraftClient.getInstance();
        final KiteScreen screen = new KiteScreen(STATE);
        view.setCloser(() -> { if (mc.currentScreen == screen) mc.setScreen(null); });
        screen.setGui(view);
        mc.setScreen(screen);
    }

    public static ClientGui openMenu(boolean hudEditor) {
        final MinecraftClient mc = MinecraftClient.getInstance();
        final KiteScreen screen = new KiteScreen(STATE);
        ClientGui gui = new ClientGui(MM, menuHost(screen, () -> mc.setScreen(null)));
        screen.setGui(hudEditor ? gui.openHudEditor() : gui);
        mc.setScreen(screen);
        return gui;
    }

    private static ClientGui.Host menuHost(final KiteScreen screen, final Runnable onClose) {
        final MinecraftClient mc = MinecraftClient.getInstance();
        return new ClientGui.Host() {
            @Override public void close() { if (mc.currentScreen == screen) onClose.run(); }
            @Override public void save() { KiteClient.save(); }
            @Override public String exportProfile(gg.kite.core.Profiles.Profile p) { return KiteClient.exportProfile(p); }
            @Override public int rescanProfiles() { return store == null ? 0 : store.rescan(MM); }
            @Override public void openProfilesFolder() { KiteClient.openProfilesFolder(); }
        };
    }

    // ---- main menu --------------------------------------------------------------------------------------------------

    /** setScreen hook: show the Kite main menu instead of vanilla's title screen. True if it replaced the screen. */
    public static boolean replaceTitleScreen(Screen screen) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean title = screen instanceof TitleScreen || (screen == null && mc.world == null);
        if (!title || !MM.client().customMainMenu.on()) return false;
        mc.setScreen(mainMenu());
        return true;
    }

    public static Screen mainMenu() {
        final MinecraftClient mc = MinecraftClient.getInstance();
        final KiteScreen screen = new KiteScreen(STATE);
        screen.setGui(new gg.kite.core.ui.MainMenuView(MM, new gg.kite.mc.McMenuHost(screen, version) {
            @Override public void clientSettings() { openFromMainMenu("settings"); }
            @Override public void wardrobe() { openFromMainMenu("wardrobe"); }
            @Override public void store() { openFromMainMenu("shop"); }
            @Override public void useVanillaMenu() {
                MM.client().customMainMenu.set(false);
                save();
                mc.setScreen(new TitleScreen());
            }
        }, gg.kite.core.ui.MenuFeed.preview()));
        return screen;
    }

    private static void openFromMainMenu(String page) {
        final MinecraftClient mc = MinecraftClient.getInstance();
        final KiteScreen screen = new KiteScreen(STATE);
        screen.setGui(new ClientGui(MM, menuHost(screen, () -> mc.setScreen(mainMenu()))).fromMainMenu().openPage(page));
        mc.setScreen(screen);
    }

    /** Vanilla title screen's "Kite menu" button. */
    public static void useKiteMenu() {
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
