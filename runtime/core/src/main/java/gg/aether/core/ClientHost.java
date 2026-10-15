package gg.aether.core;

import java.nio.file.Path;

/** Services the version adapter offers to mods. Every method is optional; defaults do nothing. */
public interface ClientHost {
    default void copyText(String text) {}
    /** Text on the clipboard, "" if none or unsupported. */
    default String clipboardText() { return ""; }
    /** Copy an image file to the clipboard. Returns false if unsupported. */
    default boolean copyImage(Path png) { return false; }
    /** Open a file or folder with the system's default program. */
    default void openFile(Path file) {}
    /**
     * Show the system's open-file dialog for {@code extensions} (no dots). {@code done} gets the chosen files, empty
     * when cancelled, null when no dialog could be shown after all, on any thread. Returns false if the platform can't
     * show one.
     */
    default boolean pickFiles(String title, String[] extensions, java.util.function.Consumer<java.util.List<Path>> done) { return false; }
    /** Short UI sounds: "ding", "pling", "levelup", "orb", "bell", "click". */
    default void playSound(String name) {}
    /**
     * A menu sound: "click", "select", "toggle_on", "toggle_off", "tick", "open", "close". {@code volume} is 0..1 of the
     * game's master volume.
     */
    default void playUiSound(String name, float pitch, float volume) {}
    /** A desktop (OS) notification, when the platform supports it. */
    default void desktopNotify(String title, String text) {}
    /** The game directory (screenshots, crash-reports...), null if unknown. */
    default Path gameDir() { return null; }
    /** Client version string ("Aether 0.1.0 on Minecraft 26.3 / Fabric"). */
    default String versionInfo() { return "Kite"; }
    /** Loaded mods as "id version" (the loader's list), empty if unknown. */
    default java.util.List<String> loadedMods() { return java.util.Collections.emptyList(); }
    /** Open the containing folder with the file selected. */
    default void revealFile(Path file) { openFile(file.getParent()); }
    /** Address of the multiplayer server the player is on, "" in singleplayer or the menus. Client thread only. */
    default String currentServer() { return ""; }
    /** The signed-in player's UUID and name; null when unknown. */
    default String sessionUuid() { return null; }
    default String sessionName() { return null; }
    /**
     * Prove the session to a third party with Mojang's "join server" call for {@code serverId} (what servers use to
     * verify players). Blocking, may run on any thread; false if it failed (offline account, network...).
     */
    default boolean joinSession(String serverId) { return false; }
    /** UUIDs of the other players in the player list. May be called from the client thread only. */
    default java.util.List<String> playerUuids() { return java.util.Collections.emptyList(); }
}
