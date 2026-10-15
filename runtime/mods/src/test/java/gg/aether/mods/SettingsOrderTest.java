package gg.aether.mods;

import static org.junit.jupiter.api.Assertions.*;

import gg.aether.core.Mod;
import gg.aether.core.ModManager;
import gg.aether.core.Setting;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every built-in mod lists its settings in the shared order documented on {@link Setting}. */
class SettingsOrderTest {
    /** The crosshair has its own editor layout. */
    private static boolean exempt(Mod m) { return m.id.equals("crosshair"); }

    private static List<Mod> mods() {
        ModManager mm = new ModManager();
        BuiltinMods.registerAll(mm);
        List<Mod> out = new ArrayList<Mod>();
        for (Mod m : mm.all()) if (!exempt(m)) out.add(m);
        return out;
    }

    @Test
    void lookSettingsComeInTheSameOrderEverywhere() {
        for (Mod m : mods()) {
            int last = -1;
            String lastKey = null;
            for (Setting<?> s : m.settings()) {
                int i = Setting.LOOK_ORDER.indexOf(s.key);
                if (i < 0) continue;
                assertTrue(i > last, m.id + ": " + s.key + " must come before " + lastKey);
                last = i;
                lastKey = s.key;
            }
        }
    }

    @Test
    void textHudModsStartWithTheirLook() {
        for (Mod m : mods()) {
            if (!(m instanceof HudTextMod)) continue;
            List<Setting<?>> st = m.settings();
            for (int i = 0; i < Setting.LOOK_ORDER.size() - 1; i++) // no border thickness on text HUDs
                assertEquals(Setting.LOOK_ORDER.get(i), st.get(i).key, m.id + " setting " + i);
        }
    }

    /** 0 = options, 1 = tables, 2 = keybinds (and their modes), 3 = buttons. */
    private static int group(Mod m, Setting<?> s) {
        switch (s.kind()) {
            case TABLE: return 1;
            case KEYBIND: return 2;
            case BUTTON: return 3;
            default:
                if (s.parent() instanceof Setting.Key) return 2;
                return 0;
        }
    }

    @Test
    void tablesThenKeybindsThenButtonsComeLast() {
        for (Mod m : mods()) {
            int last = 0;
            String lastKey = null;
            for (Setting<?> s : m.settings()) {
                int g = group(m, s);
                assertTrue(g >= last, m.id + ": " + s.key + " must come before " + lastKey);
                last = g;
                lastKey = s.key;
            }
        }
    }

    @Test
    void childrenFollowTheirParent() {
        for (Mod m : mods()) {
            List<Setting<?>> st = m.settings();
            for (int i = 0; i < st.size(); i++) {
                Setting<?> p = st.get(i).parent();
                if (p == null) continue;
                // The parent is the nearest setting above that is not a sibling (or a sibling's child).
                int j = i - 1;
                while (j >= 0 && st.get(j) != p && isUnder(st.get(j), p)) j--;
                assertTrue(j >= 0 && st.get(j) == p, m.id + ": " + st.get(i).key + " must sit right under " + p.key);
            }
        }
    }

    private static boolean isUnder(Setting<?> s, Setting<?> ancestor) {
        for (Setting<?> p = s.parent(); p != null; p = p.parent()) if (p == ancestor) return true;
        return false;
    }

    @Test
    void noModHasTheSameOptionTwice() {
        for (Mod m : mods()) {
            List<String> keys = new ArrayList<String>(), labels = new ArrayList<String>();
            for (Setting<?> s : m.settings()) {
                assertFalse(keys.contains(s.key), m.id + " has two settings keyed " + s.key);
                assertFalse(labels.contains(s.label.toLowerCase()), m.id + " has two settings labelled " + s.label);
                keys.add(s.key);
                labels.add(s.label.toLowerCase());
            }
        }
    }

    @Test
    void hiddenChildrenStillSaveLoadAndReset() {
        ModManager mm = new ModManager();
        BuiltinMods.registerAll(mm);
        Mod fps = mm.get("fps");
        Setting.Bool border = (Setting.Bool) HarnessModsTest.setting(fps, "border");
        Setting.Color color = (Setting.Color) HarnessModsTest.setting(fps, "borderColor");
        border.set(false);
        assertFalse(color.visible());
        assertEquals(1, color.depth());
        color.set(0xFF123456);
        assertEquals("#FF123456", color.toJson());
        color.fromJson("#FF654321");
        assertEquals(0xFF654321, color.base());
        border.set(true);
        assertTrue(color.visible());
        fps.resetSettings();
        assertEquals(color.getDefault(), (Integer) color.base());
    }
}
