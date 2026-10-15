package gg.aether.mc;

import gg.aether.core.ClientHost;
import gg.aether.core.Desktop;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;

/** {@link ClientHost} for the legacy adapters: clipboard, UI sounds, OS notifications and file opening. */
public final class McHost implements ClientHost {
    private final String clientVersion;

    public McHost(String clientVersion) { this.clientVersion = clientVersion; }

    private static MinecraftClient mc() { return MinecraftClient.getInstance(); }

    /** "1.8.9": the game version as Fabric Loader reports it. */
    public static String minecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    @Override public void copyText(String text) { if (text != null) Screen.setClipboard(text); }

    @Override public String clipboardText() {
        String s = Screen.getClipboard();
        return s == null ? "" : s;
    }

    @Override public boolean copyImage(Path png) {
        if (png == null) return false;
        Desktop.run(Desktop.copyImageCommand(Desktop.os(), png));
        return true;
    }

    @Override public boolean pickFiles(String title, String[] extensions, java.util.function.Consumer<List<Path>> done) {
        Desktop.pickFiles(title, extensions, done);
        return true;
    }
    @Override public void openFile(Path file) { if (file != null) Desktop.run(Desktop.openCommand(Desktop.os(), file)); }
    @Override public void revealFile(Path file) { if (file != null) Desktop.run(Desktop.revealCommand(Desktop.os(), file)); }

    @Override public void playSound(String name) {
        switch (name == null ? "" : name) {
            case "ding": LegacyCompat.playSound("random.successful_hit", "entity.arrow.hit_player", 1f); break;
            case "pling": LegacyCompat.playSound("note.pling", "block.note.pling", 1.5f); break;
            case "bell": LegacyCompat.playSound("note.harp", "block.note.bell", 1f); break;
            case "orb": LegacyCompat.playSound("random.orb", "entity.experience_orb.pickup", 1f); break;
            case "levelup": LegacyCompat.playSound("random.levelup", "entity.player.levelup", 1f); break;
            case "click": LegacyCompat.playSound("gui.button.press", "ui.button.click", 1f); break;
            default: break;
        }
    }

    @Override public void playUiSound(String name, float pitch, float volume) {
        if (volume <= 0.001f) return;
        switch (name == null ? "" : name) {
            case "click": case "select": LegacyCompat.playSound("gui.button.press", "ui.button.click", pitch); break;
            case "toggle_on": LegacyCompat.playSound("random.click", "block.stone_button.click_on", pitch * 1.2f); break;
            case "toggle_off": LegacyCompat.playSound("random.click", "block.stone_button.click_off", pitch * 0.9f); break;
            case "tick": LegacyCompat.playSound("note.hat", "block.note.hat", pitch); break;
            case "open": case "close": LegacyCompat.playSound("random.click", "ui.button.click", pitch * 0.8f); break;
            default: break;
        }
    }

    @Override public void desktopNotify(String title, String text) { Desktop.run(Desktop.notifyCommand(Desktop.os(), title, text)); }

    @Override public Path gameDir() { return mc().runDirectory.toPath(); }

    @Override public String versionInfo() {
        String loader = FabricLoader.getInstance().getModContainer("fabricloader").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        return "Aether " + clientVersion + " on Minecraft " + minecraftVersion() + " / Legacy Fabric " + loader;
    }

    @Override public String sessionUuid() { return mc().getSession() == null ? null : McSession.dashed(mc().getSession().getUuid()); }
    @Override public String sessionName() { return mc().getSession() == null ? null : mc().getSession().getUsername(); }
    @Override public String currentServer() { return mc().getCurrentServerEntry() == null || mc().isInSingleplayer() ? "" : mc().getCurrentServerEntry().address; }

    @Override public boolean joinSession(String serverId) {
        MinecraftClient mc = mc();
        try {
            mc.getSessionService().joinServer(mc.getSession().getProfile(), mc.getSession().getAccessToken(), serverId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public List<String> playerUuids() {
        MinecraftClient mc = mc();
        java.util.Set<String> out = new java.util.LinkedHashSet<String>();
        if (mc.world != null) for (Object o : mc.world.playerEntities) out.add(((PlayerEntity) o).getUuid().toString());
        if (mc.getNetworkHandler() != null) {
            for (PlayerListEntry i : mc.getNetworkHandler().getPlayerList()) out.add(i.getProfile().getId().toString());
        }
        return new ArrayList<String>(out);
    }

    @Override public List<String> loadedMods() {
        List<String> out = new ArrayList<String>();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) out.add(c.getMetadata().getId() + " " + c.getMetadata().getVersion().getFriendlyString());
        Collections.sort(out);
        return out;
    }
}
