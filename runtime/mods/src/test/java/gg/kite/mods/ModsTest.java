package gg.kite.mods;

import static org.junit.jupiter.api.Assertions.*;

import gg.kite.core.ClickCounter;
import gg.kite.core.Events;
import gg.kite.core.GameState;
import gg.kite.core.ModManager;
import gg.kite.core.UiRenderer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModsTest {
    /** Records draw calls instead of drawing. */
    static class Rec implements UiRenderer {
        final List<String> log = new ArrayList<String>();
        public int screenWidth() { return 800; }
        public int screenHeight() { return 600; }
        public void rect(float x, float y, float w, float h, int argb, float r) { log.add("rect"); }
        public void text(String s, float x, float y, int argb, boolean sh) { log.add("text:" + s); }
        public int textWidth(String s) { return s.length() * 6; }
        public int fontHeight() { return 8; }
        public void item(String id, float x, float y) { log.add("item:" + id); }
        public void push(float x, float y, float s) { log.add("push"); }
        public void pop() { log.add("pop"); }
    }

    static class State implements GameState {
        long now = 10_000;
        Set<Key> down = EnumSet.noneOf(Key.class);
        List<ArmorPiece> armor = Collections.emptyList();
        List<Effect> effects = Collections.emptyList();
        public boolean inWorld() { return true; }
        public int fps() { return 144; }
        public int pingMs() { return 23; }
        public double x() { return 10.567; }
        public double y() { return 64; }
        public double z() { return -3.2; }
        public float yaw() { return 180f; }
        public String biome() { return "plains"; }
        public String serverAddress() { return "x"; }
        public boolean isDown(Key k) { return down.contains(k); }
        public List<ArmorPiece> armor() { return armor; }
        public List<Effect> effects() { return effects; }
        public long usedMemoryBytes() { return 512L << 20; }
        public long maxMemoryBytes() { return 4096L << 20; }
        public long nowMillis() { return now; }
    }

    private static ModManager all() {
        ModManager mm = new ModManager();
        BuiltinMods.registerAll(mm);
        return mm;
    }

    @Test
    void registersAllBuiltinsWithUniqueIds() {
        ModManager mm = all();
        assertTrue(mm.all().size() >= 32, "builtins registered: " + mm.all().size());
        assertNotNull(mm.get("fps").hud());
    }

    @Test
    void fpsRendersTextWithBackground() {
        ModManager mm = all();
        mm.setEnabled("fps", true);
        Rec r = new Rec();
        mm.renderHud(r, new State());
        assertEquals(Arrays.asList("push", "rect", "text:144 FPS", "pop"), r.log);
    }

    @Test
    void disabledOrServerBlockedModsDrawNothing() {
        ModManager mm = all();
        Rec r = new Rec();
        mm.renderHud(r, new State());
        assertTrue(r.log.isEmpty());
        mm.setEnabled("fps", true);
        mm.setServerDisabled(Collections.singleton("fps"));
        mm.renderHud(r, new State());
        assertTrue(r.log.isEmpty());
    }

    @Test
    void cpsCountsWindowAndStopsWhenDisabled() {
        ModManager mm = all();
        mm.setEnabled("cps", true);
        State s = new State();
        for (int i = 0; i < 5; i++) mm.events().post(new Events.Click(0, s.now - i * 100));
        mm.events().post(new Events.Click(1, s.now));
        Rec r = new Rec();
        mm.renderHud(r, s);
        assertTrue(r.log.contains("text:5 | 1 CPS"), r.log.toString());
        s.now += 1000;
        r.log.clear();
        mm.renderHud(r, s);
        assertTrue(r.log.contains("text:0 | 0 CPS"));
        mm.setEnabled("cps", false);
        mm.events().post(new Events.Click(0, s.now)); // unregistered: must not throw or count
    }

    /** Sets a mod's setting from JSON, like a loaded profile does. */
    private static void set(ModManager mm, String mod, String key, Object value) {
        for (gg.kite.core.Setting<?> st : mm.get(mod).settings()) if (st.key.equals(key)) { st.fromJson(value); return; }
        fail(mod + " has no setting " + key);
    }

    @Test
    void labelsCanBeHiddenToShowJustNumbers() {
        ModManager mm = all();
        for (String id : new String[] { "fps", "cps", "ping", "coords" }) mm.setEnabled(id, true);
        State s = new State();
        mm.events().post(new Events.Click(0, s.now));
        Rec r = new Rec();
        mm.renderHud(r, s);
        assertTrue(r.log.containsAll(Arrays.asList("text:144 FPS", "text:1 | 0 CPS", "text:23 ms", "text:XYZ: 10 / 64 / -4")), r.log.toString());
        set(mm, "fps", "label", false);
        set(mm, "cps", "label", false);
        set(mm, "ping", "label", false);
        set(mm, "coords", "labels", false);
        s.now += 300; // past the FPS update interval
        r.log.clear();
        mm.renderHud(r, s);
        assertTrue(r.log.containsAll(Arrays.asList("text:144", "text:1 | 0", "text:23", "text:10 / 64 / -4")), r.log.toString());
    }

    @Test
    void removedOptionsAreGone() {
        ModManager mm = all();
        String[][] gone = { { "fps", "low01" }, { "cps", "total" }, { "cps", "best" }, { "cps", "window" }, { "cps", "graph" }, { "ping", "jitter" },
                { "ping", "server" }, { "ping", "tps" }, { "ping", "colors" }, { "ping", "good" }, { "ping", "bad" }, { "direction", "compassWidth" },
                { "animations", "hurtCam" }, { "animations", "bobbing" }, { "notifications", "dnd" }, { "fullbright", "worlds" } };
        for (String[] g : gone) for (gg.kite.core.Setting<?> st : mm.get(g[0]).settings()) assertNotEquals(g[1], st.key, g[0] + "." + g[1]);
    }

    @Test
    void compassClipsInsideItsOwnBox() {
        ModManager mm = all();
        mm.setEnabled("direction", true);
        set(mm, "direction", "style", "COMPASS");
        final List<float[]> clips = new ArrayList<float[]>();
        Rec r = new Rec() {
            @Override public void clip(float x, float y, float w, float h) { clips.add(new float[] { x, y, w, h }); }
        };
        mm.renderHud(r, new State());
        assertEquals(1, clips.size());
        assertArrayEquals(new float[] { 0, 0, 140, 18 }, clips.get(0), 1e-3f, "local coordinates: push() already moved to the widget");
        assertTrue(r.log.contains("text:N"), "facing north shows N: " + r.log);
    }

    @Test
    void clickCounterRingOverflowIsSafe() {
        ClickCounter c = new ClickCounter();
        for (int i = 0; i < 500; i++) c.record(1000);
        assertEquals(64, c.count(1000));
        assertEquals(0, c.count(2000));
    }

    @Test
    void keystrokesHighlightHeldKeys() {
        ModManager mm = all();
        mm.setEnabled("keystrokes", true);
        State s = new State();
        s.down.add(GameState.Key.FORWARD);
        Rec r = new Rec();
        mm.renderHud(r, s);
        assertTrue(r.log.contains("text:W"));
        assertTrue(r.log.contains("text:LMB"));
        int rects = 0;
        for (String l : r.log) if (l.equals("rect")) rects++;
        assertEquals(8, rects); // W A S D + LMB RMB + space bar and its centre line
    }

    @Test
    void armorSkipsEmptySlotsAndFormats() {
        ModManager mm = all();
        mm.setEnabled("armor", true);
        State s = new State();
        Rec r = new Rec();
        mm.renderHud(r, s);
        assertTrue(r.log.isEmpty());
        s.armor = Arrays.asList(new GameState.ArmorPiece("diamond_helmet", 100, 363), null,
                new GameState.ArmorPiece("iron_leggings", 50, 225), null);
        mm.renderHud(r, s);
        assertTrue(r.log.contains("item:diamond_helmet"));
        assertTrue(r.log.contains("item:iron_leggings"));
        assertTrue(r.log.contains("text:100"));
        assertEquals("28%", ArmorStatus.format(ArmorStatus.Display.PERCENT, 100, 363));
        assertEquals(0xFF00FF00, ArmorStatus.durabilityColor(10, 10));
        assertEquals(0xFFFF0000, ArmorStatus.durabilityColor(0, 10));
        assertEquals(0xFFFFFFFF, ArmorStatus.durabilityColor(1, 0));
    }

    @Test
    void compassAndRoman() {
        assertEquals("S", SimpleHudMods.Direction.compass(0));
        assertEquals("W", SimpleHudMods.Direction.compass(90));
        assertEquals("N", SimpleHudMods.Direction.compass(-180));
        assertEquals("E", SimpleHudMods.Direction.compass(270));
        assertEquals("E", SimpleHudMods.Direction.compass(-90));
        assertEquals("II", SimpleHudMods.Potions.roman(2));
        assertEquals("42", SimpleHudMods.Potions.roman(42));
    }

    @Test
    void potionAndCoordsText() {
        ModManager mm = all();
        mm.setEnabled("potions", true);
        mm.setEnabled("coords", true);
        State s = new State();
        s.effects = Arrays.asList(new GameState.Effect("Speed", 1, 20 * 65));
        Rec r = new Rec();
        mm.renderHud(r, s);
        assertTrue(r.log.contains("text:Speed II  1:05"), r.log.toString());
        assertTrue(r.log.contains("text:XYZ: 10 / 64 / -4"), r.log.toString());
    }
}
