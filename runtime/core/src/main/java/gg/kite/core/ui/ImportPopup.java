package gg.kite.core.ui;

import gg.kite.core.cosmetics.Importer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static gg.kite.core.ui.Theme.*;

/**
 * Wardrobe > Import (admins): pick a Blockbench project, OBJ, picture or GIF anywhere on the computer with the system
 * file dialog; it opens in the cosmetic editor. Also hands out the creator kit (Blockbench template, Blender
 * reference, guide).
 */
final class ImportPopup implements Ui.Popup {
    private final ClientGui gui;
    // Written by the file dialog's thread.
    private volatile Path picked;
    private volatile boolean picking, noDialog;
    private volatile int pickVersion;
    private int seenVersion;

    ImportPopup(ClientGui gui) { this.gui = gui; }

    @Override public void render(Ui ui) {
        take();
        float w = Math.min(300, gui.screenW - 16), pad = 10, h = 176;
        float x = Math.round((gui.screenW - w) / 2f), y = Math.round((gui.screenH - h) / 2f);
        ui.r.rect(0, 0, gui.screenW, gui.screenH, 0x60000000, 0);
        ui.r.rect(x - 1, y - 1, w + 2, h + 2, BORDER_HOVER, 7);
        ui.r.rect(x, y, w, h, 0xFA1C1C22, 6);
        ui.absorb(x, y, w, h);
        float cx = x + pad, cw = w - pad * 2, cy = y + pad;
        ui.icon("upload", cx, cy, 11, ACCENT_ALT);
        ui.display("Import a cosmetic", cx + 16, cy, 1f, TEXT);
        cy += ui.fh() + 10;
        String[] lines = {
                "Blockbench projects (.bbmodel) and OBJ models (Blender) become hats, wings, ears, boots, backpacks...",
                "Pictures (PNG, JPG) and GIFs become capes. Next you set the name, price, slot and position.",
        };
        for (String line : lines) {
            for (String l : ui.wrap(line, cw, 2)) {
                ui.text(l, cx, cy, MUTED);
                cy += ui.fh() + 2;
            }
            cy += 2;
        }
        cy += 4;
        ui.button(picking ? "Waiting for the file dialog..." : "Choose a file...", cx, cy, cw, 20, Ui.Style.PRIMARY, (mx, my, b) -> pick());
        cy += 26;
        float bw = (cw - 4) / 2f;
        ui.button("Creator kit", cx, cy, bw, 18, Ui.Style.SECONDARY, (mx, my, b) -> creatorKit());
        ui.button("Open folder", cx + bw + 4, cy, bw, 18, Ui.Style.SECONDARY, (mx, my, b) -> {
            Path dir = gui.mm.cosmetics().registry().localDir();
            if (dir != null) gui.mm.host().openFile(dir);
            gui.ui.flash("Files dropped there show in your wardrobe after Reload (only for you)");
        });
        cy += 24;
        ui.text(ui.fit("The kit has a Blockbench template with the player, and the guide", cw), cx, cy, FAINT);
    }

    /** Apply what the file dialog thread picked (render thread). */
    private void take() {
        if (pickVersion == seenVersion) return;
        seenVersion = pickVersion;
        if (noDialog) {
            noDialog = false;
            gui.ui.flash("Couldn't open a file dialog: put the file in the cosmetics folder (Open folder), then Reload", WARNING);
        }
        Path p = picked;
        if (p == null) return;
        picked = null;
        if (Importer.kind(p) == null) {
            gui.ui.flash("Pick a .bbmodel, OBJ, PNG, JPG or GIF file", WARNING);
            return;
        }
        gui.ui.closePopup();
        gui.wardrobe.startDraft(p);
    }

    private void pick() {
        if (picking) return;
        picking = true;
        boolean shown = gui.mm.host().pickFiles("Import a cosmetic", Importer.EXTENSIONS, (List<Path> files) -> {
            if (files == null) noDialog = true;
            else if (!files.isEmpty()) picked = files.get(0);
            picking = false;
            pickVersion++;
        });
        if (!shown) {
            picking = false;
            gui.ui.flash("No file dialog here: put the file in the cosmetics folder (Open folder), then Reload", WARNING);
        }
    }

    private void creatorKit() {
        Path dir = gui.mm.cosmetics().registry().localDir();
        if (dir == null) return;
        try {
            Path kit = Importer.writeCreatorKit(dir);
            gui.mm.host().openFile(kit);
            gui.ui.flash("Kit ready: open kite_cosmetic_template.bbmodel in Blockbench, see GUIDE.md", SUCCESS);
        } catch (IOException e) {
            gui.ui.flash("Couldn't write the kit: " + e.getMessage(), WARNING);
        }
    }
}
