package gg.aether.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned JSON persistence for mod state. Writes atomically; tolerant of missing/corrupt files. */
public final class ConfigStore {
    public static final int SCHEMA = 1;

    private final Path file;

    public ConfigStore(Path file) { this.file = file; }

    public void save(ModManager mm) throws IOException {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schema", (long) SCHEMA);
        root.put("competitiveMode", mm.competitiveMode());
        Map<String, Object> mods = new LinkedHashMap<String, Object>();
        for (Mod m : mm.all()) {
            Map<String, Object> o = new LinkedHashMap<String, Object>();
            o.put("enabled", m.enabled());
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            for (Setting<?> st : m.settings()) s.put(st.key, st.toJson());
            o.put("settings", s);
            if (m.hud() != null) o.put("hud", m.hud().toJson());
            mods.put(m.id, o);
        }
        root.put("mods", mods);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, Json.write(root).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Applies stored state onto registered mods. Returns false if nothing usable was found (defaults stay). */
    public boolean load(ModManager mm) {
        Map<String, Object> root;
        try {
            if (!Files.exists(file)) return false;
            root = Json.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            backupCorrupt();
            return false;
        }
        long schema = root.get("schema") instanceof Number ? ((Number) root.get("schema")).longValue() : 0;
        root = migrate(root, schema);
        if (root.get("competitiveMode") instanceof Boolean) mm.setCompetitiveMode((Boolean) root.get("competitiveMode"));
        Object mods = root.get("mods");
        if (!(mods instanceof Map)) return true;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) mods).entrySet()) {
            Mod m = mm.get(String.valueOf(e.getKey()));
            if (m == null || !(e.getValue() instanceof Map)) continue;
            Map<?, ?> o = (Map<?, ?>) e.getValue();
            if (o.get("settings") instanceof Map) {
                Map<?, ?> s = (Map<?, ?>) o.get("settings");
                for (Setting<?> st : m.settings()) if (s.containsKey(st.key)) st.fromJson(s.get(st.key));
            }
            if (m.hud() != null) m.hud().fromJson(o.get("hud"));
            if (o.get("enabled") instanceof Boolean) mm.setEnabled(m.id, (Boolean) o.get("enabled"));
        }
        return true;
    }

    /** Upgrade older schemas in place. Add a case per bump. */
    static Map<String, Object> migrate(Map<String, Object> root, long from) {
        // schema 0 (no field) has the same shape as 1; future versions add steps here.
        return root;
    }

    private void backupCorrupt() {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }
}
