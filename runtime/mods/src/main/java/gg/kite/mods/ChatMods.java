package gg.kite.mods;

import gg.kite.core.Events;
import gg.kite.core.GameState;
import gg.kite.core.Hotkey;
import gg.kite.core.HudElement;
import gg.kite.core.HudElement.Anchor;
import gg.kite.core.Keys;
import gg.kite.core.Mod;
import gg.kite.core.Setting;
import gg.kite.core.UiRenderer;
import gg.kite.core.ui.View;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Chat, chat notifications, tab list, friends and recording mode. */
public final class ChatMods {
    private ChatMods() {}

    /** Set by {@link BuiltinMods} so the tab list and chat can highlight friends. */
    static Friends friends;

    public enum Sound { NONE, DING, PLING, BELL, ORB, LEVELUP }

    static void play(gg.kite.core.ClientHost h, Sound s) {
        if (s != Sound.NONE) h.playSound(s.name().toLowerCase(Locale.ROOT));
    }

    /** One-shot hotkey actions: every flip in toggle mode, only the press in hold mode. */
    static boolean pressed(Hotkey h) { return h.mode.val() == Hotkey.Mode.TOGGLE || h.active(); }

    // ---- Chat -------------------------------------------------------------------------------------------------------

    /** One remembered chat line (for the history / search view). */
    static final class Line {
        final long timeMs;
        final String text;
        final boolean hidden;
        Line(long timeMs, String text, boolean hidden) { this.timeMs = timeMs; this.text = text; this.hidden = hidden; }
    }

    /**
     * Timestamps, mention highlighting, regex filters, ignored players, stacking of repeated lines, chat tabs, a longer
     * history, and a searchable history view with copy.
     */
    public static final class Chat extends Mod {
        private final Setting.Bool timestamps = add(new Setting.Bool("timestamps", "Timestamps", true));
        private final Setting.Bool h24 = add(new Setting.Bool("h24", "24-hour timestamps", true), timestamps);
        private final Setting.Bool seconds = add(new Setting.Bool("seconds", "Seconds in timestamps", false), timestamps);
        private final Setting.Bool mentions = add(new Setting.Bool("mentions", "Highlight mentions of my name", true));
        private final Setting.Color mentionColor = add(new Setting.Color("mentionColor", "Mention color", 0xFFFFC23D), mentions);
        private final Setting.Text mentionWords = add(new Setting.Text("mentionWords", "Also highlight (comma separated)", "", 200), mentions);
        private final Setting.Choice<Sound> mentionSound = add(new Setting.Choice<Sound>("mentionSound", "Mention sound", Sound.DING), mentions);
        private final Setting.Bool stack = add(new Setting.Bool("stack", "Stack repeated messages (x2)", true));
        private final Setting.Choice<ChatText.Channel> tab = add(new Setting.Choice<ChatText.Channel>("tab", "Chat tab", ChatText.Channel.ALL));
        private final Setting.Bool tabBar = add(new Setting.Bool("tabBar", "Show tabs while typing", true));
        private final Setting.Int history = add(new Setting.Int("history", "Chat history (lines)", 500, 100, 5000));
        private final Setting.Bool hideChat = add(new Setting.Bool("hideChat", "Hide chat (still shows while typing)", false));
        private final Setting.Entries hide = add(new Setting.Entries("hide", "Hide messages matching (regex)",
                new String[] { "pattern" }, new String[] { "Pattern" }, 50));
        private final Setting.Entries highlight = add(new Setting.Entries("highlight", "Highlight messages matching (regex)",
                new String[] { "pattern", "color" }, new String[] { "Pattern", "Color" }, 50));
        private final Setting.Entries ignored = add(new Setting.Entries("ignored", "Ignored players",
                new String[] { "name" }, new String[] { "Name" }, 200));
        private final Hotkey tabKey = hotkey("tabKey", "Next chat tab key", Keys.NONE, Hotkey.Mode.TOGGLE);
        private final Hotkey historyKey = hotkey("historyKey", "Search chat history key", Keys.NONE, Hotkey.Mode.TOGGLE);

        private final GlobalTable ignoredStore = new GlobalTable(ignored, "ignored");
        private final List<Line> log = new ArrayList<Line>();
        private String self = "";
        private long lastNow;

        static final int LOG_MAX = 2000;

        public Chat() {
            super("chat", "Chat", "Timestamps, mentions, filters, ignore list, stacking, tabs, history and search", Category.UTILITY, true);
            hud = new HudElement(Anchor.BOTTOM_RIGHT, -4, -16);
        }

        @Override protected void onEnable() {
            events.on(Events.ChatReceive.class, this, 0, this::receive);
        }

        void receive(Events.ChatReceive e) {
            String text = e.text;
            String who = ChatText.sender(text);
            if (who != null && isIgnored(who)) e.cancel = true;
            if (!e.cancel && ChatText.matchesAny(text, ChatText.column(hide, 0))) e.cancel = true;
            remember(new Line(e.timeMs, text, e.cancel));
            if (e.cancel) return;
            if (timestamps.on()) e.prefix = ChatText.timestamp(e.timeMs, h24.on(), seconds.on(), TimeZone.getDefault());
            if (stack.on()) e.stack = true;
            for (int i = 0; i < highlight.size(); i++) {
                java.util.regex.Pattern p = ChatText.regex(highlight.cell(i, 0));
                if (p != null && p.matcher(ChatText.strip(text)).find()) {
                    e.highlight = WorldMods.parseColor(highlight.cell(i, 1), 0xFF55FFFF);
                    break;
                }
            }
            if (mentions.on()) {
                List<String> words = ChatText.words(mentionWords.str());
                if (!self.isEmpty()) words.add(self);
                if (ChatText.mentions(text, words, self)) {
                    e.highlight = mentionColor.argb();
                    play(host(), mentionSound.val());
                }
            }
        }

        private void remember(Line l) {
            synchronized (log) {
                log.add(l);
                while (log.size() > LOG_MAX) log.remove(0);
            }
        }

        boolean isIgnored(String name) {
            for (int i = 0; i < ignored.size(); i++) if (ignored.cell(i, 0).trim().equalsIgnoreCase(name)) return true;
            return false;
        }

        List<Line> log() {
            synchronized (log) { return new ArrayList<Line>(log); }
        }

        @Override protected void onHotkey(Hotkey h) {
            if (!pressed(h)) return;
            if (h == tabKey) tab.next();
            else if (h == historyKey) openView(new HistoryView(this));
        }

        @Override public void onTick(GameState s) {
            ignoredStore.sync(data);
            self = s.playerName() == null ? "" : s.playerName();
            lastNow = s.nowMillis();
            fx.chatHistory = history.val();
            fx.hideChat = hideChat.on();
            final ChatText.Channel t = tab.val();
            if (t != ChatText.Channel.ALL) {
                fx.chatFilter = line -> ChatText.inTab(line, t);
                fx.chatFilterName = t.name();
            }
        }

        @Override public void renderHud(UiRenderer r, GameState s) {
            if (!tabBar.on() || !(s.chatOpen() || previewing())) return;
            ChatText.Channel[] all = ChatText.Channel.values();
            float x = 0, h = r.fontHeight() + 4;
            float total = 0;
            for (ChatText.Channel c : all) total += r.textWidth(label(c)) + 8;
            float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), total, h);
            r.push(p[0], p[1], hud.scale());
            for (ChatText.Channel c : all) {
                String l = label(c);
                float w = r.textWidth(l) + 8;
                boolean on = c == tab.val();
                r.rect(x, 0, w - 1, h, on ? 0xC04A6CFF : 0x90000000, 2f);
                r.text(l, x + 4, 2, on ? 0xFFFFFFFF : 0xFFAAAAAA, false);
                x += w;
            }
            r.pop();
        }

        static String label(ChatText.Channel c) {
            String n = c.name().toLowerCase(Locale.ROOT);
            return Character.toUpperCase(n.charAt(0)) + n.substring(1);
        }

        long now() { return lastNow == 0 ? System.currentTimeMillis() : lastNow; }
        void copy(String text) { host().copyText(text); }
    }

    /** Full-screen chat history with search; click a line to copy it. */
    static final class HistoryView implements View {
        private final Chat chat;
        private Runnable closer = () -> { };
        private String query = "";
        private int scroll; // lines scrolled up from the newest
        private float mx, my;
        private String flash = "";
        private long flashUntil;
        private List<Line> shown = Collections.emptyList();
        private float listX, listY, rowH, listW;

        HistoryView(Chat chat) { this.chat = chat; }

        /** Lines matching the query (plain substring, case-insensitive), oldest first. */
        static List<Line> filter(List<Line> all, String q) {
            String s = q.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) return all;
            List<Line> out = new ArrayList<Line>();
            for (Line l : all) if (ChatText.strip(l.text).toLowerCase(Locale.ROOT).contains(s)) out.add(l);
            return out;
        }

        @Override public void render(UiRenderer r, GameState s) {
            int W = r.screenWidth(), H = r.screenHeight();
            r.rect(0, 0, W, H, 0xC0101218, 0f);
            float pad = 12;
            r.rect(pad, pad, W - pad * 2, 16, 0xFF1C2130, 3f);
            r.text(query.isEmpty() ? "Search chat history..." : query + "_", pad + 5, pad + 4, query.isEmpty() ? 0xFFA4A4B2 : 0xFFFFFFFF, false);
            List<Line> all = filter(chat.log(), query);
            rowH = r.fontHeight() + 3;
            listX = pad;
            listY = pad + 22;
            listW = W - pad * 2;
            int rows = Math.max(1, (int) ((H - listY - pad - 14) / rowH));
            scroll = Math.max(0, Math.min(scroll, Math.max(0, all.size() - rows)));
            int end = all.size() - scroll, start = Math.max(0, end - rows);
            shown = all.subList(start, end);
            float y = listY;
            for (Line l : shown) {
                boolean hover = mx >= listX && mx < listX + listW && my >= y && my < y + rowH;
                if (hover) r.rect(listX, y, listW, rowH, 0x40FFFFFF, 0f);
                String ts = ChatText.timestamp(l.timeMs, true, true, TimeZone.getDefault());
                r.text(ts, listX + 3, y + 1, 0xFFA4A4B2, false);
                r.text(ChatText.strip(l.text), listX + 8 + r.textWidth(ts), y + 1, l.hidden ? 0xFF666C78 : 0xFFF5F5F7, false);
                y += rowH;
            }
            String help = all.size() + " lines" + (query.isEmpty() ? "" : " match") + " · click: copy · scroll: older · Esc: close";
            if (!flash.isEmpty() && System.currentTimeMillis() < flashUntil) help = flash;
            r.text(help, pad, H - pad - r.fontHeight(), 0xFFA4A4B2, false);
        }

        @Override public void mouseMoved(float x, float y) { mx = x; my = y; }

        @Override public boolean mouseDown(float x, float y, int button) {
            if (x < listX || x >= listX + listW || y < listY) return false;
            int i = (int) ((y - listY) / rowH);
            if (i < 0 || i >= shown.size()) return false;
            chat.copy(ChatText.strip(shown.get(i).text));
            flash = "Copied to clipboard";
            flashUntil = System.currentTimeMillis() + 1500;
            return true;
        }

        @Override public boolean mouseDrag(float x, float y) { return false; }
        @Override public boolean mouseUp(float x, float y, int button) { return false; }

        @Override public boolean scroll(float x, float y, float delta) {
            scroll = Math.max(0, scroll + (delta > 0 ? 3 : -3));
            return true;
        }

        @Override public boolean keyDown(int code) {
            if (code == Keys.ESCAPE) {
                closer.run();
                return true;
            }
            if (code == Keys.BACKSPACE) {
                if (!query.isEmpty()) query = query.substring(0, query.length() - 1);
                scroll = 0;
                return true;
            }
            if (code == Keys.UP) { scroll++; return true; }
            if (code == Keys.DOWN) { scroll = Math.max(0, scroll - 1); return true; }
            return false;
        }

        @Override public boolean charTyped(char c) {
            if (c < 32 || query.length() > 60) return false;
            query += c;
            scroll = 0;
            return true;
        }

        @Override public void closed() { }
        @Override public void setCloser(Runnable close) { closer = close; }
        @Override public boolean transparent() { return true; }
    }

    // ---- Chat notifications -----------------------------------------------------------------------------------------

    /** Sound / toast / desktop notification for mentions, keywords, watched players and server notices. */
    public static final class ChatNotifications extends Mod {
        private final Setting.Choice<Sound> sound = add(new Setting.Choice<Sound>("sound", "Sound", Sound.PLING));
        private final Setting.Bool toast = add(new Setting.Bool("toast", "In-game notification", true));
        private final Setting.Bool desktop = add(new Setting.Bool("desktop", "Desktop notification", false));
        private final Setting.Bool unfocusedOnly = add(new Setting.Bool("unfocusedOnly", "Desktop only when the game is in the background", true), desktop);
        private final Setting.Bool mention = add(new Setting.Bool("mention", "Mentions of my name", true));
        private final Setting.Bool pm = add(new Setting.Bool("pm", "Private messages", true));
        private final Setting.Bool party = add(new Setting.Bool("party", "Party invites", true));
        private final Setting.Bool friendJoin = add(new Setting.Bool("friendJoin", "Friend joined (server message)", true));
        private final Setting.Bool friendLeave = add(new Setting.Bool("friendLeave", "Friend left (server message)", false));
        private final Setting.Bool restart = add(new Setting.Bool("restart", "Server restart warnings", true));
        private final Setting.Bool auction = add(new Setting.Bool("auction", "Auction updates", true));
        private final Setting.Bool queue = add(new Setting.Bool("queue", "Queue updates", true));
        private final Setting.Int cooldown = add(new Setting.Int("cooldown", "Ignore repeats within (s)", 3, 0, 60));
        private final Setting.Entries keywords = add(new Setting.Entries("keywords", "Keywords", new String[] { "word" }, new String[] { "Keyword" }, 50));
        private final Setting.Entries players = add(new Setting.Entries("players", "Messages from these players", new String[] { "name" }, new String[] { "Name" }, 50));

        private final Map<String, Long> lastFired = new HashMap<String, Long>();
        private String self = "";
        private boolean focused = true;

        public ChatNotifications() { super("chatnotify", "Chat Notifications", "Alerts for mentions, keywords, PMs, party invites, friends and server notices", Category.UTILITY, true); }

        @Override protected void onEnable() {
            lastFired.clear();
            events.on(Events.ChatReceive.class, this, 10, e -> {
                if (e.cancel) return;
                String why = reason(e.text);
                if (why != null) fire(why, e.text, e.timeMs);
            });
        }

        /** Why this line deserves a notification ("Mention", "Private message"...), or null. */
        String reason(String text) {
            String[] who = new String[1];
            ChatText.Notice n = ChatText.notice(text, who);
            if (n != null) {
                switch (n) {
                    case PRIVATE_MESSAGE: if (pm.on()) return "Message from " + who[0]; break;
                    case PARTY_INVITE: if (party.on()) return "Party invite"; break;
                    case FRIEND_JOIN: if (friendJoin.on()) return who[0] + " joined"; break;
                    case FRIEND_LEAVE: if (friendLeave.on()) return who[0] + " left"; break;
                    case SERVER_RESTART: if (restart.on()) return "Server restart"; break;
                    case AUCTION: if (auction.on()) return "Auction"; break;
                    case QUEUE: if (queue.on()) return "Queue"; break;
                    default: break;
                }
            }
            String sender = ChatText.sender(text);
            if (sender != null && sender.equalsIgnoreCase(self)) return null;
            if (mention.on() && !self.isEmpty() && ChatText.mentions(text, Collections.singletonList(self), self)) return "Mentioned by " + (sender == null ? "server" : sender);
            List<String> kw = ChatText.column(keywords, 0);
            if (!kw.isEmpty() && ChatText.mentions(text, kw, self)) return "Keyword";
            if (sender != null) for (String p : ChatText.column(players, 0)) if (p.equalsIgnoreCase(sender)) return sender;
            return null;
        }

        private void fire(String why, String text, long now) {
            Long last = lastFired.get(why);
            if (last != null && now - last < cooldown.val() * 1000L) return;
            lastFired.put(why, now);
            String plain = ChatText.strip(text);
            if (plain.length() > 90) plain = plain.substring(0, 87) + "...";
            play(host(), sound.val());
            if (toast.on()) toasts.info(now, why + ": " + plain);
            if (desktop.on() && (!unfocusedOnly.on() || !focused)) host().desktopNotify("Minecraft - " + why, plain);
        }

        void setSelf(String name) { self = name == null ? "" : name; }

        @Override public void onTick(GameState s) {
            setSelf(s.playerName());
            focused = s.windowFocused();
        }
    }

    // ---- Tab list ---------------------------------------------------------------------------------------------------

    /** Replaces the vanilla player list: heads, ping numbers/bars, sorting, highlights, header/footer toggles. */
    public static final class TabList extends Mod {
        public enum PingStyle { NUMBER, BARS, HIDDEN }
        public enum Sort { VANILLA, NAME, PING, TEAM }

        private final Setting.Color bg = add(new Setting.Color("bg", "Background", 0x80000000));
        private final Setting.Color rowBg = add(new Setting.Color("rowBg", "Row background", 0x20FFFFFF));
        private final Setting.Bool heads = add(new Setting.Bool("heads", "Player heads", true));
        private final Setting.Choice<PingStyle> ping = add(new Setting.Choice<PingStyle>("ping", "Ping", PingStyle.NUMBER));
        private final Setting.Bool pingColors = add(new Setting.Bool("pingColors", "Color ping by latency", true)).visibleWhen(() -> ping.val() != PingStyle.HIDDEN);
        private final Setting.Choice<Sort> sort = add(new Setting.Choice<Sort>("sort", "Sort", Sort.VANILLA));
        private final Setting.Bool highlightSelf = add(new Setting.Bool("highlightSelf", "Highlight yourself", true));
        private final Setting.Color selfColor = add(new Setting.Color("selfColor", "Your color", 0xFF55FFFF), highlightSelf);
        private final Setting.Bool highlightFriends = add(new Setting.Bool("highlightFriends", "Highlight friends", true));
        private final Setting.Color friendColor = add(new Setting.Color("friendColor", "Friend color", 0xFF55FF77), highlightFriends);
        private final Setting.Bool header = add(new Setting.Bool("header", "Header", true));
        private final Setting.Bool footer = add(new Setting.Bool("footer", "Footer", true));
        private final Setting.Bool server = add(new Setting.Bool("server", "Server address and player count", false));
        private final Setting.Bool gameMode = add(new Setting.Bool("gameMode", "Fade spectators", true));
        private final Setting.Int rows = add(new Setting.Int("rows", "Rows per column", 20, 5, 40));

        public TabList() {
            super("tablist", "Tab List", "Player list with heads, ping numbers, sorting and friend highlights", Category.HUD, true);
            hud = new HudElement(Anchor.TOP_CENTER, 0, 10);
        }

        @Override public void onTick(GameState s) { fx.hideVanillaTabList = true; }

        /** Players in display order. */
        static List<GameState.TabPlayer> sorted(List<GameState.TabPlayer> in, Sort sort) {
            List<GameState.TabPlayer> l = new ArrayList<GameState.TabPlayer>(in);
            Comparator<GameState.TabPlayer> byName = (a, b) -> a.name.compareToIgnoreCase(b.name);
            switch (sort) {
                case NAME: Collections.sort(l, byName); break;
                case PING: Collections.sort(l, (a, b) -> a.ping != b.ping ? Integer.compare(pingKey(a.ping), pingKey(b.ping)) : byName.compare(a, b)); break;
                case TEAM: Collections.sort(l, (a, b) -> a.team.equals(b.team) ? byName.compare(a, b) : a.team.compareTo(b.team)); break;
                default: break;
            }
            return l;
        }

        /** Unknown ping (<= 0) sorts last. */
        private static int pingKey(int ping) { return ping <= 0 ? Integer.MAX_VALUE : ping; }

        /** Number of bars (0-5) vanilla would show for a latency. */
        static int bars(int ping) {
            if (ping < 0) return 0;
            if (ping < 150) return 5;
            if (ping < 300) return 4;
            if (ping < 600) return 3;
            if (ping < 1000) return 2;
            return 1;
        }

        private int pingColor(int ms) {
            if (!pingColors.on()) return 0xFFAAAAAA;
            if (ms <= 0) return 0xFF888888;
            return HudTextMod.gradient(1f - Math.min(1f, ms / 300f), fx.colorblind);
        }

        private static final List<GameState.TabPlayer> SAMPLE = Arrays.asList(
                new GameState.TabPlayer("Steve", "[MVP+] Steve", 32, "survival", "a"),
                new GameState.TabPlayer("Alex", "Alex", 110, "survival", "b"),
                new GameState.TabPlayer("Notch", "Notch", 240, "spectator", "b"));

        @Override public void renderHud(UiRenderer r, GameState s) {
            boolean show = s.isDown(GameState.Key.PLAYER_LIST) && !s.screenOpen();
            if (!show && !previewing()) return;
            List<GameState.TabPlayer> players = s.tabPlayers();
            if (players.isEmpty()) {
                if (!previewing()) return;
                players = SAMPLE;
            }
            players = sorted(players, sort.val());
            String me = s.playerName();
            int fh = r.fontHeight(), rowH = fh + 1, head = heads.on() ? fh : 0;
            int n = players.size();
            int cols = Math.max(1, (n + rows.val() - 1) / rows.val());
            int perCol = (n + cols - 1) / cols;
            String[] names = new String[n];
            float nameW = 0;
            for (int i = 0; i < n; i++) {
                names[i] = fx.replaceNames(ChatText.strip(players.get(i).displayName));
                nameW = Math.max(nameW, r.textWidth(names[i]));
            }
            float pingW = ping.val() == PingStyle.NUMBER ? r.textWidth("9999ms") : ping.val() == PingStyle.BARS ? 11 : 0;
            float cellW = (head > 0 ? head + 2 : 0) + nameW + (pingW > 0 ? pingW + 6 : 0) + 4;
            List<String> top = new ArrayList<String>(), bottom = new ArrayList<String>();
            if (header.on()) top.addAll(split(s.tabHeader()));
            if (server.on() && !fx.streamerMode) top.add(fx.replaceNames(s.serverAddress()) + " - " + n + " players");
            if (footer.on()) bottom.addAll(split(s.tabFooter()));
            float textW = 0;
            for (String l : top) textW = Math.max(textW, r.textWidth(l));
            for (String l : bottom) textW = Math.max(textW, r.textWidth(l));
            float gridW = cols * cellW + (cols - 1) * 2;
            float w = Math.max(gridW, textW) + 8;
            float h = 4 + top.size() * rowH + (top.isEmpty() ? 0 : 2) + perCol * rowH + (bottom.isEmpty() ? 0 : 2) + bottom.size() * rowH + 3;
            float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), w, h);
            r.push(p[0], p[1], hud.scale());
            r.rect(0, 0, w, h, bg.argb(), 2f);
            float y = 4;
            for (String l : top) {
                r.text(l, (w - r.textWidth(l)) / 2, y, 0xFFFFFFFF, true);
                y += rowH;
            }
            if (!top.isEmpty()) y += 2;
            float gx = (w - gridW) / 2;
            for (int i = 0; i < n; i++) {
                GameState.TabPlayer tp = players.get(i);
                int c = i / perCol, row = i % perCol;
                float x = gx + c * (cellW + 2), ry = y + row * rowH;
                r.rect(x, ry, cellW, fh, rowBg.argb(), 0f);
                float tx = x + 1;
                if (head > 0) {
                    if (!r.head(tp.name, tx, ry, head)) r.rect(tx, ry, head, head, 0xFF555555, 0f);
                    tx += head + 2;
                }
                int color = 0xFFFFFFFF;
                if (highlightFriends.on() && friends != null && friends.isFriend(tp.name)) color = friendColor.argb();
                if (highlightSelf.on() && tp.name.equalsIgnoreCase(me)) color = selfColor.argb();
                if (gameMode.on() && "spectator".equals(tp.gameMode)) color = (color & 0xFFFFFF) | 0x90000000;
                r.text(names[i], tx, ry, color, true);
                if (ping.val() == PingStyle.NUMBER) {
                    String t = tp.ping <= 0 ? "?" : tp.ping + "ms";
                    r.text(t, x + cellW - 2 - r.textWidth(t), ry, pingColor(tp.ping), true);
                } else if (ping.val() == PingStyle.BARS) {
                    int b = bars(tp.ping);
                    for (int k = 0; k < 5; k++) {
                        float bh = 2 + k * 1.5f;
                        r.rect(x + cellW - 12 + k * 2, ry + fh - 1 - bh, 1, bh, k < b ? pingColor(tp.ping) : 0xFF404040, 0f);
                    }
                }
            }
            y += perCol * rowH;
            if (!bottom.isEmpty()) y += 2;
            for (String l : bottom) {
                r.text(l, (w - r.textWidth(l)) / 2, y, 0xFFFFFFFF, true);
                y += rowH;
            }
            r.pop();
        }

        static List<String> split(String text) {
            List<String> out = new ArrayList<String>();
            if (text == null || text.trim().isEmpty()) return out;
            for (String l : text.split("\n")) out.add(ChatText.strip(l));
            return out;
        }
    }

    // ---- Friends ----------------------------------------------------------------------------------------------------

    /**
     * Client-side friends: nickname, notes, favorites, who is online on this server (from the tab list), last seen,
     * join/leave toasts. Friends are shared by every profile.
     */
    public static final class Friends extends HudTextMod {
        static final int NAME = 0, NICK = 1, NOTE = 2, FAV = 3;

        private final Setting.Bool notify = add(new Setting.Bool("notify", "Notify when friends join / leave", true));
        private final Setting.Bool nicknames = add(new Setting.Bool("nicknames", "Show nicknames in chat, tab list and nametags", true));
        private final Setting.Bool offline = add(new Setting.Bool("offline", "List offline friends with last seen", false));
        private final Setting.Int maxLines = add(new Setting.Int("maxLines", "Max friends in the HUD", 8, 1, 30));
        private final Setting.Entries list = add(new Setting.Entries("list", "Friends",
                new String[] { "name", "nick", "note", "fav" }, new String[] { "Name", "Nickname", "Notes", "Favorite (yes/no)" }, 300));
        private final Hotkey addKey = hotkey("addKey", "Add the player you look at", Keys.NONE, Hotkey.Mode.TOGGLE);

        private final GlobalTable store = new GlobalTable(list, "friends");
        private final Set<String> online = new LinkedHashSet<String>();
        private String server;
        private GameState last;

        public Friends() {
            super("friends", "Friends", "Friend list with nicknames, notes, online status and join alerts", Category.UTILITY, Anchor.TOP_LEFT, 4, 200);
            sensitive = true; // names of friends are hidden by streamer mode
        }

        boolean isFriend(String name) { return index(name) >= 0; }

        int index(String name) {
            if (name == null) return -1;
            for (int i = 0; i < list.size(); i++) if (list.cell(i, NAME).trim().equalsIgnoreCase(name)) return i;
            return -1;
        }

        static boolean yes(String s) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            return t.equals("yes") || t.equals("y") || t.equals("true") || t.equals("1") || t.equals("*") || t.equals("★");
        }

        /** Adds a friend if not already listed; returns false if present or the table is full. */
        boolean addFriend(String name) {
            if (name == null || name.trim().isEmpty() || isFriend(name)) return false;
            return list.add(name.trim(), "", "", "no") != null;
        }

        @Override protected void onHotkey(Hotkey h) {
            if (h != addKey || !pressed(h)) return;
            GameState s = last;
            if (s == null) return;
            GameState.Target t = s.target();
            if (t.kind != GameState.Target.Kind.ENTITY || !"minecraft:player".equals(t.id)) {
                toasts.info(s.nowMillis(), "Look at a player to add them as a friend");
                return;
            }
            toasts.info(s.nowMillis(), addFriend(t.name) ? "Added " + t.name + " to friends" : t.name + " is already a friend");
        }

        @Override public void onTick(GameState s) {
            last = s;
            store.sync(data);
            if (nicknames.on()) {
                for (int i = 0; i < list.size(); i++) {
                    String nick = list.cell(i, NICK).trim(), name = list.cell(i, NAME).trim();
                    if (!nick.isEmpty() && !name.isEmpty() && !fx.nameReplacements.containsKey(name)) fx.nameReplacements.put(name, nick);
                }
            }
            if (!s.inWorld()) {
                online.clear();
                server = null;
                return;
            }
            Set<String> now = new LinkedHashSet<String>();
            for (GameState.TabPlayer p : s.tabPlayers()) if (isFriend(p.name)) now.add(list.cell(index(p.name), NAME).trim());
            boolean sameServer = s.serverAddress().equals(server);
            if (sameServer && notify.on()) {
                for (String n : now) if (!online.contains(n)) toasts.info(s.nowMillis(), display(n) + " joined");
                for (String n : online) if (!now.contains(n)) toasts.info(s.nowMillis(), display(n) + " left");
            }
            if (!sameServer || !now.equals(online)) {
                Map<String, Object> seen = data.getMap("seen");
                for (String n : now) {
                    Map<String, Object> e = new LinkedHashMap<String, Object>();
                    e.put("t", s.nowMillis());
                    e.put("server", s.serverAddress());
                    seen.put(n.toLowerCase(Locale.ROOT), e);
                }
                data.put("seen", seen);
            }
            server = s.serverAddress();
            online.clear();
            online.addAll(now);
        }

        String display(String name) {
            int i = index(name);
            String nick = i < 0 ? "" : list.cell(i, NICK).trim();
            return nick.isEmpty() ? name : nick + " (" + name + ")";
        }

        /** "3m ago", "2h ago", "5d ago". */
        static String ago(long ms) {
            long s = Math.max(0, ms / 1000);
            if (s < 60) return "just now";
            if (s < 3600) return (s / 60) + "m ago";
            if (s < 86400) return (s / 3600) + "h ago";
            return (s / 86400) + "d ago";
        }

        @Override protected List<String> lines(GameState s) {
            List<String> out = new ArrayList<String>();
            List<String> on = new ArrayList<String>(online);
            Collections.sort(on, (a, b) -> Boolean.compare(!fav(a), !fav(b)));
            for (String n : on) {
                if (out.size() >= maxLines.val()) break;
                out.add((fav(n) ? "★ " : "● ") + display(n));
            }
            if (offline.on()) {
                Map<String, Object> seen = data.getMap("seen");
                for (int i = 0; i < list.size() && out.size() < maxLines.val(); i++) {
                    String n = list.cell(i, NAME).trim();
                    if (n.isEmpty() || online.contains(n)) continue;
                    Object e = seen.get(n.toLowerCase(Locale.ROOT));
                    String when = "never seen";
                    if (e instanceof Map && ((Map<?, ?>) e).get("t") instanceof Number) {
                        when = ago(s.nowMillis() - ((Number) ((Map<?, ?>) e).get("t")).longValue());
                        Object srv = ((Map<?, ?>) e).get("server");
                        if (srv != null && !String.valueOf(srv).isEmpty()) when += " on " + srv;
                    }
                    out.add("○ " + display(n) + " - " + when);
                }
            }
            if (out.isEmpty()) return out;
            out.add(0, "Friends online: " + online.size());
            return out;
        }

        private boolean fav(String name) {
            int i = index(name);
            return i >= 0 && yes(list.cell(i, FAV));
        }

        Setting.Entries entries() { return list; }
        Set<String> online() { return online; }
    }

    // ---- Recording mode ---------------------------------------------------------------------------------------------

}
