package gg.aether.core.ui;

import gg.aether.core.ClientSettings;
import gg.aether.core.GameState;
import gg.aether.core.Mod;
import gg.aether.core.ModManager;
import java.util.List;

import static gg.aether.core.ui.Theme.*;

/**
 * The mod list: ALL / NEW / HUD / SERVER / MECHANIC tabs, search and two layouts: three columns of wide 8:1 rows
 * (click toggles, the gear opens settings) or tall 1:2 cards (click opens settings, the bottom bar toggles).
 */
final class ModListPage {
    private static final float BAR = 18, GAP = 5;

    private final ClientGui gui;
    private final ModWindow window;
    final Ui.Text search = new Ui.Text("", 32);
    ModCatalog.Tab tab = ModCatalog.Tab.ALL;
    private final Ui.Scroll scroll = new Ui.Scroll();

    ModListPage(ClientGui gui, ModWindow window) {
        this.gui = gui;
        this.window = window;
    }

    boolean typeToSearch(char c) {
        gui.ui.focus(search, search, null, null);
        gui.ui.charTyped(c);
        scroll.reset();
        return true;
    }

    void render(Ui ui, GameState s, float x, float y, float w, float h) {
        ModManager mm = gui.mm;
        final ClientSettings cs = mm.client();
        // Tabs on the left, layout switch on the right; the search box joins them when there is room, otherwise it
        // gets its own row.
        float chipsW = 0;
        for (ModCatalog.Tab t : ModCatalog.Tab.values()) chipsW += chipWidth(ui, mm, t) + 4;
        float controlsW = BAR * 2 + 9;
        boolean oneRow = chipsW + 90 + controlsW <= w;
        float tx = x;
        for (final ModCatalog.Tab t : ModCatalog.Tab.values()) {
            String label = t.name();
            int n = t == ModCatalog.Tab.NEW ? ModCatalog.count(mm, t) : 0;
            float cw = chipWidth(ui, mm, t);
            boolean sel = tab == t, hover = ui.over(tx, y, cw, BAR);
            ui.r.rect(tx, y, cw, BAR, sel ? ACCENT : hover ? 0x26FFFFFF : 0x14FFFFFF, BAR / 2f);
            ui.textMid(label, tx + 7, y, BAR, sel ? 0xFFFFFFFF : hover ? TEXT : MUTED);
            if (n > 0) ui.textMid(String.valueOf(n), tx + 7 + ui.tw(label) + 4, y, BAR, sel ? 0xFFFFFFFF : WARNING);
            ui.hit(tx, y, cw, BAR, Ui.Sound.SELECT, (mx, my, b) -> {
                tab = t;
                scroll.reset();
            });
            tx += cw + 4;
        }
        // Narrow: chips alone on the first row, search and the layout switch on the second.
        float cy = oneRow ? y : y + BAR + 5;
        float right = x + w;
        right = layoutButton(ui, ClientSettings.ModLayout.CARDS, right - BAR, cy, cs) - 3;
        right = layoutButton(ui, ClientSettings.ModLayout.LIST, right - BAR, cy, cs) - 6;
        float ly;
        if (oneRow) {
            float sw = Math.min(150, right - tx - 4);
            ui.searchBox(search, right - sw, y, sw, BAR, "Search mods...");
            ly = y + BAR + 8;
        } else {
            ui.searchBox(search, x, cy, right - x, BAR, "Search mods...");
            ly = cy + BAR + 8;
        }
        float lh = y + h - ly;
        List<Mod> list = ModCatalog.list(mm, tab, search.value);
        if (list.isEmpty()) {
            String t = !search.value.isEmpty() ? "No mods match \"" + search.value + "\"" : tab == ModCatalog.Tab.NEW ? "You've seen every mod" : "Nothing here";
            ui.textCentered(t, x + w / 2f, ly + lh / 2f - 4, MUTED);
            return;
        }
        if (cs.layout.val() == ClientSettings.ModLayout.CARDS) cards(ui, list, x, ly, w, lh);
        else rows(ui, list, x, ly, w, lh);
    }

    private static float chipWidth(Ui ui, ModManager mm, ModCatalog.Tab t) {
        int n = t == ModCatalog.Tab.NEW ? ModCatalog.count(mm, t) : 0;
        return ui.tw(t.name()) + 14 + (n > 0 ? ui.tw(String.valueOf(n)) + 4 : 0);
    }

    private float layoutButton(Ui ui, final ClientSettings.ModLayout l, float x, float y, final ClientSettings cs) {
        boolean sel = cs.layout.val() == l, hover = ui.over(x, y, BAR, BAR);
        ui.r.rect(x, y, BAR, BAR, sel ? 0x338B5CF6 : hover ? 0x26FFFFFF : 0x14FFFFFF, 4);
        int c = sel ? ACCENT : hover ? TEXT : MUTED;
        if (l == ClientSettings.ModLayout.LIST) {
            for (int i = 0; i < 3; i++) ui.r.rect(x + 4, y + 5 + i * 3.5f, BAR - 8, 2, c, 0);
        } else {
            ui.r.rect(x + 4, y + 4, 4, BAR - 8, c, 1);
            ui.r.rect(x + 10, y + 4, 4, BAR - 8, c, 1);
        }
        ui.hit(x, y, BAR, BAR, (mx, my, b) -> {
            cs.layout.set(l);
            scroll.reset();
        });
        return x;
    }

    // ---- list layout: 3 columns of 8:1 rows -------------------------------------------------------------------------

    private void rows(Ui ui, List<Mod> list, float x, float y, float w, float h) {
        int cols = w >= 300 ? 3 : 2;
        float cw = (w - GAP * (cols - 1)) / cols, rh = Math.max(20, Math.round(cw / 8f));
        int lines = (list.size() + cols - 1) / cols;
        float total = lines * (rh + GAP) - GAP;
        float off = ui.beginScroll(scroll, x, y, w, h, total);
        for (int i = 0; i < list.size(); i++) {
            float rx = x + (i % cols) * (cw + GAP), ry = y + (i / cols) * (rh + GAP) - off;
            if (ry + rh < y || ry > y + h) continue;
            row(ui, list.get(i), rx, ry, cw, rh);
        }
        ui.endScroll(scroll, x, y, w, h, total);
    }

    private void row(Ui ui, final Mod m, float x, float y, float w, float h) {
        ModManager mm = gui.mm;
        boolean on = m.enabled(), allowed = mm.allowed(m), hover = ui.over(x, y, w, h);
        int edge = !allowed ? WARNING : on ? SUCCESS : DANGER;
        ui.r.rect(x, y, w, h, hover ? withAlpha(edge, 0x90) : withAlpha(edge, 0x48), 4);
        ui.r.rect(x + 1, y + 1, w - 2, h - 2, hover ? RAISED_HOVER : RAISED, 3);
        float icon = Math.min(14, h - 8);
        ui.icon(m.icon(), x + 6, y + (h - icon) / 2f, icon, !allowed ? WARNING : on ? (hover ? 0xFF9DB2FF : ACCENT) : FAINT);
        float gearW = 18;
        float nx = x + 11 + icon, nw = w - (nx - x) - gearW - 6;
        boolean isNew = !mm.seen(m);
        if (isNew) nw -= ui.tw("NEW") + 8;
        String name = ui.fit(m.name, nw);
        ui.textMid(name, nx, y, h, on ? TEXT : MUTED);
        if (isNew) ui.badge("NEW", nx + ui.tw(name) + 4, y + (h - ui.fh() - 2) / 2f, WARNING);

        // Settings gear.
        final float gx = x + w - gearW - 3, gy = y + 2;
        boolean gh = ui.over(gx, gy, gearW, h - 4);
        if (gh) ui.r.rect(gx, gy, gearW, h - 4, 0x338B5CF6, 3);
        gear(ui, gx + gearW / 2f, y + h / 2f, gh ? ACCENT : FAINT);

        ui.hit(x, y, w, h, (mx, my, b) -> {
            if (b == 1 || mx >= gx) window.openMod(m);
            else toggle(ui, m);
        });
    }

    // ---- card layout: 1:2 cards -------------------------------------------------------------------------------------

    private void cards(Ui ui, List<Mod> list, float x, float y, float w, float h) {
        int cols = Math.max(3, (int) ((w + GAP) / (70 + GAP)));
        float cw = (w - GAP * (cols - 1)) / cols, ch = Math.round(Math.min(cw * 2, h - 2));
        int lines = (list.size() + cols - 1) / cols;
        float total = lines * (ch + GAP) - GAP;
        float off = ui.beginScroll(scroll, x, y, w, h, total);
        for (int i = 0; i < list.size(); i++) {
            float cx = x + (i % cols) * (cw + GAP), cy = y + (i / cols) * (ch + GAP) - off;
            if (cy + ch < y || cy > y + h) continue;
            card(ui, list.get(i), cx, cy, cw, ch);
        }
        ui.endScroll(scroll, x, y, w, h, total);
    }

    private void card(Ui ui, final Mod m, float x, float y, float w, float h) {
        ModManager mm = gui.mm;
        boolean on = m.enabled(), allowed = mm.allowed(m), hover = ui.over(x, y, w, h);
        ui.r.rect(x, y, w, h, hover ? BORDER_HOVER : BORDER, 5);
        ui.r.rect(x + 1, y + 1, w - 2, h - 2, hover ? RAISED_HOVER : RAISED, 4);
        float icon = Math.round(Math.min(28, w * 0.4f));
        float iy = y + h * 0.2f;
        // Soft glow behind the icon.
        ui.r.rect(x + (w - icon - 12) / 2f, iy - 6, icon + 12, icon + 12, on ? 0x1A8B5CF6 : 0x0CFFFFFF, (icon + 12) / 2f);
        ui.icon(m.icon(), x + (w - icon) / 2f, iy, icon, on ? (hover ? 0xFF9DB2FF : ACCENT) : MUTED);
        float ty = iy + icon + 10;
        for (String line : ui.wrap(m.name, w - 8, 2)) {
            ui.textCentered(line, x + w / 2f, ty, on ? TEXT : LABEL);
            ty += ui.fh() + 2;
        }
        if (!mm.seen(m)) ui.badge("NEW", x + 4, y + 4, WARNING);

        float barH = 18, by = y + h - barH - 4;
        boolean bh = ui.over(x + 4, by, w - 8, barH);
        String state = !allowed ? "BLOCKED" : on ? "ENABLED" : "DISABLED";
        int color = !allowed ? WARNING : on ? SUCCESS : DANGER;
        ui.r.rect(x + 4, by, w - 8, barH, withAlpha(color, bh ? 0x55 : 0x2E), 4);
        ui.textCentered(state, x + w / 2f, by + (barH - ui.fh()) / 2f + 1, color);
        final float barY = by;
        ui.hit(x, y, w, h, (mx, my, b) -> {
            if (b == 0 && my >= barY) toggle(ui, m);
            else window.openMod(m);
        });
    }

    /** Toggle a mod from the list, with the switch's sound. */
    private void toggle(Ui ui, Mod m) {
        boolean was = m.enabled();
        gui.mm.toggleByUser(m.id);
        ui.play(m.enabled() == was ? Ui.Sound.CLICK : was ? Ui.Sound.TOGGLE_OFF : Ui.Sound.TOGGLE_ON);
    }

    /** A small cog centred at (cx, cy). */
    static void gear(Ui ui, float cx, float cy, int color) {
        if (Icons.draw(ui.r, "settings", cx - 5.5f, cy - 5.5f, 11, color)) return;
        ui.r.rect(cx - 1, cy - 5, 2, 10, color, 0);
        ui.r.rect(cx - 5, cy - 1, 10, 2, color, 0);
        ui.r.rect(cx - 3.5f, cy - 3.5f, 7, 7, color, 3.5f);
        ui.r.rect(cx - 1.5f, cy - 1.5f, 3, 3, RAISED, 1.5f);
    }
}
