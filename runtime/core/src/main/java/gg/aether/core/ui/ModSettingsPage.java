package gg.aether.core.ui;

import gg.aether.core.GameState;
import gg.aether.core.HudElement;
import gg.aether.core.Mod;
import gg.aether.core.Setting;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static gg.aether.core.ui.Theme.*;

/** One mod's settings: back, icon and name, search, reset, enable switch, description, then every option. */
final class ModSettingsPage {
    /** One line (or block) of the options list. */
    interface Row {
        String label();
        float height();
        void draw(Ui ui, float x, float y, float w);
    }

    private final ClientGui gui;
    private final ModWindow window;
    private Mod mod;
    final Ui.Text search = new Ui.Text("", 32);
    private final Ui.Scroll scroll = new Ui.Scroll();

    ModSettingsPage(ClientGui gui, ModWindow window) {
        this.gui = gui;
        this.window = window;
    }

    void open(Mod m) {
        mod = m;
        search.value = "";
        scroll.reset();
    }

    boolean typeToSearch(char c) {
        gui.ui.focus(search, search, null, null);
        gui.ui.charTyped(c);
        scroll.reset();
        return true;
    }

    void render(Ui ui, GameState s, float x, float y, float w, float h) {
        final Mod m = mod;
        // Row 1: back, icon, name ... enable switch, reset. Row 2: description, search.
        ui.button("< Back", x, y, 44, 18, Ui.Style.SECONDARY, (mx, my, b) -> window.closeMod());
        ui.icon(m.icon(), x + 52, y + 1, 16, m.enabled() ? ACCENT : MUTED);
        float right = x + w;
        right = ui.confirmButton("reset:" + m.id, "Reset", right, y, 18, () -> {
            m.resetSettings();
            gui.ui.flash(m.name + " reset to defaults");
        }) - 6;
        ui.toggle(right - 24, y + 3, m.enabled(), () -> gui.mm.toggleByUser(m.id));
        right -= 32;
        // The name in the display face, smaller when long, clipped in the UI face when it still doesn't fit.
        float nameW = right - (x + 74);
        float nameScale = ui.displayWidth(m.name, 1.25f) <= nameW ? 1.25f : 1f;
        if (ui.displayWidth(m.name, nameScale) <= nameW) ui.display(m.name, x + 74, y + (18 - ui.fh() * nameScale) / 2f + 1, nameScale, TEXT);
        else ui.textMid(ui.fit(m.name, nameW), x + 74, y, 18, TEXT);

        float sw = Math.min(130, w * 0.32f);
        float dy = y + 24;
        ui.searchBox(search, x + w - sw, dy, sw, 16, "Search options...");
        float descW = w - sw - 10;
        List<String> desc = ui.wrap(m.description, descW, 2);
        float ty = dy + (desc.size() == 1 ? (16 - ui.fh()) / 2f + 1 : -2);
        for (String line : desc) {
            ui.text(line, x, ty, MUTED);
            ty += ui.fh() + 2;
        }
        dy = Math.max(dy + 16, ty) + 2;
        if (!gui.mm.allowed(m)) {
            ui.text("Blocked by the server or competitive mode", x, dy, WARNING);
            dy += ui.fh() + 2;
        }
        dy += 4;
        ui.r.rect(x, dy, w, 1, 0x1CFFFFFF, 0);
        dy += 4;

        List<Row> rows = filter(rows(m), search.value);
        float top = dy, viewH = y + h - dy;
        if (rows.isEmpty()) {
            String t = search.value.isEmpty() ? "This mod has no options" : "No options match \"" + search.value + "\"";
            ui.textCentered(t, x + w / 2f, top + 20, MUTED);
            return;
        }
        float total = 0;
        for (Row r : rows) total += r.height();
        float off = ui.beginScroll(scroll, x, top, w, viewH, total);
        float ry = top - off;
        for (Row r : rows) {
            float rh = r.height();
            if (ry + rh >= top && ry <= top + viewH) r.draw(ui, x, ry, w);
            ry += rh;
        }
        ui.endScroll(scroll, x, top, w, viewH, total);
    }

    static List<Row> filter(List<Row> rows, String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return rows;
        List<Row> out = new ArrayList<Row>();
        for (Row r : rows) if (r.label() != null && r.label().toLowerCase(Locale.ROOT).contains(q)) out.add(r);
        return out;
    }

    /**
     * The option rows of {@code m}: its visible settings in declaration order (children of an off toggle are
     * skipped, see {@link Setting#dependsOn}), then the universal HUD options for HUD mods. Rebuilt every frame, so
     * flipping a toggle shows or hides its children at once.
     */
    List<Row> rows(final Mod m) {
        List<Row> rows = new ArrayList<Row>();
        for (Setting<?> st : m.settings()) if (st.visible()) rows.add(settingRow(st));
        final HudElement hud = m.hud();
        if (hud == null) return rows;
        rows.add(section("HUD"));
        rows.add(custom("Scale", (ui, x, y, w) -> ui.slider(x, y, w, (hud.scale() - HudElement.MIN_SCALE) / (HudElement.MAX_SCALE - HudElement.MIN_SCALE),
                String.format(Locale.ROOT, "%.2fx", hud.scale()),
                f -> hud.setScale(Math.round((HudElement.MIN_SCALE + f * (HudElement.MAX_SCALE - HudElement.MIN_SCALE)) * 20f) / 20f))));
        rows.add(custom("Opacity", (ui, x, y, w) -> ui.slider(x, y, w, hud.opacity(), Math.round(hud.opacity() * 100) + "%",
                f -> hud.setOpacity(Math.round(f * 20f) / 20f))));
        rows.add(custom("Position (" + Ui.title(hud.anchor.name()) + ")", (ui, x, y, w) -> {
            float bw = (w - 4) / 2f;
            ui.button("Move...", x, y + 3, bw, 16, Ui.Style.SECONDARY, (mx, my, b) -> gui.openEditor());
            ui.button("Reset", x + bw + 4, y + 3, bw, 16, Ui.Style.SECONDARY, (mx, my, b) -> hud.reset());
        }));
        rows.add(custom("Hide while F3 is open", (ui, x, y, w) -> ui.toggle(x + w - 24, y + 5, hud.hideInDebug, () -> hud.hideInDebug = !hud.hideInDebug)));
        rows.add(custom("Hide while chat is open", (ui, x, y, w) -> ui.toggle(x + w - 24, y + 5, hud.hideInChat, () -> hud.hideInChat = !hud.hideInChat)));
        rows.add(custom("Hide in inventory and menus", (ui, x, y, w) -> ui.toggle(x + w - 24, y + 5, hud.hideInScreens, () -> hud.hideInScreens = !hud.hideInScreens)));
        return rows;
    }

    interface Control { void draw(Ui ui, float x, float y, float w); }

    /** A labelled row whose control fills the right part. */
    static Row custom(final String label, final Control c) { return custom(label, 0, c); }

    /** Pixels a child setting's label moves right per {@link Setting#depth} level. */
    static final float INDENT = 12;

    /** {@link #custom} indented {@code depth} levels, with a guide line showing it belongs to the row above. */
    static Row custom(final String label, final int depth, final Control c) {
        return new Row() {
            @Override public String label() { return label; }
            @Override public float height() { return Ui.ROW; }
            @Override public void draw(Ui ui, float x, float y, float w) {
                float cw = controlWidth(w);
                float in = depth * INDENT;
                rowBackground(ui, x, y, w);
                if (depth > 0) ui.r.rect(x + in - 3, y + 2, 1, Ui.ROW - 4, 0x33FFFFFF, 0);
                ui.textMid(ui.fit(label, w - cw - 12 - in), x + 6 + in, y, Ui.ROW, LABEL);
                c.draw(ui, x + w - cw - 4, y, cw);
            }
        };
    }

    /** A line of muted help text. */
    static Row note(final String text) {
        return new Row() {
            @Override public String label() { return null; }
            @Override public float height() { return Ui.ROW - 6; }
            @Override public void draw(Ui ui, float x, float y, float w) { ui.textMid(ui.fit(text, w - 12), x + 6, y, Ui.ROW - 6, MUTED); }
        };
    }

    static Row section(final String title) {
        return new Row() {
            @Override public String label() { return null; }
            @Override public float height() { return Ui.ROW; }
            @Override public void draw(Ui ui, float x, float y, float w) {
                ui.text(title.toUpperCase(Locale.ROOT), x + 6, y + Ui.ROW - ui.fh() - 3, ACCENT);
                ui.r.rect(x + 12 + ui.tw(title.toUpperCase(Locale.ROOT)), y + Ui.ROW - ui.fh() / 2f - 3, w - 18 - ui.tw(title.toUpperCase(Locale.ROOT)), 1, 0x1CFFFFFF, 0);
            }
        };
    }

    static Row settingRow(final Setting<?> st) {
        if (st instanceof Setting.Entries) {
            final Setting.Entries e = (Setting.Entries) st;
            return new Row() {
                @Override public String label() { return e.label; }
                @Override public float height() { return Ui.ROW * Ui.tableRows(e); }
                @Override public void draw(Ui ui, float x, float y, float w) { ui.table(e, x + 6, y, w - 10); }
            };
        }
        return custom(st.label, st.depth(), (ui, x, y, w) -> ui.control(st, x, y, w));
    }

    static float controlWidth(float rowW) { return Math.min(150, rowW * 0.45f); }

    static void rowBackground(Ui ui, float x, float y, float w) {
        if (ui.over(x, y + 1, w, Ui.ROW - 2)) ui.r.rect(x, y + 1, w, Ui.ROW - 2, 0x0DFFFFFF, 3);
    }
}
