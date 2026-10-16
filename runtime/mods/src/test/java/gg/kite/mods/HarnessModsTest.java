package gg.kite.mods;

import static org.junit.jupiter.api.Assertions.*;

import gg.kite.core.ConfigStore;
import gg.kite.core.Events;
import gg.kite.core.GameState;
import gg.kite.core.Keys;
import gg.kite.core.ModManager;
import gg.kite.core.Overrides;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for the mods built on the harness (overrides, hotkeys, events, notifications). */
class HarnessModsTest {
    /** Fully scriptable game state. */
    static class World implements GameState {
        long now = 100_000;
        int fps = 144;
        boolean dead, screen;
        double x = 10, y = 64, z = 10;
        String dim = "overworld", address = "play.example.net";
        ItemStack held;
        List<ItemStack> inv = new ArrayList<ItemStack>();
        List<ArmorPiece> armor = new ArrayList<ArmorPiece>();
        Map<String, Integer> mobs = new LinkedHashMap<String, Integer>();
        Target target = Target.NONE;
        public boolean inWorld() { return true; }
        public int fps() { return fps; }
        public int pingMs() { return 20; }
        public double x() { return x; }
        public double y() { return y; }
        public double z() { return z; }
        public float yaw() { return 0; }
        public String biome() { return "plains"; }
        public String serverAddress() { return address; }
        public boolean isDown(Key k) { return false; }
        public List<ArmorPiece> armor() { return armor; }
        public List<Effect> effects() { return Collections.emptyList(); }
        public long usedMemoryBytes() { return 1L << 29; }
        public long maxMemoryBytes() { return 1L << 32; }
        public long nowMillis() { return now; }
        @Override public boolean screenOpen() { return screen; }
        @Override public boolean isDead() { return dead; }
        @Override public String dimension() { return dim; }
        @Override public ItemStack heldItem() { return held; }
        @Override public List<ItemStack> inventory() { return inv; }
        @Override public Map<String, Integer> nearbyEntities(int radius) { return mobs; }
        @Override public Target target() { return target; }
    }

    private static ModManager mm(String... enable) {
        ModManager mm = new ModManager();
        BuiltinMods.registerAll(mm);
        for (String id : enable) mm.setEnabled(id, true);
        return mm;
    }

    @Test
    void everyModHasUniqueIdSettingsKeysAndSurvivesTickAndRender() {
        ModManager mm = new ModManager();
        BuiltinMods.registerAll(mm);
        assertTrue(mm.all().size() >= 32, "builtins registered: " + mm.all().size());
        for (gg.kite.core.Mod m : mm.all()) {
            java.util.Set<String> keys = new java.util.HashSet<String>();
            for (gg.kite.core.Setting<?> st : m.settings()) assertTrue(keys.add(st.key), m.id + " duplicate setting " + st.key);
            mm.setEnabled(m.id, true);
        }
        World w = new World();
        w.held = new GameState.ItemStack("minecraft:diamond_pickaxe", 1, 5, 1561);
        w.inv = Collections.nCopies(36, new GameState.ItemStack("minecraft:cobblestone", 32, 0, 0));
        w.mobs.put("Zombie", 3);
        w.target = new GameState.Target(GameState.Target.Kind.BLOCK, "Stone", "minecraft:stone", 3.2);
        ModsTest.Rec r = new ModsTest.Rec();
        mm.tick(w);
        mm.renderHud(r, w);
        mm.renderNotifications(r, w);
        assertEquals(Collections.<String>emptyList(), mm.failures());
        assertFalse(r.log.isEmpty());
    }

    @Test
    void zoomHoldsSmoothsAndRestores() {
        ModManager mm = mm("zoom");
        World w = new World();
        mm.tick(w);
        assertEquals(1f, mm.overrides().fovMultiplier);
        mm.key(Keys.C, true);
        for (int i = 0; i < 40; i++) mm.tick(w);
        assertEquals(0.25f, mm.overrides().fovMultiplier, 0.01f);
        assertEquals(0.25f, mm.overrides().sensitivityMultiplier, 0.01f);
        mm.key(Keys.C, false);
        for (int i = 0; i < 40; i++) mm.tick(w);
        assertEquals(1f, mm.overrides().fovMultiplier);
    }

    @Test
    void zoomScrollChangesLevelOnlyWhileZooming() {
        ModManager mm = mm("zoom");
        World w = new World();
        mm.events().post(new Events.Scroll(1));
        mm.key(Keys.C, true);
        mm.events().post(new Events.Scroll(1));
        for (int i = 0; i < 60; i++) mm.tick(w);
        assertEquals(1f / 4.4f, mm.overrides().fovMultiplier, 0.01f);
    }

    @Test
    void fullbrightToggleKeyTurnsItOffAndOn() {
        ModManager mm = mm("fullbright");
        World w = new World();
        mm.tick(w);
        assertEquals(12.0, mm.overrides().gamma);
        mm.key(Keys.G, true);
        mm.key(Keys.G, false);
        mm.tick(w);
        assertNull(mm.overrides().gamma);
        mm.key(Keys.G, true);
        mm.tick(w);
        assertEquals(12.0, mm.overrides().gamma);
    }

    @Test
    void freelookAccumulatesOnlyWhileHeldAndClampsPitch() {
        ModManager mm = mm("freelook");
        World w = new World();
        mm.events().post(new Events.MouseMove(100, 0));
        mm.tick(w);
        assertFalse(mm.overrides().freelook);
        mm.key(Keys.V, true);
        mm.events().post(new Events.MouseMove(100, 10_000));
        mm.tick(w);
        assertTrue(mm.overrides().freelook);
        assertTrue(mm.overrides().forceThirdPerson);
        assertEquals(15f, mm.overrides().cameraYawOffset, 0.001f);
        assertEquals(90f, mm.overrides().cameraPitchOffset, 0.001f);
        mm.key(Keys.V, false);
        mm.tick(w);
        assertFalse(mm.overrides().freelook);
        assertEquals(0f, mm.overrides().cameraYawOffset);
    }

    @Test
    void timeAndWeatherOverrides() {
        ModManager mm = mm("time", "weather");
        World w = new World();
        mm.tick(w);
        assertEquals(1000, mm.overrides().timeOfDay);
        assertEquals(Overrides.Weather.CLEAR, mm.overrides().weather);
        mm.setEnabled("time", false);
        mm.setEnabled("weather", false);
        mm.tick(w);
        assertNull(mm.overrides().timeOfDay);
        assertEquals(Overrides.Weather.VANILLA, mm.overrides().weather);
    }

    @Test
    void toggleSprintDefaultsToAlwaysSprintAndShowsStatus() {
        ModManager mm = mm("togglesprint");
        World w = new World();
        mm.tick(w);
        assertTrue(mm.overrides().forceSprint);
        assertFalse(mm.overrides().forceSneak);
        ModsTest.Rec r = new ModsTest.Rec();
        mm.renderHud(r, w);
        assertTrue(r.log.contains("text:[Sprinting (toggled)]"));
    }

    @Test
    void crosshairGeometry() {
        assertEquals(4, Crosshair.segments(Crosshair.Shape.CROSS, 100, 100, 6, 2, 2, false).size());
        assertEquals(3, Crosshair.segments(Crosshair.Shape.T, 100, 100, 6, 2, 2, false).size());
        assertEquals(5, Crosshair.segments(Crosshair.Shape.CROSS, 100, 100, 6, 2, 2, true).size());
        assertEquals(1, Crosshair.segments(Crosshair.Shape.DOT, 100, 100, 6, 2, 2, true).size());
        assertEquals(32, Crosshair.segments(Crosshair.Shape.CIRCLE, 100, 100, 6, 2, 2, false).size());
        // The left arm ends `gap` pixels left of centre.
        float[] left = Crosshair.segments(Crosshair.Shape.CROSS, 100, 100, 6, 2, 2, false).get(0);
        assertEquals(98f, left[0] + left[2]);
    }

    @Test
    void crosshairHidesVanillaOneAndTurnsRedOverEntities() {
        ModManager mm = mm("crosshair");
        World w = new World();
        mm.tick(w);
        assertTrue(mm.overrides().hideVanillaCrosshair);
        w.target = new GameState.Target(GameState.Target.Kind.ENTITY, "Zombie", "minecraft:zombie", 2);
        java.util.List<Integer> colors = new ArrayList<Integer>();
        mm.renderHud(new ModsTest.Rec() {
            @Override public void rect(float x, float y, float w2, float h, int argb, float r) { colors.add(argb); }
        }, w);
        assertTrue(colors.contains(0xFFFF4040));
        w.screen = true;
        colors.clear();
        mm.renderHud(new ModsTest.Rec() {
            @Override public void rect(float x, float y, float w2, float h, int argb, float r) { colors.add(argb); }
        }, w);
        assertTrue(colors.isEmpty());
    }

    @Test
    void comboCountsHitsResetsOnHurtAndTimeout() {
        ModManager mm = mm("combo");
        World w = new World();
        for (int i = 0; i < 3; i++) mm.events().post(new Events.Attack(3.0, false, w.now));
        ModsTest.Rec r = new ModsTest.Rec();
        mm.renderHud(r, w);
        assertTrue(r.log.contains("text:Combo: 3") && r.log.contains("text:Best: 3"));
        mm.events().post(new Events.Hurt(2f, w.now));
        r = new ModsTest.Rec();
        mm.renderHud(r, w);
        assertTrue(r.log.contains("text:Combo: 0") && r.log.contains("text:Best: 3"));
        mm.events().post(new Events.Attack(3.0, false, w.now));
        w.now += 4000;
        r = new ModsTest.Rec();
        mm.renderHud(r, w);
        assertTrue(r.log.contains("text:Combo: 0"));
    }

    @Test
    void reachShowsLastAndAverage() {
        ModManager mm = mm("reach");
        World w = new World();
        ModsTest.Rec r = new ModsTest.Rec();
        mm.renderHud(r, w);
        assertTrue(r.log.contains("text:Reach: --"));
        mm.events().post(new Events.Attack(3.0, false, w.now));
        mm.events().post(new Events.Attack(2.0, false, w.now));
        r = new ModsTest.Rec();
        mm.renderHud(r, w);
        assertTrue(r.log.contains("text:Reach: 2.00") && r.log.contains("text:Avg: 2.50"), r.log.toString());
    }

    @Test
    void itemCounterTotalsAcrossInventoryAndWarnsWhenLow() {
        ModManager mm = mm("itemcounter");
        World w = new World();
        w.held = new GameState.ItemStack("minecraft:oak_planks", 5, 0, 0);
        w.inv = Arrays.asList(w.held, null, new GameState.ItemStack("minecraft:oak_planks", 20, 0, 0), new GameState.ItemStack("minecraft:stone", 64, 0, 0));
        ModsTest.Rec r = new ModsTest.Rec();
        mm.renderHud(r, w);
        assertTrue(r.log.contains("text:Oak Planks: 25"), r.log.toString());
        w.inv = Collections.singletonList(w.held);
        r = new ModsTest.Rec();
        mm.renderHud(r, w);
        assertTrue(r.log.contains("text:(!) Oak Planks: 5"), r.log.toString());
    }

    @Test
    void durabilityWarningsFireOnceThenRespectCooldown() {
        ModManager mm = mm("durabilitywarn");
        World w = new World();
        w.armor.add(new GameState.ArmorPiece("minecraft:diamond_helmet", 20, 363));
        mm.tick(w);
        mm.tick(w);
        assertEquals(1, mm.notifications().active(w.now).size());
        assertTrue(mm.notifications().active(w.now).get(0).text.contains("Diamond Helmet"));
        w.now += 61_000;
        mm.tick(w);
        assertEquals(1, mm.notifications().active(w.now).size());
        w.armor.set(0, new GameState.ArmorPiece("minecraft:diamond_helmet", 300, 363));
        w.now += 1000;
        mm.tick(w);
    }

    @Test
    void streamerModeHidesSensitiveMods() {
        ModManager mm = mm("coords", "serverip", "fps", "streamer");
        World w = new World();
        mm.tick(w);
        ModsTest.Rec r = new ModsTest.Rec();
        mm.renderHud(r, w);
        assertTrue(r.log.contains("text:144 FPS"));
        assertTrue(r.log.stream().noneMatch(l -> l.contains("XYZ") || l.contains("play.example.net")), r.log.toString());
    }

    @Test
    void actionBarShowsMessageThenExpires() {
        ModManager mm = mm("actionbar");
        World w = new World();
        mm.tick(w);
        assertTrue(mm.overrides().hideVanillaActionBar);
        mm.events().post(new Events.ActionBar("+5 coins", w.now));
        ModsTest.Rec r = new ModsTest.Rec();
        mm.renderHud(r, w);
        assertTrue(r.log.contains("text:+5 coins"));
        w.now += 3500;
        r = new ModsTest.Rec();
        mm.renderHud(r, w);
        assertTrue(r.log.isEmpty());
    }

    @Test
    void targetInfoShowsNothingWithoutTarget() {
        ModManager mm = mm("targetinfo");
        World w = new World();
        ModsTest.Rec r = new ModsTest.Rec();
        mm.renderHud(r, w);
        assertTrue(r.log.isEmpty());
        w.target = new GameState.Target(GameState.Target.Kind.BLOCK, "Stone", "minecraft:stone", 3.24);
        mm.renderHud(r, w);
        assertTrue(r.log.contains("text:Stone") && r.log.contains("text:Minecraft") && r.log.contains("text:Any tool"), r.log.toString());
    }

    @Test
    void inventoryHudDrawsHotbarOrFullGrid() {
        ModManager mm = mm("inventoryhud");
        World w = new World();
        w.inv = new ArrayList<GameState.ItemStack>(Collections.nCopies(36, (GameState.ItemStack) null));
        w.inv.set(0, new GameState.ItemStack("minecraft:apple", 3, 0, 0));
        w.inv.set(20, new GameState.ItemStack("minecraft:stone", 64, 0, 0));
        ModsTest.Rec r = new ModsTest.Rec();
        mm.renderHud(r, w);
        assertTrue(r.log.contains("item:minecraft:apple"));
        assertFalse(r.log.contains("item:minecraft:stone"));
        ((gg.kite.core.Setting.Bool) setting(mm.get("inventoryhud"), "full")).set(true);
        r = new ModsTest.Rec();
        mm.renderHud(r, w);
        assertTrue(r.log.contains("item:minecraft:stone"));
    }

    @Test
    void hotkeyStateResetsWhenModDisabledAndConflictsAreReported() {
        ModManager mm = mm("zoom");
        World w = new World();
        mm.key(Keys.C, true);
        mm.setEnabled("zoom", false);
        mm.setEnabled("zoom", true);
        for (int i = 0; i < 5; i++) mm.tick(w);
        assertEquals(1f, mm.overrides().fovMultiplier);
        assertTrue(mm.keybindConflicts().isEmpty());
        ((gg.kite.core.Setting.Key) setting(mm.get("freelook"), "key")).set(Keys.C);
        assertEquals(1, mm.keybindConflicts().size());
    }

    @Test
    void newModSettingsRoundTripThroughConfigFile() throws Exception {
        Path f = Files.createTempFile("kite-mods", ".json");
        try {
            ModManager a = mm("zoom", "crosshair");
            ((gg.kite.core.Setting.Dbl) setting(a.get("zoom"), "level")).set(9.0);
            ((gg.kite.core.Setting.Key) setting(a.get("zoom"), "key")).set(Keys.Z);
            new ConfigStore(f).save(a);
            ModManager b = mm();
            assertTrue(new ConfigStore(f).load(b));
            assertTrue(b.get("zoom").enabled() && b.get("crosshair").enabled());
            assertEquals(9.0, ((gg.kite.core.Setting.Dbl) setting(b.get("zoom"), "level")).val());
            assertEquals(Keys.Z, ((gg.kite.core.Setting.Key) setting(b.get("zoom"), "key")).code());
        } finally {
            Files.deleteIfExists(f);
        }
    }

    /** {@code m}'s setting with key {@code key} (settings are looked up by key, not position, since their order is cosmetic). */
    static gg.kite.core.Setting<?> setting(gg.kite.core.Mod m, String key) {
        for (gg.kite.core.Setting<?> st : m.settings()) if (st.key.equals(key)) return st;
        throw new AssertionError(m.id + " has no setting " + key);
    }
}
