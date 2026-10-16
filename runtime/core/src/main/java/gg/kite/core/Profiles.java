package gg.kite.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Named sets of mod state (enabled flags, settings, HUD layout). Exactly one is live in the {@link ModManager};
 * the others are kept as JSON snapshots. A profile can list servers it switches on for automatically. Each profile
 * is saved as its own file ({@link ProfileFiles}), named by {@link Profile#file()}.
 */
public final class Profiles {
    public static final class Profile {
        public final Setting.Text name = new Setting.Text("name", "Name", "Default", 24);
        /** Comma-separated server patterns: "hypixel.net, *.minemen.club, singleplayer". */
        public final Setting.Text servers = new Setting.Text("servers", "Auto-activate on", "", 200);
        /** Minecraft item id shown next to the name. */
        public final Setting.Text icon = new Setting.Text("icon", "Icon", DEFAULT_ICON, 64);
        /** Stored mod state; null while this profile is the live one. */
        Map<String, Object> mods;
        /** File name without ".json"; fixed once assigned so renaming never orphans a file. */
        String file;

        Profile(String name) { this.name.set(name); }

        public String file() { return file; }

        /** True if this profile's server list matches {@code address} ("" = singleplayer). */
        public boolean matches(String address) {
            String host = normalize(address);
            for (String raw : servers.str().split(",")) {
                String p = raw.trim().toLowerCase(Locale.ROOT);
                if (p.isEmpty()) continue;
                if (host.isEmpty()) {
                    if (p.equals("singleplayer") || p.equals("local")) return true;
                    continue;
                }
                p = normalize(p);
                if (p.startsWith("*.")) {
                    String base = p.substring(2);
                    if (host.equals(base) || host.endsWith("." + base)) return true;
                } else if (host.equals(p) || host.endsWith("." + p)) {
                    return true;
                }
            }
            return false;
        }

        /** "Play.Example.net:25565" -> "play.example.net". */
        static String normalize(String address) {
            if (address == null) return "";
            String a = address.trim().toLowerCase(Locale.ROOT);
            int colon = a.lastIndexOf(':');
            if (colon > 0 && a.indexOf(':') == colon) a = a.substring(0, colon);
            while (a.endsWith(".")) a = a.substring(0, a.length() - 1);
            return a;
        }
    }

    public static final String DEFAULT_ICON = "minecraft:grass_block";
    /** Icons the menu cycles through for profiles. */
    public static final String[] ICONS = { "minecraft:grass_block", "minecraft:diamond_sword", "minecraft:bow", "minecraft:red_bed",
            "minecraft:golden_apple", "minecraft:ender_pearl", "minecraft:diamond_pickaxe", "minecraft:elytra", "minecraft:totem_of_undying",
            "minecraft:nether_star", "minecraft:compass", "minecraft:fishing_rod", "minecraft:tnt", "minecraft:enchanted_book",
            "minecraft:clock", "minecraft:redstone" };

    private final List<Profile> list = new ArrayList<Profile>();
    /** Files of profiles deleted since the last save. */
    private final List<String> removedFiles = new ArrayList<String>();
    private Profile active;
    /** The profile the player picked by hand; auto-switching falls back to it on servers no profile claims. */
    private Profile manual;
    private String lastAddress;

    Profiles() {
        active = manual = new Profile("Default");
        active.file = "default";
        list.add(active);
    }

    public List<Profile> all() { return Collections.unmodifiableList(list); }
    public Profile active() { return active; }

    public Profile byName(String name) {
        for (Profile p : list) if (p.name.str().equalsIgnoreCase(name)) return p;
        return null;
    }

    public Profile byFile(String file) {
        for (Profile p : list) if (p.file != null && p.file.equalsIgnoreCase(file)) return p;
        return null;
    }

    /** A new profile starting as a copy of the live state. */
    Profile create(String name, Map<String, Object> liveSnapshot) {
        Profile p = new Profile(uniqueName(name));
        p.mods = liveSnapshot;
        p.icon.set(active == null ? DEFAULT_ICON : active.icon.str());
        p.file = uniqueFile(slug(p.name.str()));
        list.add(p);
        return p;
    }

    /** "My PvP setup!" -> "my-pvp-setup". */
    static String slug(String name) {
        StringBuilder b = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
            else if (b.length() > 0 && b.charAt(b.length() - 1) != '-') b.append('-');
        }
        while (b.length() > 0 && b.charAt(b.length() - 1) == '-') b.setLength(b.length() - 1);
        if (b.length() > 40) b.setLength(40);
        return b.length() == 0 ? "profile" : b.toString();
    }

    private String uniqueFile(String base) {
        if (byFile(base) == null && !removedFiles.contains(base)) return base;
        for (int i = 2; ; i++) if (byFile(base + "-" + i) == null && !removedFiles.contains(base + "-" + i)) return base + "-" + i;
    }

    /** Next icon from {@link #ICONS} after {@code p}'s. */
    public void cycleIcon(Profile p, int dir) {
        int at = 0;
        for (int i = 0; i < ICONS.length; i++) if (ICONS[i].equals(p.icon.str())) at = i;
        p.icon.set(ICONS[((at + dir) % ICONS.length + ICONS.length) % ICONS.length]);
    }

    private String uniqueName(String base) {
        if (byName(base) == null) return base;
        for (int i = 2; ; i++) if (byName(base + " " + i) == null) return base + " " + i;
    }

    /** Removes a non-live profile. The last profile can never be removed. */
    boolean remove(Profile p) {
        if (list.size() <= 1 || p == active) return false;
        list.remove(p);
        if (p.file != null) removedFiles.add(p.file);
        if (manual == p) manual = active;
        return true;
    }

    /** Called by the manager after it has swapped the live mod state. */
    void setActive(Profile p, boolean byHand) {
        active = p;
        p.mods = null;
        if (byHand) manual = p;
    }

    Profile manual() { return manual; }

    /** Profile to switch to after the server changed, or null to stay. Only reacts to actual address changes. */
    Profile autoSelect(String address) {
        String a = address == null ? "" : address;
        if (a.equals(lastAddress)) return null;
        lastAddress = a;
        for (Profile p : list) if (p.matches(a)) return p == active ? null : p;
        return manual == active ? null : manual;
    }

    // Persistence helpers (ConfigStore).
    Map<String, Object> stored(Profile p) { return p.mods; }

    /** Files to delete on the next save (and forget). */
    List<String> takeRemovedFiles() {
        List<String> out = new ArrayList<String>(removedFiles);
        removedFiles.clear();
        return out;
    }

    void clear() {
        list.clear();
        removedFiles.clear();
        active = manual = null;
        lastAddress = null;
    }

    Profile add(String name, String servers, Map<String, Object> mods) { return add(name, servers, null, null, mods); }

    /** {@code file} null or taken: derived from the name. */
    Profile add(String name, String servers, String icon, String file, Map<String, Object> mods) {
        Profile p = new Profile(uniqueName(name == null || name.trim().isEmpty() ? "Profile" : name.trim()));
        p.servers.set(servers == null ? "" : servers);
        if (icon != null && !icon.trim().isEmpty()) p.icon.set(icon.trim());
        p.mods = mods;
        p.file = file != null && !file.isEmpty() && byFile(file) == null ? file : uniqueFile(slug(p.name.str()));
        list.add(p);
        return p;
    }

    void restore(Profile activeProfile, Profile manualProfile) {
        active = activeProfile;
        active.mods = null;
        manual = manualProfile == null ? activeProfile : manualProfile;
    }
}
