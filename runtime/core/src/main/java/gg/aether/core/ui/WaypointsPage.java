package gg.aether.core.ui;

import gg.aether.core.GameState;
import gg.aether.core.Mod;
import gg.aether.core.Setting;

import static gg.aether.core.ui.Theme.*;

/** The window's Waypoints tab: the waypoints mod's per-world list with quick actions. */
final class WaypointsPage {
    static final String MOD = "waypoints";

    private final ClientGui gui;
    private final ModWindow window;
    private final Ui.Scroll scroll = new Ui.Scroll();

    WaypointsPage(ClientGui gui, ModWindow window) {
        this.gui = gui;
        this.window = window;
    }

    void render(Ui ui, GameState s, float x, float y, float w, float h) {
        final Mod m = gui.mm.get(MOD);
        ui.display("Waypoints", x, y + 4, 1f, TEXT);
        if (m == null) {
            ui.text("The waypoints mod is not installed", x, y + 20, MUTED);
            return;
        }
        Setting.Entries list = null;
        Setting.Action addHere = null;
        for (Setting<?> st : m.settings()) {
            if (st instanceof Setting.Entries && st.key.equals("list")) list = (Setting.Entries) st;
            if (st instanceof Setting.Action && st.key.equals("addHere")) addHere = (Setting.Action) st;
        }
        boolean inWorld = s != null && s.inWorld();
        String sub = !m.enabled() ? "The waypoints mod is off" : !inWorld ? "Join a world to see its waypoints" : (list == null ? 0 : list.size()) + " in this world";
        ui.text(sub, x, y + 20, m.enabled() && inWorld ? MUTED : WARNING);

        float right = x + w;
        right = ui.button("Options", right - 56, y, 56, 18, Ui.Style.SECONDARY, (mx, my, b) -> window.openMod(m)) - 6;
        if (addHere != null && inWorld && m.enabled()) {
            final Setting.Action a = addHere;
            right = ui.button("+ Add here", right - 70, y, 70, 18, Ui.Style.PRIMARY, (mx, my, b) -> a.run()) - 6;
        }
        ui.toggle(right - 24, y + 3, m.enabled(), () -> gui.mm.toggleByUser(m.id));

        if (list == null || !inWorld) return;
        float top = y + 36, viewH = h - 36, total = Ui.ROW * Ui.tableRows(list);
        float off = ui.beginScroll(scroll, x, top, w, viewH, total);
        ui.table(list, x, top - off, w - 4);
        ui.endScroll(scroll, x, top, w, viewH, total);
    }
}
