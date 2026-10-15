package gg.aether.mods;

import static org.junit.jupiter.api.Assertions.*;

import gg.aether.core.ConfigStore;
import gg.aether.core.Events;
import gg.aether.core.GameState;
import gg.aether.core.ModManager;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Logic of the task-10 HUD mods: statistics, parsing, formatting and persistence. */
class HudModsTest {
    static ModManager mm(String... enable) {
        ModManager mm = new ModManager();
        BuiltinMods.registerAll(mm);
        for (String id : enable) mm.setEnabled(id, true);
        return mm;
    }

    static List<String> lines(ModManager mm, String id, GameState s) {
        return ((HudTextMod) mm.get(id)).lines(s);
    }

    @Test
    void graphStatistics() {
        Graph g = new Graph(4);
        for (int v : new int[] { 10, 20, 30, 40, 50 }) g.add(v);
        assertEquals(4, g.size());
        assertEquals(20, g.min());
        assertEquals(50, g.max());
        assertEquals(35, g.average());
        assertEquals(20, g.lowAverage(0.01));
        assertEquals(25, g.lowAverage(0.5));
        assertTrue(g.stddev() > 11 && g.stddev() < 12);
    }

    @Test
    void clockDirectionAndFood() {
        assertEquals("06:00", SimpleHudMods.Clock.gameTime(0, true));
        assertEquals("18:00", SimpleHudMods.Clock.gameTime(12000, true));
        assertEquals("12:00 AM", SimpleHudMods.Clock.gameTime(18000, false));
        assertEquals(0, SimpleHudMods.Direction.bearing(180f), 1e-3); // yaw 180 = north
        assertEquals("+Z", SimpleHudMods.Direction.axis(0));
        assertEquals("North-East", SimpleHudMods.Direction.full(-135));
        assertEquals("Fast", StatusMods.Food.regenState(20, 3, 10, 20));
        assertEquals("Yes", StatusMods.Food.regenState(18, 0, 10, 20));
        assertEquals("No", StatusMods.Food.regenState(17, 5, 10, 20));
        assertEquals("Full", StatusMods.Food.regenState(20, 5, 20, 20));
    }


    @Test
    void playtimeAccumulatesWhileInWorld() {
        ModManager m = mm("playtime");
        HarnessModsTest.World w = new HarnessModsTest.World();
        w.now = System.currentTimeMillis();
        for (int i = 0; i < 61; i++) {
            m.tick(w);
            w.now += 1000;
        }
        assertEquals(60_000, m.get("playtime").data().getLong("total", 0));
        assertTrue(lines(m, "playtime", w).contains("Session: 1m"), lines(m, "playtime", w).toString());
    }

    @Test
    void potionFiltersAndTntTimer() {
        ModManager m = mm("potions", "tnttimer");
        HarnessModsTest.World w = new HarnessModsTest.World() {
            @Override public List<Effect> effects() {
                return Arrays.asList(new Effect("Poison", 0, 200, false, "minecraft:poison"), new Effect("Speed", 1, 400, true, "minecraft:speed"));
            }
            @Override public List<EntityInfo> entities(double radius) {
                return Collections.singletonList(new EntityInfo(1, "minecraft:tnt", "Primed TNT", 0, 0, 0, 3, 0, 0, 1, false, false, false, false, false, false, 30));
            }
        };
        mm_set(m, "potions", "hideNegative", true);
        assertEquals(Collections.singletonList("Speed II  0:20"), lines(m, "potions", w));
        assertEquals(Collections.singletonList("TNT 1.5s  3m"), lines(m, "tnttimer", w));
    }

    static void mm_set(ModManager m, String mod, String key, Object json) {
        for (gg.aether.core.Setting<?> s : m.get(mod).settings()) if (s.key.equals(key)) s.fromJson(json);
    }

    @Test
    void replacementHudsHideVanilla() {
        ModManager m = mm("bossbar", "titles", "scoreboard");
        m.tick(new HarnessModsTest.World());
        assertTrue(m.overrides().hideVanillaBossBar);
        assertTrue(m.overrides().hideVanillaTitle);
        assertTrue(m.overrides().hideVanillaScoreboard);
    }
}
