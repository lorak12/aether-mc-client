package gg.kite.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned JSON persistence. {@code config.json} holds client-wide state; every profile is its own file in the
 * {@code profiles} folder next to it ({@link ProfileFiles}). Writes atomically; tolerant of missing/corrupt files.
 */
public final class ConfigStore {
    /** 3: profiles live in profiles/*.json; 2: in config.json's "profiles"; 1: a single top-level "mods" object. */
    public static final int SCHEMA = 3;

    private final Path file;

    public ConfigStore(Path file) { this.file = file; }

    public Path file() { return file; }

    /** Folder of profile files. */
    public Path profilesDir() { return file.resolveSibling("profiles"); }

    public void save(ModManager mm) throws IOException {
        Path dir = profilesDir();
        Profiles pr = mm.profiles();
        for (Profiles.Profile p : pr.all()) ProfileFiles.write(dir.resolve(p.file() + ".json"), mm.exportProfile(p));
        for (String gone : pr.takeRemovedFiles()) {
            if (pr.byFile(gone) == null) Files.deleteIfExists(dir.resolve(gone + ".json"));
        }
        write(file, toJson(mm));
    }

    /** Applies stored state onto registered mods. Returns false if nothing usable was found (defaults stay). */
    public boolean load(ModManager mm) {
        Map<String, Object> root = read(file, true);
        boolean migrated = root != null && schemaOf(root) < SCHEMA;
        if (root == null) {
            // No config yet, but profile files may have been copied in.
            if (ProfileFiles.list(profilesDir()).isEmpty()) return false;
            root = new LinkedHashMap<String, Object>();
            root.put("schema", (long) SCHEMA);
        }
        fromJson(mm, root, profilesDir());
        if (migrated) {
            try {
                save(mm);
            } catch (IOException ignored) { }
        }
        return true;
    }

    /**
     * Picks up profile files that appeared in the folder since loading (dropped in by the player). Returns how many
     * were added.
     */
    public int rescan(ModManager mm) {
        int n = 0;
        for (Path f : ProfileFiles.list(profilesDir())) {
            String stem = ProfileFiles.stem(f);
            if (mm.profiles().byFile(stem) != null) continue;
            if (ProfileFiles.addFrom(mm.profiles(), ProfileFiles.read(f), stem) != null) n++;
        }
        return n;
    }

    /** Writes one profile to {@code dir/<file>.json} for sharing; returns the written path. */
    public static Path exportProfile(ModManager mm, Profiles.Profile p, Path dir) throws IOException {
        Path out = dir.resolve(p.file() + ".json");
        ProfileFiles.write(out, mm.exportProfile(p));
        return out;
    }

    /** Adds a profile from an exported file. Returns it, or null if the file is not a profile. */
    public static Profiles.Profile importProfile(ModManager mm, Path from) {
        return ProfileFiles.addFrom(mm.profiles(), read(from, false));
    }

    static Map<String, Object> toJson(ModManager mm) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schema", (long) SCHEMA);
        root.put("client", mm.client().toJson());
        root.put("menuKey", mm.menuKey.toJson());
        root.put("favorites", new ArrayList<Object>(mm.favorites()));
        root.put("seen", new ArrayList<Object>(mm.seenIds()));
        Profiles pr = mm.profiles();
        root.put("activeProfile", pr.active().file());
        root.put("manualProfile", pr.manual().file());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        for (Mod m : mm.all()) {
            Map<String, Object> d = m.data().toJson();
            if (!d.isEmpty()) data.put(m.id, d);
        }
        root.put("data", data);
        root.put("cosmetics", mm.cosmetics().toJson());
        return root;
    }

    static void fromJson(ModManager mm, Map<String, Object> root, Path profilesDir) {
        root = migrate(root, schemaOf(root));
        mm.client().fromJson(root.get("client"));
        if (root.get("competitiveMode") instanceof Boolean) mm.setCompetitiveMode((Boolean) root.get("competitiveMode"));
        mm.menuKey.fromJson(root.get("menuKey"));
        mm.cosmetics().fromJson(root.get("cosmetics"));
        if (root.get("data") instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) root.get("data")).entrySet()) {
                Mod m = mm.get(String.valueOf(e.getKey()));
                if (m != null) m.data().fromJson(e.getValue());
            }
        }
        if (root.get("favorites") instanceof List) for (Object f : (List<?>) root.get("favorites")) if (f instanceof String) mm.setFavorite((String) f, true);
        List<String> seen = null;
        if (root.get("seen") instanceof List) {
            seen = new ArrayList<String>();
            for (Object o : (List<?>) root.get("seen")) if (o instanceof String) seen.add((String) o);
        }
        mm.loadSeen(seen);

        Profiles pr = mm.profiles();
        pr.clear();
        // Schema 2 configs carry their profiles inline; they become files on the next save.
        Object ps = root.get("profiles");
        if (ps instanceof List) {
            for (Object o : (List<?>) ps) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> p = (Map<?, ?>) o;
                Object mods = p.get("mods");
                pr.add(str(p.get("name"), "Profile"), str(p.get("servers"), ""), mods instanceof Map ? ProfileFiles.copy((Map<?, ?>) mods) : new LinkedHashMap<String, Object>());
            }
        }
        for (Path f : ProfileFiles.list(profilesDir)) {
            String stem = ProfileFiles.stem(f);
            if (pr.byFile(stem) == null) ProfileFiles.addFrom(pr, ProfileFiles.read(f), stem);
        }
        if (pr.all().isEmpty()) pr.add("Default", "", null, "default", new LinkedHashMap<String, Object>());
        Profiles.Profile active = profile(pr, str(root.get("activeProfile"), ""));
        if (active == null) active = pr.all().get(0);
        Profiles.Profile manual = profile(pr, str(root.get("manualProfile"), ""));
        Map<String, Object> stored = pr.stored(active);
        boolean hasAccessibility = root.get("client") instanceof Map && ((Map<?, ?>) root.get("client")).containsKey("reducedMotion");
        if (!hasAccessibility && stored != null) mm.client().migrateAccessibility(stored.get("accessibility"));
        applyMods(mm, stored, false);
        pr.restore(active, manual);
    }

    /** By file name (schema 3) or display name (schema 2). */
    private static Profiles.Profile profile(Profiles pr, String key) {
        Profiles.Profile p = pr.byFile(key);
        return p != null ? p : pr.byName(key);
    }

    /** Live mod state as JSON: {modId: {enabled, settings, hud}}. */
    public static Map<String, Object> snapshot(ModManager mm) {
        Map<String, Object> mods = new LinkedHashMap<String, Object>();
        for (Mod m : mm.all()) {
            Map<String, Object> o = new LinkedHashMap<String, Object>();
            o.put("enabled", m.enabled());
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            for (Setting<?> st : m.settings()) if (st.persist) s.put(st.key, st.toJson());
            o.put("settings", s);
            if (m.hud() != null) o.put("hud", m.hud().toJson());
            mods.put(m.id, o);
        }
        return mods;
    }

    /**
     * Load a {@link #snapshot}. With {@code resetFirst} every mod starts from its defaults, so settings missing
     * from the snapshot don't leak in from the previous profile; mods it does not mention are switched off.
     */
    public static void applyMods(ModManager mm, Map<String, Object> mods, boolean resetFirst) {
        if (resetFirst) {
            for (Mod m : mm.all()) {
                m.resetSettings();
                if (mods == null || !mods.containsKey(m.id)) mm.setEnabled(m.id, false);
            }
        }
        if (mods == null) return;
        for (Map.Entry<String, Object> e : mods.entrySet()) {
            Mod m = mm.get(e.getKey());
            if (m == null || !(e.getValue() instanceof Map)) continue;
            Map<?, ?> o = (Map<?, ?>) e.getValue();
            if (o.get("settings") instanceof Map) {
                Map<?, ?> s = (Map<?, ?>) o.get("settings");
                for (Setting<?> st : m.settings()) if (st.persist && s.containsKey(st.key)) st.fromJson(s.get(st.key));
            }
            if (m.hud() != null) m.hud().fromJson(o.get("hud"));
            if (o.get("enabled") instanceof Boolean) mm.setEnabled(m.id, (Boolean) o.get("enabled"));
        }
    }

    /** Upgrade older schemas in place. Add a case per bump. */
    static Map<String, Object> migrate(Map<String, Object> root, long from) {
        if (from < 2 && !(root.get("profiles") instanceof List)) {
            // 0/1: one top-level "mods" object becomes the "Default" profile.
            Map<String, Object> p = new LinkedHashMap<String, Object>();
            p.put("name", "Default");
            p.put("servers", "");
            p.put("mods", root.get("mods") instanceof Map ? root.get("mods") : new LinkedHashMap<String, Object>());
            List<Object> list = new ArrayList<Object>();
            list.add(p);
            root.put("profiles", list);
            root.put("activeProfile", "Default");
            root.remove("mods");
            root.put("schema", (long) SCHEMA);
        }
        return root;
    }

    private static long schemaOf(Map<String, Object> root) {
        return root.get("schema") instanceof Number ? ((Number) root.get("schema")).longValue() : 0;
    }

    private static Map<String, Object> read(Path file, boolean backupIfCorrupt) {
        try {
            if (!Files.exists(file)) return null;
            return Json.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            if (backupIfCorrupt) backupCorrupt(file);
            return null;
        }
    }

    private static void write(Path file, Map<String, Object> root) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, Json.write(root).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String str(Object o, String fallback) { return o instanceof String ? (String) o : fallback; }

    private static void backupCorrupt(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }
}
