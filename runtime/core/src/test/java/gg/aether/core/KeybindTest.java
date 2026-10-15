package gg.aether.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Unbinding keys, the menu key's refusals and the Right Shift fallback that keeps the menu reachable. */
class KeybindTest {
    static final class KeyMod extends Mod {
        final Setting.Bool look = add(new Setting.Bool("look", "Look", true));
        final Setting.Color lookColor = add(new Setting.Color("lookColor", "Look color", 0xFFFFFFFF), look);
        final Setting.Int deep = add(new Setting.Int("deep", "Deep", 1, 0, 5), lookColor);
        final Hotkey hk;
        int fired;

        KeyMod(String id, int key) {
            super(id, "Key " + id, "", Category.UTILITY, true);
            hk = hotkey("key", "Key", key, Hotkey.Mode.TOGGLE);
        }

        @Override protected void onHotkey(Hotkey h) { fired++; }
    }

    private static ModManager mm(KeyMod... mods) {
        ModManager mm = new ModManager();
        for (KeyMod m : mods) {
            mm.register(m);
            mm.setEnabled(m.id, true);
        }
        return mm;
    }

    @Test
    void unboundHotkeysNeverFire() {
        KeyMod m = new KeyMod("a", Keys.K);
        ModManager mm = mm(m);
        mm.key(Keys.K, true);
        assertEquals(1, m.fired);
        m.hk.key.unbind();
        assertFalse(m.hk.key.bound());
        for (int code : new int[] { Keys.NONE, Keys.K, 0, -5 }) mm.key(code, true);
        assertEquals(1, m.fired);
        assertTrue(mm.keybindConflicts().isEmpty());
    }

    @Test
    void keysUnbindSaveAndLoadAsNone() {
        Setting.Key k = new Setting.Key("k", "K", Keys.K);
        assertTrue(k.set(Keys.NONE));
        assertEquals(Keys.NONE, k.code());
        assertTrue(k.set(-42)); // anything negative is "unbound"
        assertEquals(Keys.NONE, k.code());
        assertEquals(-1L, k.toJson());
        k.set(Keys.J);
        k.fromJson(-1L);
        assertEquals(Keys.NONE, k.code());
        k.reset();
        assertEquals(Keys.K, k.code());
    }

    @Test
    void hotkeyModeIsHiddenWhileTheKeyIsUnbound() {
        KeyMod m = new KeyMod("a", Keys.K);
        assertTrue(m.hk.mode.visible());
        assertEquals(1, m.hk.mode.depth());
        m.hk.key.unbind();
        assertFalse(m.hk.mode.visible());
    }

    @Test
    void childrenHideWithTheirParentAndStillReset() {
        KeyMod m = new KeyMod("a", Keys.K);
        assertTrue(m.lookColor.visible() && m.deep.visible());
        assertEquals(2, m.deep.depth());
        m.look.set(false);
        assertFalse(m.lookColor.visible());
        assertFalse(m.deep.visible(), "grandchildren hide with the parent");
        m.deep.set(4);
        m.resetSettings();
        assertEquals(1, m.deep.val());
        assertTrue(m.deep.visible());
        Setting.Int cond = new Setting.Int("c", "C", 0, 0, 1).visibleWhen(() -> m.deep.val() > 2);
        assertFalse(cond.visible());
        m.deep.set(3);
        assertTrue(cond.visible());
        assertEquals(0, cond.depth());
    }

    @Test
    void menuKeyRefusesKeysThatWouldLockThePlayerOut() {
        ModManager mm = new ModManager();
        for (int bad : new int[] { Keys.ESCAPE, Keys.ENTER, Keys.TAB, Keys.W, Keys.A, Keys.S, Keys.D, Keys.SPACE, Keys.E, Keys.T, Keys.SLASH }) {
            assertFalse(mm.menuKey.set(bad), Keys.name(bad));
            assertEquals(Keys.RIGHT_SHIFT, mm.menuKey.code());
        }
        assertTrue(mm.menuKey.set(Keys.K));
        assertTrue(mm.menuKey.set(Keys.NONE));
        // A refused key in a hand-edited config loads as the default.
        mm.menuKey.fromJson((long) Keys.ESCAPE);
        assertEquals(Keys.RIGHT_SHIFT, mm.menuKey.code());
        mm.menuKey.fromJson(-1L);
        assertEquals(Keys.NONE, mm.menuKey.code());
    }

    @Test
    void menuKeyOpensTheMenu() {
        ModManager mm = new ModManager();
        assertTrue(mm.opensMenu(Keys.RIGHT_SHIFT));
        assertFalse(mm.opensMenu(Keys.K));
        assertFalse(mm.opensMenu(Keys.NONE));
        mm.menuKey.set(Keys.K);
        assertTrue(mm.opensMenu(Keys.K));
        assertFalse(mm.opensMenu(Keys.RIGHT_SHIFT), "rebinding frees Right Shift");
        assertEquals("K", mm.menuKeyName());
    }

    @Test
    void rightShiftOpensTheMenuWhileTheMenuKeyIsUnbound() {
        ModManager mm = new ModManager();
        mm.menuKey.unbind();
        assertTrue(mm.opensMenu(Keys.RIGHT_SHIFT));
        assertFalse(mm.opensMenu(Keys.NONE));
        assertEquals("RShift", mm.menuKeyName());
    }

    @Test
    void aModOnRightShiftKeepsItButCtrlRightShiftStillOpensTheMenu() {
        KeyMod m = new KeyMod("a", Keys.RIGHT_SHIFT);
        ModManager mm = mm(m);
        mm.menuKey.unbind();
        assertFalse(mm.opensMenu(Keys.RIGHT_SHIFT), "the mod's key wins over the fallback");
        assertEquals("Ctrl+RShift", mm.menuKeyName());
        mm.key(Keys.LEFT_CONTROL, true);
        assertTrue(mm.opensMenu(Keys.RIGHT_SHIFT));
        mm.key(Keys.LEFT_CONTROL, false);
        assertFalse(mm.opensMenu(Keys.RIGHT_SHIFT));
        // A disabled mod doesn't hold the key.
        mm.setEnabled("a", false);
        assertTrue(mm.opensMenu(Keys.RIGHT_SHIFT));
    }

    @Test
    void ctrlRightShiftOpensTheMenuWhateverTheMenuKeyIs() {
        ModManager mm = new ModManager();
        mm.menuKey.set(Keys.K);
        mm.key(Keys.RIGHT_CONTROL, true);
        assertTrue(mm.opensMenu(Keys.RIGHT_SHIFT));
        assertTrue(mm.opensMenu(Keys.K));
    }
}
