package gg.aether.core.ui;

import gg.aether.core.cosmetics.Cosmetic;
import gg.aether.core.cosmetics.Cosmetics;
import gg.aether.core.cosmetics.Shop;
import java.util.List;

import static gg.aether.core.ui.Theme.*;

/**
 * Clicking a cosmetic you don't own: a card over the grid (the preview stays visible and wears it) with its picture,
 * description, price and balance, and Buy. Once bought it says so and offers to wear it. Admins can also take
 * published items out of the store here.
 */
final class BuyPopup implements Ui.Popup {
    private final ClientGui gui;
    final Cosmetic item;
    /** Where to centre it: the grid area of the wardrobe window. */
    private final float ax, ay, aw, ah;

    BuyPopup(ClientGui gui, Cosmetic item, float ax, float ay, float aw, float ah) {
        this.gui = gui;
        this.item = item;
        this.ax = ax;
        this.ay = ay;
        this.aw = aw;
        this.ah = ah;
    }

    @Override public void closed() { gui.wardrobe.popupClosed(this); }

    @Override public void render(final Ui ui) {
        final Cosmetics cos = gui.mm.cosmetics();
        final Shop shop = cos.shop();
        Shop.Offer offer = shop.offer(item.id);
        boolean owned = cos.owns(item);
        float w = Math.min(270, aw - 8), h = 150, pad = 10;
        float x = Math.round(ax + (aw - w) / 2f), y = Math.round(ay + (ah - h) / 2f);
        ui.r.rect(x - 1, y - 1, w + 2, h + 2, item.rarity.color, 7);
        ui.r.rect(x, y, w, h, 0xFC18181D, 6);
        ui.absorb(x, y, w, h);

        // Picture.
        float th = 84, tw = 84, tx = x + pad, ty = y + pad;
        ui.r.rect(tx, ty, tw, th, (item.rarity.color & 0xFFFFFF) | 0x24000000, 5);
        if (!ui.r.cosmeticIcon(item, tx + 6, ty + 6, tw - 12, th - 12)) ui.textCentered(item.slot.label.substring(0, 1), tx + tw / 2f, ty + th / 2f - 4, item.rarity.color);

        // Text.
        float cx = tx + tw + 10, cw = x + w - pad - cx, cy = ty;
        ui.display(ui.fit(item.name, cw), cx, cy, 1f, TEXT);
        cy += ui.fh() + 4;
        String meta = Ui.title(item.rarity.name()) + " · " + item.slot.label + (item.animated() ? " · Animated" : "");
        ui.text(ui.fit(meta, cw), cx, cy, item.rarity.color);
        cy += ui.fh() + 6;
        String desc = item.description == null || item.description.isEmpty() ? (offer != null ? offer.description : "") : item.description;
        List<String> lines = ui.wrap(desc, cw, 3);
        for (String l : lines) {
            ui.text(l, cx, cy, MUTED);
            cy += ui.fh() + 2;
        }
        if (offer != null && offer.custom && !offer.author.isEmpty()) ui.text(ui.fit("By " + offer.author, cw), cx, ty + th - ui.fh(), FAINT);

        // Price and balance.
        float py = ty + th + 8;
        ui.r.rect(x + pad, py - 4, w - pad * 2, 1, BORDER, 0);
        String balance = shop.unlimited() ? "Unlimited" : MenuFeed.formatCoins(shop.coins());
        if (!owned && offer != null && !offer.free()) {
            ClientGui.drawCoin(ui.r, x + pad, py + 2, 10);
            ui.text(offer.price == 0 ? "Free" : MenuFeed.formatCoins(offer.price), x + pad + 14, py + 3, WARNING);
            ui.text(ui.fit("You have " + balance, w / 2f), x + pad, py + 3 + ui.fh() + 3, FAINT);
        } else if (owned) {
            ui.text("It's yours", x + pad, py + 3, SUCCESS);
        } else {
            ui.text(ui.fit(shop.available() ? "Not for sale" : "The shop is offline", w / 2f), x + pad, py + 3, MUTED);
        }

        // Buttons, right to left.
        float right = x + w - pad, by = y + h - pad - 18;
        if (owned) {
            final boolean wearing = item.id.equals(cos.equippedId(item.slot));
            String label = wearing ? "Take off" : "Wear it";
            float bw = ui.buttonWidth(label) + 10;
            right = ui.button(label, right - bw, by, bw, 18, Ui.Style.PRIMARY, (mx, my, b) -> {
                cos.equip(item.slot, wearing ? null : item.id);
                gui.host.save();
                gui.ui.closePopup();
            }) - 4;
        } else if (offer != null && !offer.free()) {
            if (item.id.equals(shop.buying())) {
                float bw = ui.buttonWidth("Buying...") + 10;
                right = ui.button("Buying...", right - bw, by, bw, 18, Ui.Style.GHOST, (mx, my, b) -> { }) - 4;
            } else if (!shop.affordable(offer.price)) {
                String label = "Need " + MenuFeed.formatCoins(offer.price - Math.max(0, shop.coins())) + " more";
                float bw = ui.buttonWidth(label) + 10;
                right = ui.button(label, right - bw, by, bw, 18, Ui.Style.SECONDARY, (mx, my, b) -> ui.flash("Top up coins in the Kite launcher's store", WARNING)) - 4;
            } else {
                String label = shop.unlimited() || offer.price == 0 ? "Get it" : "Buy for " + MenuFeed.formatCoins(offer.price);
                float bw = ui.buttonWidth(label) + 10;
                right = ui.button(label, right - bw, by, bw, 18, Ui.Style.STORE, (mx, my, b) -> shop.buy(item.id)) - 4;
            }
        }
        float cw2 = ui.buttonWidth("Close");
        right = ui.button("Close", right - cw2, by, cw2, 18, Ui.Style.SECONDARY, (mx, my, b) -> gui.ui.closePopup()) - 4;
        if (shop.admin() && offer != null && offer.custom) {
            ui.confirmButton("unpublish:" + item.id, "Remove", right, by, 18, () -> {
                shop.unpublish(item.id);
                gui.ui.closePopup();
            });
        }
    }
}
