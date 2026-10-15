package gg.aether.core.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the main menu shows that comes from the Aether backend: coin balance, announcements and the promo under the
 * Store button. The backend has no endpoints for these yet, so {@link #preview()} supplies sample content (the
 * launcher does the same with its mock data).
 */
public final class MenuFeed {
    public static final class Announcement {
        public final String tag, title, body;
        public final int color;

        public Announcement(String tag, String title, String body, int color) {
            this.tag = tag;
            this.title = title;
            this.body = body;
            this.color = color;
        }
    }

    /** Coin balance, or -1 when unknown (signed out / offline). */
    public final long coins;
    public final List<Announcement> announcements;
    public final String promoTitle, promoText;

    public MenuFeed(long coins, List<Announcement> announcements, String promoTitle, String promoText) {
        this.coins = coins;
        this.announcements = Collections.unmodifiableList(new ArrayList<Announcement>(announcements));
        this.promoTitle = promoTitle;
        this.promoText = promoText;
    }

    /** Sample content until the backend serves the real thing (the coin balance is live: see Shop). */
    public static MenuFeed preview() {
        List<Announcement> a = new ArrayList<Announcement>();
        a.add(new Announcement("UPDATE", "A brand new menu", "Profiles, two mod list layouts and a colour picker for every colour option.", Theme.ACCENT));
        a.add(new Announcement("COSMETICS", "Wavy capes are here", "Every cape now sways and flutters as you move. Toggle it in the Wardrobe.", Theme.ACCENT_ALT));
        a.add(new Announcement("TIP", "Share your setup", "Copy a profile from the sidebar and send it to a friend. Import pastes it back.", Theme.SUCCESS));
        a.add(new Announcement("EVENT", "Season 1 starts soon", "New capes and wings every week. Keep an eye on the store.", Theme.WARNING));
        return new MenuFeed(-1, a, "Season 1 capes", "New in the store this week");
    }

    /** "1250" -> "1,250". */
    public static String formatCoins(long coins) {
        if (coins < 0) return "-";
        return String.format(java.util.Locale.ROOT, "%,d", coins);
    }
}
