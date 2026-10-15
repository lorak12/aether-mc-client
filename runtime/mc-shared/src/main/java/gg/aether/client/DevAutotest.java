package gg.aether.client;

import gg.aether.core.Events;
import gg.aether.core.Keys;
import gg.aether.core.Mod;
import gg.aether.core.ModManager;
import gg.aether.core.Setting;
import gg.aether.core.ui.ClientGui;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
//#if >=26.1
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
//#endif

/**
 * In-game smoke test, only active with {@code -Daether.autotest=true} (Gradle task {@code runAutotest}). Once a world
 * is loaded it walks the client through the HUD, the Right Shift hub and mod window (both layouts, a settings page,
 * client settings, waypoints, wardrobe), the HUD editor, the main menu, zoom, time/weather,
 * fullbright and freelook, saving a screenshot of each to {@code run/screenshots/aether-*.png}, then quits.
 * Config is never saved while it runs.
 */
final class DevAutotest {
    static final boolean ACTIVE = Boolean.getBoolean("aether.autotest");

    private interface Step { void run(Minecraft mc, ModManager mm); }

    private static final List<Object[]> STEPS = new ArrayList<Object[]>();
    private static int index, wait = -1;
    private static ClientGui gui;
//#if >=26.1
    /** Real-input checks: raw SDL events go through Minecraft's own handlers, like a physical keyboard/mouse. */
    private static final List<Integer> clicks = new ArrayList<Integer>();
    private static final List<String> inputFailures = new ArrayList<String>();
//#endif

    private static void step(int ticksAfter, Step s) { STEPS.add(new Object[] { ticksAfter, s }); }

    private static void shot(Minecraft mc, String name) {
        gg.aether.mc.Compat.screenshot(mc, "aether-" + name + ".png", msg -> AetherClient.LOG.info("[autotest] {}", msg.getString()));
    }

    private static void set(ModManager mm, String mod, String key, Object json) {
        for (Setting<?> s : mm.get(mod).settings()) if (s.key.equals(key)) s.fromJson(json);
    }

    static {
        step(100, (mc, mm) -> {
            for (Mod m : mm.all()) mm.setEnabled(m.id, !m.id.equals("streamer") && !m.id.equals("fullbright"));
            set(mm, "togglesprint", "always", false);
            mm.notifications().info(System.currentTimeMillis(), "Aether autotest running");
            mm.events().post(new Events.ActionBar("Action bar restyled by Aether", System.currentTimeMillis()));
        });
        step(40, (mc, mm) -> {
            shot(mc, "01-hud");
//#if >=26.1
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Aether probe chat line"));
//#else
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("Aether probe chat line"), false);
//#endif
            for (String l : gg.aether.mc.StateProbe.dump(AetherClient.state())) AetherClient.LOG.info("[autotest] state {}", l);
//#if >=26.3
        });
        step(5, (mc, mm) -> {
            mm.events().on(Events.Click.class, DevAutotest.class, e -> clicks.add(e.button));
            long w = mc.getWindow().handle();
            int[] sdlButtons = { 1, 1, 1, 3, 3 }; // left x3, right x2
            for (int b : sdlButtons) {
                mc.mouseHandler.onButton(w, new MouseButtonInfo(b, 0), 1);
                mc.mouseHandler.onButton(w, new MouseButtonInfo(b, 0), 0);
            }
        });
        // Mouse buttons may be queued until the next frame, so check them (and only then press keys) a few ticks later.
        step(5, (mc, mm) -> {
            if (!clicks.equals(java.util.Arrays.asList(0, 0, 0, 1, 1))) inputFailures.add("clicks " + clicks + " != [0, 0, 0, 1, 1]");
            mm.events().unregister(DevAutotest.class);
            long w = mc.getWindow().handle();
            mc.keyboardHandler.keyPress(w, 1, new KeyEvent(229, 0x400000E5, 0)); // SDL right shift
            mc.keyboardHandler.keyPress(w, -1, new KeyEvent(229, 0x400000E5, 0)); // auto-repeat must be ignored
            mc.keyboardHandler.keyPress(w, 0, new KeyEvent(229, 0x400000E5, 0));
        });
        step(5, (mc, mm) -> {
            if (!(mc.gui.screen() instanceof AetherScreen)) inputFailures.add("Right Shift did not open the menu (screen=" + mc.gui.screen() + ")");
            mc.gui.setScreen(null);
//#endif
        });
        step(10, (mc, mm) -> gui = AetherClient.openMenu(false));
        step(10, (mc, mm) -> shot(mc, "02-hub"));
        step(10, (mc, mm) -> gui.openPage("mods"));
        step(10, (mc, mm) -> shot(mc, "02b-mods-list"));
        step(10, (mc, mm) -> mm.client().layout.set(gg.aether.core.ClientSettings.ModLayout.CARDS));
        step(10, (mc, mm) -> {
            shot(mc, "02c-mods-cards");
            mm.client().layout.set(gg.aether.core.ClientSettings.ModLayout.LIST);
        });
        step(10, (mc, mm) -> gui.openSettings("crosshair"));
        step(10, (mc, mm) -> shot(mc, "03-settings"));
        step(10, (mc, mm) -> gui.openPage("settings"));
        step(10, (mc, mm) -> shot(mc, "03b-client-settings"));
        step(10, (mc, mm) -> gui.openPage("waypoints"));
        step(10, (mc, mm) -> shot(mc, "03e-waypoints"));
        step(10, (mc, mm) -> gui.openPage("wardrobe"));
        step(10, (mc, mm) -> shot(mc, "03f-wardrobe"));
        step(10, (mc, mm) -> gui.openPage("keybinds"));
        step(10, (mc, mm) -> shot(mc, "03c-keybinds"));
        step(10, (mc, mm) -> gui.openPage("developer"));
        step(10, (mc, mm) -> shot(mc, "03d-developer"));
        step(10, (mc, mm) -> gui.openHudEditor());
        step(10, (mc, mm) -> shot(mc, "04-hud-editor"));
        step(10, (mc, mm) -> gg.aether.mc.Compat.setScreen(mc, AetherClient.mainMenu()));
        step(20, (mc, mm) -> shot(mc, "04b-main-menu"));
        step(10, (mc, mm) -> {
//#if >=26.2
            mc.gui.setScreen(null);
//#else
            mc.setScreen(null);
//#endif
            mm.key(Keys.C, true);
        });
        step(30, (mc, mm) -> shot(mc, "05-zoom"));
        step(10, (mc, mm) -> {
            mm.key(Keys.C, false);
            set(mm, "time", "preset", "MIDNIGHT");
            set(mm, "weather", "weather", "RAIN");
        });
        step(60, (mc, mm) -> shot(mc, "06-midnight-rain"));
        step(10, (mc, mm) -> mm.setEnabled("fullbright", true));
        step(40, (mc, mm) -> shot(mc, "07-fullbright"));
        step(10, (mc, mm) -> {
            mm.setEnabled("fullbright", false);
            set(mm, "time", "preset", "DAY");
            set(mm, "weather", "weather", "CLEAR");
            mm.key(Keys.V, true);
            mm.events().post(new Events.MouseMove(900, -150));
        });
        step(30, (mc, mm) -> shot(mc, "08-freelook"));
        step(10, (mc, mm) -> {
            mm.key(Keys.V, false);
        });
        step(10, (mc, mm) -> {
            mm.key(Keys.M, true); // world map
            mm.key(Keys.M, false);
        });
        step(30, (mc, mm) -> shot(mc, "10-worldmap"));
        step(20, (mc, mm) -> {
//#if >=26.1
            AetherClient.LOG.info("[autotest] input checks: {}", inputFailures.isEmpty() ? "PASS" : "FAIL " + inputFailures);
//#endif
            AetherClient.LOG.info("[autotest] mod failures: {}", mm.failures());
            AetherClient.LOG.info("[autotest] event errors: {}", mm.events().errors());
            AetherClient.LOG.info("[autotest] events posted: {}", mm.events().counts());
            // Leave the world: the title screen must come up as the Aether main menu, over the panorama.
            titleTicks = 0;
            // Like the pause menu's Disconnect: close the connection first, then wait for the server to save.
            mc.execute(() -> {
                gg.aether.mc.Compat.leaveWorld(mc);
                gg.aether.mc.Compat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen());
            });
        });
    }

    /** Ticks since leaving the world at the end; -1 while the in-world steps run. */
    private static int titleTicks = -1;
//#if >=1.19.3 && <1.20
    private static boolean startedLegacyWorld;
    private static int legacyReadyTicks;

    /** Quick Play did not exist before 1.20; enter a throwaway world through the game's normal flow. */
    private static void startLegacyWorld(Minecraft mc) {
        if (startedLegacyWorld || mc.level != null || mc.screen == null || mc.getOverlay() != null) return;
        if (++legacyReadyTicks < 40) return;
        startedLegacyWorld = true;
//#if ==1.19.3
        String name = "AetherSmoke1193";
//#else
        String name = "AetherSmoke1194";
//#endif
        java.io.File dir = new java.io.File(mc.gameDirectory, "saves/" + name);
        if (dir.isDirectory()) {
            mc.createWorldOpenFlows().loadLevel(mc.screen, name);
        } else {
            net.minecraft.world.level.LevelSettings settings = new net.minecraft.world.level.LevelSettings(name,
                    net.minecraft.world.level.GameType.CREATIVE, false, net.minecraft.world.Difficulty.PEACEFUL, true,
                    new net.minecraft.world.level.GameRules(), net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, settings,
                    net.minecraft.world.level.levelgen.WorldOptions.defaultWithRandomSeed(),
                    net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions);
        }
    }
//#endif

    static void tick(Minecraft mc) {
        if (titleTicks >= 0) {
            titleTicks++;
            if (titleTicks == 80) {
                shot(mc, "11-title-screen");
                AetherClient.LOG.info("[autotest] title screen: {}", gg.aether.mc.Compat.screen(mc) instanceof AetherScreen ? "Aether main menu" : String.valueOf(gg.aether.mc.Compat.screen(mc)));
            }
            if (titleTicks == 90) {
                AetherClient.LOG.info("[autotest] done");
                mc.stop();
            }
            return;
        }
//#if >=1.19.3 && <1.20
        startLegacyWorld(mc);
//#endif
        if (index >= STEPS.size() || mc.player == null || mc.level == null) return;
        if (wait < 0) wait = (Integer) STEPS.get(index)[0];
        if (wait-- > 0) return;
        try {
            ((Step) STEPS.get(index)[1]).run(mc, AetherClient.mods());
        } catch (RuntimeException e) {
            AetherClient.LOG.error("[autotest] step {} failed", index, e);
        }
        index++;
        wait = -1;
    }

    private DevAutotest() {}
}
