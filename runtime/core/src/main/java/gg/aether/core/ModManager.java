package gg.aether.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Registry + lifecycle + gating (server disable list, competitive mode) for all mods. */
public final class ModManager {
    private final Map<String, Mod> mods = new LinkedHashMap<String, Mod>();
    private final Set<String> serverDisabled = new HashSet<String>();
    private final EventBus bus = new EventBus();
    private final Overrides overrides = new Overrides();
    private final Notifications notifications = new Notifications();
    private final gg.aether.core.cosmetics.Cosmetics cosmetics = new gg.aether.core.cosmetics.Cosmetics();
    private final Profiles profiles = new Profiles();
    private final Set<String> favorites = new LinkedHashSet<String>();
    /** Mods the player touched this session, most recent last. */
    private final LinkedHashSet<String> recent = new LinkedHashSet<String>();
    private final List<String> plugins = new ArrayList<String>();
    private final ClientSettings client = new ClientSettings();
    private boolean editing;

    /**
     * Key that opens the mod menu. Listed in the keybind manager and checked for conflicts like any mod key. It can
     * be rebound or unbound, but never to a key that would lock the player out of the menu ({@link #refusedMenuKey});
     * {@link #opensMenu} keeps Right Shift as a way back in.
     */
    public final Setting.Key menuKey = new Setting.Key("menuKey", "Open Kite menu", Keys.RIGHT_SHIFT).refuse(ModManager::refusedMenuKey);

    /** Opens the menu while {@link #menuKey} is unbound (unless a mod uses it), and with Ctrl held always. */
    public static final int FALLBACK_MENU_KEY = Keys.RIGHT_SHIFT;
    private boolean ctrlDown;

    /**
     * Keys the menu key may not use: Escape closes menus, Enter/Tab/T// open or send chat, and movement or inventory
     * keys would open the menu while playing.
     */
    public static boolean refusedMenuKey(int code) {
        switch (code) {
            case Keys.ESCAPE: case Keys.ENTER: case Keys.KP_ENTER: case Keys.TAB: case Keys.SPACE:
            case Keys.W: case Keys.A: case Keys.S: case Keys.D: case Keys.E: case Keys.T: case Keys.SLASH:
                return true;
            default:
                return code < 0 && code != Keys.NONE;
        }
    }

    /**
     * True when pressing {@code code} (in game, no screen open) should open the menu: the menu key, Right Shift while
     * the menu key is unbound and no active mod binds Right Shift, or Ctrl + Right Shift no matter what. The adapters
     * call this before handing the key to mods, so the menu can never become unreachable.
     */
    public boolean opensMenu(int code) {
        if (code == Keys.NONE) return false;
        if (menuKey.bound() && code == menuKey.code()) return true;
        if (code != FALLBACK_MENU_KEY) return false;
        return ctrlDown || (!menuKey.bound() && !boundByMod(code));
    }

    /** Name of the key that opens the menu right now, for hints ("RShift"). */
    public String menuKeyName() {
        if (menuKey.bound()) return Keys.name(menuKey.code());
        return boundByMod(FALLBACK_MENU_KEY) ? "Ctrl+" + Keys.name(FALLBACK_MENU_KEY) : Keys.name(FALLBACK_MENU_KEY);
    }

    /** True if an active mod has a keybind on {@code code}. */
    private boolean boundByMod(int code) {
        for (Mod m : mods.values()) {
            if (!active(m)) continue;
            for (Setting<?> st : m.settings()) if (st instanceof Setting.Key && ((Setting.Key) st).code() == code) return true;
        }
        return false;
    }

    public EventBus events() { return bus; }
    /** Read by the version adapter every frame. */
    public Overrides overrides() { return overrides; }
    public Notifications notifications() { return notifications; }
    /** Cosmetics catalog, loadout and display state (global, not per profile). */
    public gg.aether.core.cosmetics.Cosmetics cosmetics() { return cosmetics; }
    /** Client-wide options (main menu, menu layout, competitive mode). */
    public ClientSettings client() { return client; }

    public void register(Mod m) {
        if (mods.containsKey(m.id)) throw new IllegalArgumentException("duplicate mod id " + m.id);
        m.bind(this, bus, overrides, notifications, new ModData());
        mods.put(m.id, m);
    }

    public Mod get(String id) { return mods.get(id); }
    public List<Mod> all() { return Collections.unmodifiableList(new ArrayList<Mod>(mods.values())); }

    /**
     * Mods matching {@code query}, best first: name prefix, then name, then description/category, then setting
     * labels (so "gap" still finds the crosshair). Registration order within each rank.
     */
    public List<Mod> search(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return new ArrayList<Mod>(mods.values());
        List<List<Mod>> ranks = new ArrayList<List<Mod>>();
        for (int i = 0; i < 4; i++) ranks.add(new ArrayList<Mod>());
        for (Mod m : mods.values()) {
            int r = rank(m, q);
            if (r >= 0) ranks.get(r).add(m);
        }
        List<Mod> out = new ArrayList<Mod>();
        for (List<Mod> l : ranks) out.addAll(l);
        return out;
    }

    /** 0 name starts with (or a word of it does), 1 name contains, 2 description/category/id, 3 a setting label; -1 no match. */
    static int rank(Mod m, String q) {
        String name = m.name.toLowerCase(Locale.ROOT);
        if (name.startsWith(q) || name.contains(" " + q)) return 0;
        if (name.contains(q)) return 1;
        if (m.description.toLowerCase(Locale.ROOT).contains(q) || m.category.name().toLowerCase(Locale.ROOT).contains(q) || m.id.contains(q)) return 2;
        for (Setting<?> st : m.settings()) if (st.label.toLowerCase(Locale.ROOT).contains(q)) return 3;
        return -1;
    }

    // ---- seen ("NEW" badge) -------------------------------------------------------------------------------------

    private final Set<String> seen = new LinkedHashSet<String>();
    private boolean seenLoaded;

    /** False for mods added since the player last looked (the menu's NEW tab). */
    public boolean seen(Mod m) { return !seenLoaded || seen.contains(m.id); }

    public void markSeen(String id) { if (mods.containsKey(id)) seen.add(id); }

    /** Seen ids from the config; null on first run, when every mod counts as seen. */
    void loadSeen(java.util.Collection<String> ids) {
        seen.clear();
        if (ids == null) seen.addAll(mods.keySet());
        else seen.addAll(ids);
        seenLoaded = true;
    }

    Set<String> seenIds() {
        if (!seenLoaded) return new LinkedHashSet<String>(mods.keySet());
        return Collections.unmodifiableSet(seen);
    }

    // ---- favorites, recents, resets -----------------------------------------------------------------------------

    public boolean favorite(Mod m) { return favorites.contains(m.id); }

    public void setFavorite(String id, boolean on) {
        if (on) favorites.add(id); else favorites.remove(id);
    }

    public Set<String> favorites() { return Collections.unmodifiableSet(favorites); }

    /** Record that the player changed a mod (toggle, settings), for the "Recent" filter. */
    public void touch(String id) {
        markSeen(id);
        recent.remove(id);
        recent.add(id);
    }

    /** Most recently touched first. */
    public List<Mod> recent() {
        List<Mod> out = new ArrayList<Mod>();
        for (String id : recent) if (mods.containsKey(id)) out.add(0, mods.get(id));
        return out;
    }

    /** Settings and HUD layout of every mod in {@code c} (all mods when null) back to defaults. */
    public void resetCategory(Mod.Category c) {
        for (Mod m : mods.values()) if (c == null || m.category == c) m.resetSettings();
    }

    // ---- profiles ---------------------------------------------------------------------------------------------------

    public Profiles profiles() { return profiles; }

    /** Store the live state into the current profile and load {@code p}. */
    public void switchProfile(Profiles.Profile p, boolean byHand) {
        if (p == null) return;
        if (p == profiles.active()) {
            if (byHand) profiles.setActive(p, true);
            return;
        }
        Profiles.Profile old = profiles.active();
        old.mods = ConfigStore.snapshot(this);
        ConfigStore.applyMods(this, p.mods, true);
        profiles.setActive(p, byHand);
    }

    /** New profile copied from the live state (not switched to). */
    public Profiles.Profile createProfile(String name) {
        return profiles.create(name, ConfigStore.snapshot(this));
    }

    public boolean deleteProfile(Profiles.Profile p) { return profiles.remove(p); }

    /** A profile as its exportable JSON file (the live state for the active one). */
    public Map<String, Object> exportProfile(Profiles.Profile p) {
        return ProfileFiles.toJson(p, p == profiles.active() ? ConfigStore.snapshot(this) : profiles.stored(p));
    }

    /**
     * Adds a profile from exported JSON text (see {@link ProfileFiles}). Returns the new profile, or null when the
     * text is not a profile.
     */
    public Profiles.Profile importProfile(String json) {
        Map<String, Object> root;
        try {
            root = Json.parseObject(json == null ? "" : json.trim());
        } catch (RuntimeException e) {
            return null;
        }
        return ProfileFiles.addFrom(profiles, root);
    }

    // ---- plugins ----------------------------------------------------------------------------------------------------

    /** Let third-party plugins register their mods. A plugin that throws is skipped and reported in {@link #failures}. */
    public void loadPlugins(Iterable<? extends AetherPlugin> found) {
        for (AetherPlugin p : found) {
            String name;
            try {
                name = p.name();
            } catch (RuntimeException e) {
                name = p.getClass().getName();
            }
            try {
                p.register(this);
                plugins.add(name);
            } catch (RuntimeException | LinkageError e) {
                failures.add("plugin " + name + ": " + e);
            }
        }
    }

    public List<String> plugins() { return Collections.unmodifiableList(plugins); }

    /** True if the mod may run right now given server restrictions and competitive mode. */
    public boolean allowed(Mod m) {
        if (serverDisabled.contains(m.id)) return false;
        return !(client.competitive.on() && !m.competitiveSafe);
    }

    public boolean active(Mod m) { return m.enabled() && allowed(m); }

    public void setEnabled(String id, boolean on) {
        Mod m = mods.get(id);
        if (m != null) m.setEnabledInternal(on);
    }

    /** Enable/disable from the menu: also remembered for the "Recent" filter. */
    public void toggleByUser(String id) {
        Mod m = mods.get(id);
        if (m == null) return;
        touch(id);
        m.setEnabledInternal(!m.enabled());
    }

    /** Servers may send a list of mod ids that must be off while connected (like Lunar's mod-disable packet). */
    public void setServerDisabled(Set<String> ids) {
        serverDisabled.clear();
        serverDisabled.addAll(ids);
    }

    public void clearServerDisabled() { serverDisabled.clear(); }

    /** HUD editor open: every active HUD mod draws (with placeholders), ignoring screenshot / streamer hiding. */
    public void setEditing(boolean on) { editing = on; }
    public boolean editing() { return editing; }

    public void setCompetitiveMode(boolean on) { client.competitive.set(on); }
    public boolean competitiveMode() { return client.competitive.on(); }

    public void tick(GameState s) {
        Profiles.Profile auto = s == null ? null : profiles.autoSelect(s.serverAddress());
        if (auto != null) {
            switchProfile(auto, false);
            notifications.info(s.nowMillis(), "Profile: " + auto.name.str());
        }
        overrides.reset();
        client.apply(overrides);
        notifications.resetConfig();
        cosmetics.tick(host, System.currentTimeMillis());
        for (Mod m : mods.values()) {
            if (!active(m)) continue;
            try {
                m.onTick(s);
            } catch (RuntimeException e) {
                fail(m, e);
            }
        }
    }

    /** Forward a key press/release (GLFW code) to every active mod. */
    public void key(int code, boolean down) {
        if (code == Keys.LEFT_CONTROL || code == Keys.RIGHT_CONTROL) ctrlDown = down;
        bus.post(new Events.KeyInput(code, down));
        for (Mod m : mods.values()) {
            if (!active(m)) continue;
            try {
                m.handleKey(code, down);
            } catch (RuntimeException e) {
                fail(m, e);
            }
        }
    }

    /** Keys bound to more than one mod setting, e.g. "key 67 -> [Zoom: Zoom key, Foo: Bar]". */
    public List<String> keybindConflicts() {
        Map<Integer, List<String>> byKey = new LinkedHashMap<Integer, List<String>>();
        if (menuKey.code() != Keys.NONE) byKey.put(menuKey.code(), new ArrayList<String>(Collections.singletonList("Kite: " + menuKey.label)));
        for (Mod m : mods.values()) {
            for (Setting<?> st : m.settings()) {
                if (!(st instanceof Setting.Key) || ((Setting.Key) st).code() == Keys.NONE) continue;
                Integer code = ((Setting.Key) st).code();
                List<String> l = byKey.get(code);
                if (l == null) byKey.put(code, l = new ArrayList<String>());
                l.add(m.name + ": " + st.label);
            }
        }
        List<String> out = new ArrayList<String>();
        for (Map.Entry<Integer, List<String>> e : byKey.entrySet()) if (e.getValue().size() > 1) out.add("key " + e.getKey() + " -> " + e.getValue());
        return out;
    }

    public void renderHud(UiRenderer r, GameState s) {
        if (overrides.hideAllHud && !editing) return;
        for (Mod m : mods.values()) {
            if (m.hud() != null) m.hud().beginFrame();
            if (!active(m) || m.hud() == null) continue;
            if (!editing && (m.sensitive() && overrides.streamerMode || m.hud().hiddenBy(s))) continue;
            m.preview = editing;
            try {
                m.renderHud(r, s);
            } catch (RuntimeException e) {
                fail(m, e);
            } finally {
                m.preview = false;
            }
        }
    }

    /** Draw queued toasts. Call after {@link #renderHud}; separate so adapters can draw them above their own UI. */
    public void renderNotifications(UiRenderer r, GameState s) {
        if (!overrides.hideNotifications && !overrides.hideAllHud) notifications.render(r, s.nowMillis());
    }

    private final List<String> failures = new ArrayList<String>();

    /** A mod that throws is switched off rather than crashing the game every frame. */
    private void fail(Mod m, RuntimeException e) {
        failures.add(m.id + ": " + e);
        m.setEnabledInternal(false);
    }

    public List<String> failures() { return new ArrayList<String>(failures); }

    // ---- host services ------------------------------------------------------------------------------------------

    private java.nio.file.Path dataDir;
    private ClientHost host = new ClientHost() { };

    public void setHost(ClientHost h) { host = h == null ? new ClientHost() { } : h; }
    public ClientHost host() { return host; }
    private java.util.function.Consumer<gg.aether.core.ui.View> viewOpener;

    /** Where mods may keep larger files (config/aether/data). */
    public void setDataDir(java.nio.file.Path dir) { dataDir = dir; }
    public java.nio.file.Path dataDir() { return dataDir; }

    /** The adapter's way of showing a full-screen view. */
    public void setViewOpener(java.util.function.Consumer<gg.aether.core.ui.View> opener) { viewOpener = opener; }

    public void openView(gg.aether.core.ui.View v) { if (viewOpener != null && v != null) viewOpener.accept(v); }

    /** 3D overlays of every active mod (skipped in screenshot mode). */
    public void renderWorld(WorldRenderer w, GameState s) {
        if (overrides.hideAllHud) return;
        for (Mod m : mods.values()) {
            if (!active(m)) continue;
            try {
                m.renderWorld(w, s);
            } catch (RuntimeException e) {
                fail(m, e);
            }
        }
    }

    /** True if any mod's persistent data changed since the last call (hosts save now and then). */
    public boolean dataDirty() {
        boolean any = false;
        for (Mod m : mods.values()) any |= m.data.takeDirty();
        return any;
    }
}
