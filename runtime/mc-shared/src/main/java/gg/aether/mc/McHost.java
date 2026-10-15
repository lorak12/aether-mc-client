package gg.aether.mc;

import gg.aether.core.ClientHost;
import gg.aether.core.Desktop;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/** {@link ClientHost} for the Fabric adapters: clipboard, UI sounds, OS notifications and file opening. */
public final class McHost implements ClientHost {
    private final String clientVersion;

    public McHost(String clientVersion) { this.clientVersion = clientVersion; }

    private static Minecraft mc() { return Minecraft.getInstance(); }

    @Override public void copyText(String text) {
        Minecraft mc = mc();
        if (text == null) return;
        if (mc.isSameThread()) mc.keyboardHandler.setClipboard(text);
        else mc.execute(() -> mc.keyboardHandler.setClipboard(text));
    }

    @Override public String clipboardText() {
        try {
            String s = mc().keyboardHandler.getClipboard();
            return s == null ? "" : s;
        } catch (RuntimeException e) {
            return "";
        }
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
        Minecraft mc = mc();
        Runnable play = () -> {
            SimpleSoundInstance s;
            switch (name == null ? "" : name) {
                case "ding": s = SimpleSoundInstance.forUI(SoundEvents.ARROW_HIT_PLAYER, 1f); break;
                case "pling": s = SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.5f); break;
                case "bell": s = SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1f); break;
                case "orb": s = SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f); break;
                case "levelup": s = SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1f); break;
                case "click": s = SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f); break;
                default: return;
            }
            mc.getSoundManager().play(s);
        };
        if (mc.isSameThread()) play.run(); else mc.execute(play);
    }

    @Override public void playUiSound(String name, float pitch, float volume) {
        if (volume <= 0.001f) return;
        Minecraft mc = mc();
        Runnable play = () -> {
            net.minecraft.sounds.SoundEvent e;
            switch (name == null ? "" : name) {
                case "click":
//#if >=1.19.3
                case "select": e = SoundEvents.UI_BUTTON_CLICK.value(); break;
//#else
                case "select": e = SoundEvents.UI_BUTTON_CLICK; break;
//#endif
                case "toggle_on": e = SoundEvents.STONE_BUTTON_CLICK_ON; break;
                case "toggle_off": e = SoundEvents.STONE_BUTTON_CLICK_OFF; break;
//#if >=1.19.3
                case "tick": e = SoundEvents.NOTE_BLOCK_HAT.value(); break;
//#else
                case "tick": e = SoundEvents.NOTE_BLOCK_HAT; break;
//#endif
                case "open": e = SoundEvents.UI_TOAST_IN; break;
                case "close": e = SoundEvents.UI_TOAST_OUT; break;
                default: return;
            }
            mc.getSoundManager().play(SimpleSoundInstance.forUI(e, pitch, volume));
        };
        if (mc.isSameThread()) play.run(); else mc.execute(play);
    }

    @Override public void desktopNotify(String title, String text) { Desktop.run(Desktop.notifyCommand(Desktop.os(), title, text)); }

    @Override public Path gameDir() { return mc().gameDirectory.toPath(); }

    @Override public String versionInfo() {
        String loader = FabricLoader.getInstance().getModContainer("fabricloader").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        return "Aether " + clientVersion + " on Minecraft " + SharedConstants.getCurrentVersion().name() + " / Fabric " + loader;
    }

    @Override public String sessionUuid() {
        java.util.UUID id = mc().getUser() == null ? null : Compat.userId(mc().getUser());
        return id == null ? null : id.toString();
    }

    @Override public String sessionName() { return mc().getUser() == null ? null : mc().getUser().getName(); }

    @Override public String currentServer() { return Compat.state().serverAddress(); }

    /** Mojang's "join server" with the signed-in session, as when joining an online-mode server. */
    @Override public boolean joinSession(String serverId) {
        Minecraft mc = mc();
        try {
//#if >=1.20.2
            mc.services().sessionService().joinServer(mc.getUser().getProfileId(), mc.getUser().getAccessToken(), serverId);
//#else
            mc.getMinecraftSessionService().joinServer(mc.getUser().getGameProfile(), mc.getUser().getAccessToken(), serverId);
//#endif
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public List<String> playerUuids() {
        Minecraft mc = mc();
        java.util.Set<String> out = new java.util.LinkedHashSet<String>();
        if (mc.level != null) for (net.minecraft.world.entity.player.Player p : mc.level.players()) out.add(p.getUUID().toString());
        if (mc.getConnection() != null) {
//#if >=1.19.3
            for (net.minecraft.client.multiplayer.PlayerInfo i : mc.getConnection().getListedOnlinePlayers()) out.add(i.getProfile().id().toString());
//#else
            for (net.minecraft.client.multiplayer.PlayerInfo i : mc.getConnection().getOnlinePlayers()) out.add(i.getProfile().id().toString());
//#endif
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
