package gg.kite.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-mod persistent data that is not a setting and not part of a profile: statistics, playtime, waypoints, friends.
 * Stored in the config file under "data"; values must be JSON types (String, Boolean, Long/Double, Map, List).
 */
public final class ModData {
    private final Map<String, Object> map = new LinkedHashMap<String, Object>();
    private boolean dirty;

    public synchronized Object get(String key) { return map.get(key); }

    public synchronized void put(String key, Object value) {
        if (value == null) map.remove(key); else map.put(key, value);
        dirty = true;
    }

    public long getLong(String key, long def) {
        Object o = get(key);
        return o instanceof Number ? ((Number) o).longValue() : def;
    }

    public double getDouble(String key, double def) {
        Object o = get(key);
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }

    public String getString(String key, String def) {
        Object o = get(key);
        return o instanceof String ? (String) o : def;
    }

    /** A mutable copy-free view is not offered: callers get a fresh map and must {@link #put} it back. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String key) {
        Object o = get(key);
        return o instanceof Map ? new LinkedHashMap<String, Object>((Map<String, Object>) o) : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    public List<Object> getList(String key) {
        Object o = get(key);
        return o instanceof List ? new ArrayList<Object>((List<Object>) o) : new ArrayList<Object>();
    }

    public void add(String key, long delta) { put(key, getLong(key, 0) + delta); }
    public void add(String key, double delta) { put(key, getDouble(key, 0) + delta); }

    public synchronized void clear() {
        map.clear();
        dirty = true;
    }

    /** True once since the last call if anything changed (lets hosts save occasionally). */
    public synchronized boolean takeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    synchronized Map<String, Object> toJson() { return new LinkedHashMap<String, Object>(map); }

    synchronized void fromJson(Object o) {
        map.clear();
        if (!(o instanceof Map)) return;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) map.put(String.valueOf(e.getKey()), e.getValue());
    }
}
