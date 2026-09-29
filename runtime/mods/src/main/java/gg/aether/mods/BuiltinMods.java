package gg.aether.mods;

import gg.aether.core.ModManager;

public final class BuiltinMods {
    private BuiltinMods() {}

    public static void registerAll(ModManager mm) {
        mm.register(new SimpleHudMods.Fps());
        mm.register(new SimpleHudMods.Cps());
        mm.register(new SimpleHudMods.Ping());
        mm.register(new SimpleHudMods.Coordinates());
        mm.register(new SimpleHudMods.Direction());
        mm.register(new SimpleHudMods.Clock());
        mm.register(new SimpleHudMods.Memory());
        mm.register(new SimpleHudMods.Potions());
        mm.register(new Keystrokes());
        mm.register(new ArmorStatus());
    }
}
