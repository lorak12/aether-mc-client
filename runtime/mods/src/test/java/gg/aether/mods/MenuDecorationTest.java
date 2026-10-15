package gg.aether.mods;

import static org.junit.jupiter.api.Assertions.*;

import gg.aether.core.Mod;
import gg.aether.core.ModManager;
import gg.aether.core.Setting;
import gg.aether.core.ui.Icons;
import gg.aether.core.ui.ModCatalog;
import org.junit.jupiter.api.Test;

class MenuDecorationTest {
    @Test
    void everyBuiltinModHasAnIconAndServerModsAreTagged() {
        ModManager mm = new ModManager();
        BuiltinMods.registerAll(mm);
        for (Mod m : mm.all()) {
            assertTrue(BuiltinMods.ICONS.containsKey(m.id), m.id + " has no menu icon");
            assertTrue(Icons.has(m.icon()), m.id + " icon " + m.icon() + " is not in the icon set");
        }
        for (String id : BuiltinMods.SERVER) assertEquals(ModCatalog.Tab.SERVER, ModCatalog.tabOf(mm.get(id)), id);
        assertEquals(ModCatalog.Tab.HUD, ModCatalog.tabOf(mm.get("fps")));
        assertEquals(ModCatalog.Tab.MECHANIC, ModCatalog.tabOf(mm.get("zoom")));
    }

    @Test
    void waypointsOffersAddHere() {
        ModManager mm = new ModManager();
        BuiltinMods.registerAll(mm);
        boolean found = false;
        for (Setting<?> s : mm.get("waypoints").settings()) if (s.key.equals("addHere") && s.kind() == Setting.Kind.BUTTON) found = true;
        assertTrue(found);
    }
}
