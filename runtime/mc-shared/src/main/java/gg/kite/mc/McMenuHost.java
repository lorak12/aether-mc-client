package gg.kite.mc;

import gg.kite.core.Json;
import gg.kite.core.ui.MainMenuView;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The main menu's actions on Minecraft: vanilla screens (returning to {@code parent}), Mod Menu when installed,
 * quitting, and accounts. The launcher passes KITE_ACCOUNTS_FILE (its accounts.json: names and uuids, no tokens)
 * and KITE_RELAUNCH_FILE (where a switch request goes; the launcher relaunches as that account after exit).
 */
public abstract class McMenuHost implements MainMenuView.Host {
    private final Screen parent;
    private final String clientVersion;

    protected McMenuHost(Screen parent, String clientVersion) {
        this.parent = parent;
        this.clientVersion = clientVersion;
    }

    private static Minecraft mc() { return Minecraft.getInstance(); }

    @Override public void singleplayer() { Compat.setScreen(mc(), new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(parent)); }

    @Override public void multiplayer() { Compat.setScreen(mc(), new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(parent)); }

//#if >=26.1 && <26.3
    // 26.1-26.2 take an "opened in a world" flag (the pause screen passes true, the title screen false).
    @Override public void minecraftOptions() { Compat.setScreen(mc(), new net.minecraft.client.gui.screens.options.OptionsScreen(parent, mc().options, mc().level != null)); }
//#else
    @Override public void minecraftOptions() { Compat.setScreen(mc(), new net.minecraft.client.gui.screens.options.OptionsScreen(parent, mc().options)); }
//#endif

    @Override public void realms() { Compat.setScreen(mc(), new com.mojang.realmsclient.RealmsMainScreen(parent)); }

    @Override public boolean hasModMenu() { return FabricLoader.getInstance().isModLoaded("modmenu"); }

    /** Mod Menu's list screen, found by reflection so Kite doesn't depend on it. */
    @Override public void modMenu() {
        try {
            Class<?> c = Class.forName("com.terraformersmc.modmenu.gui.ModsScreen");
            Compat.setScreen(mc(), (Screen) c.getConstructor(Screen.class).newInstance(parent));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("kite").warn("Could not open Mod Menu", e);
        }
    }

    @Override public void quit() { mc().stop(); }

    @Override public String versionLine() { return McUi.versionLine(clientVersion); }

    @Override public List<MainMenuView.Account> accounts() { return readAccounts(env("KITE_ACCOUNTS_FILE"), currentUuid(), currentName()); }

    /** Result of a live switch, for {@link #pollNotice}. Render thread. */
    private MainMenuView.Notice notice;

    @Override public boolean switchesLive() { return McSession.liveSwitch(); }

    @Override public MainMenuView.Notice pollNotice() {
        MainMenuView.Notice n = notice;
        notice = null;
        return n;
    }

    @Override public String switchAccount(final MainMenuView.Account a) {
        if (McSession.liveSwitch()) {
            McSession.switchTo(a.uuid, err -> notice = err == null ? new MainMenuView.Notice("Signed in as " + a.name, true) : new MainMenuView.Notice(err, false));
            return "Signing in as " + a.name + "...";
        }
        // Older launchers: quit, and the launcher relaunches as that account.
        Path relaunch = env("KITE_RELAUNCH_FILE");
        if (relaunch == null) return "Start the game from the Kite launcher to switch accounts";
        try {
            Map<String, Object> req = new java.util.LinkedHashMap<String, Object>();
            req.put("uuid", a.uuid);
            if (relaunch.getParent() != null) Files.createDirectories(relaunch.getParent());
            Files.write(relaunch, Json.write(req).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "Could not ask the launcher to switch: " + e.getMessage();
        }
        mc().stop();
        return "Restarting as " + a.name + "...";
    }

    private static Path env(String name) {
        String v = System.getenv(name);
        return v == null || v.trim().isEmpty() ? null : Paths.get(v.trim());
    }

    private static String currentUuid() {
        java.util.UUID id = mc().getUser() == null ? null : Compat.userId(mc().getUser());
        return id == null ? "" : id.toString();
    }

    private static String currentName() { return mc().getUser() == null ? "Player" : mc().getUser().getName(); }

    /** Accounts from the launcher's registry; the signed-in one first and marked active (added if missing). */
    static List<MainMenuView.Account> readAccounts(Path file, String currentUuid, String currentName) {
        List<MainMenuView.Account> out = new ArrayList<MainMenuView.Account>();
        String cur = normalize(currentUuid);
        boolean sawCurrent = false;
        if (file != null && Files.isRegularFile(file)) {
            try {
                Object root = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                if (root instanceof List) {
                    for (Object o : (List<?>) root) {
                        if (!(o instanceof Map)) continue;
                        Map<?, ?> m = (Map<?, ?>) o;
                        String uuid = String.valueOf(m.get("uuid")), name = String.valueOf(m.get("username")), kind = String.valueOf(m.get("kind"));
                        boolean active = normalize(uuid).equals(cur);
                        sawCurrent |= active;
                        MainMenuView.Account a = new MainMenuView.Account(name, uuid, kind, active);
                        if (active) out.add(0, a); else out.add(a);
                    }
                }
            } catch (Exception ignored) { }
        }
        if (!sawCurrent) out.add(0, new MainMenuView.Account(currentName, currentUuid, "microsoft", true));
        return out;
    }

    private static String normalize(String uuid) { return uuid == null ? "" : uuid.replace("-", "").toLowerCase(Locale.ROOT); }
}
