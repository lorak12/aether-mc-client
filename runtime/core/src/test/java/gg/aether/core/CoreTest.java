package gg.aether.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreTest {
    static class TestMod extends Mod {
        final Setting.Bool flag = add(new Setting.Bool("flag", "Flag", false));
        final Setting.Int size = add(new Setting.Int("size", "Size", 5, 1, 10));
        final Setting.Color color = add(new Setting.Color("color", "Color", 0xFFFFFFFF));
        int ticks;
        boolean throwOnTick;

        TestMod(String id, boolean safe) {
            super(id, "Mod " + id, "desc " + id, Category.HUD, safe);
            hud = new HudElement(HudElement.Anchor.TOP_LEFT, 4, 4);
        }

        @Override public void onTick(GameState s) {
            if (throwOnTick) throw new IllegalStateException("boom");
            ticks++;
        }
    }

    @Test
    void jsonRoundTripAndErrors() {
        Map<String, Object> m = Json.parseObject("{\"a\": [1, 2.5, true, null, \"x\\n\\u0041\"], \"b\": {}}");
        assertEquals(Arrays.<Object>asList(1L, 2.5, true, null, "x\nA"), m.get("a"));
        assertEquals(m, Json.parseObject(Json.write(m)));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,]"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} extra"));
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) deep.append('[');
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deep.toString()));
    }

    @Test
    void settingsClampAndTolerateGarbage() {
        Setting.Int i = new Setting.Int("i", "i", 5, 1, 10);
        i.fromJson(999L);
        assertEquals(10, i.val());
        i.fromJson("nope");
        assertEquals(10, i.val());
        Setting.Color c = new Setting.Color("c", "c", 0);
        c.fromJson("#FF8800");
        assertEquals(0xFFFF8800, c.argb());
        c.fromJson("garbage");
        assertEquals(0xFFFF8800, c.argb());
        assertEquals("#FFFF8800", c.toJson());
        Setting.Dbl d = new Setting.Dbl("d", "d", 1, 0, 2);
        d.set(Double.NaN);
        assertEquals(1.0, d.val());
    }

    enum Mode { A, B, C }

    @Test
    void choiceCycles() {
        Setting.Choice<Mode> ch = new Setting.Choice<Mode>("m", "m", Mode.A);
        ch.next();
        ch.next();
        ch.next();
        assertEquals(Mode.A, ch.val());
        ch.fromJson("C");
        assertEquals(Mode.C, ch.val());
        ch.fromJson("Z");
        assertEquals(Mode.C, ch.val());
    }

    @Test
    void hudAnchorsSurviveResize() {
        HudElement h = new HudElement(HudElement.Anchor.BOTTOM_RIGHT, -10, -10);
        float[] p = h.resolve(800, 600, 100, 20);
        assertEquals(690f, p[0]);
        assertEquals(570f, p[1]);
        p = h.resolve(1920, 1080, 100, 20);
        assertEquals(1810f, p[0]);
        assertEquals(1050f, p[1]);
    }

    @Test
    void hudDragReanchorsAndRoundTrips() {
        HudElement h = new HudElement(HudElement.Anchor.TOP_LEFT, 0, 0);
        h.setScale(2f);
        h.moveTo(700, 500, 800, 600, 50, 20);
        assertEquals(HudElement.Anchor.BOTTOM_RIGHT, h.anchor);
        float[] p = h.resolve(800, 600, 50, 20);
        assertEquals(700f, p[0], 0.01f);
        assertEquals(500f, p[1], 0.01f);
    }

    @Test
    void hudClampsOffscreenAndBadValues() {
        HudElement h = new HudElement(HudElement.Anchor.TOP_LEFT, -500, 9999);
        float[] p = h.resolve(800, 600, 100, 20);
        assertEquals(0f, p[0]);
        assertEquals(580f, p[1]);
        h.setScale(100f);
        assertEquals(HudElement.MAX_SCALE, h.scale());
        HudElement g = new HudElement(HudElement.Anchor.CENTER, 0, 0);
        g.fromJson(Json.parseObject("{\"anchor\":\"NOPE\",\"x\":\"a\",\"scale\":0.0}"));
        assertEquals(HudElement.Anchor.CENTER, g.anchor);
        assertEquals(HudElement.MIN_SCALE, g.scale());
    }

    @Test
    void eventBusPriorityAndIsolation() {
        EventBus bus = new EventBus();
        StringBuilder sb = new StringBuilder();
        bus.on(String.class, "o1", 10, e -> sb.append("late"));
        bus.on(String.class, "o2", 0, e -> { throw new RuntimeException("bad"); });
        bus.on(String.class, "o3", 5, e -> sb.append("mid,"));
        bus.post("x");
        assertEquals("mid,late", sb.toString());
        assertEquals(1, bus.errors().size());
        bus.unregister("o1");
        sb.setLength(0);
        bus.post("x");
        assertEquals("mid,", sb.toString());
    }

    @Test
    void managerGatingAndFailureIsolation() {
        ModManager mm = new ModManager();
        TestMod safe = new TestMod("safe", true), edge = new TestMod("edge", false), bad = new TestMod("bad", true);
        mm.register(safe);
        mm.register(edge);
        mm.register(bad);
        assertThrows(IllegalArgumentException.class, () -> mm.register(new TestMod("safe", true)));
        for (String id : new String[] { "safe", "edge", "bad" }) mm.setEnabled(id, true);

        mm.tick(null);
        assertEquals(1, safe.ticks);
        mm.setServerDisabled(new HashSet<String>(Arrays.asList("safe")));
        mm.tick(null);
        assertEquals(1, safe.ticks);
        mm.clearServerDisabled();
        mm.setCompetitiveMode(true);
        mm.tick(null);
        assertEquals(2, edge.ticks); // ticked twice before competitive mode, not after
        assertEquals(2, safe.ticks);

        bad.throwOnTick = true;
        mm.tick(null);
        assertFalse(bad.enabled());
        assertEquals(1, mm.failures().size());
        assertEquals(1, mm.search("EDGE").size());
    }

    @Test
    void configPersistsAndSurvivesCorruption(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cfg").resolve("mods.json");
        ModManager a = new ModManager();
        TestMod m = new TestMod("m", true);
        a.register(m);
        a.setEnabled("m", true);
        m.flag.set(true);
        m.size.set(8);
        m.hud().anchor = HudElement.Anchor.BOTTOM_LEFT;
        m.hud().setScale(1.5f);
        a.setCompetitiveMode(true);
        new ConfigStore(file).save(a);

        ModManager b = new ModManager();
        TestMod m2 = new TestMod("m", true);
        b.register(m2);
        assertTrue(new ConfigStore(file).load(b));
        assertTrue(m2.enabled());
        assertTrue(m2.flag.on());
        assertEquals(8, m2.size.val());
        assertEquals(HudElement.Anchor.BOTTOM_LEFT, m2.hud().anchor);
        assertEquals(1.5f, m2.hud().scale());
        assertTrue(b.competitiveMode());

        Files.write(file, "{ not json".getBytes(StandardCharsets.UTF_8));
        ModManager c = new ModManager();
        c.register(new TestMod("m", true));
        assertFalse(new ConfigStore(file).load(c));
        assertTrue(Files.exists(dir.resolve("cfg").resolve("mods.json.corrupt")));
    }
}
