package gg.aether.core.ui;

import static org.junit.jupiter.api.Assertions.*;

import gg.aether.core.ClientHost;
import gg.aether.core.ClientSettings;
import gg.aether.core.GameState;
import gg.aether.core.HudElement;
import gg.aether.core.Keys;
import gg.aether.core.Mod;
import gg.aether.core.ModManager;
import gg.aether.core.Profiles;
import gg.aether.core.Setting;
import gg.aether.core.UiRenderer;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class UiTest {
    /** Records every text draw (with its translation) so tests can find and click labels. */
    static final class Rec implements UiRenderer {
        static final class Drawn {
            final String s;
            final float x, y;
            Drawn(String s, float x, float y) { this.s = s; this.x = x; this.y = y; }
        }

        final List<Drawn> texts = new ArrayList<Drawn>();
        final List<String> textures = new ArrayList<String>();
        final List<Font> fonts = new ArrayList<Font>();
        private final List<float[]> stack = new ArrayList<float[]>();
        private float tx, ty, sc = 1;
        int w = 640, h = 360;

        void clear() { texts.clear(); textures.clear(); fonts.clear(); }

        @Override public int screenWidth() { return w; }
        @Override public int screenHeight() { return h; }
        @Override public void rect(float x, float y, float w, float h, int argb, float cornerRadius) { }
        @Override public void text(String s, float x, float y, int argb, boolean shadow) { texts.add(new Drawn(s, tx + x * sc, ty + y * sc)); }
        @Override public int textWidth(String s) { return s.length() * 5; }
        @Override public int fontHeight() { return 9; }
        @Override public void item(String itemId, float x, float y) { }
        @Override public void font(Font f) { fonts.add(f); }
        @Override public boolean texture(String id, float x, float y, float w, float h) { textures.add(id); return true; }
        @Override public void push(float x, float y, float scale) {
            stack.add(new float[] { tx, ty, sc });
            tx += x * sc;
            ty += y * sc;
            sc *= scale;
        }
        @Override public void pop() {
            float[] p = stack.remove(stack.size() - 1);
            tx = p[0];
            ty = p[1];
            sc = p[2];
        }

        boolean has(String s) {
            for (Drawn d : texts) if (d.s.equals(s)) return true;
            return false;
        }

        boolean hasPrefix(String s) {
            for (Drawn d : texts) if (d.s.startsWith(s)) return true;
            return false;
        }

        Drawn find(String s) {
            for (Drawn d : texts) if (d.s.equals(s)) return d;
            throw new AssertionError("\"" + s + "\" not drawn; drawn: " + strings());
        }

        List<String> strings() {
            List<String> out = new ArrayList<String>();
            for (Drawn d : texts) out.add(d.s);
            return out;
        }
    }

    static final class Host implements ClientHost {
        String copied, clipboard = "";
        final List<String> sounds = new ArrayList<String>();
        @Override public void copyText(String text) { copied = text; }
        @Override public void playUiSound(String name, float pitch, float volume) { sounds.add(name); }
        @Override public String clipboardText() { return clipboard; }
        @Override public String sessionName() { return "Tester"; }
    }

    enum Mode { ONE, TWO, THREE }

    static class Demo extends Mod {
        final Setting.Bool flag = add(new Setting.Bool("flag", "Some flag", false));
        final Setting.Int size = add(new Setting.Int("size", "Size", 4, 1, 10));
        final Setting.Int far = add(new Setting.Int("far", "Far distance", 0, 0, 100000));
        final Setting.Dbl speed = add(new Setting.Dbl("speed", "Speed", 1, 0, 2));
        final Setting.Color color = add(new Setting.Color("color", "Text color", 0xFFFFFFFF));
        final Setting.Choice<Mode> mode = add(new Setting.Choice<Mode>("mode", "Mode", Mode.ONE));
        final Setting.Text text = add(new Setting.Text("text", "Message", "gg", 20));
        final Setting.Key key = add(new Setting.Key("key", "Key", Keys.NONE));
        int clicks;
        final Setting.Action button = add(new Setting.Action("act", "Do it", "Run", () -> clicks++));

        Demo(String id, String name, Category c) {
            super(id, name, name + " description", c, true);
            if (c == Category.HUD) hud = new HudElement(HudElement.Anchor.TOP_LEFT, 2, 2);
        }
    }

    static GameState state() {
        return (GameState) Proxy.newProxyInstance(UiTest.class.getClassLoader(), new Class<?>[] { GameState.class }, (p, m, a) -> {
            Class<?> t = m.getReturnType();
            if (t == boolean.class) return false;
            if (t == int.class) return 0;
            if (t == long.class) return 0L;
            if (t == float.class) return 0f;
            if (t == double.class) return 0d;
            if (t == String.class) return "";
            if (t == List.class) return new ArrayList<Object>();
            return null;
        });
    }

    final Host host = new Host();
    final ModManager mm = new ModManager();
    final Demo fps = new Demo("fps", "FPS", Mod.Category.HUD);
    final Demo bed = new Demo("bedwars", "Bedwars Helper", Mod.Category.HUD);
    final Demo zoom = new Demo("zoom", "Zoom", Mod.Category.VISUAL);
    final Rec r = new Rec();
    int saves;

    UiTest() {
        mm.setHost(host);
        mm.register(fps);
        mm.register(bed);
        mm.register(zoom);
        bed.serverSpecific(true);
    }

    ClientGui gui() {
        return new ClientGui(mm, new ClientGui.Host() {
            @Override public void close() { }
            @Override public void save() { saves++; }
        });
    }

    void frame(View v) {
        r.clear();
        v.render(r, state());
    }

    void click(View v, String label, int button) {
        Rec.Drawn d = r.find(label);
        v.mouseDown(d.x + 1, d.y + 2, button);
        v.mouseUp(d.x + 1, d.y + 2, button);
        frame(v);
    }

    void click(View v, String label) { click(v, label, 0); }

    void clickAt(View v, float x, float y) {
        v.mouseMoved(x, y);
        v.mouseDown(x, y, 0);
        v.mouseUp(x, y, 0);
        frame(v);
    }

    /** A main menu bottom button by its tooltip. */
    void clickIcon(MainMenuView v, String tooltip) {
        float[] c = v.iconButtons.get(tooltip);
        assertNotNull(c, tooltip + " button missing: " + v.iconButtons.keySet());
        clickAt(v, c[0], c[1]);
    }

    // ---- setting kinds / colour maths / catalog ---------------------------------------------------------------------

    @Test
    void settingsReportTheirInputKind() {
        Demo d = new Demo("k", "K", Mod.Category.HUD);
        assertEquals(Setting.Kind.TOGGLE, d.flag.kind());
        assertEquals(Setting.Kind.SLIDER, d.size.kind());
        assertEquals(Setting.Kind.NUMBER_VALUE, d.far.kind(), "huge ranges are typed");
        assertEquals(Setting.Kind.SLIDER, d.far.slider().kind());
        assertEquals(Setting.Kind.NUMBER_VALUE, d.size.number().kind());
        assertEquals(Setting.Kind.SLIDER, d.speed.kind());
        assertEquals(Setting.Kind.COLOR_PICKER, d.color.kind());
        assertEquals(Setting.Kind.OPTION, d.mode.kind());
        assertEquals(Setting.Kind.TEXT_VALUE, d.text.kind());
        assertEquals(Setting.Kind.KEYBIND, d.key.kind());
        assertEquals(Setting.Kind.BUTTON, d.button.kind());
        assertEquals(Setting.Kind.TABLE, new Setting.Entries("e", "E", new String[] { "a" }, new String[] { "A" }, 5).kind());
    }

    @Test
    void hsvRoundTripsAndHexParses() {
        for (int c : new int[] { 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF6C8CFF, 0xFFB36CFF, 0xFF123456, 0xFFFFFFFF, 0xFF000000 }) {
            float[] hsv = ColorPicker.toHsv(c);
            assertEquals(c, ColorPicker.fromHsv(hsv[0], hsv[1], hsv[2]), Integer.toHexString(c));
        }
        assertEquals(120f, ColorPicker.toHsv(0xFF00FF00)[0], 0.01f);
        assertEquals(Integer.valueOf(0xFF6C8CFF), ColorPicker.parseHex("#6C8CFF"));
        assertEquals(Integer.valueOf(0x806C8CFF), ColorPicker.parseHex("806c8cff"));
        assertNull(ColorPicker.parseHex("12345"));
        assertNull(ColorPicker.parseHex("zzzzzz"));
    }

    @Test
    void catalogTabsNewAndRankedSearch() {
        assertEquals(ModCatalog.Tab.SERVER, ModCatalog.tabOf(bed), "server-specific beats HUD");
        assertEquals(ModCatalog.Tab.HUD, ModCatalog.tabOf(fps));
        assertEquals(ModCatalog.Tab.MECHANIC, ModCatalog.tabOf(zoom));
        assertEquals(Arrays.asList(fps), ModCatalog.list(mm, ModCatalog.Tab.HUD, ""));
        assertEquals(0, ModCatalog.count(mm, ModCatalog.Tab.NEW), "before the config loads nothing is new");

        // "speed" only matches a setting label; "zo" a name prefix; "description" every description.
        assertEquals(3, mm.search("speed").size());
        assertEquals(zoom, mm.search("zo").get(0));
        // Name matches beat description matches: Zoom only has an "s" in its description.
        assertEquals(Arrays.asList(fps, bed, zoom), mm.search("s"));
    }

    @Test
    void searchPutsNamePrefixesFirst() {
        Demo helper = new Demo("helper", "Server Helper", Mod.Category.HUD);
        mm.register(helper);
        // "help": "Bedwars Helper" and "Server Helper" match a word start, registration order between them.
        assertEquals(Arrays.asList(bed, helper), mm.search("help"));
        assertEquals(helper, mm.search("serv").get(0));
    }

    // ---- right shift menu --------------------------------------------------------------------------------------------

    @Test
    void hubLeadsToTheModWindow() {
        ClientGui gui = gui();
        frame(gui);
        assertTrue(r.has("KITE CLIENT"));
        assertTrue(r.has("Emotes") && r.has("Wardrobe"));
        assertTrue(r.textures.contains("aether:textures/gui/logo.png"));
        assertTrue(r.fonts.contains(UiRenderer.Font.DISPLAY));
        assertEquals(UiRenderer.Font.VANILLA, r.fonts.get(r.fonts.size() - 1), "the HUD keeps the vanilla font");
        click(gui, "Mods");
        assertTrue(r.has("Kite Client"));
        for (String tab : new String[] { "ALL", "NEW", "HUD", "SERVER", "MECHANIC", "Mods", "Settings", "Waypoints", "PROFILES", "Default" }) {
            assertTrue(r.has(tab), tab + " missing: " + r.strings());
        }
        assertTrue(r.has("FPS") && r.has("Zoom") && r.has("Bedwars Helper"));

        // Rows toggle; tabs filter.
        assertFalse(fps.enabled());
        click(gui, "FPS");
        assertTrue(fps.enabled());
        click(gui, "SERVER");
        assertTrue(r.has("Bedwars Helper"));
        assertFalse(r.has("FPS"));

        // Esc goes back to the hub, then closes.
        gui.keyDown(Keys.ESCAPE);
        frame(gui);
        assertTrue(r.has("KITE CLIENT"));
    }

    @Test
    void typingSearchesAndCardsOpenSettings() {
        ClientGui gui = gui().openPage("mods");
        frame(gui);
        for (char c : "zo".toCharArray()) gui.charTyped(c);
        frame(gui);
        assertTrue(r.has("Zoom"));
        assertFalse(r.has("FPS"));

        mm.client().layout.set(ClientSettings.ModLayout.CARDS);
        frame(gui);
        assertTrue(r.has("DISABLED"));
        click(gui, "Zoom");
        assertTrue(r.has("< Back"));
        assertTrue(r.has("Zoom description"));
        for (String l : new String[] { "Some flag", "Size", "Far distance", "Speed", "Text color", "Mode", "Message", "Key", "Do it" }) assertTrue(r.has(l), l);

        // The settings search filters options.
        for (char c : "spe".toCharArray()) gui.charTyped(c);
        frame(gui);
        assertTrue(r.has("Speed"));
        assertFalse(r.has("Some flag"));
    }

    @Test
    void everyInputTypeWorks() {
        ClientGui gui = gui().openSettings("fps");
        frame(gui);
        assertTrue(r.has("HUD") && r.has("Opacity"), "HUD mods get the universal options");

        click(gui, "Run");
        assertEquals(1, fps.clicks);

        // OPTION: opens a list; picking a value sets it.
        click(gui, "One");
        assertTrue(r.has("Three"));
        click(gui, "Three");
        assertEquals(Mode.THREE, fps.mode.val());

        // NUMBER_VALUE: + steps, typing commits on Enter (clamped).
        click(gui, "+");
        assertEquals(10, fps.far.val(), "a 0..100000 range steps by 10");
        click(gui, "10");
        for (char c : "250".toCharArray()) gui.charTyped(c);
        gui.keyDown(Keys.ENTER);
        assertEquals(250, fps.far.val());

        // TEXT_VALUE.
        click(gui, "gg");
        gui.keyDown(Keys.END); // the click put the caret where it landed (before "gg")
        gui.charTyped('!');
        gui.keyDown(Keys.ENTER);
        assertEquals("gg!", fps.text.str());

        // KEYBIND: click then press.
        click(gui, Keys.name(Keys.NONE));
        gui.keyDown(Keys.K);
        assertEquals(Keys.K, fps.key.code());

        // COLOR_PICKER: HEX / RGB / HSL tabs, typed values, chroma.
        click(gui, "#FFFFFF");
        for (String s : new String[] { "HEX", "RGB", "HSL", "Copy", "Paste", "Rainbow colour cycle" }) assertTrue(r.has(s), s + " missing: " + r.strings());
        // The hex field replaces its text when typed into, and applies as soon as the value is complete.
        click(gui, "FFFFFF");
        for (char c : "6c8cff".toCharArray()) gui.charTyped(c);
        frame(gui);
        assertEquals(0xFF6C8CFF, fps.color.base());
        gui.keyDown(Keys.ENTER);
        frame(gui);
        assertTrue(r.has("RGB   108, 140, 255"), r.strings().toString());
        // Paste a colour.
        host.clipboard = "#80123456";
        click(gui, "Paste");
        assertEquals(0x80123456, fps.color.base());
        // RGB channel typed value.
        click(gui, "RGB");
        assertTrue(r.has("R") && r.has("G") && r.has("B") && r.has("A"));
        click(gui, "18");
        for (char c : "255".toCharArray()) gui.charTyped(c);
        gui.keyDown(Keys.ENTER);
        frame(gui);
        assertEquals(0x80FF3456, fps.color.base());
        // HSL tab shows the colour in HSL.
        click(gui, "HSL");
        assertTrue(r.has("H") && r.has("S") && r.has("L"));
        // Saved colours: save the current one, change it, then click the saved swatch to get it back.
        int savedColor = fps.color.base();
        click(gui, "+ Save");
        assertEquals(java.util.Collections.singletonList(savedColor), mm.client().savedColors);
        assertFalse(r.has("+ Save"), "the button says it is saved");
        click(gui, "HEX");
        host.clipboard = "#112233";
        click(gui, "Paste");
        assertEquals(0xFF112233, fps.color.base());
        assertEquals(java.util.Collections.singletonList(savedColor), mm.client().savedColors, "pasting does not save");
        gg.aether.core.ClientSettings copy = new gg.aether.core.ClientSettings();
        copy.fromJson(mm.client().toJson());
        assertEquals(mm.client().savedColors, copy.savedColors, "saved colours persist in config.json");
        // Chroma.
        click(gui, "Rainbow colour cycle");
        assertTrue(fps.color.chroma());
        assertTrue(r.hasPrefix("Chroma  #"), "the swatch says chroma");
    }

    @Test
    void chromaCyclesAndSaves() {
        Setting.Color c = new Setting.Color("c", "C", 0xFFFF0000);
        assertEquals("#FFFF0000", c.toJson());
        c.chroma(true);
        c.speed(2);
        java.util.function.LongSupplier was = Setting.Color.clock;
        try {
            Setting.Color.clock = () -> 0L;
            int a = c.argb();
            Setting.Color.clock = () -> 1000L;
            int b = c.argb();
            assertNotEquals(a, b, "the hue moves over time");
            assertEquals(0xFF, b >>> 24, "alpha is kept");
            Setting.Color.clock = () -> 2000L;
            assertEquals(a, c.argb(), "one cycle every 2 s");
        } finally {
            Setting.Color.clock = was;
        }
        assertEquals(0xFFFF0000, c.base());
        Object json = c.toJson();
        Setting.Color back = new Setting.Color("c", "C", 0);
        back.fromJson(json);
        assertTrue(back.chroma());
        assertEquals(2.0, back.speed(), 1e-9);
        assertEquals(0xFFFF0000, back.base());
        back.fromJson("#FF00FF00");
        assertFalse(back.chroma(), "a plain hex turns chroma off");
        c.reset();
        assertFalse(c.chroma());
    }

    @Test
    void hslAndShortHex() {
        for (int c : new int[] { 0xFFFF0000, 0xFF6C8CFF, 0xFF123456, 0xFF808080, 0xFFFFFFFF, 0xFF000000 }) {
            float[] hsl = ColorPicker.toHsl(c);
            assertEquals(c, ColorPicker.fromHsl(hsl[0], hsl[1], hsl[2]), Integer.toHexString(c));
        }
        assertArrayEquals(new int[] { 0, 100, 50 }, ColorPicker.hsl(0xFFFF0000));
        assertEquals(Integer.valueOf(0xFFFFAA00), ColorPicker.parseHex("#fa0"));
        assertEquals(Integer.valueOf(0xFF6C8CFF), ColorPicker.parseHex(" 0x6C8CFF "));
        assertEquals("6C8CFF", ColorPicker.hex(0xFF6C8CFF));
        assertEquals("806C8CFF", ColorPicker.hex(0x806C8CFF));
    }

    @Test
    void iconSetRasterizes() {
        assertTrue(Icons.names().size() >= 60);
        for (String n : Icons.names()) {
            int[] m = Icons.mask(n, 24);
            int covered = 0, full = 0;
            for (int px : m) {
                int a = px >>> 24;
                if (a > 0) covered++;
                if (a == 255) full++;
            }
            assertTrue(covered > 20 && full > 5, n + " draws something");
            assertTrue(covered < m.length * 0.8, n + " isn't a filled square");
        }
        // A 2-unit stroke along y = 12 covers the middle rows fully and leaves the corners empty.
        int[] line = Icons.rasterize(new String[] { "M2 12 H22" }, 24);
        assertEquals(255, line[12 * 24 + 12] >>> 24);
        assertEquals(0, line[0] >>> 24);
        // Every mod falls back to an icon in the set.
        for (Mod m : mm.all()) assertTrue(Icons.has(m.icon()), m.id);
    }

    @Test
    void widgetsPlaySounds() {
        ClientGui gui = gui().openSettings("fps");
        frame(gui);
        host.sounds.clear();
        click(gui, "Run");
        assertEquals(Arrays.asList("click"), host.sounds);
        host.sounds.clear();
        click(gui, "One");
        assertEquals(Arrays.asList("open"), host.sounds, "opening a popup whooshes instead of clicking");
        host.sounds.clear();
        click(gui, "Two");
        assertEquals(Arrays.asList("select"), host.sounds);
        // Off: silent.
        mm.client().uiSounds.set(false);
        frame(gui);
        host.sounds.clear();
        click(gui, "Run");
        assertTrue(host.sounds.isEmpty());
    }

    @Test
    void accessibilityIsAClientSetting() {
        assertNull(mm.get("accessibility"));
        mm.client().reducedMotion.set(true);
        mm.client().uiScale.set(1.5);
        mm.tick(null);
        assertTrue(mm.overrides().reducedMotion);
        assertEquals(1.5f, mm.overrides().uiScale, 1e-6f);
        // Old profiles stored it as a mod; it moves over when config.json doesn't have it yet.
        java.util.Map<String, Object> settings = new java.util.LinkedHashMap<String, Object>();
        settings.put("colorblind", true);
        java.util.Map<String, Object> mod = new java.util.LinkedHashMap<String, Object>();
        mod.put("settings", settings);
        mm.client().migrateAccessibility(mod);
        assertTrue(mm.client().colorblind.on());
        // The General page lists it.
        ClientGui gui = gui().openPage("settings");
        frame(gui);
        // Reduced motion is on, so scrolling jumps.
        gui.scroll(r.w / 2f, r.h / 2f, -40);
        frame(gui);
        assertTrue(r.has("Colorblind-safe colors") && r.has("No nausea / portal wobble"), r.strings().toString());
        assertEquals("Accessibility", mm.client().sectionBefore(mm.client().reducedMotion));
        assertEquals("Sounds", mm.client().sectionBefore(mm.client().uiSounds));
    }

    @Test
    void profilesSidebarCreatesEditsAndCopies() {
        ClientGui gui = gui().openPage("mods");
        frame(gui);
        click(gui, "Save as new profile");
        assertEquals(2, mm.profiles().all().size());
        assertEquals("New profile", mm.profiles().active().name.str());
        assertTrue(r.has("Switch to it on servers"), "the editor opens for the new profile");
        click(gui, "Copy");
        assertNotNull(host.copied);
        assertTrue(host.copied.contains("aether-profile"));

        // Import from the clipboard adds a third.
        gui.keyDown(Keys.ESCAPE);
        frame(gui);
        host.clipboard = host.copied;
        click(gui, "Import profile");
        assertEquals(3, mm.profiles().all().size());

        // Clicking a profile switches to it.
        click(gui, "Default");
        assertEquals("Default", mm.profiles().active().name.str());
        assertTrue(saves > 0);
    }

    @Test
    void settingsTabAndOtherPagesRender() {
        ClientGui gui = gui().openPage("settings");
        frame(gui);
        assertTrue(r.has("Kite main menu") && r.has("Mod list layout") && r.has("Open Kite menu"));
        click(gui, "List");
        click(gui, "Cards");
        assertEquals(ClientSettings.ModLayout.CARDS, mm.client().layout.val());
        for (String p : new String[] { "keybinds", "developer", "waypoints", "wardrobe", "hub" }) {
            gui.openPage(p);
            frame(gui);
        }
        gui.openPage("keybinds");
        frame(gui);
        assertTrue(r.has("Keybinds"));
        gui.openPage("waypoints");
        frame(gui);
        assertTrue(r.has("The waypoints mod is not installed"));
    }

    @Test
    void wardrobeAndShopTabs() {
        mm.cosmetics().load(null);
        ClientGui gui = gui();
        frame(gui);
        assertTrue(r.has("Shop"), "the hub has a Shop tile");
        click(gui, "Shop");
        assertTrue(r.hasPrefix("Offline: the shop needs the Kite backend"), r.strings().toString());
        assertTrue(r.has("The shop is offline"));
        assertTrue(r.has("All"), "the shop has an All tab");

        click(gui, "Wardrobe");
        assertFalse(r.has("Import") || r.has("Folder"), "importing is for admins only");
        assertTrue(r.has("Capes") && r.has("None") && r.has("Yours"));
        click(gui, "Aether");
        assertEquals("cape-aether", mm.cosmetics().equippedId(gg.aether.core.cosmetics.Slot.CAPE));
        assertTrue(r.has("ON"), "the worn card is marked");
        click(gui, "Aether");
        assertNull(mm.cosmetics().equippedId(gg.aether.core.cosmetics.Slot.CAPE), "clicking it again takes it off");

        gui.openPage("store");
        frame(gui);
        assertTrue(r.hasPrefix("Offline: the shop"));
    }

    // ---- main menu --------------------------------------------------------------------------------------------------

    static final class MenuHost implements MainMenuView.Host {
        final List<String> calls = new ArrayList<String>();
        boolean modMenu, live;
        MainMenuView.Notice notice;
        @Override public boolean switchesLive() { return live; }
        @Override public MainMenuView.Notice pollNotice() {
            MainMenuView.Notice n = notice;
            notice = null;
            return n;
        }
        @Override public void singleplayer() { calls.add("singleplayer"); }
        @Override public void multiplayer() { calls.add("multiplayer"); }
        @Override public void minecraftOptions() { calls.add("options"); }
        @Override public void realms() { calls.add("realms"); }
        @Override public boolean hasModMenu() { return modMenu; }
        @Override public void modMenu() { calls.add("modmenu"); }
        @Override public void quit() { calls.add("quit"); }
        @Override public void clientSettings() { calls.add("client"); }
        @Override public void wardrobe() { calls.add("wardrobe"); }
        @Override public void store() { calls.add("store"); }
        @Override public void useVanillaMenu() { calls.add("vanilla"); }
        @Override public List<MainMenuView.Account> accounts() {
            return Arrays.asList(new MainMenuView.Account("Tester", "u1", "microsoft", true), new MainMenuView.Account("Alt", "u2", "offline", false));
        }
        @Override public String switchAccount(MainMenuView.Account a) {
            calls.add("switch:" + a.name);
            return "Restarting as " + a.name;
        }
        @Override public String versionLine() { return "Kite Client 1.21.11 (v0.1.0-7)"; }
    }

    @Test
    void mainMenuLayoutAndActions() {
        MenuHost mh = new MenuHost();
        MainMenuView v = new MainMenuView(mm, mh, MenuFeed.preview());
        assertTrue(v.panorama());
        frame(v);
        // Coins are "-" until the backend reports a balance.
        for (String s : new String[] { "KITE", "Singleplayer", "Multiplayer", "Store", "Tester", "-", "Kite Client 1.21.11 (v0.1.0-7)",
                MainMenuView.DISCLAIMER, "UPDATE" }) {
            assertTrue(r.has(s), s + " missing: " + r.strings());
        }
        // The bottom row is icons only; labels show as tooltips.
        assertEquals(Arrays.asList("Kite settings", "Wardrobe", "Chat", "Minecraft settings", "Realms"), new ArrayList<String>(v.iconButtons.keySet()));
        assertFalse(r.has("Wardrobe"));
        float[] wardrobe = v.iconButtons.get("Wardrobe");
        v.mouseMoved(wardrobe[0], wardrobe[1]);
        frame(v);
        assertTrue(r.has("Wardrobe"), "hover shows the tooltip");
        mh.modMenu = true;
        frame(v);
        assertTrue(v.iconButtons.containsKey("Mod Menu"));
        // The faint corner text is drawn smaller than the rest.
        assertTrue(r.find(MainMenuView.DISCLAIMER).y > r.find("Singleplayer").y);

        click(v, "Singleplayer");
        click(v, "Multiplayer");
        clickIcon(v, "Minecraft settings");
        clickIcon(v, "Realms");
        clickIcon(v, "Mod Menu");
        clickIcon(v, "Kite settings");
        clickIcon(v, "Wardrobe");
        assertEquals(Arrays.asList("singleplayer", "multiplayer", "options", "realms", "modmenu", "client", "wardrobe"), mh.calls);

        click(v, "Store");
        assertEquals("store", mh.calls.get(mh.calls.size() - 1), "Store opens the in-game shop");

        // Account switching needs two clicks.
        click(v, "Tester");
        assertTrue(r.has("Alt"));
        click(v, "Alt");
        assertTrue(r.has("Click again to restart"));
        click(v, "Click again to restart");
        assertTrue(mh.calls.contains("switch:Alt"));
        assertTrue(r.has("Restarting as Alt"));
    }

    @Test
    void liveAccountSwitchReportsBack() {
        MenuHost mh = new MenuHost();
        mh.live = true;
        MainMenuView v = new MainMenuView(mm, mh, MenuFeed.preview());
        frame(v);
        click(v, "Tester");
        click(v, "Alt");
        assertTrue(r.has("Click again to switch"));
        assertTrue(r.hasPrefix("Switching signs you in"));
        click(v, "Click again to switch");
        assertTrue(mh.calls.contains("switch:Alt"));
        mh.notice = new MainMenuView.Notice("Signed in as Alt", true);
        frame(v);
        assertTrue(r.has("Signed in as Alt"));
    }

    @Test
    void carouselDotSwipesWhileHovered() throws InterruptedException {
        MainMenuView v = new MainMenuView(mm, new MenuHost(), MenuFeed.preview());
        frame(v);
        assertTrue(r.has("A brand new menu"));
        float[] box = v.carousel;
        // Hover the card (pauses auto-advance), then pick the third slide.
        v.mouseMoved(box[0] + 10, box[1] + 10);
        float[] dot = v.dots.get(2);
        v.mouseDown(dot[0], dot[1], 0);
        v.mouseUp(dot[0], dot[1], 0);
        v.mouseMoved(box[0] + 10, box[1] + 10);
        long until = System.currentTimeMillis() + MainMenuView.SWIPE_MS + 150;
        while (System.currentTimeMillis() < until) {
            frame(v);
            Thread.sleep(20);
        }
        frame(v);
        assertTrue(r.has("Share your setup"), r.strings().toString());
        assertFalse(r.has("A brand new menu"), "the swipe finished although the mouse stayed on the card");
        Rec.Drawn title = r.find("Share your setup");
        assertTrue(title.x > box[0] && title.x < box[0] + box[2] / 2f, "the new card sits in place, not shifted");
        assertEquals(box[1] + 7, title.y, 0.01f);
    }

    @Test
    void mainMenuFitsSmallScreens() {
        // 1080p at GUI scale 4.
        r.w = 480;
        r.h = 270;
        MainMenuView v = new MainMenuView(mm, new MenuHost(), MenuFeed.preview());
        frame(v);
        float rowTop = v.iconButtons.get("Wardrobe")[1] - MainMenuView.ICON_BTN / 2f;
        for (String s : new String[] { "Singleplayer", "Multiplayer", "Store" }) assertTrue(r.find(s).y + 9 <= rowTop, s + " overlaps the bottom row");
        if (r.has("New in the store this week")) assertTrue(r.find("New in the store this week").y + 9 <= rowTop, "promo overlaps the bottom row");
        assertTrue(r.has("UPDATE"), "the carousel still fits beside the centre column");
    }

    @Test
    void mainMenuMusicFollowsTheSetting() {
        MainMenuView v = new MainMenuView(mm, new MenuHost(), null);
        assertFalse(v.muteMusic());
        mm.client().menuMusic.set(false);
        assertTrue(v.muteMusic());
    }

    @Test
    void profileIconsCycle() {
        Profiles.Profile p = mm.profiles().active();
        assertEquals(Profiles.DEFAULT_ICON, p.icon.str());
        mm.profiles().cycleIcon(p, 1);
        assertEquals(Profiles.ICONS[1], p.icon.str());
        mm.profiles().cycleIcon(p, -2);
        assertEquals(Profiles.ICONS[Profiles.ICONS.length - 1], p.icon.str());
    }

    @Test
    void keybindControlUnbindsAndRefusesKeys() {
        final Ui ui = new Ui(null);
        final Setting.Key k = new Setting.Key("k", "Thing", Keys.K).refuse(c -> c == Keys.W);
        Runnable frame = () -> {
            r.clear();
            ui.begin(r, true);
            ui.keybind(k, 0, 0, 100, false);
        };
        frame.run();
        ui.mouseDown(10, 8, 0);
        assertTrue(ui.typing(), "listening for a key");
        assertTrue(ui.keyDown(Keys.ESCAPE));
        assertEquals(Keys.K, k.code(), "Escape cancels");
        ui.mouseDown(10, 8, 0);
        ui.keyDown(Keys.W);
        assertEquals(Keys.K, k.code(), "refused key");
        assertNotNull(ui.flashText());
        ui.mouseDown(10, 8, 0);
        ui.keyDown(Keys.BACKSPACE);
        assertEquals(Keys.NONE, k.code(), "Backspace unbinds");
        k.set(Keys.J);
        frame.run();
        ui.mouseDown(10, 8, 1);
        assertEquals(Keys.NONE, k.code(), "right-click unbinds");
        k.set(Keys.J);
        frame.run();
        ui.mouseDown(92, 8, 0); // the "x" at the right end
        assertEquals(Keys.NONE, k.code(), "x unbinds");
        assertFalse(ui.typing());
    }

    @Test
    void textFieldEditsAtTheCaret() {
        final Ui ui = new Ui(null);
        final Ui.Text t = new Ui.Text("abcdef", 10);
        Runnable frame = () -> {
            r.clear();
            ui.begin(r, true);
            ui.textField(t, t, 0, 0, 200, 16, "", null, null);
        };
        frame.run();
        // Text starts at x 4 and each character is 5 px wide (Rec): x 15 is between "b" and "c".
        ui.mouseDown(15, 8, 0);
        ui.mouseUp(15, 8, 0);
        frame.run();
        ui.charTyped('X');
        assertEquals("abXcdef", t.value);
        ui.keyDown(Keys.LEFT);
        ui.keyDown(Keys.LEFT);
        ui.charTyped('Y');
        assertEquals("aYbXcdef", t.value);
        ui.keyDown(Keys.END);
        ui.keyDown(Keys.BACKSPACE);
        ui.keyDown(Keys.HOME);
        ui.keyDown(Keys.DELETE);
        assertEquals("YbXcde", t.value);
        // Shift+arrows select; typing replaces the selection.
        ui.keyDown(Keys.LEFT_SHIFT);
        ui.keyDown(Keys.RIGHT);
        ui.keyDown(Keys.RIGHT);
        ui.keyUp(Keys.LEFT_SHIFT);
        ui.charTyped('Z');
        assertEquals("ZXcde", t.value);
        // Clicking the focused field again moves the caret and keeps editing (no blur).
        frame.run();
        ui.mouseDown(5, 8, 0);
        ui.mouseUp(5, 8, 0);
        assertTrue(ui.focused(t));
        ui.charTyped('Q');
        assertEquals("QZXcde", t.value);
        // Ctrl+Backspace deletes a word; Ctrl+A then typing replaces everything.
        ui.keyDown(Keys.END);
        ui.keyDown(Keys.LEFT_CONTROL);
        ui.keyDown(Keys.BACKSPACE);
        assertEquals("", t.value);
        t.value = "one two";
        ui.keyDown(Keys.END);
        ui.keyDown(Keys.BACKSPACE);
        assertEquals("one ", t.value);
        ui.keyDown(Keys.A);
        ui.keyUp(Keys.LEFT_CONTROL);
        ui.charTyped('k');
        assertEquals("k", t.value);
        // The field's max length stops typing instead of cutting the end off.
        t.value = "0123456789";
        ui.keyDown(Keys.HOME);
        ui.charTyped('x');
        assertEquals("0123456789", t.value);
    }
}
