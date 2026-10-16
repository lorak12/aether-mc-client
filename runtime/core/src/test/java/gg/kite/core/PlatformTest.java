package gg.kite.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Profiles, schema migration, import/export, favorites, plugins and HUD visibility rules. */
class PlatformTest {
    static class State implements GameState {
        String address = "";
        boolean debug, chat, screen;
        public boolean inWorld() { return true; }
        public int fps() { return 60; }
        public int pingMs() { return -1; }
        public double x() { return 0; }
        public double y() { return 0; }
        public double z() { return 0; }
        public float yaw() { return 0; }
        public String biome() { return ""; }
        public String serverAddress() { return address; }
        public boolean isDown(Key k) { return false; }
        public List<ArmorPiece> armor() { return Collections.emptyList(); }
        public List<Effect> effects() { return Collections.emptyList(); }
        public long usedMemoryBytes() { return 0; }
        public long maxMemoryBytes() { return 1; }
        public long nowMillis() { return 1000; }
        @Override public boolean debugOpen() { return debug; }
        @Override public boolean chatOpen() { return chat; }
        @Override public boolean screenOpen() { return screen || chat; }
    }

    static ModManager manager() {
        ModManager mm = new ModManager();
        mm.register(new CoreTest.TestMod("a", true));
        mm.register(new CoreTest.TestMod("b", true));
        return mm;
    }

    static CoreTest.TestMod mod(ModManager mm, String id) { return (CoreTest.TestMod) mm.get(id); }

    @Test
    void profilesKeepSeparateStateAndSwitchBack() {
        ModManager mm = manager();
        mm.setEnabled("a", true);
        mod(mm, "a").size.set(9);
        Profiles.Profile pvp = mm.createProfile("PvP");
        mm.switchProfile(pvp, true);
        // Copied from the live state...
        assertTrue(mm.get("a").enabled());
        assertEquals(9, mod(mm, "a").size.val());
        // ...then diverges.
        mm.setEnabled("a", false);
        mm.setEnabled("b", true);
        mod(mm, "a").size.set(2);
        mm.switchProfile(mm.profiles().byName("Default"), true);
        assertTrue(mm.get("a").enabled());
        assertFalse(mm.get("b").enabled());
        assertEquals(9, mod(mm, "a").size.val());
        mm.switchProfile(pvp, true);
        assertEquals(2, mod(mm, "a").size.val());
        assertTrue(mm.get("b").enabled());
        assertFalse(mm.deleteProfile(pvp), "the live profile cannot be deleted");
    }

    @Test
    void serverRulesAutoSwitchAndFallBack() {
        ModManager mm = manager();
        Profiles.Profile hyp = mm.createProfile("Hypixel");
        hyp.servers.set("hypixel.net, *.minemen.club");
        State s = new State();
        s.address = "mc.hypixel.net:25565";
        mm.tick(s);
        assertSame(hyp, mm.profiles().active());
        s.address = "eu.minemen.club";
        mm.tick(s);
        assertSame(hyp, mm.profiles().active());
        s.address = "other.org";
        mm.tick(s);
        assertEquals("Default", mm.profiles().active().name.str());
        Profiles.Profile sp = mm.createProfile("Solo");
        sp.servers.set("singleplayer");
        s.address = "";
        mm.tick(s);
        assertSame(sp, mm.profiles().active());
        assertFalse(sp.matches("hypixel.net"));
        assertTrue(hyp.matches("HYPIXEL.NET."));
        assertFalse(hyp.matches("nothypixel.net"));
    }

    @Test
    void schemaOneConfigMigratesToDefaultProfile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.write(file, ("{\"schema\":1,\"competitiveMode\":false,\"mods\":{\"a\":{\"enabled\":true,\"settings\":{\"size\":7}}}}")
                .getBytes(StandardCharsets.UTF_8));
        ModManager mm = manager();
        assertTrue(new ConfigStore(file).load(mm));
        assertTrue(mm.get("a").enabled());
        assertEquals(7, mod(mm, "a").size.val());
        assertEquals("Default", mm.profiles().active().name.str());
        new ConfigStore(file).save(mm);
        Map<String, Object> root = Json.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(3L, root.get("schema"));
        assertFalse(root.containsKey("mods"));
        assertFalse(root.containsKey("profiles"));
        assertTrue(Files.exists(dir.resolve("profiles").resolve("default.json")));
    }

    @Test
    void schemaTwoProfilesMoveIntoTheirOwnFiles(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.write(file, ("{\"schema\":2,\"activeProfile\":\"PvP\",\"profiles\":["
                + "{\"name\":\"Default\",\"servers\":\"\",\"mods\":{\"a\":{\"enabled\":true}}},"
                + "{\"name\":\"PvP\",\"servers\":\"pvp.land\",\"mods\":{\"b\":{\"enabled\":true}}}]}").getBytes(StandardCharsets.UTF_8));
        ModManager mm = manager();
        assertTrue(new ConfigStore(file).load(mm));
        assertEquals("PvP", mm.profiles().active().name.str());
        assertTrue(mm.get("b").enabled());
        Path pvp = dir.resolve("profiles").resolve("pvp.json");
        assertTrue(Files.exists(pvp), "migration writes the files right away");
        Map<String, Object> p = Json.parseObject(new String(Files.readAllBytes(pvp), StandardCharsets.UTF_8));
        assertEquals(ProfileFiles.FORMAT, p.get("format"));
        assertEquals("pvp.land", p.get("servers"));
        Map<String, Object> root = Json.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals("pvp", root.get("activeProfile"));
        assertFalse(root.containsKey("profiles"));
    }

    @Test
    void deletedProfilesLoseTheirFileAndDroppedFilesArePickedUp(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        ConfigStore store = new ConfigStore(file);
        ModManager a = manager();
        Profiles.Profile p = a.createProfile("Survival");
        store.save(a);
        Path survival = dir.resolve("profiles").resolve("survival.json");
        assertTrue(Files.exists(survival));
        assertTrue(a.deleteProfile(p));
        store.save(a);
        assertFalse(Files.exists(survival));

        Files.write(dir.resolve("profiles").resolve("friend.json"),
                "{\"format\":\"kite-profile\",\"name\":\"Friend's PvP\",\"icon\":\"minecraft:bow\",\"mods\":{\"b\":{\"enabled\":true}}}".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("profiles").resolve("junk.json"), "[1,2]".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, store.rescan(a));
        assertEquals(0, store.rescan(a));
        Profiles.Profile f = a.profiles().byFile("friend");
        assertEquals("Friend's PvP", f.name.str());
        assertEquals("minecraft:bow", f.icon.str());
        a.switchProfile(f, true);
        assertTrue(a.get("b").enabled());
    }

    @Test
    void profilesFavoritesAndMenuKeyPersist(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        ModManager a = manager();
        a.setFavorite("b", true);
        a.menuKey.set(Keys.K);
        Profiles.Profile p = a.createProfile("PvP");
        p.servers.set("pvp.land");
        a.switchProfile(p, true);
        a.setEnabled("b", true);
        new ConfigStore(file).save(a);

        ModManager b = manager();
        assertTrue(new ConfigStore(file).load(b));
        assertEquals(2, b.profiles().all().size());
        assertEquals("PvP", b.profiles().active().name.str());
        assertEquals("pvp.land", b.profiles().byName("PvP").servers.str());
        assertTrue(b.get("b").enabled());
        assertTrue(b.favorite(b.get("b")));
        assertEquals(Keys.K, b.menuKey.code());
        b.switchProfile(b.profiles().byName("Default"), true);
        assertFalse(b.get("b").enabled());
    }

    @Test
    void exportedProfileImportsFromFileAndClipboard(@TempDir Path dir) throws Exception {
        ModManager a = manager();
        a.setEnabled("b", true);
        Profiles.Profile live = a.profiles().active();
        live.icon.set("minecraft:tnt");
        Path out = ConfigStore.exportProfile(a, live, dir.resolve("exports"));
        assertEquals("default.json", out.getFileName().toString());

        ModManager b = manager();
        Profiles.Profile imported = ConfigStore.importProfile(b, out);
        assertNotNull(imported);
        assertEquals("Default 2", imported.name.str(), "clashing names get a suffix");
        assertEquals("default-2", imported.file());
        assertEquals("minecraft:tnt", imported.icon.str());
        b.switchProfile(imported, true);
        assertTrue(b.get("b").enabled());

        String json = Json.write(a.exportProfile(live));
        assertNotNull(b.importProfile(json));
        assertNull(b.importProfile("not json"));
        assertNull(b.importProfile("{\"format\":\"something-else\",\"mods\":{}}"));
        assertNull(ConfigStore.importProfile(b, dir.resolve("missing.json")));
    }

    @Test
    void hudVisibilityRulesHideOutsideTheEditor() {
        ModManager mm = manager();
        final int[] drawn = { 0 };
        CoreTest.TestMod m = new CoreTest.TestMod("h", true) {
            @Override public void renderHud(UiRenderer r, GameState s) { drawn[0]++; }
        };
        mm.register(m);
        mm.setEnabled("h", true);
        m.hud().hideInDebug = true;
        m.hud().hideInChat = true;
        State s = new State();
        mm.renderHud(null, s);
        assertEquals(1, drawn[0]);
        s.debug = true;
        mm.renderHud(null, s);
        assertEquals(1, drawn[0]);
        mm.setEditing(true);
        mm.renderHud(null, s);
        assertEquals(2, drawn[0]);
        mm.setEditing(false);
        s.debug = false;
        s.chat = true;
        mm.renderHud(null, s);
        assertEquals(2, drawn[0]);
        m.hud().hideInScreens = true;
        m.hud().hideInChat = false;
        mm.renderHud(null, s);
        assertEquals(3, drawn[0], "chat counts as chat, not as a menu");
    }

    @Test
    void searchFindsSettingLabelsAndFailingPluginsAreIsolated() {
        ModManager mm = manager();
        assertEquals(2, mm.search("size").size());
        mm.loadPlugins(java.util.Arrays.asList(
                (KitePlugin) mods -> { throw new IllegalStateException("broken"); },
                (KitePlugin) mods -> mods.register(new CoreTest.TestMod("plug", true))));
        assertNotNull(mm.get("plug"));
        assertEquals(1, mm.plugins().size());
        assertTrue(mm.failures().get(0).contains("broken"));
    }

    @Test
    void notificationsRespectDoNotDisturbAndLimit() {
        Notifications n = new Notifications();
        n.max = 2;
        n.info(0, "a");
        n.info(0, "b");
        n.warn(0, "c");
        assertEquals(2, n.active(1).size());
        n.doNotDisturb = true;
        assertEquals(0, n.active(1).size());
        n.error(0, "boom");
        assertEquals(1, n.active(1).size());
    }
}
