package gg.aether.core.ui;

import gg.aether.core.GameState;
import gg.aether.core.Keys;
import gg.aether.core.ModManager;
import gg.aether.core.UiRenderer;
import java.util.Collections;
import java.util.List;

import static gg.aether.core.ui.Theme.*;

/**
 * The Kite title screen. Top left: account switcher. Top right: coins, switch to the vanilla menu, quit. Centre:
 * logo, Singleplayer, Multiplayer, Store and a promo. Bottom centre: client buttons. Bottom right: announcement
 * carousel and disclaimer. Bottom left: version.
 */
public final class MainMenuView implements View {
    /** What the adapter does for the menu. */
    public interface Host {
        void singleplayer();
        void multiplayer();
        /** Minecraft's own options screen. */
        void minecraftOptions();
        void realms();
        /** True when the Mod Menu mod is installed. */
        default boolean hasModMenu() { return false; }
        default void modMenu() {}
        void quit();
        /** The Kite mod window (Settings tab). */
        void clientSettings();
        void wardrobe();
        /** The in-game shop (the wardrobe's Shop tab). */
        default void store() { wardrobe(); }
        /** Turn the custom menu off and show vanilla's title screen. */
        void useVanillaMenu();
        /** Signed-in accounts known to the launcher, the current one marked active. */
        default List<Account> accounts() { return Collections.emptyList(); }
        /** Ask to play as {@code a}. Returns a message for the player. */
        default String switchAccount(Account a) { return "Switch accounts in the launcher"; }
        /** True when {@link #switchAccount} signs in right away instead of restarting the game. */
        default boolean switchesLive() { return false; }
        /** A message that arrived since the last call (a live account switch finished), or null. */
        default Notice pollNotice() { return null; }
        /** "Kite Client 1.21.11 (v0.1.0-2651)". */
        String versionLine();
    }

    public static final class Account {
        public final String name, uuid, kind;
        public final boolean active;

        public Account(String name, String uuid, String kind, boolean active) {
            this.name = name;
            this.uuid = uuid;
            this.kind = kind;
            this.active = active;
        }
    }

    /** A message for the player from the host, shown as a flash. */
    public static final class Notice {
        public final String text;
        public final boolean ok;

        public Notice(String text, boolean ok) {
            this.text = text;
            this.ok = ok;
        }
    }

    static final String DISCLAIMER = "Not affiliated with Mojang or Microsoft. Do not distribute!";
    static final long SLIDE_MS = 6000, SWIPE_MS = 450;
    /** Height of the top-corner pills and size of the bottom icon buttons. */
    static final float PILL = 18, ICON_BTN = 24;
    /** Scale of the faint corner text (version, disclaimer). */
    static final float SMALL = 0.75f;

    private final ModManager mm;
    private final Host host;
    private final MenuFeed feed;
    final Ui ui;
    private final long openedAt = System.currentTimeMillis();

    private int slide;
    private int fromSlide = -1;
    /** When the current swipe started; the swipe animation runs on this clock only. */
    private long swipeAt = System.currentTimeMillis();
    /** +1 = the new slide comes in from the right, -1 from the left. */
    private int swipeDir = 1;
    /** Milliseconds the current slide has been shown, not counting time under the mouse. */
    private float shownMs;
    /** Centre of each bottom button by its tooltip label (tests click them). */
    final java.util.Map<String, float[]> iconButtons = new java.util.LinkedHashMap<String, float[]>();
    /** Centre of each carousel dot, and the carousel's box (tests). */
    final List<float[]> dots = new java.util.ArrayList<float[]>();
    float[] carousel;

    public MainMenuView(ModManager mm, Host host, MenuFeed feed) {
        this.mm = mm;
        this.host = host;
        this.feed = feed == null ? MenuFeed.preview() : feed;
        this.ui = new Ui(mm.host());
        ui.soundVolume(mm.client().uiSoundVolume());
    }

    @Override public boolean transparent() { return false; }
    @Override public boolean panorama() { return true; }
    @Override public boolean muteMusic() { return !mm.client().menuMusic.on(); }

    private float intro() {
        if (mm.client().reducedMotion.on()) return 1f;
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 400f);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    // ---- rendering --------------------------------------------------------------------------------------------------

    @Override public void render(UiRenderer r, GameState s) {
        ui.soundVolume(mm.client().uiSoundVolume());
        ui.begin(r, mm.client().reducedMotion.on());
        float w = r.screenWidth(), h = r.screenHeight(), t = intro();
        // Darken the panorama towards the bottom so text stays readable.
        r.rect(0, 0, w, h, 0x50000000, 0);
        r.rect(0, h * 0.55f, w, h * 0.45f, 0x30000000, 0);
        r.rect(0, h * 0.78f, w, h * 0.22f, 0x30000000, 0);

        topLeft(w, t);
        topRight(w, t);
        float rowTop = bottomRow(w, h, t);
        float centreBottom = centre(w, h, t, rowTop);
        announcements(w, h, t, centreBottom, rowTop);

        float sy = h - ui.fh() * SMALL - 5;
        small(host.versionLine(), 6, sy, alpha(0x80FFFFFF, t));
        small(DISCLAIMER, w - ui.tw(DISCLAIMER) * SMALL - 6, sy, alpha(0x80FFFFFF, t));
        Notice notice = host.pollNotice();
        if (notice != null) ui.flash(notice.text, notice.ok ? SUCCESS : WARNING);
        ui.drawFlash(w / 2f, rowTop - ui.fh() - 16);
        ui.end();
    }

    /** Faint corner text at {@link #SMALL} scale. */
    private void small(String s, float x, float y, int color) {
        ui.r.push(x, y, SMALL);
        ui.text(s, 0, 0, color);
        ui.r.pop();
    }

    private void topLeft(float w, float t) {
        List<Account> accounts = host.accounts();
        Account cur = null;
        for (Account a : accounts) if (a.active) cur = a;
        final String name = cur != null ? cur.name : mm.host().sessionName() != null ? mm.host().sessionName() : "Player";
        final String uuid = cur != null ? cur.uuid : mm.host().sessionUuid();
        float x = 6, y = 6, h = PILL;
        float pw = 4 + 12 + 5 + ui.tw(name) + 16;
        boolean hover = ui.over(x, y, pw, h);
        ui.r.rect(x, y, pw, h, alpha(hover ? 0xC024242C : 0x900E0E11, t), h / 2f);
        avatar(name, uuid, x + 3, y + 3, 12, t);
        ui.textMid(name, x + 20, y, h, alpha(TEXT, t));
        ui.chevron(x + pw - 9, y + h / 2f - 1, alpha(MUTED, t));
        final float ax = x, ay = y + h + 4;
        ui.hit(x, y, pw, h, (mx, my, b) -> ui.openPopup(new AccountMenu(ax, ay)));
    }

    /** The account's skin face (with the hat layer), or their initial on an accent tile until the skin is known. */
    private void avatar(String name, String uuid, float x, float y, float size, float t) {
        if (ui.r.head(name, uuid, x, y, size)) return;
        ui.r.rect(x, y, size, size, alpha(ACCENT, t), 3);
        String ini = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        ui.textCentered(ini, x + size / 2f, y + (size - ui.fh()) / 2f + 1, alpha(0xFFFFFFFF, t));
    }

    private void topRight(float w, float t) {
        float y = 6, h = PILL, x = w - 6;
        // Quit.
        x -= h;
        boolean qh = ui.over(x, y, h, h);
        ui.r.rect(x, y, h, h, alpha(qh ? 0xC0E07A84 : 0x900E0E11, t), h / 2f);
        ModWindow.cross(ui, x + h / 2f, y + h / 2f, alpha(qh ? 0xFFFFFFFF : LABEL, t));
        ui.hit(x, y, h, h, (mx, my, b) -> host.quit());
        if (qh) tooltip("Quit game", x + h / 2f, y + h + 4);
        // Vanilla menu switch.
        x -= h + 5;
        boolean vh = ui.over(x, y, h, h);
        ui.r.rect(x, y, h, h, alpha(vh ? 0xC024242C : 0x900E0E11, t), h / 2f);
        // Two overlapping frames: "swap layout".
        ui.outline(x + 4.5f, y + 4.5f, 7, 6, alpha(vh ? TEXT : LABEL, t));
        ui.outline(x + 7, y + 7, 7, 6, alpha(vh ? ACCENT : MUTED, t));
        ui.hit(x, y, h, h, (mx, my, b) -> {
            mm.client().customMainMenu.set(false);
            host.useVanillaMenu();
        });
        if (vh) tooltip("Use the vanilla menu", x + h / 2f, y + h + 4);
        // Coins: the live balance once the backend answered, the feed's otherwise.
        gg.aether.core.cosmetics.Shop shop = mm.cosmetics().shop();
        String coins = shop.unlimited() ? "Unlimited" : MenuFeed.formatCoins(shop.coins() >= 0 ? shop.coins() : feed.coins);
        float cw = 6 + 10 + 4 + ui.tw(coins) + 8;
        x -= cw + 5;
        boolean ch = ui.over(x, y, cw, h);
        ui.r.rect(x, y, cw, h, alpha(ch ? 0xC024242C : 0x900E0E11, t), h / 2f);
        coin(x + 6, y + 4, 10, t);
        ui.textMid(coins, x + 20, y, h, alpha(WARNING, t));
        ui.hit(x, y, cw, h, (mx, my, b) -> host.store());
        if (ch) tooltip("Kite coins", x + cw / 2f, y + h + 4);
    }

    private void coin(float x, float y, float s, float t) {
        ui.r.rect(x, y, s, s, alpha(0xFFE0A526, t), s / 2f);
        ui.r.rect(x + 1.5f, y + 1.5f, s - 3, s - 3, alpha(0xFFF5B942, t), s / 2f - 1.5f);
        ui.r.rect(x + s / 2f - 1, y + 2.5f, 2, s - 5, alpha(0xFFE0A526, t), 0);
    }

    private void tooltip(String text, float cx, float y) {
        float w = ui.tw(text) + 10;
        float x = Math.max(4, Math.min(ui.r.screenWidth() - w - 4, cx - w / 2f));
        ui.r.rect(x, y, w, ui.fh() + 6, 0xF01C1C22, 3);
        ui.text(text, x + 5, y + 4, TEXT);
    }

    /** Width of the centre column (buttons, promo). */
    static float centreWidth(float w) { return Math.min(170, Math.max(128, w * 0.3f)); }

    /**
     * Logo, name, Singleplayer / Multiplayer / Store and the promo, fitted between the top bar and the bottom row
     * (smaller logo, then no promo, on short screens). Returns the block's bottom edge.
     */
    private float centre(float w, float h, float t, float rowTop) {
        float bw = centreWidth(w), bh = 17, gap = 4, promoH = 26;
        float top = 30, avail = rowTop - 8 - top;
        float logo = Math.max(24, Math.min(44, h * 0.11f));
        float nameScale = Math.max(1.75f, Math.min(2.75f, h / 120f));
        boolean promo = true;
        float block = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            block = logo + 5 + ui.fh() * nameScale + 10 + (bh + gap) * 3 - gap + (promo ? 3 + promoH : 0);
            if (block <= avail) break;
            if (attempt == 0) {
                logo = 22;
                nameScale = 1.75f;
            } else {
                promo = false;
            }
        }
        float nameH = ui.fh() * nameScale;
        float y = top + Math.max(0, (avail - block) / 2f) + (1 - t) * 12;
        float cx = w / 2f;

        ClientGui.drawLogo(ui.r, cx - logo / 2f, y, logo);
        y += logo + 5;
        String name = "KITE";
        float nw = ui.displayWidth(name, nameScale);
        ui.display(name, cx - nw / 2f + 1, y + 1, nameScale, alpha(0x66000000, t));
        ui.display(name, cx - nw / 2f, y, nameScale, alpha(TEXT, t));
        y += nameH + 10;

        float bx = cx - bw / 2f;
        menuButton("Singleplayer", bx, y, bw, bh, Ui.Style.SECONDARY, t, host::singleplayer);
        y += bh + gap;
        menuButton("Multiplayer", bx, y, bw, bh, Ui.Style.SECONDARY, t, host::multiplayer);
        y += bh + gap;
        menuButton("Store", bx, y, bw, bh, Ui.Style.STORE, t, host::store);
        y += bh;
        if (!promo) return y;
        y += 3;

        // Promo under the store button.
        boolean ph = ui.over(bx, y, bw, promoH);
        ui.r.rect(bx, y, bw, promoH, alpha(ph ? 0xD0241A38 : 0xB0150F22, t), 4);
        ui.r.rect(bx, y, 2, promoH, alpha(ACCENT_ALT, t), 1);
        float tagW = ui.badge("AD", bx + 7, y + 3, ACCENT_ALT);
        ui.text(ui.fit(feed.promoTitle, bw - tagW - 18), bx + 11 + tagW, y + 4, alpha(TEXT, t));
        small(ui.fit(feed.promoText, (bw - 14) / SMALL), bx + 7, y + 6 + ui.fh(), alpha(MUTED, t));
        ui.hit(bx, y, bw, promoH, (mx, my, b) -> host.store());
        return y + promoH;
    }

    private void menuButton(String text, float x, float y, float w, float h, Ui.Style style, float t, Runnable run) {
        boolean hover = ui.over(x, y, w, h);
        if (style == Ui.Style.STORE) {
            ui.r.rect(x, y, w, h, alpha(hover ? 0xFFC385FF : ACCENT_ALT, t), 4);
            ui.r.rect(x, y, w, h / 2f, alpha(0x18FFFFFF, t), 4);
        } else {
            ui.r.rect(x, y, w, h, alpha(hover ? 0x80FFFFFF : 0x30FFFFFF, t), 4);
            ui.r.rect(x + 1, y + 1, w - 2, h - 2, alpha(hover ? 0xE024242C : 0xC00E0E11, t), 3);
        }
        ui.textCentered(text, x + w / 2f, y + (h - ui.fh()) / 2f + 1, alpha(style == Ui.Style.STORE || hover ? 0xFFFFFFFF : TEXT, t));
        ui.hit(x, y, w, h, (mx, my, b) -> run.run());
    }

    /** The client buttons along the bottom: icon-only squares with a tooltip. Returns their top edge. */
    private float bottomRow(float w, float h, float t) {
        String[][] items = {
                { "aether", "Kite settings" },
                { "wardrobe", "Wardrobe" },
                { "chat", "Chat" },
                { "sliders", "Minecraft settings" },
                { "realms", "Realms" },
                { "package", "Mod Menu" } };
        int n = host.hasModMenu() ? items.length : items.length - 1;
        float s = ICON_BTN, gap = 5, total = n * s + (n - 1) * gap;
        float x = (w - total) / 2f, y = h - s - ui.fh() * SMALL - 12;
        iconButtons.clear();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final String[] it = items[i];
            boolean hover = ui.over(x, y, s, s);
            float lift = hover && !ui.reducedMotion() ? 1 : 0;
            ui.r.rect(x, y - lift, s, s, alpha(hover ? 0x808B5CF6 : 0x30FFFFFF, t), 6);
            ui.r.rect(x + 1, y + 1 - lift, s - 2, s - 2, alpha(hover ? 0xE824242C : 0xB00E0E11, t), 5);
            float is = 14;
            ui.icon(it[0], x + (s - is) / 2f, y + (s - is) / 2f - lift, is, alpha(hover ? 0xFFFFFFFF : LABEL, t));
            ui.hit(x, y, s, s, (mx, my, b) -> bottomAction(idx));
            iconButtons.put(it[1], new float[] { x + s / 2f, y + s / 2f });
            if (hover) tooltip(it[1], x + s / 2f, y - ui.fh() - 10);
            x += s + gap;
        }
        return y;
    }

    private void bottomAction(int i) {
        switch (i) {
            case 0: host.clientSettings(); break;
            case 1: host.wardrobe(); break;
            case 2: comingSoon("Chat", "Friends, parties and messages between Kite players are coming with the social update."); break;
            case 3: host.minecraftOptions(); break;
            case 4: host.realms(); break;
            default: host.modMenu(); break;
        }
    }

    // ---- announcements ----------------------------------------------------------------------------------------------

    private void announcements(float w, float h, float t, float centreBottom, float rowTop) {
        List<MenuFeed.Announcement> list = feed.announcements;
        if (list.isEmpty() || !mm.client().announcements.on()) return;
        float chh = 48;
        float centreRight = w / 2f + centreWidth(w) / 2f;
        float x, y, cw;
        float rowHalf = (6 * (ICON_BTN + 5)) / 2f;
        float bottom = h - ui.fh() * SMALL - 10;
        if (w - (w / 2f + rowHalf) - 14 >= 140) {
            // Room beside the button row: the bottom-right corner.
            cw = Math.min(170, w - (w / 2f + rowHalf) - 14);
            y = bottom - chh;
        } else {
            // Otherwise above the button row, beside the centre column.
            cw = Math.min(170, w - centreRight - 14);
            y = rowTop - chh - 8;
            if (cw < 120) return;
        }
        x = w - cw - 6;
        if (x < centreRight + 6 && y < centreBottom + 6) return; // no room on small windows

        long now = System.currentTimeMillis();
        boolean hover = ui.over(x, y, cw, chh);
        // Auto-advance pauses while hovered; the swipe itself always runs on its own clock.
        if (!hover) shownMs += ui.dt() * 1000;
        if (shownMs >= SLIDE_MS && list.size() > 1) go((slide + 1) % list.size(), now, 1);
        float p = mm.client().reducedMotion.on() || fromSlide < 0 ? 1f : Math.max(0f, Math.min(1f, (now - swipeAt) / (float) SWIPE_MS));
        p = 1 - (1 - p) * (1 - p) * (1 - p);
        float shift = Math.round(cw * p);

        ui.r.rect(x, y, cw, chh, alpha(0xD00E0E11, t), 5);
        ui.outline(x, y, cw, chh, alpha(0x28FFFFFF, t));
        ui.clip(x + 1, y + 1, cw - 2, chh - 2);
        if (p < 1f && fromSlide >= 0 && fromSlide < list.size()) card(list.get(fromSlide), x - swipeDir * shift, y, cw, chh, t);
        card(list.get(slide % list.size()), x + swipeDir * (cw - shift), y, cw, chh, t);
        ui.unclip();

        // Dots.
        carousel = new float[] { x, y, cw, chh };
        dots.clear();
        float dx = x + cw - 8 - list.size() * 7;
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            boolean sel = i == slide;
            ui.r.rect(dx + i * 7, y + chh - 7, sel ? 5 : 3, 3, alpha(sel ? TEXT : 0x66FFFFFF, t), 1.5f);
            dots.add(new float[] { dx + i * 7 + 1.5f, y + chh - 5.5f });
            ui.hit(dx + i * 7 - 2, y + chh - 10, 7, 9, Ui.Sound.SELECT, (mx, my, b) -> go(idx, System.currentTimeMillis(), idx > slide ? 1 : -1));
        }
    }

    private void go(int to, long now, int dir) {
        if (to == slide) return;
        fromSlide = slide;
        slide = to;
        swipeAt = now;
        swipeDir = dir;
        shownMs = 0;
    }

    private void card(MenuFeed.Announcement a, float x, float y, float w, float h, float t) {
        float tw = ui.badge(a.tag, x + 7, y + 6, a.color);
        ui.text(ui.fit(a.title, w - tw - 20), x + 11 + tw, y + 7, alpha(TEXT, t));
        float ly = y + 10 + ui.fh();
        for (String line : ui.wrap(a.body, (w - 14) / SMALL, 2)) {
            small(line, x + 7, ly, alpha(MUTED, t));
            ly += ui.fh() * SMALL + 1;
        }
    }

    // ---- popups -----------------------------------------------------------------------------------------------------

    private void comingSoon(final String title, final String text) {
        ui.openPopup(new Ui.Popup() {
            @Override public void render(Ui u) {
                float w = Math.min(220, u.r.screenWidth() - 20);
                List<String> lines = u.wrap(text, w - 20, 4);
                float h = 14 + u.fh() + 8 + lines.size() * (u.fh() + 2) + 10 + 18 + 10;
                float x = (u.r.screenWidth() - w) / 2f, y = (u.r.screenHeight() - h) / 2f;
                u.r.rect(0, 0, u.r.screenWidth(), u.r.screenHeight(), 0x66000000, 0);
                u.r.rect(x - 1, y - 1, w + 2, h + 2, BORDER_HOVER, 7);
                u.r.rect(x, y, w, h, 0xFA1C1C22, 6);
                u.absorb(x, y, w, h);
                float cy = y + 14;
                u.display(title, x + 10, cy, 1f, TEXT);
                u.badge("SOON", x + w - 10 - u.tw("SOON") - 6, cy - 1, WARNING);
                cy += u.fh() + 8;
                for (String l : lines) {
                    u.text(l, x + 10, cy, MUTED);
                    cy += u.fh() + 2;
                }
                u.button("Got it", x + w - 70, y + h - 28, 60, 18, Ui.Style.PRIMARY, (mx, my, b) -> u.closePopup());
            }
        });
    }

    private final class AccountMenu implements Ui.Popup {
        private final float x, y;
        private String confirm;

        AccountMenu(float x, float y) {
            this.x = x;
            this.y = y;
        }

        @Override public void render(Ui u) {
            List<Account> list = host.accounts();
            float w = 180, rowH = 22;
            float h = 8 + Math.max(1, list.size()) * rowH + 6 + u.fh() * 2 + 10;
            u.r.rect(x - 1, y - 1, w + 2, h + 2, BORDER_HOVER, 6);
            u.r.rect(x, y, w, h, 0xFA1C1C22, 5);
            u.absorb(x, y, w, h);
            float ry = y + 4;
            if (list.isEmpty()) {
                String n = mm.host().sessionName() == null ? "Player" : mm.host().sessionName();
                avatar(n, mm.host().sessionUuid(), x + 8, ry + 3, 16, 1f);
                u.textMid(n, x + 30, ry, rowH, TEXT);
                ry += rowH;
            }
            for (final Account a : list) {
                boolean hover = !a.active && u.over(x + 3, ry, w - 6, rowH);
                if (a.active) u.r.rect(x + 3, ry, w - 6, rowH, 0x338B5CF6, 4);
                else if (hover) u.r.rect(x + 3, ry, w - 6, rowH, 0x14FFFFFF, 4);
                avatar(a.name, a.uuid, x + 8, ry + 3, 16, 1f);
                boolean asking = a.uuid != null && a.uuid.equals(confirm);
                String again = host.switchesLive() ? "Click again to switch" : "Click again to restart";
                u.textMid(u.fit(asking ? again : a.name, w - 70), x + 30, ry, rowH, asking ? WARNING : TEXT);
                String kind = a.active ? "Playing" : "offline".equals(a.kind) ? "Offline" : "Microsoft";
                u.textMid(kind, x + w - 8 - u.tw(kind), ry, rowH, a.active ? SUCCESS : FAINT);
                if (!a.active) {
                    u.hit(x + 3, ry, w - 6, rowH, (mx, my, b) -> {
                        if (a.uuid != null && a.uuid.equals(confirm)) {
                            String msg = host.switchAccount(a);
                            u.closePopup();
                            if (msg != null) ui.flash(msg, host.switchesLive() ? ACCENT : WARNING);
                        } else {
                            confirm = a.uuid;
                        }
                    });
                }
                ry += rowH;
            }
            ry += 4;
            u.r.rect(x + 8, ry, w - 16, 1, BORDER, 0);
            ry += 5;
            String help = host.switchesLive() ? "Switching signs you in right away. Add accounts in the launcher."
                    : "Switching restarts the game through the launcher. Add accounts in the launcher.";
            for (String l : u.wrap(help, w - 16, 2)) {
                u.text(l, x + 8, ry, FAINT);
                ry += u.fh() + 1;
            }
        }
    }

    // ---- input ------------------------------------------------------------------------------------------------------

    @Override public void mouseMoved(float x, float y) { ui.mouseMoved(x, y); }
    @Override public boolean mouseDown(float x, float y, int button) { return ui.mouseDown(x, y, button); }
    @Override public boolean mouseDrag(float x, float y) { return ui.mouseDrag(x, y); }
    @Override public boolean mouseUp(float x, float y, int button) { return ui.mouseUp(x, y, button); }
    @Override public boolean scroll(float x, float y, float delta) { return ui.scroll(x, y, delta); }

    @Override public boolean keyDown(int code) {
        if (ui.keyDown(code)) return true;
        // The title screen never closes on Esc.
        return code == Keys.ESCAPE;
    }

    @Override public void keyUp(int code) { ui.keyUp(code); }
    @Override public boolean charTyped(char c) { return ui.charTyped(c); }
    @Override public void closed() { ui.closePopup(); }
}
