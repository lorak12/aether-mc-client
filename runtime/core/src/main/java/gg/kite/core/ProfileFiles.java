package gg.kite.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One profile = one shareable JSON file in {@code config/kite/profiles}:
 * <pre>
 * { "format": "kite-profile", "version": 1, "name": "PvP", "icon": "minecraft:diamond_sword",
 *   "servers": "hypixel.net", "mods": { "fps": { "enabled": true, "settings": {...}, "hud": {...} } } }
 * </pre>
 * The same file is what Export writes and Import (file or clipboard) reads.
 */
public final class ProfileFiles {
    public static final String FORMAT = "kite-profile";
    public static final int VERSION = 1;

    private ProfileFiles() {}

    public static Map<String, Object> toJson(Profiles.Profile p, Map<String, Object> mods) {
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("format", FORMAT);
        o.put("version", (long) VERSION);
        o.put("name", p.name.str());
        o.put("icon", p.icon.str());
        o.put("servers", p.servers.str());
        o.put("mods", mods == null ? new LinkedHashMap<String, Object>() : mods);
        return o;
    }

    /** True if {@code root} looks like a profile file (also accepts files without the format marker but with "mods"). */
    static boolean isProfile(Map<String, Object> root) {
        if (root == null) return false;
        Object f = root.get("format");
        if (f != null && !FORMAT.equals(f)) return false;
        return root.get("mods") instanceof Map;
    }

    /** Adds the profile described by {@code root}; null if it is not a profile. Clashing names/files get a suffix. */
    static Profiles.Profile addFrom(Profiles profiles, Map<String, Object> root) { return addFrom(profiles, root, null); }

    static Profiles.Profile addFrom(Profiles profiles, Map<String, Object> root, String file) {
        if (!isProfile(root)) return null;
        return profiles.add(str(root.get("name"), file != null ? file : "Imported"), str(root.get("servers"), ""), str(root.get("icon"), null), file,
                copy((Map<?, ?>) root.get("mods")));
    }

    /** Profile files in {@code dir}, sorted by name for a stable order. Unreadable files are skipped. */
    static List<Path> list(Path dir) {
        List<Path> out = new ArrayList<Path>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) out.add(p);
        } catch (IOException ignored) { }
        out.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        return out;
    }

    static String stem(Path p) {
        String n = p.getFileName().toString();
        return n.toLowerCase(java.util.Locale.ROOT).endsWith(".json") ? n.substring(0, n.length() - 5) : n;
    }

    static Map<String, Object> read(Path file) {
        try {
            return Json.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static void write(Path file, Map<String, Object> root) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, Json.write(root).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String str(Object o, String fallback) { return o instanceof String ? (String) o : fallback; }

    static Map<String, Object> copy(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }
}
