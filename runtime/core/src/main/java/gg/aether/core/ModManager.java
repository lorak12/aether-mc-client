package gg.aether.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Registry + lifecycle + gating (server disable list, competitive mode) for all mods. */
public final class ModManager {
    private final Map<String, Mod> mods = new LinkedHashMap<String, Mod>();
    private final Set<String> serverDisabled = new HashSet<String>();
    private final EventBus bus = new EventBus();
    private boolean competitiveMode;

    public EventBus events() { return bus; }

    public void register(Mod m) {
        if (mods.containsKey(m.id)) throw new IllegalArgumentException("duplicate mod id " + m.id);
        m.bind(bus);
        mods.put(m.id, m);
    }

    public Mod get(String id) { return mods.get(id); }
    public List<Mod> all() { return Collections.unmodifiableList(new ArrayList<Mod>(mods.values())); }

    public List<Mod> search(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<Mod> out = new ArrayList<Mod>();
        for (Mod m : mods.values()) {
            if (q.isEmpty() || m.name.toLowerCase(Locale.ROOT).contains(q) || m.description.toLowerCase(Locale.ROOT).contains(q)) out.add(m);
        }
        return out;
    }

    /** True if the mod may run right now given server restrictions and competitive mode. */
    public boolean allowed(Mod m) {
        if (serverDisabled.contains(m.id)) return false;
        return !(competitiveMode && !m.competitiveSafe);
    }

    public boolean active(Mod m) { return m.enabled() && allowed(m); }

    public void setEnabled(String id, boolean on) {
        Mod m = mods.get(id);
        if (m != null) m.setEnabledInternal(on);
    }

    /** Servers may send a list of mod ids that must be off while connected (like Lunar's mod-disable packet). */
    public void setServerDisabled(Set<String> ids) {
        serverDisabled.clear();
        serverDisabled.addAll(ids);
    }

    public void clearServerDisabled() { serverDisabled.clear(); }

    public void setCompetitiveMode(boolean on) { competitiveMode = on; }
    public boolean competitiveMode() { return competitiveMode; }

    public void tick(GameState s) {
        for (Mod m : mods.values()) {
            if (!active(m)) continue;
            try {
                m.onTick(s);
            } catch (RuntimeException e) {
                fail(m, e);
            }
        }
    }

    public void renderHud(UiRenderer r, GameState s) {
        for (Mod m : mods.values()) {
            if (!active(m) || m.hud() == null) continue;
            try {
                m.renderHud(r, s);
            } catch (RuntimeException e) {
                fail(m, e);
            }
        }
    }

    private final List<String> failures = new ArrayList<String>();

    /** A mod that throws is switched off rather than crashing the game every frame. */
    private void fail(Mod m, RuntimeException e) {
        failures.add(m.id + ": " + e);
        m.setEnabledInternal(false);
    }

    public List<String> failures() { return new ArrayList<String>(failures); }
}
