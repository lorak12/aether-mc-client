package gg.kite.core.ui;

import gg.kite.core.GameState;
import gg.kite.core.Keys;
import gg.kite.core.Mod;
import gg.kite.core.ModManager;
import gg.kite.core.Overrides;
import gg.kite.core.Profiles;
import gg.kite.core.UiRenderer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * The Right Shift menu: a hub (logo, Emotes / Mods / Wardrobe / Shop) leading to the mod window, the wardrobe (with
 * its Shop tab) and the HUD editor. Backend-agnostic: it draws through {@link UiRenderer} and takes already-translated input (GUI-scaled mouse
 * coordinates, GLFW key codes), so every version adapter shares it.
 */
public final class ClientGui implements View {
    /** What the hosting screen must do for the GUI. */
    public interface Host {
        void close();
        /** Persist the config (called when the GUI closes and after bigger changes). */
        void save();
        /** Write a profile to the exports folder and show it. Returns a message for the player; null if unsupported. */
        default String exportProfile(Profiles.Profile p) { return null; }
        /** Pick up profile files dropped into the profiles folder. Returns how many were added. */
        default int rescanProfiles() { return 0; }
        /** Open the folder holding the profile files. */
        default void openProfilesFolder() {}
    }

    enum Page { HUB, WINDOW, WARDROBE, HUD_EDITOR }

    final ModManager mm;
    final Host host;
    final Ui ui;
    final ModWindow window;
    final WardrobePage wardrobe;
    final HudEditor editor;
    private final HubPage hub;

    private Page page = Page.HUB;
    /** Page to return to when the HUD editor closes. */
    private Page beforeEditor = Page.WINDOW;
    /** Opened from the main menu: no hub, no HUD editor, closing returns to the menu. */
    private boolean fromMainMenu;
    /** False after the menu key opened the GUI until that key is released, so its auto-repeat can't close it again. */
    private boolean menuKeyArmed = true;
    private long openedAt = System.currentTimeMillis();
    float screenW, screenH;

    public ClientGui(ModManager mm, Host host) {
        this.mm = mm;
        this.host = host;
        this.ui = new Ui(mm.host());
        ui.palette(mm.client().savedColors);
        ui.soundVolume(mm.client().uiSoundVolume());
        this.window = new ModWindow(this);
        this.wardrobe = new WardrobePage(this);
        this.editor = new HudEditor(this);
        this.hub = new HubPage(this);
        int added = host.rescanProfiles();
        if (added > 0) ui.flash("Found " + added + " new profile" + (added == 1 ? "" : "s") + " in the profiles folder");
    }

    // ---- entry points (adapters, autotest, main menu) ---------------------------------------------------------------

    /** Start in the HUD editor instead of the hub. */
    public ClientGui openHudEditor() {
        setPage(Page.HUD_EDITOR);
        return this;
    }

    public boolean inHudEditor() { return page == Page.HUD_EDITOR; }

    /** Jump straight to a mod's settings page (unknown ids are ignored). */
    public ClientGui openSettings(String modId) {
        Mod m = mm.get(modId);
        if (m != null) {
            window.openMod(m);
            setPage(Page.WINDOW);
        }
        return this;
    }

    /**
     * Open a page by name: "hub", "mods", "settings", "waypoints", "profiles", "keybinds", "developer",
     * "cosmetics"/"wardrobe", "shop"/"store".
     */
    public ClientGui openPage(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        switch (n) {
            case "hub": setPage(fromMainMenu ? Page.WINDOW : Page.HUB); break;
            case "cosmetics":
            case "wardrobe": openWardrobe(); break;
            case "shop":
            case "store": openShop(); break;
            case "settings":
            case "general": window.showSettings(ClientSettingsPage.Section.GENERAL); setPage(Page.WINDOW); break;
            case "keybinds": window.showSettings(ClientSettingsPage.Section.KEYBINDS); setPage(Page.WINDOW); break;
            case "developer": window.showSettings(ClientSettingsPage.Section.DEVELOPER); setPage(Page.WINDOW); break;
            case "waypoints": window.showTab(ModWindow.Tab.WAYPOINTS); setPage(Page.WINDOW); break;
            default: window.showTab(ModWindow.Tab.MODS); setPage(Page.WINDOW); break;
        }
        return this;
    }

    /** Opened from the custom main menu: closing (or Esc) goes back to it, and there is no hub. */
    public ClientGui fromMainMenu() {
        fromMainMenu = true;
        if (page == Page.HUB) setPage(Page.WINDOW);
        return this;
    }

    public boolean isFromMainMenu() { return fromMainMenu; }

    /** The host opened the GUI because the menu key went down; ignore that key until it is released. */
    public ClientGui openedByMenuKey() {
        menuKeyArmed = false;
        return this;
    }

    // ---- navigation -------------------------------------------------------------------------------------------------

    void setPage(Page p) {
        if (page == Page.HUD_EDITOR && p != Page.HUD_EDITOR) host.save();
        if (p == Page.HUD_EDITOR && page != Page.HUD_EDITOR) beforeEditor = page;
        ui.blur();
        ui.closePopup();
        page = p;
        openedAt = System.currentTimeMillis();
        mm.setEditing(p == Page.HUD_EDITOR);
        mm.cosmetics().clearPreview();
    }

    Page page() { return page; }

    void openWindow() { setPage(Page.WINDOW); }
    void openWardrobe() {
        setPage(Page.WARDROBE);
        wardrobe.open(WardrobePage.Mode.WARDROBE);
    }

    void openShop() {
        setPage(Page.WARDROBE);
        wardrobe.open(WardrobePage.Mode.SHOP);
    }
    void openEditor() { if (!fromMainMenu) setPage(Page.HUD_EDITOR); else ui.flash("Join a world to move HUD mods", Theme.WARNING); }
    void closeEditor() { setPage(beforeEditor == Page.HUD_EDITOR ? Page.WINDOW : beforeEditor); }

    /** Back one level: settings page -> list, window/wardrobe -> hub, hub -> close. */
    void back() {
        if (ui.popupOpen()) {
            ui.closePopup();
            return;
        }
        switch (page) {
            case HUD_EDITOR: closeEditor(); break;
            case WINDOW:
                if (window.back()) break;
                if (fromMainMenu) host.close(); else setPage(Page.HUB);
                break;
            case WARDROBE:
                if (wardrobe.editing()) wardrobe.closeEditor();
                else if (fromMainMenu) host.close(); else setPage(Page.HUB);
                break;
            default: host.close(); break;
        }
    }

    void close() { host.close(); }

    /** Seconds since the current page opened, for entrance animations. */
    float pageAge() { return (System.currentTimeMillis() - openedAt) / 1000f; }

    /** 0..1 ease-out for an entrance animation of {@code seconds}. */
    float intro(float seconds) {
        if (mm.client().reducedMotion.on()) return 1f;
        float t = Math.min(1f, pageAge() / seconds);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    // ---- View -------------------------------------------------------------------------------------------------------

    /** Menu scale from accessibility settings; the HUD editor always works in real HUD coordinates. */
    private float scale() {
        if (page == Page.HUD_EDITOR) return 1f;
        return Math.max(0.5f, Math.min(2f, (float) mm.client().uiScale.val()));
    }

    @Override public void render(UiRenderer r, GameState s) {
        float sc = scale();
        screenW = r.screenWidth() / sc;
        screenH = r.screenHeight() / sc;
        ui.soundVolume(mm.client().uiSoundVolume());
        ui.begin(r, mm.client().reducedMotion.on());
        if (page == Page.HUD_EDITOR) {
            editor.render(r, s);
            ui.end();
            return;
        }
        if (sc != 1f) r.push(0, 0, sc);
        int dim = mm.client().dim.val();
        r.rect(0, 0, screenW, screenH, Theme.withAlpha(0, Math.round(dim * 2.55f * intro(0.18f))), 0);
        switch (page) {
            case WINDOW: window.render(r, s); break;
            case WARDROBE: wardrobe.render(r, s); break;
            default: hub.render(r, s); break;
        }
        ui.end();
        if (sc != 1f) r.pop();
    }

    @Override public void mouseMoved(float x, float y) { ui.mouseMoved(x / scale(), y / scale()); }

    @Override public boolean mouseDown(float x, float y, int button) {
        if (page == Page.HUD_EDITOR) return editor.mouseDown(x, y, button);
        return ui.mouseDown(x / scale(), y / scale(), button);
    }

    @Override public boolean mouseDrag(float x, float y) {
        if (page == Page.HUD_EDITOR) return editor.mouseDrag(x, y);
        return ui.mouseDrag(x / scale(), y / scale());
    }

    @Override public boolean mouseUp(float x, float y, int button) {
        if (page == Page.HUD_EDITOR) return editor.mouseUp(x, y, button);
        return ui.mouseUp(x / scale(), y / scale(), button);
    }

    @Override public boolean scroll(float x, float y, float delta) {
        if (page == Page.HUD_EDITOR) return editor.scroll(x, y, delta);
        return ui.scroll(x / scale(), y / scale(), delta);
    }

    @Override public boolean keyDown(int code) {
        if (ui.keyDown(code)) return true;
        if (page == Page.HUD_EDITOR && editor.key(code)) return true;
        if (code == Keys.ESCAPE) {
            back();
            return true;
        }
        if (mm.opensMenu(code) && page != Page.HUD_EDITOR) {
            if (menuKeyArmed) host.close();
            return true;
        }
        // Typing anywhere in the mod list searches, like Lunar's menu.
        return false;
    }

    @Override public void keyUp(int code) {
        ui.keyUp(code);
        if (code == mm.menuKey.code() || code == ModManager.FALLBACK_MENU_KEY) menuKeyArmed = true;
    }

    @Override public boolean charTyped(char c) {
        if (ui.charTyped(c)) return true;
        if (page == Page.WINDOW) return window.typeToSearch(c);
        return false;
    }

    @Override public void closed() {
        wardrobe.closeEditor();
        ui.blur();
        mm.setEditing(false);
        mm.cosmetics().clearPreview();
        host.save();
    }

    // ---- shared drawing ---------------------------------------------------------------------------------------------

    /**
     * The Kite logo: the brand mark rasterised at the drawn pixel size (sharp at any GUI scale), else the logo texture,
     * else a drawn fallback when the adapter has neither images nor textures.
     */
    void logo(UiRenderer r, float x, float y, float size) { drawLogo(r, x, y, size); }

    static void drawLogo(UiRenderer r, float x, float y, float size) {
        // Whole units, so the mark's pixels land 1:1 on the screen's.
        x = Math.round(x);
        y = Math.round(y);
        size = Math.max(1, Math.round(size));
        if (KiteMark.draw(r, x, y, size)) return;
        if (r.texture("kite:textures/gui/logo.png", x, y, size, size)) return;
        r.rect(x, y, size, size, Theme.ACCENT, size * 0.28f);
        r.rect(x + size * 0.3f, y + size * 0.3f, size * 0.4f, size * 0.4f, Theme.ACCENT_ALT, size * 0.1f);
    }

    /** A Kite coin of {@code size} px. */
    static void drawCoin(UiRenderer r, float x, float y, float size) {
        r.rect(x, y, size, size, 0xFFE0A526, size / 2f);
        r.rect(x + size * 0.15f, y + size * 0.15f, size * 0.7f, size * 0.7f, 0xFFF5B942, size * 0.35f);
        r.rect(x + size / 2f - size * 0.1f, y + size * 0.25f, size * 0.2f, size * 0.5f, 0xFFE0A526, 0);
    }

    /** Every override that differs from its reset value, e.g. "fovMultiplier = 0.25". */
    static List<String> overrideSummary(Overrides fx) {
        List<String> out = new ArrayList<String>();
        Overrides def = new Overrides();
        for (Field f : Overrides.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                Object v = f.get(fx), d = f.get(def);
                if (v == null ? d != null : !v.equals(d)) out.add(f.getName() + " = " + v);
            } catch (IllegalAccessException ignored) { }
        }
        if (out.isEmpty()) out.add("none (vanilla)");
        return out;
    }

    static String title(String enumName) { return Ui.title(enumName); }
}
