package gg.aether.core.ui;

import gg.aether.core.UiRenderer;
import gg.aether.core.cosmetics.Cosmetic;
import gg.aether.core.cosmetics.CosmeticModel;
import gg.aether.core.cosmetics.Cosmetics;
import gg.aether.core.cosmetics.Draft;
import gg.aether.core.cosmetics.Importer;
import gg.aether.core.cosmetics.Slot;
import java.io.IOException;
import java.util.Locale;

import static gg.aether.core.ui.Theme.*;

/**
 * The importer's editor (admins), next to the wardrobe preview which wears the draft: name, description, category,
 * rarity and store price; for models the body part it follows, position / rotation / size sliders (live) and an
 * animation. Save keeps it in your own wardrobe; Publish puts it in the store for everyone.
 */
final class CosmeticEditor {
    private static final String[] BONE_LABELS = { "Auto", "Head", "Body", "Right arm", "Left arm", "Right leg", "Left leg" };

    private final ClientGui gui;
    final Draft draft;
    private final Ui.Text name = new Ui.Text("", 40), description = new Ui.Text("", 200), price = new Ui.Text("", 7);
    private final Ui.Scroll scroll = new Ui.Scroll();
    private boolean dirty = true;

    CosmeticEditor(ClientGui gui, Draft draft) {
        this.gui = gui;
        this.draft = draft;
        name.value = draft.name;
        price.value = String.valueOf(draft.price);
    }

    /** The draft's cosmetic for the preview (rebuilt after discrete changes). */
    Cosmetic cosmetic() {
        Cosmetics cos = gui.mm.cosmetics();
        if (dirty) {
            dirty = false;
            draft.rebuild(cos.registry());
        }
        return draft.cosmetic();
    }

    void render(final Ui ui, float x, float y, float w, float h) {
        UiRenderer r = ui.r;
        draft.name = name.value;
        draft.description = description.value;
        try {
            draft.price = price.value.isEmpty() ? 0 : Long.parseLong(price.value);
        } catch (NumberFormatException e) {
            draft.price = 0;
        }
        cosmetic();

        // Title row.
        ui.text(ui.fit("New cosmetic from " + draft.source, w - 60), x, y + 4, LABEL);
        float cw = ui.buttonWidth("Cancel");
        ui.button("Cancel", x + w - cw, y, cw, 16, Ui.Style.GHOST, (mx, my, b) -> gui.wardrobe.closeEditor());
        y += 20;
        h -= 20;
        if (draft.problem() != null) {
            ui.text(ui.fit("Can't load it: " + draft.problem(), w), x, y, WARNING);
            y += ui.fh() + 4;
            h -= ui.fh() + 4;
        }

        float footer = 24, formH = h - footer;
        float content = contentHeight(ui);
        float off = ui.beginScroll(scroll, x, y, w, formH, content);
        form(ui, x, y - off, w - 6);
        ui.endScroll(scroll, x, y, w, formH, content);

        // Footer: save / publish.
        float fy = y + formH + 6, bw = (w - 4) / 2f;
        boolean ok = draft.cosmetic() != null;
        ui.button("Save to my wardrobe", x, fy, bw, 18, Ui.Style.SECONDARY, (mx, my, b) -> {
            if (ok) save();
            else ui.flash("Fix the problem above first", WARNING);
        });
        String pub = gui.mm.cosmetics().shop().publishing() ? "Publishing..." : "Publish · " + (draft.price == 0 ? "free" : MenuFeed.formatCoins(draft.price));
        ui.button(pub, x + bw + 4, fy, bw, 18, Ui.Style.STORE, (mx, my, b) -> {
            if (ok) publish();
            else ui.flash("Fix the problem above first", WARNING);
        });
        r.rect(x, fy - 4, w, 1, BORDER, 0);
    }

    private static final float LABEL_H = 12, FIELD = 16, GAP = 7;

    private float contentHeight(Ui ui) {
        float hgt = (LABEL_H + FIELD + GAP) * 2; // name, description
        hgt += (LABEL_H + 16 + GAP) * 2 + 18; // category (may wrap), rarity
        hgt += LABEL_H + FIELD + GAP; // price
        if (draft.kind == Importer.Kind.MODEL) {
            hgt += LABEL_H + 36 + GAP; // body part (wraps)
            hgt += LABEL_H + Ui.ROW * 7 + GAP; // placement sliders
            hgt += LABEL_H + 16 + GAP; // animation
        } else if (draft.looksLikeCapeTexture) {
            hgt += 20;
        }
        return hgt + 6;
    }

    private void form(final Ui ui, float x, float y, float w) {
        y = label(ui, "Name", x, y);
        ui.textField(name, name, x, y, w, FIELD, "Name in the shop", null, null);
        y += FIELD + GAP;
        y = label(ui, "Description", x, y);
        ui.textField(description, description, x, y, w, FIELD, "Optional, shown in the shop", null, null);
        y += FIELD + GAP;

        y = label(ui, "Category", x, y);
        float tx = x;
        for (final Slot s : draft.slots()) {
            float tw = ui.tw(s.label) + 12;
            if (tx + tw > x + w) {
                tx = x;
                y += 18;
            }
            chip(ui, s.label, tx, y, tw, draft.slot == s, ACCENT, () -> {
                if (draft.slot != s) {
                    draft.slot = s;
                    dirty = true;
                }
            });
            tx += tw + 4;
        }
        y += 16 + GAP;
        y = label(ui, "Rarity", x, y);
        tx = x;
        for (final Cosmetic.Rarity rr : Cosmetic.Rarity.values()) {
            String l = Ui.title(rr.name());
            float tw = ui.tw(l) + 12;
            chip(ui, l, tx, y, tw, draft.rarity == rr, rr.color, () -> {
                draft.rarity = rr;
                dirty = true;
            });
            tx += tw + 4;
        }
        y += 16 + GAP;
        y = label(ui, "Price in coins (0 = free)", x, y);
        ClientGui.drawCoin(ui.r, x, y + 3, 10);
        ui.textField(price, price, x + 14, y, Math.min(110, w - 14), FIELD, "500", "0123456789", null);
        y += FIELD + GAP;

        if (draft.kind == Importer.Kind.PICTURE) {
            if (draft.looksLikeCapeTexture) {
                ui.textMid(ui.fit("It's a cape texture (64x32 layout)", w - 34), x, y, 14, draft.capeTexture ? LABEL : MUTED);
                ui.toggle(x + w - 26, y + 1, draft.capeTexture, () -> {
                    draft.capeTexture = !draft.capeTexture;
                    dirty = true;
                });
            }
            return;
        }

        y = label(ui, "Follows (groups named head, body, right_arm... follow their part anyway)", x, y);
        tx = x;
        CosmeticModel.Bone[] bones = CosmeticModel.Bone.values();
        for (int i = 0; i < BONE_LABELS.length; i++) {
            final CosmeticModel.Bone b = i == 0 ? null : bones[i - 1];
            float tw = ui.tw(BONE_LABELS[i]) + 12;
            if (tx + tw > x + w) {
                tx = x;
                y += 18;
            }
            chip(ui, BONE_LABELS[i], tx, y, tw, draft.bone == b, ACCENT, () -> {
                if (draft.bone != b) {
                    draft.bone = b;
                    dirty = true;
                }
            });
            tx += tw + 4;
        }
        y += 20 + GAP;

        y = label(ui, "Position on the player", x, y);
        final float[] off = draft.offset().clone(), rot = draft.rotation().clone();
        final float[] scale = { draft.scale() };
        String[] names = { "Right / left", "Up / down", "Forward / back" };
        for (int i = 0; i < 3; i++) {
            final int a = i;
            y = slider(ui, names[i], x, y, w, (off[a] + 16) / 32f, fmt(off[a]) + " px", f -> {
                off[a] = Math.round((f * 32 - 16) * 4) / 4f;
                draft.setPlacement(off, rot, scale[0]);
            });
        }
        String[] rn = { "Tilt (pitch)", "Turn (yaw)", "Roll" };
        for (int i = 0; i < 3; i++) {
            final int a = i;
            y = slider(ui, rn[i], x, y, w, (rot[a] + 180) / 360f, Math.round(rot[a]) + "°", f -> {
                rot[a] = Math.round(f * 360 - 180);
                draft.setPlacement(off, rot, scale[0]);
            });
        }
        y = slider(ui, "Size", x, y, w, (scale[0] - 0.25f) / 2.75f, fmt(scale[0]) + "x", f -> {
            scale[0] = Math.round((0.25f + f * 2.75f) * 20) / 20f;
            draft.setPlacement(off, rot, scale[0]);
        });
        float rw = ui.buttonWidth("Reset position");
        ui.button("Reset position", x + w - rw, y - Ui.ROW * 7 - LABEL_H - 1, rw, 12, Ui.Style.GHOST,
                (mx, my, b) -> draft.setPlacement(new float[3], new float[3], 1));
        y += GAP;

        y = label(ui, "Animation", x, y);
        tx = x;
        for (final String p : CosmeticModel.PRESETS) {
            String l = Ui.title(p.toUpperCase(Locale.ROOT));
            float tw = ui.tw(l) + 12;
            chip(ui, l, tx, y, tw, draft.animation.equals(p), ACCENT, () -> {
                if (!draft.animation.equals(p)) {
                    draft.animation = p;
                    dirty = true;
                }
            });
            tx += tw + 4;
        }
    }

    private static String fmt(float v) { return v == Math.round(v) ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.2f", v); }

    private static float label(Ui ui, String text, float x, float y) {
        ui.text(text, x, y, MUTED);
        return y + LABEL_H;
    }

    private static float slider(Ui ui, String label, float x, float y, float w, float frac, String value, Ui.FloatSetter set) {
        float lw = Math.min(90, w * 0.32f);
        ui.textMid(ui.fit(label, lw - 4), x, y, Ui.ROW, LABEL);
        ui.slider(x + lw, y, w - lw, frac, value, set);
        return y + Ui.ROW;
    }

    private static void chip(Ui ui, String label, float x, float y, float w, boolean sel, int color, Runnable pick) {
        boolean hover = ui.over(x, y, w, 15);
        ui.r.rect(x, y, w, 15, sel ? color : hover ? 0x26FFFFFF : 0x14FFFFFF, 7.5f);
        ui.textMid(label, x + 6, y, 15, sel ? 0xFF101014 : LABEL);
        ui.hit(x, y, w, 15, (mx, my, b) -> pick.run());
    }

    // ---- actions ----------------------------------------------------------------------------------------------------

    private void save() {
        Cosmetics cos = gui.mm.cosmetics();
        try {
            String id = draft.saveLocal(cos.registry());
            cos.load(cos.registry().localDir());
            Cosmetic c = cos.registry().get(id);
            gui.wardrobe.closeEditor();
            if (c != null) {
                cos.equip(c.slot, c.id);
                gui.host.save();
                gui.wardrobe.showImported(c);
                gui.ui.flash(c.name + " saved to your wardrobe (only you see it). Publish it to share it", SUCCESS);
            } else {
                gui.ui.flash("Saved, but it didn't load: see the top of the Wardrobe", WARNING);
            }
        } catch (IOException e) {
            gui.ui.flash("Couldn't save: " + e.getMessage(), WARNING);
        }
    }

    private void publish() {
        Cosmetics cos = gui.mm.cosmetics();
        if (!cos.shop().available()) {
            gui.ui.flash("Publishing needs the Kite backend", WARNING);
            return;
        }
        if (cos.registry().get(draft.storeId()) != null && cos.shop().offer(draft.storeId()) == null) {
            gui.ui.flash("That name is taken by a built-in cosmetic: rename it", WARNING);
            return;
        }
        try {
            cos.shop().publish(draft.bundle());
        } catch (IOException | RuntimeException e) {
            gui.ui.flash("Couldn't publish: " + e.getMessage(), WARNING);
        }
    }
}
