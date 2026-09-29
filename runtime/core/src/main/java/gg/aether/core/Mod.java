package gg.aether.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Base class of every built-in (and, later, third-party) client mod. */
public abstract class Mod {
    public enum Category { HUD, VISUAL, GAMEPLAY, UTILITY, PERFORMANCE }

    public final String id;
    public final String name;
    public final String description;
    public final Category category;
    /** False for anything that gives a competitive edge; hidden in tournament mode and gateable by servers. */
    public final boolean competitiveSafe;

    private final List<Setting<?>> settings = new ArrayList<Setting<?>>();
    private boolean enabled;
    protected EventBus events;
    /** Non-null for mods that draw on the HUD. */
    protected HudElement hud;

    protected Mod(String id, String name, String description, Category category, boolean competitiveSafe) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.category = category;
        this.competitiveSafe = competitiveSafe;
    }

    protected <S extends Setting<?>> S add(S s) {
        settings.add(s);
        return s;
    }

    public List<Setting<?>> settings() { return Collections.unmodifiableList(settings); }
    public HudElement hud() { return hud; }
    public boolean enabled() { return enabled; }

    final void bind(EventBus bus) { this.events = bus; }

    final void setEnabledInternal(boolean on) {
        if (on == enabled) return;
        enabled = on;
        if (on) onEnable(); else { onDisable(); if (events != null) events.unregister(this); }
    }

    protected void onEnable() {}
    protected void onDisable() {}
    public void onTick(GameState s) {}

    /** Draw into the HUD. Only called while enabled and allowed. */
    public void renderHud(UiRenderer r, GameState s) {}
}
