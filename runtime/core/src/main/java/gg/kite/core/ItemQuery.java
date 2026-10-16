package gg.kite.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Item search syntax shared by container search, the item browser and the recipe viewer. Space-separated terms must
 * all match: plain words match the name or id, {@code @mod} the namespace, {@code #tag} an item tag, {@code $text}
 * enchantments / lore, and a leading {@code -} negates a term ("sword -@minecraft").
 */
public final class ItemQuery {
    private final List<String[]> terms = new ArrayList<String[]>();

    public ItemQuery(String query) {
        if (query == null) return;
        for (String t : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (t.isEmpty() || t.equals("-")) continue;
            boolean neg = t.startsWith("-");
            if (neg) t = t.substring(1);
            char kind = t.charAt(0);
            if (kind == '@' || kind == '#' || kind == '$') t = t.substring(1);
            else kind = ' ';
            terms.add(new String[] { neg ? "-" : "+", String.valueOf(kind), t });
        }
    }

    public boolean isEmpty() { return terms.isEmpty(); }

    /**
     * @param id    registry id ("minecraft:diamond_sword")
     * @param name  display name (custom names included)
     * @param tags  tag ids without '#'
     * @param extra enchantment names, lore and similar text
     */
    public boolean matches(String id, String name, Collection<String> tags, Collection<String> extra) {
        String lid = id == null ? "" : id.toLowerCase(Locale.ROOT);
        String ns = lid.indexOf(':') < 0 ? "minecraft" : lid.substring(0, lid.indexOf(':'));
        String path = lid.substring(lid.indexOf(':') + 1);
        String lname = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String[] t : terms) {
            String v = t[2];
            boolean hit;
            switch (t[1].charAt(0)) {
                case '@': hit = ns.startsWith(v); break;
                case '#': hit = anyContains(tags, v); break;
                case '$': hit = anyContains(extra, v); break;
                default: hit = lname.contains(v) || path.contains(v) || path.replace('_', ' ').contains(v) || lid.equals(v); break;
            }
            if (hit == t[0].equals("-")) return false;
        }
        return true;
    }

    private static boolean anyContains(Collection<String> values, String v) {
        if (values == null) return false;
        for (String s : values) if (s != null && s.toLowerCase(Locale.ROOT).contains(v)) return true;
        return false;
    }
}
