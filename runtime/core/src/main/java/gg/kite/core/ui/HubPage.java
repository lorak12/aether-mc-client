package gg.kite.core.ui;

import gg.kite.core.GameState;
import gg.kite.core.UiRenderer;

import static gg.kite.core.ui.Theme.*;

/**
 * Right Shift landing screen: a 1px frame inset 2px from the screen edges, the logo and name in the middle, and three
 * big buttons: Emotes (coming soon), Mods, Wardrobe and Shop.
 */
final class HubPage {
    private final ClientGui gui;

    HubPage(ClientGui gui) { this.gui = gui; }

    void render(UiRenderer r, GameState s) {
        Ui ui = gui.ui;
        float w = gui.screenW, h = gui.screenH, t = gui.intro(0.25f);

        // Frame: 2px gap from the edge, then a 1px line.
        int line = withAlpha(0xFFFFFF, Math.round(0x55 * t));
        ui.outline(2, 2, w - 4, h - 4, line);

        float cx = w / 2f;
        float logo = Math.min(56, h * 0.16f);
        float nameScale = Math.max(1.5f, Math.min(3f, h / 120f));
        float nameH = ui.fh() * nameScale;
        float btn = Math.min(72, (w - 60) / 4.5f), btnH = btn * 0.82f, gap = 10;
        float block = logo + 8 + nameH + 4 + ui.fh() + 22 + btnH;
        float y = (h - block) / 2f + (1 - t) * 10;

        ClientGui.drawLogo(r, cx - logo / 2f, y, logo);
        y += logo + 8;
        String name = "KITE CLIENT";
        float nw = ui.displayWidth(name, nameScale);
        ui.display(name, cx - nw / 2f, y, nameScale, alpha(TEXT, t));
        y += nameH + 4;
        String sub = "Profile: " + gui.mm.profiles().active().name.str();
        ui.textCentered(sub, cx, y, alpha(MUTED, t));
        y += ui.fh() + 22;

        float total = btn * 4 + gap * 3, bx = cx - total / 2f;
        tile(ui, "Emotes", "emote", bx, y, btn, btnH, false, t, () -> ui.flash("Emotes are coming soon", WARNING));
        tile(ui, "Mods", "mods", bx + btn + gap, y, btn, btnH, true, t, gui::openWindow);
        tile(ui, "Wardrobe", "wardrobe", bx + (btn + gap) * 2, y, btn, btnH, true, t, gui::openWardrobe);
        tile(ui, "Shop", "cart", bx + (btn + gap) * 3, y, btn, btnH, true, t, gui::openShop);

        String hint = gui.mm.menuKeyName() + " or Esc to close";
        ui.textCentered(hint, cx, h - ui.fh() - 12, alpha(FAINT, t));
        ui.drawFlash(cx, y + btnH + 14);
    }

    private void tile(Ui ui, String label, String icon, float x, float y, float w, float h, boolean enabled, float t, Runnable click) {
        boolean hover = ui.over(x, y, w, h);
        float lift = hover && enabled ? 2 : 0;
        y -= lift;
        ui.r.rect(x, y, w, h, alpha(hover && enabled ? 0xFF8B5CF6 : 0x40FFFFFF, t), 6);
        ui.r.rect(x + 1, y + 1, w - 2, h - 2, alpha(hover && enabled ? 0xF024242C : 0xD81C1C22, t), 5);
        float size = Math.round(Math.min(26, h * 0.36f));
        ui.icon(icon, x + (w - size) / 2f, y + h * 0.2f, size, alpha(!enabled ? MUTED : hover ? 0xFF9DB2FF : TEXT, t));
        ui.textCentered(label, x + w / 2f, y + h - ui.fh() - 8, alpha(enabled ? TEXT : MUTED, t));
        if (!enabled) {
            float bw = ui.tw("SOON") + 6;
            ui.badge("SOON", x + w - bw - 3, y + 3, WARNING);
        }
        ui.hit(x, y, w, h, (mx, my, b) -> click.run());
    }
}
