package gg.aether.core.ui;

import gg.aether.core.GameState;
import gg.aether.core.Keys;
import gg.aether.core.Mod;
import gg.aether.core.ModManager;
import gg.aether.core.Setting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static gg.aether.core.ui.Theme.*;

/** The window's Settings tab: client-wide options, the keybind manager and developer info. */
final class ClientSettingsPage {
    enum Section { GENERAL, KEYBINDS, DEVELOPER }

    private final ClientGui gui;
    Section section = Section.GENERAL;
    private final Ui.Text search = new Ui.Text("", 32);
    private final Ui.Scroll scroll = new Ui.Scroll();
    private Mod inspected;

    ClientSettingsPage(ClientGui gui) { this.gui = gui; }

    void sidebar(Ui ui, float x, float y, float w, float h) {
        ui.text("SETTINGS", x + 10, y + 9, FAINT);
        float ry = y + 24;
        for (final Section s : Section.values()) {
            boolean sel = section == s, hover = ui.over(x + 4, ry, w - 8, 18);
            if (sel) {
                ui.r.rect(x + 4, ry, w - 8, 18, 0x338B5CF6, 4);
                ui.r.rect(x + 4, ry + 4, 2, 10, ACCENT, 1);
            } else if (hover) {
                ui.r.rect(x + 4, ry, w - 8, 18, 0x14FFFFFF, 4);
            }
            ui.textMid(Ui.title(s.name()), x + 12, ry, 18, sel ? TEXT : LABEL);
            if (s == Section.KEYBINDS && !gui.mm.keybindConflicts().isEmpty()) ui.textMid("!", x + w - 14, ry, 18, WARNING);
            if (s == Section.DEVELOPER && !gui.mm.failures().isEmpty()) ui.textMid(String.valueOf(gui.mm.failures().size()), x + w - 16, ry, 18, DANGER);
            ui.hit(x + 4, ry, w - 8, 18, Ui.Sound.SELECT, (mx, my, b) -> {
                section = s;
                search.value = "";
                scroll.reset();
            });
            ry += 20;
        }
    }

    void render(Ui ui, GameState s, float x, float y, float w, float h) {
        switch (section) {
            case KEYBINDS: keybinds(ui, x, y, w, h); break;
            case DEVELOPER: developer(ui, x, y, w, h); break;
            default: general(ui, x, y, w, h); break;
        }
    }

    // ---- general ----------------------------------------------------------------------------------------------------

    private void general(Ui ui, float x, float y, float w, float h) {
        ui.display("General", x, y + 4, 1f, TEXT);
        ui.text("Saved for every profile", x, y + 18, MUTED);
        final ModManager mm = gui.mm;
        List<ModSettingsPage.Row> rows = new ArrayList<ModSettingsPage.Row>();
        for (Setting<?> st : mm.client().all()) {
            String section = mm.client().sectionBefore(st);
            if (section != null) rows.add(ModSettingsPage.section(section));
            if (st == mm.client().customMainMenu) {
                rows.add(ModSettingsPage.settingRow(mm.menuKey));
                rows.add(ModSettingsPage.note(mm.menuKey.bound() ? "Ctrl + RShift also opens the menu, whatever the key"
                        : "Unbound: RShift opens the menu (Ctrl + RShift always does)"));
            }
            if (st.visible()) rows.add(ModSettingsPage.settingRow(st));
        }
        rows.add(ModSettingsPage.section("Data"));
        rows.add(ModSettingsPage.custom("Profiles folder", (u, cx, cy, cw) ->
                u.button("Open", cx, cy + 3, cw, 16, Ui.Style.SECONDARY, (mx, my, b) -> gui.host.openProfilesFolder())));
        rows.add(ModSettingsPage.custom("Reset every mod in this profile", (u, cx, cy, cw) ->
                u.confirmButton("reset-all", "Reset all", cx + cw, cy + 3, 16, () -> {
                    mm.resetCategory(null);
                    gui.ui.flash("Every mod reset to defaults");
                })));
        list(ui, rows, x, y + 32, w, h - 32);
    }

    private void list(Ui ui, List<ModSettingsPage.Row> rows, float x, float top, float w, float viewH) {
        float total = 0;
        for (ModSettingsPage.Row r : rows) total += r.height();
        float off = ui.beginScroll(scroll, x, top, w, viewH, total);
        float ry = top - off;
        for (ModSettingsPage.Row r : rows) {
            float rh = r.height();
            if (ry + rh >= top && ry <= top + viewH) r.draw(ui, x, ry, w);
            ry += rh;
        }
        ui.endScroll(scroll, x, top, w, viewH, total);
    }

    // ---- keybinds ---------------------------------------------------------------------------------------------------

    private static final class Bind {
        final String owner;
        final Setting.Key key;
        final Setting.Choice<?> mode;
        Bind(String owner, Setting.Key key, Setting.Choice<?> mode) { this.owner = owner; this.key = key; this.mode = mode; }
    }

    private List<Bind> binds() {
        List<Bind> out = new ArrayList<Bind>();
        out.add(new Bind("Kite", gui.mm.menuKey, null));
        for (Mod m : gui.mm.all()) {
            for (Setting<?> st : m.settings()) {
                if (!(st instanceof Setting.Key)) continue;
                Setting.Choice<?> mode = null;
                for (Setting<?> o : m.settings()) if (o instanceof Setting.Choice && o.key.equals(st.key + "Mode")) mode = (Setting.Choice<?>) o;
                out.add(new Bind(m.name, (Setting.Key) st, mode));
            }
        }
        return out;
    }

    private void keybinds(Ui ui, float x, float y, float w, float h) {
        List<Bind> all = binds();
        Map<Integer, Integer> uses = new HashMap<Integer, Integer>();
        for (Bind b : all) if (b.key.code() != Keys.NONE) uses.put(b.key.code(), uses.containsKey(b.key.code()) ? uses.get(b.key.code()) + 1 : 1);
        int conflicts = 0;
        for (Integer n : uses.values()) if (n > 1) conflicts++;
        ui.display("Keybinds", x, y + 4, 1f, TEXT);
        String info = conflicts == 0 ? "No conflicts" : conflicts + " key" + (conflicts == 1 ? " is" : "s are") + " bound twice";
        ui.text(info + ". Click to rebind; Backspace, right-click or x to unbind.", x, y + 18, conflicts == 0 ? MUTED : WARNING);
        float sw = Math.min(140, w * 0.35f);
        ui.searchBox(search, x + w - sw, y, sw, 18, "Search keybinds...");

        String q = search.value.toLowerCase(Locale.ROOT).trim();
        List<Bind> list = new ArrayList<Bind>();
        for (Bind b : all) {
            String hay = (b.owner + " " + b.key.label + " " + Keys.name(b.key.code())).toLowerCase(Locale.ROOT);
            if (q.isEmpty() || hay.contains(q)) list.add(b);
        }
        float top = y + 32, viewH = h - 32, total = list.size() * Ui.ROW;
        float off = ui.beginScroll(scroll, x, top, w, viewH, total);
        float ry = top - off;
        float keyW = Math.min(90, w * 0.25f), modeW = Math.min(64, w * 0.18f);
        for (final Bind b : list) {
            if (ry + Ui.ROW >= top && ry <= top + viewH) {
                boolean conflict = b.key.code() != Keys.NONE && uses.get(b.key.code()) > 1;
                ModSettingsPage.rowBackground(ui, x, ry, w);
                float labelW = w - keyW - modeW - 16;
                String owner = b.owner + ": ";
                ui.textMid(ui.fit(owner, labelW * 0.5f), x + 6, ry, Ui.ROW, MUTED);
                float ox = x + 6 + Math.min(ui.tw(owner), labelW * 0.5f);
                ui.textMid(ui.fit(b.key.label, labelW - (ox - x)), ox, ry, Ui.ROW, conflict ? WARNING : LABEL);
                ui.keybind(b.key, x + w - keyW - modeW - 8, ry + 3, keyW, conflict);
                if (b.mode != null) ui.dropdown(b.mode, x + w - modeW - 4, ry + 3, modeW, 16);
            }
            ry += Ui.ROW;
        }
        ui.endScroll(scroll, x, top, w, viewH, total);
    }

    // ---- developer --------------------------------------------------------------------------------------------------

    private static final class Line {
        final String text;
        final int color;
        final Mod inspect;
        Line(String text, int color, Mod inspect) { this.text = text; this.color = color; this.inspect = inspect; }
    }

    private void developer(Ui ui, float x, float y, float w, float h) {
        ModManager mm = gui.mm;
        ui.display("Developer", x, y + 4, 1f, TEXT);
        List<Line> lines = new ArrayList<Line>();
        head(lines, "Runtime");
        line(lines, mm.all().size() + " mods registered, profile \"" + mm.profiles().active().name.str() + "\" (" + mm.profiles().all().size() + " total)");
        line(lines, "Plugins: " + (mm.plugins().isEmpty() ? "none" : String.join(", ", mm.plugins())));
        head(lines, "Mod errors");
        if (mm.failures().isEmpty() && mm.events().errors().isEmpty()) line(lines, "none");
        for (String f : mm.failures()) lines.add(new Line(f, DANGER, null));
        for (String f : mm.events().errors()) lines.add(new Line("event " + f, DANGER, null));
        head(lines, "Event monitor (posted / listeners)");
        Map<String, Integer> listeners = mm.events().listeners();
        for (Map.Entry<String, Long> e : mm.events().counts().entrySet()) {
            Integer n = listeners.get(e.getKey());
            line(lines, e.getKey() + ": " + e.getValue() + " / " + (n == null ? 0 : n));
        }
        head(lines, "Overrides this tick");
        for (String o : ClientGui.overrideSummary(mm.overrides())) line(lines, o);
        head(lines, "Config inspector (click an enabled mod)");
        for (Mod m : mm.all()) {
            if (!m.enabled()) continue;
            lines.add(new Line((m == inspected ? "- " : "+ ") + m.name + " (" + m.id + ")", m == inspected ? ACCENT : LABEL, m));
            if (m != inspected) continue;
            for (Setting<?> st : m.settings()) lines.add(new Line("    " + st.key + " = " + st.toJson(), MUTED, null));
            if (m.hud() != null) lines.add(new Line("    hud = " + m.hud().toJson(), MUTED, null));
        }
        float top = y + 22, viewH = h - 22, lh = ui.fh() + 3, total = lines.size() * lh;
        float off = ui.beginScroll(scroll, x, top, w, viewH, total);
        float ly = top - off;
        for (final Line l : lines) {
            if (ly + lh >= top && ly <= top + viewH) {
                ui.text(ui.fit(l.text, w), x, ly, l.color);
                if (l.inspect != null) ui.hit(x, ly, w, lh, (mx, my, b) -> inspected = inspected == l.inspect ? null : l.inspect);
            }
            ly += lh;
        }
        ui.endScroll(scroll, x, top, w, viewH, total);
    }

    private static void head(List<Line> lines, String t) {
        if (!lines.isEmpty()) lines.add(new Line("", MUTED, null));
        lines.add(new Line(t, ACCENT, null));
    }

    private static void line(List<Line> lines, String t) { lines.add(new Line(t, LABEL, null)); }
}
