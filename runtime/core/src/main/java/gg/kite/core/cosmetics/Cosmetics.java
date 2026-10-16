package gg.kite.core.cosmetics;

import gg.kite.core.ClientHost;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cosmetics state for the whole client: the catalog, what the player wears (global, not per profile), display toggles
 * and what other client users wear (from the backend). Adapters ask {@link #forPlayer} while rendering; the menu edits
 * the loadout. Everything except the sync worker runs on the client thread.
 */
public final class Cosmetics {
    /** The client badge: the brand's diamond kite, drawn before the names of client users (1.21.11+ draws the kite mark itself). */
    public static final String BADGE = "◆";
    public static final int BADGE_COLOR = 0xFF8F86FF;

    /** What one player shows this frame. */
    public static final class Equipped {
        public static final Equipped NONE = new Equipped(new EnumMap<Slot, Cosmetic>(Slot.class), false);

        private final EnumMap<Slot, Cosmetic> items;
        public final boolean badge;

        Equipped(EnumMap<Slot, Cosmetic> items, boolean badge) {
            this.items = items;
            this.badge = badge;
        }

        public Cosmetic get(Slot s) { return items.get(s); }
        public boolean isEmpty() { return items.isEmpty(); }
        public java.util.Collection<Cosmetic> all() { return Collections.unmodifiableCollection(items.values()); }
    }

    /** Another player's data from the backend. */
    static final class Remote {
        final boolean badge;
        final Map<Slot, String> loadout;

        Remote(boolean badge, Map<Slot, String> loadout) {
            this.badge = badge;
            this.loadout = loadout;
        }
    }

    private final CosmeticRegistry registry = new CosmeticRegistry();
    private final Shop shop = new Shop(this);
    private final EnumMap<Slot, String> loadout = new EnumMap<Slot, String>(Slot.class);
    /** Menu try-on for the local player: a cosmetic id, or "" to try on nothing. */
    private final EnumMap<Slot, String> preview = new EnumMap<Slot, String>(Slot.class);
    private final Map<String, Remote> remote = new ConcurrentHashMap<String, Remote>();
    private boolean show = true, showOthers = true, badges = true, hideUnderArmor = true, wavyCapes = true;
    private volatile String self = "";
    private int version;
    private Equipped ownCache;
    private int ownCacheVersion = -1;
    private CosmeticsSync sync;
    private boolean syncResolved;
    /**
     * The shared loadout ({@link #sharedLoadout()} form) as of the last agreement with the backend, or null before the
     * first. Local edits are what differs from it; saved so edits made while offline still win at the next start.
     */
    private Map<String, Object> synced;
    /** Set by the sync worker when published cosmetics were downloaded or withdrawn; the client thread reloads. */
    private volatile boolean remoteChanged;

    void remoteChanged() { remoteChanged = true; }

    /** Built-in cosmetics this account owns on the backend; null while unknown (offline, not signed in yet). */
    private volatile java.util.Set<String> owned;

    public CosmeticRegistry registry() { return registry; }

    /** The in-game store (catalog, coins, buying). */
    public Shop shop() { return shop; }

    CosmeticsSync sync() { return sync; }

    /** Load the built-in set and the player's folder. */
    public void load(Path localDir) {
        registry.load(localDir);
        version++;
    }

    // ---- toggles ----------------------------------------------------------------------------------------------------

    public boolean showAll() { return show; }
    public void setShowAll(boolean on) { show = on; version++; }
    public boolean showOthers() { return showOthers; }
    public void setShowOthers(boolean on) { showOthers = on; }
    public boolean badges() { return badges; }
    public void setBadges(boolean on) { badges = on; version++; }
    /** Hats, ears and boots hide under a helmet / boots; wings hide under an elytra. */
    public boolean hideUnderArmor() { return hideUnderArmor; }
    public void setHideUnderArmor(boolean on) { hideUnderArmor = on; }
    /** Capes move like cloth ({@link WavyCape}) instead of vanilla's stiff board; applies to every cape, Mojang ones too. */
    public boolean wavyCapes() { return wavyCapes; }
    public void setWavyCapes(boolean on) { wavyCapes = on; }

    // ---- loadout ----------------------------------------------------------------------------------------------------

    /** Equip {@code id} in its slot, or clear {@code slot} when {@code id} is null. Unknown ids are ignored. */
    public void equip(Slot slot, String id) {
        if (id == null) {
            if (loadout.remove(slot) != null) version++;
            return;
        }
        Cosmetic c = registry.get(id);
        if (c == null || c.slot != slot || id.equals(loadout.get(slot))) return;
        loadout.put(slot, id);
        version++;
    }

    /** The equipped cosmetic id in {@code slot} (it may be missing from the catalog), or null. */
    public String equippedId(Slot slot) { return loadout.get(slot); }

    public Cosmetic equipped(Slot slot) { return registry.get(loadout.get(slot)); }

    /** Menu hover: show {@code id} (or nothing, for "") on the local player instead of the equipped one. */
    public void preview(Slot slot, String id) {
        String old = preview.put(slot, id == null ? "" : id);
        if (!preview.get(slot).equals(old)) version++;
    }

    /** Try on {@code id} (or nothing, for "") in {@code slot} and nothing else; cheap to call every frame. */
    public void previewOnly(Slot slot, String id) {
        String v = id == null ? "" : id;
        if (preview.size() == 1 && v.equals(preview.get(slot))) return;
        preview.clear();
        preview.put(slot, v);
        version++;
    }

    public void clearPreview() {
        if (preview.isEmpty()) return;
        preview.clear();
        version++;
    }

    /** Bumped on every loadout / toggle change (the sync pushes when it moves). */
    int version() { return version; }

    // ---- lookup (render thread) -------------------------------------------------------------------------------------

    /** The local player's UUID (normalised), "" before the first tick. */
    public String self() { return self; }

    public boolean isSelf(String uuid) { return !self.isEmpty() && self.equals(norm(uuid)); }

    /** What {@code uuid} shows. The local player always counts as a client user; others only via the backend. */
    public Equipped forPlayer(String uuid) {
        String id = norm(uuid);
        if (id.isEmpty()) return Equipped.NONE;
        if (id.equals(self)) return own();
        Remote r = remote.get(id);
        if (r == null) return Equipped.NONE;
        EnumMap<Slot, Cosmetic> items = new EnumMap<Slot, Cosmetic>(Slot.class);
        if (show && showOthers) {
            for (Map.Entry<Slot, String> e : r.loadout.entrySet()) {
                Cosmetic c = registry.get(e.getValue());
                if (c != null && c.builtin && c.slot == e.getKey()) items.put(e.getKey(), c);
            }
        }
        return new Equipped(items, badges && r.badge);
    }

    /** The local player's cosmetics (with menu try-ons). */
    public Equipped own() {
        if (ownCache != null && ownCacheVersion == version) return ownCache;
        EnumMap<Slot, Cosmetic> items = new EnumMap<Slot, Cosmetic>(Slot.class);
        if (show) {
            for (Slot s : Slot.values()) {
                String id = preview.containsKey(s) ? preview.get(s) : loadout.get(s);
                Cosmetic c = registry.get(id);
                if (c != null && c.slot == s) items.put(s, c);
            }
        }
        ownCache = new Equipped(items, badges);
        ownCacheVersion = version;
        return ownCache;
    }

    /** Whether {@code uuid}'s name gets the client badge. */
    public boolean badge(String uuid) {
        if (!badges) return false;
        String id = norm(uuid);
        if (id.isEmpty()) return false;
        if (id.equals(self)) return true;
        Remote r = remote.get(id);
        return r != null && r.badge;
    }

    /** Client users the backend reported (excluding the local player). */
    public int remoteUsers() { return remote.size(); }

    void setRemote(Map<String, Remote> users, java.util.Collection<String> queried) {
        for (String q : queried) if (!users.containsKey(q)) remote.remove(q);
        remote.putAll(users);
    }

    /** "8667ba71-b85a-4004-af54-457a9734eed7" and "8667ba71b85a4004af54457a9734eed7" are the same player. */
    public static String norm(String uuid) { return uuid == null ? "" : uuid.replace("-", "").toLowerCase(Locale.ROOT).trim(); }

    // ---- backend sync -----------------------------------------------------------------------------------------------

    Map<String, Object> synced() { return synced; }
    void synced(Map<String, Object> s) { synced = s == null ? null : new LinkedHashMap<String, Object>(s); }

    void setOwned(java.util.Set<String> ids) { owned = ids; }

    /** False only for a built-in cosmetic the backend says this account does not own (buy it in the launcher store). */
    public boolean owns(Cosmetic c) {
        java.util.Set<String> o = owned;
        return c == null || !c.builtin || o == null || o.contains(c.id);
    }

    /**
     * Take the backend's loadout (slot id -> cosmetic id or null). A slot holding one of the player's own local
     * cosmetics (never shared) keeps it when the backend has nothing there.
     */
    void adopt(Map<String, Object> server) {
        for (Slot s : Slot.values()) {
            Object id = server.get(s.id());
            Cosmetic mine = registry.get(loadout.get(s));
            if (id instanceof String) loadout.put(s, (String) id);
            else if (mine == null || mine.builtin) loadout.remove(s);
        }
        version++;
    }

    /** Sync with the backend at {@code url} (null disables); by default from -Dkite.backend or KITE_BACKEND_URL. */
    public void setBackend(String url) {
        if (sync != null) sync.close();
        sync = url == null || url.trim().isEmpty() ? null : new CosmeticsSync(url.trim(), this);
        syncResolved = true;
    }

    /** Called every client tick. */
    public void tick(ClientHost host, long now) {
        String s = host == null ? null : host.sessionUuid();
        self = norm(s);
        if (!syncResolved) {
            String url = System.getProperty("kite.backend");
            if (url == null || url.trim().isEmpty()) url = System.getenv("KITE_BACKEND_URL");
            setBackend(url);
        }
        if (sync != null && host != null) sync.tick(host, now);
        if (remoteChanged) {
            remoteChanged = false;
            load(registry.localDir());
        }
        shop.autoRefresh(now);
    }

    /** One line for the menu: sync state and how many client users are around. */
    public String status() {
        if (sync == null) return "Offline: no backend configured, only you see your cosmetics";
        return sync.status();
    }

    /** The loadout as the backend stores it: slot id -> cosmetic id (built-in ones only; others are not shared). */
    Map<String, Object> sharedLoadout() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Slot s : Slot.values()) {
            Cosmetic c = registry.get(loadout.get(s));
            out.put(s.id(), c != null && c.builtin ? c.id : null);
        }
        return out;
    }

    // ---- persistence ------------------------------------------------------------------------------------------------

    public Object toJson() {
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        Map<String, Object> l = new LinkedHashMap<String, Object>();
        for (Map.Entry<Slot, String> e : loadout.entrySet()) l.put(e.getKey().id(), e.getValue());
        o.put("loadout", l);
        o.put("show", show);
        o.put("showOthers", showOthers);
        o.put("badges", badges);
        o.put("hideUnderArmor", hideUnderArmor);
        o.put("wavyCapes", wavyCapes);
        if (synced != null) o.put("synced", new LinkedHashMap<String, Object>(synced));
        return o;
    }

    /** Keeps ids that are not in the catalog (yet): a local cosmetic may come back after a reload. */
    public void fromJson(Object json) {
        if (!(json instanceof Map)) return;
        Map<?, ?> o = (Map<?, ?>) json;
        loadout.clear();
        if (o.get("loadout") instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o.get("loadout")).entrySet()) {
                Slot s = Slot.byId(String.valueOf(e.getKey()));
                if (s != null && e.getValue() instanceof String) loadout.put(s, (String) e.getValue());
            }
        }
        if (o.get("show") instanceof Boolean) show = (Boolean) o.get("show");
        if (o.get("showOthers") instanceof Boolean) showOthers = (Boolean) o.get("showOthers");
        if (o.get("badges") instanceof Boolean) badges = (Boolean) o.get("badges");
        if (o.get("hideUnderArmor") instanceof Boolean) hideUnderArmor = (Boolean) o.get("hideUnderArmor");
        if (o.get("wavyCapes") instanceof Boolean) wavyCapes = (Boolean) o.get("wavyCapes");
        synced = null;
        if (o.get("synced") instanceof Map) {
            synced = new LinkedHashMap<String, Object>();
            Map<?, ?> m = (Map<?, ?>) o.get("synced");
            for (Slot s : Slot.values()) synced.put(s.id(), m.get(s.id()) instanceof String ? m.get(s.id()) : null);
        }
        version++;
    }

    /** Ids in the catalog for {@code slot}, in display order. */
    public List<Cosmetic> catalog(Slot slot) { return registry.bySlot(slot); }
}
