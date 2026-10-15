package gg.aether.mc;

import gg.aether.core.Json;
import gg.aether.core.ui.MainMenuView;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

/**
 * The main menu's actions on legacy Minecraft: vanilla screens (returning to {@code parent}), quitting, and accounts.
 * Accounts come from AETHER_ACCOUNTS_FILE; switching writes AETHER_RELAUNCH_FILE and quits (the launcher relaunches).
 */
public abstract class McMenuHost implements MainMenuView.Host {
    private final Screen parent;
    private final String clientVersion;

    protected McMenuHost(Screen parent, String clientVersion) {
        this.parent = parent;
        this.clientVersion = clientVersion;
    }

    private static MinecraftClient mc() { return MinecraftClient.getInstance(); }

    @Override public void singleplayer() { mc().setScreen(new net.minecraft.client.gui.screen.world.SelectWorldScreen(parent)); }

    @Override public void multiplayer() { mc().setScreen(new net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen(parent)); }

    @Override public void minecraftOptions() { mc().setScreen(new net.minecraft.client.gui.screen.SettingsScreen(parent, mc().options)); }

    @Override public void realms() { new net.minecraft.realms.RealmsBridge().switchToRealms(parent); }

    @Override public boolean hasModMenu() { return FabricLoader.getInstance().isModLoaded("modmenu"); }

    /** Mod Menu's list screen, found by reflection so Aether doesn't depend on it. */
    @Override public void modMenu() {
        try {
            Class<?> c = Class.forName("com.terraformersmc.modmenu.gui.ModsScreen");
            mc().setScreen((Screen) c.getConstructor(Screen.class).newInstance(parent));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.warn("Could not open Mod Menu", e);
        }
    }

    @Override public void quit() { mc().scheduleStop(); }

    @Override public String versionLine() {
        String build = FabricLoader.getInstance().getModContainer("aether")
                .map(c -> c.getMetadata().getCustomValue("aether:build"))
                .map(v -> v.getType() == net.fabricmc.loader.api.metadata.CustomValue.CvType.STRING ? v.getAsString() : "")
                .orElse("");
        return "Aether Client " + McHost.minecraftVersion() + " (v" + clientVersion + (build.isEmpty() ? "" : "-" + build) + ")";
    }

    @Override public List<MainMenuView.Account> accounts() { return readAccounts(env("AETHER_ACCOUNTS_FILE"), currentUuid(), currentName()); }

    @Override public String switchAccount(final MainMenuView.Account a) {
        // Older launchers: quit, and the launcher relaunches as that account.
        Path relaunch = env("AETHER_RELAUNCH_FILE");
        if (relaunch == null) return "Start the game from the Aether launcher to switch accounts";
        try {
            Map<String, Object> req = new java.util.LinkedHashMap<String, Object>();
            req.put("uuid", a.uuid);
            if (relaunch.getParent() != null) Files.createDirectories(relaunch.getParent());
            Files.write(relaunch, Json.write(req).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "Could not ask the launcher to switch: " + e.getMessage();
        }
        mc().scheduleStop();
        return "Restarting as " + a.name + "...";
    }

    private static Path env(String name) {
        String v = System.getenv(name);
        return v == null || v.trim().isEmpty() ? null : Paths.get(v.trim());
    }

    private static String currentUuid() {
        String id = mc().getSession() == null ? null : McSession.dashed(mc().getSession().getUuid());
        return id == null ? "" : id;
    }

    private static String currentName() { return mc().getSession() == null ? "Player" : mc().getSession().getUsername(); }

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
