package gg.aether.core.ui;

import gg.aether.core.GameState;
import gg.aether.core.UiRenderer;
import gg.aether.core.cosmetics.Cosmetic;
import gg.aether.core.cosmetics.Cosmetics;
import gg.aether.core.cosmetics.Draft;
import gg.aether.core.cosmetics.Shop;
import gg.aether.core.cosmetics.Slot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static gg.aether.core.ui.Theme.*;

/**
 * Wardrobe and Shop, one window: a preview on the left (drag to turn and tilt; it wears what you hover), slot tabs
 * and search on the right. The Wardrobe tab lists what you own first ("Yours") and below it what you can buy ("Get in
 * the shop"); the Shop tab lists everything for sale. Clicking something you don't own opens {@link BuyPopup}.
 * Admins also get Import, which opens {@link CosmeticEditor} in place of the lists.
 */
final class WardrobePage {
    enum Mode { WARDROBE, SHOP }

    private static final float HEADER = 28, GAP = 6, SECTION = 18;
    /** Re-ask the backend for prices and the balance at most this often while the page is open. */
    private static final long REFRESH_MS = 20_000;

    private final ClientGui gui;
    private Mode mode = Mode.WARDROBE;
    /** Slot tab; null = every slot (shop only). */
    private Slot slot = Slot.CAPE;
    /** Preview turn in degrees (0 faces the viewer, 180 shows the back) and tilt (positive looks down on the head). */
    private float yaw = 160, pitch, turnFromX, turnFromY, turnFromYaw, turnFromPitch;
    private final Ui.Scroll scroll = new Ui.Scroll();
    private final Ui.Text search = new Ui.Text("", 32);
    private long lastRefresh;
    private BuyPopup buying;
    private CosmeticEditor editor;

    WardrobePage(ClientGui gui) { this.gui = gui; }

    /** Called when the page opens. */
    void open(Mode m) {
        mode = m;
        if (m == Mode.SHOP) slot = null;
        else if (slot == null) slot = Slot.CAPE;
        search.value = "";
        scroll.reset();
        faceFor(slot);
        lastRefresh = 0;
    }

    Mode mode() { return mode; }

    /** After an import: the Wardrobe tab on the new item's slot. */
    void showImported(Cosmetic c) {
        open(Mode.WARDROBE);
        select(c.slot);
    }

    private void select(Slot s) {
        slot = s;
        scroll.reset();
        faceFor(s);
    }

    /** Capes, wings and backpacks are seen from behind; everything else from the front. */
    private void faceFor(Slot s) {
        yaw = s == null || s == Slot.CAPE || s == Slot.WINGS || s == Slot.BACK ? 160 : 25;
        pitch = 0;
    }

    // ---- importer ---------------------------------------------------------------------------------------------------

    /** Open the editor on a picked file (admins). */
    void startDraft(Path file) {
        Cosmetics cos = gui.mm.cosmetics();
        Path dir = cos.registry().localDir();
        if (dir == null) return;
        try {
            if (editor != null) editor.draft.discard(cos.registry());
            Draft d = Draft.start(dir, file);
            editor = new CosmeticEditor(gui, d);
            mode = Mode.WARDROBE;
            slot = d.slot;
            faceFor(d.slot);
        } catch (IOException | RuntimeException e) {
            gui.ui.flash("Couldn't open it: " + e.getMessage(), WARNING);
        }
    }

    void closeEditor() {
        if (editor == null) return;
        editor.draft.discard(gui.mm.cosmetics().registry());
        editor = null;
        gui.mm.cosmetics().clearPreview();
    }

    boolean editing() { return editor != null; }

    void popupClosed(BuyPopup p) {
        if (buying == p) buying = null;
    }

    // ---- page -------------------------------------------------------------------------------------------------------

    void render(UiRenderer r, GameState s) {
        Ui ui = gui.ui;
        final Cosmetics cos = gui.mm.cosmetics();
        final Shop shop = cos.shop();
        pollShop(cos, shop);
        long now = System.currentTimeMillis();
        if (shop.available() && now - lastRefresh > REFRESH_MS) {
            lastRefresh = now;
            shop.refresh();
        }
        if (editor != null && !shop.admin() && shop.coins() >= 0) closeEditor(); // signed in as someone else

        float sw = gui.screenW, sh = gui.screenH;
        float w = Math.round(Math.min(660, sw - 24)), h = Math.round(Math.min(390, sh - 24));
        float t = gui.intro(0.2f);
        float x = Math.round((sw - w) / 2f), y = Math.round((sh - h) / 2f + (1 - t) * 8);
        r.rect(x - 1, y - 1, w + 2, h + 2, alpha(0x40FFFFFF, t), 7);
        r.rect(x, y, w, h, alpha(WINDOW, t), 6);
        header(ui, cos, shop, x, y, w);
        r.rect(x, y + HEADER, w, 1, 0x24FFFFFF, 0);

        float cx = x + 10, cy = y + HEADER + 8, cw = w - 20, chh = h - HEADER - 16;
        if (editor == null) {
            String status = statusLine(cos, shop);
            ui.text(ui.fit(status, cw), cx, cy, statusColor(cos, shop));
            cy += ui.fh() + 6;
            chh -= ui.fh() + 6;
        }

        float pw = Math.round(Math.min(150, cw * 0.3f));
        preview(ui, cos, cx, cy, pw, chh);
        float gx = cx + pw + 10, gw = cw - pw - 10;

        if (editor != null) {
            editor.render(ui, gx, cy, gw, chh);
            Cosmetic d = editor.cosmetic();
            if (d != null) cos.previewOnly(d.slot, d.id);
            else cos.clearPreview();
            ui.drawFlash(x + w / 2f, y + h - ui.fh() - 30);
            return;
        }

        float gridTop = tabs(ui, cos, gx, cy, gw);
        String hovered = mode == Mode.WARDROBE ? wardrobeLists(ui, cos, shop, gx, gridTop, gw, cy + chh - gridTop)
                : shopGrid(ui, cos, shop, gx, gridTop, gw, cy + chh - gridTop);

        // Try on whatever is under the mouse, else what the buy popup shows.
        Cosmetic hov = hovered == null || hovered.isEmpty() ? null : cos.registry().get(hovered);
        if (buying != null) cos.previewOnly(buying.item.slot, buying.item.id);
        else if (hovered != null && hov == null && slot != null) cos.previewOnly(slot, "");
        else if (hov != null) cos.previewOnly(hov.slot, hov.id);
        else cos.clearPreview();
        ui.drawFlash(x + w / 2f, y + h - ui.fh() - 14);
    }

    private void pollShop(Cosmetics cos, Shop shop) {
        for (Shop.Event e = shop.poll(); e != null; e = shop.poll()) {
            if (e.publishedId != null) {
                closeEditor();
                open(Mode.SHOP);
                gui.ui.flash(e.message, SUCCESS);
                continue;
            }
            Cosmetic c = e.boughtId == null ? null : cos.registry().get(e.boughtId);
            if (c == null) {
                gui.ui.flash(e.message, e.ok ? SUCCESS : WARNING);
                continue;
            }
            cos.equip(c.slot, c.id);
            gui.host.save();
            gui.ui.flash(c.name + " is yours and you're wearing it", SUCCESS);
            gui.mm.host().playUiSound("toggle_on", 1.4f, 0.6f);
        }
    }

    /** Back, logo, Wardrobe / Shop tabs; right: close, coins and (admins) Import / folder / reload. */
    private void header(final Ui ui, final Cosmetics cos, final Shop shop, float x, float y, float w) {
        UiRenderer r = ui.r;
        ui.button("<", x + 6, y + 6, 16, 16, Ui.Style.GHOST, (mx, my, b) -> {
            if (editor != null) closeEditor();
            else gui.back();
        });
        ClientGui.drawLogo(r, x + 27, y + 7, 14);
        float tx = x + 48;
        for (final Mode m : Mode.values()) {
            String label = m == Mode.WARDROBE ? "Wardrobe" : "Shop";
            float tw = ui.tw(label) + 16;
            boolean sel = mode == m && editor == null, hover = ui.over(tx, y + 6, tw, 16);
            r.rect(tx, y + 6, tw, 16, sel ? ACCENT : hover ? 0x26FFFFFF : 0x14FFFFFF, 8);
            ui.textMid(label, tx + 8, y + 6, 16, sel ? 0xFFFFFFFF : LABEL);
            ui.hit(tx, y + 6, tw, 16, (mx, my, b) -> {
                if (editor != null) closeEditor();
                if (mode != m) open(m);
            });
            tx += tw + 4;
        }
        float right = x + w - 22;
        boolean ch = ui.over(right, y + 6, 16, 16);
        if (ch) r.rect(right, y + 6, 16, 16, 0x33E07A84, 4);
        ModWindow.cross(ui, right + 8, y + 14, ch ? DANGER : MUTED);
        ui.hit(right, y + 6, 16, 16, (mx, my, b) -> gui.close());
        right -= 6;
        right = coinChip(ui, shop, right, y + 6) - 6;
        if (!shop.admin() || editor != null) return;
        float bw = ui.buttonWidth("Reload");
        if (right - bw > tx + 4) right = ui.button("Reload", right - bw, y + 6, bw, 16, Ui.Style.SECONDARY, (mx, my, b) -> reload(cos)) - 4;
        bw = ui.buttonWidth("Folder");
        if (right - bw > tx + 4) {
            right = ui.button("Folder", right - bw, y + 6, bw, 16, Ui.Style.SECONDARY, (mx, my, b) -> {
                if (cos.registry().localDir() != null) gui.mm.host().openFile(cos.registry().localDir());
            }) - 4;
        }
        bw = ui.buttonWidth("Import") + 12;
        if (right - bw > tx + 4) {
            float bx = right - bw;
            ui.button("", bx, y + 6, bw, 16, Ui.Style.PRIMARY, (mx, my, b) -> gui.ui.openPopup(new ImportPopup(gui)));
            ui.icon("upload", bx + 5, y + 9, 10, 0xFFFFFFFF);
            ui.text("Import", bx + 18, y + 6 + (16 - ui.fh()) / 2f + 1, 0xFFFFFFFF);
        }
    }

    /** Coin balance pill ending at {@code right}; opens the shop. Returns its left edge. */
    private float coinChip(Ui ui, Shop shop, float right, float y) {
        String text = shop.unlimited() ? "Unlimited" : MenuFeed.formatCoins(shop.coins());
        float cw = 5 + 10 + 4 + ui.tw(text) + 7, x = right - cw;
        boolean hover = ui.over(x, y, cw, 16);
        ui.r.rect(x, y, cw, 16, hover ? 0xFF2C2A1E : 0xFF22201A, 8);
        ClientGui.drawCoin(ui.r, x + 5, y + 3, 10);
        ui.textMid(text, x + 19, y, 16, WARNING);
        ui.hit(x, y, cw, 16, (mx, my, b) -> {
            if (editor != null) closeEditor();
            if (mode != Mode.SHOP) open(Mode.SHOP);
            else ui.flash(shop.unlimited() ? "Admin account: buying is free" : "Top up coins in the Kite launcher's store");
        });
        return x;
    }

    private void reload(Cosmetics cos) {
        cos.load(cos.registry().localDir());
        int problems = cos.registry().problems().size();
        gui.ui.flash("Loaded " + cos.registry().all().size() + " cosmetics" + (problems == 0 ? "" : ", " + problems + " failed"), problems == 0 ? ACCENT : WARNING);
    }

    private String statusLine(Cosmetics cos, Shop shop) {
        List<String> problems = cos.registry().problems();
        if (mode == Mode.WARDROBE) {
            if (!problems.isEmpty() && shop.admin()) return problems.size() + " failed to load: " + problems.get(0);
            return "Click yours to wear it (right-click takes it off). Click one from the shop to buy it.";
        }
        if (!shop.available()) return "Offline: the shop needs the Kite backend (start the game from the Kite launcher)";
        if (shop.error() != null && shop.offers() == null) return "Couldn't load the shop: " + shop.error();
        if (shop.offers() == null) return "Loading the shop...";
        return shop.unlimited() ? "Admin account: everything is free for you" : "Buy with the coins from your Kite account, the same as in the launcher";
    }

    private int statusColor(Cosmetics cos, Shop shop) {
        if (mode == Mode.WARDROBE) return cos.registry().problems().isEmpty() || !shop.admin() ? MUTED : WARNING;
        if (!shop.available() || shop.error() != null && shop.offers() == null) return WARNING;
        return shop.unlimited() ? SUCCESS : MUTED;
    }

    // ---- preview ----------------------------------------------------------------------------------------------------

    private void preview(final Ui ui, final Cosmetics cos, float x, float y, float w, float h) {
        UiRenderer r = ui.r;
        r.rect(x, y, w, h, BORDER, 5);
        r.rect(x + 1, y + 1, w - 2, h - 2, RAISED, 4);
        float rowH = 15, togglesH = mode == Mode.WARDROBE && editor == null ? rowH * 5 + 8 : 0;
        float bx = x + 4, by = y + 4, bw = w - 8, bh = h - togglesH - 8;
        r.rect(bx + bw * 0.2f, by + bh - 10, bw * 0.6f, 6, 0x228B5CF6, 3);
        if (!r.player(bx, by, bw, bh - 4, yaw, pitch)) {
            ui.textCentered("Join a world", bx + bw / 2f, by + bh / 2f - ui.fh(), MUTED);
            ui.textCentered(ui.fit("to see a preview", bw), bx + bw / 2f, by + bh / 2f + 2, MUTED);
        } else if (ui.over(bx, by, bw, bh)) {
            ui.textCentered(ui.fit("Drag to turn and tilt", bw), bx + bw / 2f, by + bh - ui.fh() - 1, FAINT);
        }
        // Drag right turns the player to the right; drag up looks down on it (down: from below).
        ui.hitDrag(bx, by, bw, bh, (mx, my, b) -> {
            turnFromX = mx;
            turnFromY = my;
            turnFromYaw = yaw;
            turnFromPitch = pitch;
        }, (mx, my) -> {
            yaw = ((turnFromYaw - (mx - turnFromX) * 2.5f) % 360 + 360) % 360;
            pitch = Math.max(-60, Math.min(80, turnFromPitch + (turnFromY - my) * 1.5f));
        });
        // Back to eye level once tilted.
        if (pitch != 0) {
            float rw = ui.buttonWidth("Level");
            ui.button("Level", bx + bw - rw - 2, by + 2, rw, 12, Ui.Style.GHOST, (mx, my, b) -> pitch = 0);
        }
        if (togglesH == 0) return;

        float ry = y + h - togglesH;
        r.rect(x + 6, ry - 2, w - 12, 1, BORDER, 0);
        ry = previewToggle(ui, "Cosmetics", cos.showAll(), x, ry, w, rowH, () -> cos.setShowAll(!cos.showAll()));
        ry = previewToggle(ui, "Other players", cos.showOthers(), x, ry, w, rowH, () -> cos.setShowOthers(!cos.showOthers()));
        ry = previewToggle(ui, "Client badge", cos.badges(), x, ry, w, rowH, () -> cos.setBadges(!cos.badges()));
        ry = previewToggle(ui, "Hide under armor", cos.hideUnderArmor(), x, ry, w, rowH, () -> cos.setHideUnderArmor(!cos.hideUnderArmor()));
        previewToggle(ui, "Wavy capes", cos.wavyCapes(), x, ry, w, rowH, () -> cos.setWavyCapes(!cos.wavyCapes()));
    }

    private float previewToggle(Ui ui, String text, boolean on, float x, float y, float w, float rowH, Runnable flip) {
        float sx = x + w - 24;
        ui.textMid(ui.fit(text, w - 34), x + 7, y, rowH, on ? LABEL : MUTED);
        ui.r.rect(sx, y + 3, 18, 9, on ? ACCENT : SWITCH_OFF, 4.5f);
        ui.r.rect(on ? sx + 10 : sx + 1, y + 4, 7, 7, 0xFFFFFFFF, 3.5f);
        ui.hit(x + 2, y, w - 4, rowH, on ? Ui.Sound.TOGGLE_OFF : Ui.Sound.TOGGLE_ON, (mx, my, b) -> flip.run());
        return y + rowH;
    }

    // ---- tabs + search ----------------------------------------------------------------------------------------------

    /** Slot tabs (wrapping) and the search box. Returns the grid's top. */
    private float tabs(Ui ui, Cosmetics cos, float gx, float y, float gw) {
        UiRenderer r = ui.r;
        float searchW = Math.min(110, gw * 0.3f);
        boolean searchInline = gw > 360;
        float tabsW = searchInline ? gw - searchW - 8 : gw;
        float tx = gx, ty = y;
        List<Slot> tabs = new ArrayList<Slot>();
        if (mode == Mode.SHOP) tabs.add(null);
        for (Slot sl : Slot.values()) tabs.add(sl);
        for (final Slot sl : tabs) {
            String label = sl == null ? "All" : sl.label;
            float tw = ui.tw(label) + 14;
            if (tx + tw > gx + tabsW && tx > gx) {
                tx = gx;
                ty += 20;
            }
            boolean sel = slot == sl, hover = ui.over(tx, ty, tw, 16);
            boolean wearing = sl != null && cos.equippedId(sl) != null;
            r.rect(tx, ty, tw, 16, sel ? ACCENT : hover ? 0x26FFFFFF : 0x14FFFFFF, 8);
            ui.textMid(label, tx + 7, ty, 16, sel ? 0xFFFFFFFF : wearing ? LABEL : MUTED);
            if (wearing && !sel && mode == Mode.WARDROBE) r.rect(tx + tw - 5, ty + 3, 3, 3, ACCENT, 1.5f);
            ui.hit(tx, ty, tw, 16, (mx, my, b) -> select(sl));
            tx += tw + 4;
        }
        if (searchInline) {
            ui.searchBox(search, gx + gw - searchW, y, searchW, 16, "Search");
            return ty + 22;
        }
        ui.searchBox(search, gx, ty + 20, gw, 16, "Search");
        return ty + 42;
    }

    // ---- lists ------------------------------------------------------------------------------------------------------

    private static boolean matches(Cosmetic c, String q) {
        return q.isEmpty() || c.name.toLowerCase(Locale.ROOT).contains(q) || c.slot.label.toLowerCase(Locale.ROOT).contains(q)
                || c.rarity.name().toLowerCase(Locale.ROOT).contains(q);
    }

    private String query() { return search.value.trim().toLowerCase(Locale.ROOT); }

    /** One scroll: "Yours" (None first) then "Get in the shop". Returns the hovered id ("" = None, null = nothing). */
    private String wardrobeLists(Ui ui, Cosmetics cos, Shop shop, float x, float y, float w, float h) {
        String q = query();
        List<Cosmetic> mine = new ArrayList<Cosmetic>(), toBuy = new ArrayList<Cosmetic>();
        for (Cosmetic c : cos.catalog(slot)) {
            if (!matches(c, q)) continue;
            if (cos.owns(c)) mine.add(c);
            else toBuy.add(c);
        }
        boolean none = q.isEmpty();
        Layout l = new Layout(ui, w);
        float total = SECTION + l.height(mine.size() + (none ? 1 : 0)) + (toBuy.isEmpty() ? 0 : GAP * 2 + SECTION + l.height(toBuy.size()));
        float off = ui.beginScroll(scroll, x, y, w, h, total);
        String hovered = null;
        float cy = y - off;
        section(ui, "Yours", mine.size(), x, cy, w, SUCCESS, false);
        cy += SECTION;
        List<Cosmetic> first = new ArrayList<Cosmetic>();
        if (none) first.add(null);
        first.addAll(mine);
        String hv = cards(ui, cos, shop, l, first, x, cy, y, h);
        if (hv != null) hovered = hv;
        cy += l.height(first.size());
        if (!toBuy.isEmpty()) {
            cy += GAP * 2;
            section(ui, "Get in the shop", toBuy.size(), x, cy, w, WARNING, true);
            cy += SECTION;
            hv = cards(ui, cos, shop, l, toBuy, x, cy, y, h);
            if (hv != null) hovered = hv;
        }
        ui.endScroll(scroll, x, y, w, h, total);
        if (mine.isEmpty() && toBuy.isEmpty() && !q.isEmpty()) ui.textCentered(ui.fit("Nothing matches \"" + search.value.trim() + "\"", w), x + w / 2f, y + h / 2f, MUTED);
        return hovered;
    }

    /** The Shop tab: everything for sale in the tab's slot, ones you don't own first. */
    private String shopGrid(Ui ui, Cosmetics cos, Shop shop, float x, float y, float w, float h) {
        String q = query();
        List<Cosmetic> list = new ArrayList<Cosmetic>(), owned = new ArrayList<Cosmetic>();
        List<Shop.Offer> offers = shop.offers();
        if (offers != null) {
            for (Shop.Offer o : offers) {
                if (o.free() || slot != null && o.slot != slot) continue;
                Cosmetic c = cos.registry().get(o.id);
                if (c == null || !matches(c, q)) continue; // not downloaded / shipped yet
                (cos.owns(c) ? owned : list).add(c);
            }
        }
        list.addAll(owned);
        Layout l = new Layout(ui, w);
        float total = l.height(list.size());
        float off = ui.beginScroll(scroll, x, y, w, h, total);
        String hovered = cards(ui, cos, shop, l, list, x, y - off, y, h);
        ui.endScroll(scroll, x, y, w, h, total);
        if (list.isEmpty()) {
            String line = !q.isEmpty() ? "Nothing matches \"" + search.value.trim() + "\"" : !shop.available() ? "The shop is offline"
                    : offers == null ? "Loading..." : "Nothing for sale here right now";
            ui.textCentered(ui.fit(line, w - 10), x + w / 2f, y + h / 2f - ui.fh() / 2f, MUTED);
        }
        return hovered;
    }

    private void section(Ui ui, String title, int count, float x, float y, float w, int color, boolean coin) {
        float tx = x;
        if (coin) {
            ClientGui.drawCoin(ui.r, x, y + 3, 9);
            tx += 13;
        } else {
            ui.r.rect(x, y + 4, 3, 8, color, 1.5f);
            tx += 7;
        }
        ui.text(title, tx, y + 3, TEXT);
        ui.text(String.valueOf(count), tx + ui.tw(title) + 6, y + 3, FAINT);
        ui.r.rect(tx + ui.tw(title) + ui.tw(String.valueOf(count)) + 12, y + 8, Math.max(0, x + w - (tx + ui.tw(title) + ui.tw(String.valueOf(count)) + 12)), 1, BORDER, 0);
    }

    /** Card grid geometry. */
    private static final class Layout {
        final int cols;
        final float cardW, thumbH = 46, cardH;

        Layout(Ui ui, float w) {
            cols = Math.max(2, (int) ((w + GAP) / (92 + GAP)));
            cardW = (w - GAP * (cols - 1)) / cols;
            cardH = thumbH + ui.fh() * 2 + 14;
        }

        float height(int n) { return n == 0 ? 0 : ((n + cols - 1) / cols) * (cardH + GAP) - GAP; }
    }

    private String cards(Ui ui, Cosmetics cos, Shop shop, Layout l, List<Cosmetic> list, float x, float y, float clipY, float clipH) {
        String hovered = null;
        for (int i = 0; i < list.size(); i++) {
            float cx = x + (i % l.cols) * (l.cardW + GAP), cy = y + (i / l.cols) * (l.cardH + GAP);
            Cosmetic c = list.get(i);
            boolean hover = ui.over(cx, cy, l.cardW, l.cardH);
            if (hover) hovered = c == null ? "" : c.id;
            if (cy + l.cardH < clipY || cy > clipY + clipH) continue;
            card(ui, cos, shop, c, cx, cy, l.cardW, l.cardH, l.thumbH, hover, x, clipY, l.cols * (l.cardW + GAP), clipH);
        }
        return hovered;
    }

    private void card(Ui ui, final Cosmetics cos, final Shop shop, final Cosmetic c, float x, float y, float w, float h, float thumbH, boolean hover,
                      final float areaX, final float areaY, final float areaW, final float areaH) {
        UiRenderer r = ui.r;
        final Slot sl = c == null ? slot : c.slot;
        String equippedId = sl == null ? null : cos.equippedId(sl);
        boolean equipped = c == null ? equippedId == null || cos.equipped(sl) == null : c.id.equals(equippedId);
        boolean owned = c == null || cos.owns(c);
        int rarity = c == null ? FAINT : c.rarity.color;
        r.rect(x, y, w, h, equipped && owned ? ACCENT : hover ? BORDER_HOVER : BORDER, 5);
        r.rect(x + 1, y + 1, w - 2, h - 2, hover ? RAISED_HOVER : RAISED, 4);
        float tx = x + 4, ty = y + 4, tw = w - 8;
        r.rect(tx, ty, tw, thumbH, (rarity & 0x00FFFFFF) | 0x1E000000, 3);
        r.rect(tx, ty + thumbH - 2, tw, 2, (rarity & 0x00FFFFFF) | 0x99000000, 1);
        if (c == null) {
            ui.textCentered("-", tx + tw / 2f, ty + (thumbH - ui.fh()) / 2f, MUTED);
        } else if (!r.cosmeticIcon(c, tx + 3, ty + 2, tw - 6, thumbH - 5)) {
            ui.textCentered(c.slot.label.substring(0, 1), tx + tw / 2f, ty + (thumbH - ui.fh()) / 2f, rarity);
        }
        if (!owned) {
            r.rect(tx, ty, tw, thumbH, 0x50000000, 3); // greyed out: not yours yet
            r.rect(tx + tw - 15, ty + 3, 12, 12, 0xE0101014, 3);
            ui.icon("lock", tx + tw - 13, ty + 5, 8, LABEL);
        }
        if (equipped && c != null && owned) {
            float bw = ui.tw("ON") + 6;
            ui.badge("ON", x + w - bw - 5, y + 5, ACCENT);
        }
        float ny = ty + thumbH + 4;
        ui.text(ui.fit(c == null ? "None" : c.name, w - 8), x + 4, ny, owned ? TEXT : LABEL);
        float sy = ny + ui.fh() + 2;
        if (c == null) {
            ui.text(ui.fit("Nothing in this slot", w - 8), x + 4, sy, MUTED);
        } else if (!owned) {
            Shop.Offer o = shop.offer(c.id);
            if (o != null && !o.free()) {
                ClientGui.drawCoin(r, x + 4, sy, 8);
                ui.text(ui.fit(shop.unlimited() || o.price == 0 ? "Free" : MenuFeed.formatCoins(o.price), w - 18), x + 15, sy, WARNING);
            } else ui.text(ui.fit("In the store", w - 8), x + 4, sy, MUTED);
        } else if (mode == Mode.SHOP) {
            ui.text(ui.fit(equipped ? "Owned · Wearing" : "Owned", w - 8), x + 4, sy, SUCCESS);
        } else {
            String sub = Ui.title(c.rarity.name());
            if (c.animated()) sub += " · Animated";
            if (c.id.startsWith("local/")) sub += " · Only you";
            ui.text(ui.fit(sub, w - 8), x + 4, sy, rarity);
        }
        ui.hit(x, y, w, h, (mx, my, b) -> {
            if (c == null) {
                if (sl != null) cos.equip(sl, null);
                return;
            }
            if (!cos.owns(c) || mode == Mode.SHOP && b == 0 && !c.id.equals(cos.equippedId(sl))) {
                buying = new BuyPopup(gui, c, areaX, areaY, Math.min(areaW, gui.screenW), areaH);
                gui.ui.openPopup(buying);
                return;
            }
            if (b == 1 || c.id.equals(cos.equippedId(sl))) cos.equip(sl, null);
            else cos.equip(sl, c.id);
            gui.host.save();
        });
    }
}
