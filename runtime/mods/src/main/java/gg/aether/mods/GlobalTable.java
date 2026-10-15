package gg.aether.mods;

import gg.aether.core.ModData;
import gg.aether.core.Setting;
import java.util.List;
import java.util.Map;

/**
 * Keeps a {@link Setting.Entries} table in a mod's {@link ModData} instead of the active profile, so lists like
 * friends or ignored players survive profile switches. Call {@link #sync} every tick.
 */
final class GlobalTable {
    private final Setting.Entries table;
    private final String key;
    private boolean loaded;
    private String fingerprint = "";

    GlobalTable(Setting.Entries table, String key) {
        this.table = table;
        this.key = key;
        table.persist = false;
    }

    /** Loads once, then saves whenever the table was edited (in the menu or by the mod). */
    void sync(ModData data) {
        if (data == null) return;
        if (!loaded) {
            loaded = true;
            List<Object> rows = data.getList(key);
            if (!rows.isEmpty() || table.size() == 0) {
                table.clear();
                for (Object o : rows) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) o;
                    String[] cells = new String[table.columns.length];
                    for (int i = 0; i < cells.length; i++) cells[i] = m.get(table.columns[i]) == null ? "" : String.valueOf(m.get(table.columns[i]));
                    table.add(cells);
                }
                fingerprint = table.fingerprint();
            }
            // Otherwise rows added before the first sync are new and get saved below.
        }
        String fp = table.fingerprint();
        if (!fp.equals(fingerprint)) {
            fingerprint = fp;
            data.put(key, table.toJson());
        }
    }
}
