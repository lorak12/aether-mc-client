package gg.kite.core.ui;

import gg.kite.core.Json;
import gg.kite.core.Profiles;

import static gg.kite.core.ui.Theme.*;

/** Popup to rename a profile, pick its icon and auto-switch servers, export/copy it or delete it. */
final class ProfileEditor implements Ui.Popup {
    private final ClientGui gui;
    private final Profiles.Profile p;
    private final float ax, ay;

    ProfileEditor(ClientGui gui, Profiles.Profile p, float ax, float ay) {
        this.gui = gui;
        this.p = p;
        this.ax = ax;
        this.ay = ay;
    }

    @Override public void render(Ui ui) {
        float w = 176, pad = 8, cell = 20;
        int cols = 8;
        int rows = (Profiles.ICONS.length + cols - 1) / cols;
        float h = pad + ui.fh() + 4 + 16 + 8 + ui.fh() + 4 + rows * cell + 8 + ui.fh() + 4 + 16 + 2 + ui.fh() + 6 + 18 + pad;
        float x = Math.min(ax, ui.r.screenWidth() - w - 4), y = Math.max(4, Math.min(ay, ui.r.screenHeight() - h - 4));
        ui.r.rect(x - 1, y - 1, w + 2, h + 2, BORDER_HOVER, 6);
        ui.r.rect(x, y, w, h, 0xFA1C1C22, 5);
        ui.absorb(x, y, w, h);

        float cx = x + pad, cw = w - pad * 2, cy = y + pad;
        ui.text("Name", cx, cy, MUTED);
        cy += ui.fh() + 4;
        ui.textField(p.name, Ui.field(p.name), cx, cy, cw, 16, "Profile name", null, () -> {
            if (p.name.str().trim().isEmpty()) p.name.set("Profile");
            gui.host.save();
        });
        cy += 16 + 8;

        ui.text("Icon", cx, cy, MUTED);
        cy += ui.fh() + 4;
        for (int i = 0; i < Profiles.ICONS.length; i++) {
            final String icon = Profiles.ICONS[i];
            float ix = cx + (i % cols) * cell, iy = cy + (i / cols) * cell;
            boolean sel = icon.equals(p.icon.str()), hover = ui.over(ix, iy, cell - 2, cell - 2);
            if (sel || hover) ui.r.rect(ix, iy, cell - 2, cell - 2, sel ? 0x558B5CF6 : 0x22FFFFFF, 3);
            ui.item(icon, ix + 1, iy + 1, 16);
            ui.hit(ix, iy, cell - 2, cell - 2, (mx, my, b) -> {
                p.icon.set(icon);
                gui.host.save();
            });
        }
        cy += rows * cell + 8;

        ui.text("Switch to it on servers", cx, cy, MUTED);
        cy += ui.fh() + 4;
        ui.textField(p.servers, Ui.field(p.servers), cx, cy, cw, 16, "hypixel.net, *.example.org", null, () -> gui.host.save());
        cy += 16 + 2;
        ui.text(ui.fit("Comma-separated, \"singleplayer\" works too", cw), cx, cy, FAINT);
        cy += ui.fh() + 6;

        float bw = (cw - 8) / 3f;
        ui.button("Export", cx, cy, bw, 18, Ui.Style.SECONDARY, (mx, my, b) -> {
            gui.host.save();
            String msg = gui.host.exportProfile(p);
            gui.ui.flash(msg == null ? "Export is not available here" : msg);
        });
        ui.button("Copy", cx + bw + 4, cy, bw, 18, Ui.Style.SECONDARY, (mx, my, b) -> {
            gui.mm.host().copyText(Json.write(gui.mm.exportProfile(p)));
            gui.ui.flash("Copied \"" + p.name.str() + "\" to the clipboard");
        });
        boolean active = p == gui.mm.profiles().active();
        if (active) {
            ui.button("In use", cx + (bw + 4) * 2, cy, bw, 18, Ui.Style.GHOST, (mx, my, b) -> gui.ui.flash("Switch to another profile to delete this one", WARNING));
        } else {
            ui.confirmButton("delete:" + p.file(), "Delete", cx + cw, cy, 18, () -> {
                String n = p.name.str();
                if (gui.mm.deleteProfile(p)) {
                    gui.host.save();
                    gui.ui.closePopup();
                    gui.ui.flash("Deleted \"" + n + "\"");
                }
            });
        }
    }
}
