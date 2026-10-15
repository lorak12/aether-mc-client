package gg.aether.core;

/**
 * Entry point for third-party Aether mods. Fabric mods declare it under the {@code "aether"} entrypoint in
 * fabric.mod.json; plain jars on the classpath can list it in {@code META-INF/services/gg.aether.core.AetherPlugin}.
 * <pre>
 * public final class MyPlugin implements AetherPlugin {
 *     public void register(ModManager mods) { mods.register(new MyHudMod()); }
 * }
 * </pre>
 */
public interface AetherPlugin {
    /** Register mods (and event handlers). Called once at start-up, before the config is loaded. */
    void register(ModManager mods);

    /** Shown in the developer page and in error messages. */
    default String name() { return getClass().getName(); }
}
