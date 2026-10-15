package gg.aether.core.ui;

import gg.aether.core.GameState;
import gg.aether.core.Mod;
import gg.aether.core.Profiles;
import gg.aether.core.UiRenderer;
import java.util.List;

import static gg.aether.core.ui.Theme.*;

/**
 * The translucent mod window: header (logo, "Kite Client", Mods / Settings / Waypoints tabs, close), a sidebar
 * (profiles with Save as new profile, Import and Edit HUD layout; or the Settings sections) and the body.
 */
final class ModWindow {
    enum Tab { MODS, SETTINGS, WAYPOINTS }

    static final float HEADER = 28, SIDEBAR = 130;

    private final ClientGui gui;
    final ModListPage list;
    final ModSettingsPage settings;
    final ClientSettingsPage client;
    final WaypointsPage waypoints;
    private Tab tab = Tab.MODS;
    /** Non-null while the Mods tab shows one mod's settings. */
    private Mod open;
    private final Ui.Scroll profileScroll = new Ui.Scroll();

    ModWindow(ClientGui gui) {
        this.gui = gui;
        this.list = new ModListPage(gui, this);
        this.settings = new ModSettingsPage(gui, this);
        this.client = new ClientSettingsPage(gui);
        this.waypoints = new WaypointsPage(gui, this);
    }

    void openMod(Mod m) {
        tab = Tab.MODS;
        open = m;
        gui.mm.touch(m.id);
        settings.open(m);
    }

    void closeMod() { open = null; }

    void showTab(Tab t) {
        tab = t;
        open = null;
    }

    void showSettings(ClientSettingsPage.Section s) {
        tab = Tab.SETTINGS;
        client.section = s;
    }

    /** Esc inside the window: leave a mod's settings first. */
    boolean back() {
        if (tab == Tab.MODS && open != null) {
            open = null;
            return true;
        }
        return false;
    }

    boolean typeToSearch(char c) {
        if (tab != Tab.MODS || gui.ui.popupOpen() || !Character.isLetterOrDigit(c)) return false;
        if (open != null) return settings.typeToSearch(c);
        return list.typeToSearch(c);
    }

    void render(UiRenderer r, GameState s) {
        Ui ui = gui.ui;
        float sw = gui.screenW, sh = gui.screenH;
        float w = Math.round(Math.min(640, sw - 24)), h = Math.round(Math.min(380, sh - 24));
        float t = gui.intro(0.2f);
        float x = Math.round((sw - w) / 2f), y = Math.round((sh - h) / 2f + (1 - t) * 8);

        r.rect(x - 1, y - 1, w + 2, h + 2, alpha(0x40FFFFFF, t), 7);
        r.rect(x, y, w, h, alpha(WINDOW, t), 6);
        header(ui, x, y, w);
        r.rect(x, y + HEADER, w, 1, 0x24FFFFFF, 0);

        float by = y + HEADER + 1, bh = h - HEADER - 1;
        r.rect(x + SIDEBAR, by, 1, bh, 0x1CFFFFFF, 0);
        if (tab == Tab.SETTINGS) client.sidebar(ui, x, by, SIDEBAR, bh);
        else profiles(ui, x, by, SIDEBAR, bh);

        float cx = x + SIDEBAR + 10, cy = by + 8, cw = w - SIDEBAR - 20, ch = bh - 14;
        switch (tab) {
            case SETTINGS: client.render(ui, s, cx, cy, cw, ch); break;
            case WAYPOINTS: waypoints.render(ui, s, cx, cy, cw, ch); break;
            default:
                if (open != null) settings.render(ui, s, cx, cy, cw, ch);
                else list.render(ui, s, cx, cy, cw, ch);
                break;
        }
        ui.drawFlash(x + w / 2f, y + h - ui.fh() - 14);
    }

    private void header(Ui ui, float x, float y, float w) {
        ClientGui.drawLogo(ui.r, x + 9, y + 7, 14);
        ui.display("Kite Client", x + 28, y + (HEADER - ui.fh()) / 2f + 1, 1f, TEXT);

        // Tabs, centred.
        Tab[] tabs = Tab.values();
        float tabW = 74, total = tabs.length * tabW, tx = x + (w - total) / 2f;
        for (final Tab tb : tabs) {
            boolean sel = tab == tb, hover = ui.over(tx, y + 4, tabW, HEADER - 8);
            if (sel) ui.r.rect(tx + 2, y + 4, tabW - 4, HEADER - 8, 0x268B5CF6, 4);
            else if (hover) ui.r.rect(tx + 2, y + 4, tabW - 4, HEADER - 8, 0x12FFFFFF, 4);
            String label = Ui.title(tb.name());
            ui.textCentered(label, tx + tabW / 2f, y + (HEADER - ui.fh()) / 2f + 1, sel ? TEXT : hover ? LABEL : MUTED);
            if (sel) ui.r.rect(tx + tabW / 2f - 10, y + HEADER - 3, 20, 2, ACCENT, 1);
            ui.hit(tx + 2, y + 4, tabW - 4, HEADER - 8, Ui.Sound.SELECT, (mx, my, b) -> showTab(tb));
            tx += tabW;
        }

        // Close.
        float bx = x + w - 22, byy = y + 6;
        boolean hover = ui.over(bx, byy, 16, 16);
        if (hover) ui.r.rect(bx, byy, 16, 16, 0x33E07A84, 4);
        cross(ui, bx + 8, byy + 8, hover ? DANGER : MUTED);
        ui.hit(bx, byy, 16, 16, (mx, my, b) -> gui.close());
    }

    /** An "x" centred at (cx, cy): the anti-aliased close icon, or 1px squares when the renderer has no images. */
    static void cross(Ui ui, float cx, float cy, int color) {
        if (Icons.draw(ui.r, "close", cx - 7.5f, cy - 7.5f, 15, color)) return;
        for (int i = -3; i <= 3; i++) {
            ui.r.rect(cx + i - 0.5f, cy + i - 0.5f, 1.5f, 1.5f, color, 0);
            ui.r.rect(cx + i - 0.5f, cy - i - 0.5f, 1.5f, 1.5f, color, 0);
        }
    }

    // ---- profiles sidebar -------------------------------------------------------------------------------------------

    private void profiles(final Ui ui, float x, float y, float w, float h) {
        final Profiles pr = gui.mm.profiles();
        ui.text("PROFILES", x + 10, y + 9, FAINT);
        float fw = ui.tw("Folder") + 8;
        ui.button("Folder", x + w - fw - 6, y + 5, fw, 14, Ui.Style.GHOST, (mx, my, b) -> gui.host.openProfilesFolder());

        float bottomH = 3 * 18 + 2 * 4 + 8;
        float ly = y + 24, lh = h - 24 - bottomH;
        final float rowH = 20;
        List<Profiles.Profile> all = pr.all();
        float off = ui.beginScroll(profileScroll, x + 4, ly, w - 8, lh, all.size() * rowH);
        float ry = ly - off;
        for (final Profiles.Profile p : all) {
            boolean active = p == pr.active(), hover = ui.over(x + 4, ry, w - 8, rowH - 2);
            if (active) {
                ui.r.rect(x + 4, ry, w - 8, rowH - 2, 0x338B5CF6, 4);
                ui.r.rect(x + 4, ry + 4, 2, rowH - 10, ACCENT, 1);
            } else if (hover) {
                ui.r.rect(x + 4, ry, w - 8, rowH - 2, 0x14FFFFFF, 4);
            }
            ui.item(p.icon.str(), x + 10, ry + 3, 12);
            float nameW = w - 40 - (hover || active ? 14 : 0);
            ui.textMid(ui.fit(p.name.str(), nameW), x + 26, ry, rowH - 2, active ? TEXT : LABEL);
            final float rowY = ry;
            if (hover || active) {
                float ex = x + w - 22;
                boolean eh = ui.over(ex, ry + 2, 14, rowH - 6);
                ui.textMid("...", ex + 2, ry - 2, rowH - 2, eh ? ACCENT : MUTED);
                ui.hit(x + 4, ry, w - 8, rowH - 2, (mx, my, b) -> {
                    if (b == 1 || mx >= ex) editProfile(p, x + w + 4, rowY);
                    else switchTo(p);
                });
            } else {
                ui.hit(x + 4, ry, w - 8, rowH - 2, (mx, my, b) -> {
                    if (b == 1) editProfile(p, x + w + 4, rowY);
                    else switchTo(p);
                });
            }
            ry += rowH;
        }
        ui.endScroll(profileScroll, x + 4, ly, w - 12, lh, all.size() * rowH);

        float bx = x + 6, bw = w - 12, by = y + h - bottomH + 4;
        ui.button("Save as new profile", bx, by, bw, 18, Ui.Style.SECONDARY, (mx, my, b) -> {
            Profiles.Profile p = gui.mm.createProfile("New profile");
            gui.mm.switchProfile(p, true);
            gui.host.save();
            editProfile(p, x + w + 4, by - 60);
        });
        ui.button("Import profile", bx, by + 22, bw, 18, Ui.Style.SECONDARY, (mx, my, b) -> importProfile());
        ui.button("Edit HUD layout", bx, by + 44, bw, 18, Ui.Style.PRIMARY, (mx, my, b) -> gui.openEditor());
    }

    private void switchTo(Profiles.Profile p) {
        if (p == gui.mm.profiles().active()) return;
        gui.mm.switchProfile(p, true);
        gui.host.save();
        gui.ui.flash("Switched to \"" + p.name.str() + "\"");
    }

    private void editProfile(Profiles.Profile p, float x, float y) { gui.ui.openPopup(new ProfileEditor(gui, p, x, y)); }

    /** Clipboard JSON first, then any files dropped into the profiles folder. */
    private void importProfile() {
        String clip = gui.mm.host().clipboardText();
        Profiles.Profile p = clip == null || clip.trim().isEmpty() ? null : gui.mm.importProfile(clip);
        if (p != null) {
            gui.host.save();
            gui.ui.flash("Imported \"" + p.name.str() + "\" from the clipboard");
            return;
        }
        int n = gui.host.rescanProfiles();
        if (n > 0) {
            gui.host.save();
            gui.ui.flash("Imported " + n + " profile" + (n == 1 ? "" : "s") + " from the profiles folder");
        } else {
            gui.ui.flash("Copy a profile's JSON, or drop its file into the profiles folder", WARNING);
        }
    }
}
