package gg.kite.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Base class of every built-in (and, later, third-party) client mod. */
public abstract class Mod {
    public enum Category { HUD, VISUAL, GAMEPLAY, UTILITY, PERFORMANCE, PVP, WORLD, INVENTORY }

    public final String id;
    public final String name;
    public final String description;
    public final Category category;
    /** False for anything that gives a competitive edge; hidden in tournament mode and gateable by servers. */
    public final boolean competitiveSafe;

    private final List<Setting<?>> settings = new ArrayList<Setting<?>>();
    private boolean enabled;
    protected EventBus events;
    /** Ask the game to do something differently (zoom, sprint, time...). Reset every tick. */
    protected Overrides fx;
    protected Notifications toasts;
    /** Persistent data shared by every profile (statistics, waypoints...). */
    protected ModData data;
    /** True for mods that reveal coordinates / server address; hidden by streamer mode. */
    protected boolean sensitive;
    private final List<Hotkey> hotkeys = new ArrayList<Hotkey>();
    /** Non-null for mods that draw on the HUD. */
    protected HudElement hud;
    /** Set while the HUD editor is open: draw a placeholder instead of nothing, so the element can be placed. */
    boolean preview;

    protected Mod(String id, String name, String description, Category category, boolean competitiveSafe) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.category = category;
        this.competitiveSafe = competitiveSafe;
    }

    /** Adds {@code s} to this mod's settings; the menu lists them in this order (see {@link Setting} for the convention). */
    protected <S extends Setting<?>> S add(S s) {
        settings.add(s);
        return s;
    }

    /** {@link #add} a setting shown only while {@code parent} is on, indented under it ({@link Setting#dependsOn}). */
    protected <S extends Setting<?>> S add(S s, Setting<?> parent) {
        s.dependsOn(parent);
        return add(s);
    }

    public List<Setting<?>> settings() { return Collections.unmodifiableList(settings); }

    private String icon;
    private boolean serverSpecific;

    /** Name of this mod's menu icon in {@link gg.kite.core.ui.Icons}; a per-category default when none was set. */
    public String icon() {
        if (icon != null) return icon;
        switch (category) {
            case HUD: return "layout";
            case VISUAL: return "eye";
            case GAMEPLAY: return "gamepad";
            case PVP: return "sword";
            case WORLD: return "globe";
            case INVENTORY: return "package";
            case PERFORMANCE: return "gauge";
            default: return "sliders";
        }
    }

    public Mod icon(String name) {
        icon = name;
        return this;
    }

    /** True for mods made for particular servers (Hypixel, server API...): the menu's SERVER tab. */
    public boolean serverSpecific() { return serverSpecific; }

    public Mod serverSpecific(boolean on) {
        serverSpecific = on;
        return this;
    }
    public HudElement hud() { return hud; }
    public boolean enabled() { return enabled; }

    /**
     * Adds a configurable key (plus hold/toggle) to this mod's settings. Declare hotkeys after the mod's other
     * settings (see {@link Setting}); the mode row is hidden while the key is unbound, and an unbound hotkey never fires.
     */
    protected Hotkey hotkey(String id, String label, int defaultKey, Hotkey.Mode defaultMode) {
        Setting.Key key = add(new Setting.Key(id, label, defaultKey));
        Setting.Choice<Hotkey.Mode> mode = new Setting.Choice<Hotkey.Mode>(id + "Mode", label + " mode", defaultMode);
        mode.dependsOn(key);
        Hotkey h = new Hotkey(key, add(mode));
        hotkeys.add(h);
        return h;
    }

    public boolean sensitive() { return sensitive; }

    /** True while the HUD editor draws this mod; show sample content if there is nothing real to show. */
    protected final boolean previewing() { return preview; }

    /** Every setting and the HUD placement back to defaults. */
    public void resetSettings() {
        for (Setting<?> s : settings) s.reset();
        if (hud != null) hud.reset();
    }

    private ModManager manager;

    final void bind(ModManager mm, EventBus bus, Overrides fx, Notifications toasts, ModData data) {
        this.manager = mm;
        this.events = bus;
        this.fx = fx;
        this.toasts = toasts;
        this.data = data;
    }

    public ModData data() { return data; }

    /** Key press/release routed by the mod manager: updates this mod's hotkeys, then calls {@link #onHotkey}. */
    public final void handleKey(int code, boolean down) {
        for (Hotkey h : hotkeys) if (h.onKey(code, down)) onHotkey(h);
    }

    /** A hotkey of this mod changed state (press/release for HOLD, flip for TOGGLE). */
    protected void onHotkey(Hotkey h) {}

    final void setEnabledInternal(boolean on) {
        if (on == enabled) return;
        enabled = on;
        if (on) onEnable(); else { onDisable(); for (Hotkey h : hotkeys) h.setActive(false); if (events != null) events.unregister(this); }
    }

    protected void onEnable() {}
    protected void onDisable() {}
    public void onTick(GameState s) {}

    /** Draw into the HUD. Only called while enabled and allowed. */
    public void renderHud(UiRenderer r, GameState s) {}

    /** Draw 3D overlays in the world. Only called while enabled and allowed. */
    public void renderWorld(WorldRenderer w, GameState s) {}

    /** Folder for this mod's larger files (map tiles...), created on demand; null when the host has none. */
    protected java.nio.file.Path dataDir() {
        java.nio.file.Path root = manager == null ? null : manager.dataDir();
        return root == null ? null : root.resolve(id);
    }

    /** Adapter services (clipboard, sounds, files...). Never null once registered. */
    protected ClientHost host() { return manager == null ? new ClientHost() { } : manager.host(); }

    /** Server-side restrictions (server API): these mod ids must be off while connected. */
    protected void setServerDisabled(java.util.Set<String> ids) { if (manager != null) manager.setServerDisabled(ids); }

    /** Every registered mod, for mods that coordinate with others. */
    protected java.util.List<Mod> allMods() { return manager == null ? java.util.Collections.<Mod>emptyList() : manager.all(); }

    /** Ask the host to show a full-screen view (world map...). Ignored when the host cannot. */
    protected void openView(gg.kite.core.ui.View v) { if (manager != null) manager.openView(v); }
}
