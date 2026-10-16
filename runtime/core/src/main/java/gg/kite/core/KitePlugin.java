package gg.kite.core;

/**
 * Entry point for third-party Kite mods. Fabric mods declare it under the {@code "kite"} entrypoint in
 * fabric.mod.json; plain jars on the classpath can list it in {@code META-INF/services/gg.kite.core.KitePlugin}.
 * <pre>
 * public final class MyPlugin implements KitePlugin {
 *     public void register(ModManager mods) { mods.register(new MyHudMod()); }
 * }
 * </pre>
 */
public interface KitePlugin {
    /** Register mods (and event handlers). Called once at start-up, before the config is loaded. */
    void register(ModManager mods);

    /** Shown in the developer page and in error messages. */
    default String name() { return getClass().getName(); }
}
