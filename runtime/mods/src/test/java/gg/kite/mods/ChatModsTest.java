package gg.kite.mods;

import static org.junit.jupiter.api.Assertions.*;

import gg.kite.core.ClientHost;
import gg.kite.core.Events;
import gg.kite.core.GameState;
import gg.kite.core.ModManager;
import gg.kite.core.Overrides;
import gg.kite.core.Setting;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/** Chat parsing and the task-13 chat / social mods. */
class ChatModsTest {
    static ModManager mm(String... enable) {
        ModManager mm = new ModManager();
        BuiltinMods.registerAll(mm);
        for (String id : enable) mm.setEnabled(id, true);
        return mm;
    }

    /** World with a player name and a tab list. */
    static class Social extends HarnessModsTest.World {
        String me = "Nakii";
        List<TabPlayer> tab = new ArrayList<TabPlayer>();
        boolean focused = true;
        @Override public String playerName() { return me; }
        @Override public List<TabPlayer> tabPlayers() { return tab; }
        @Override public boolean windowFocused() { return focused; }
    }

    static Events.ChatReceive receive(ModManager mm, String text) {
        Events.ChatReceive e = new Events.ChatReceive(text, false, 1_000);
        mm.events().post(e);
        return e;
    }

    @Test
    void senderParsing() {
        assertEquals("Steve", ChatText.sender("<Steve> hello"));
        assertEquals("Steve", ChatText.sender("[MVP+] Steve: gg"));
        assertEquals("Steve", ChatText.sender("§b[MVP§c+§b] Steve§f: gg"));
        assertEquals("Alex", ChatText.sender("Party > [VIP] Alex: invite me"));
        assertEquals("Alex", ChatText.sender("Guild > Alex [OFFICER]: hi"));
        assertEquals("Alex", ChatText.sender("From [MVP++] Alex: psst"));
        assertEquals("Alex", ChatText.sender("Alex whispers to you: hey"));
        assertNull(ChatText.sender("Welcome to the server!"));
        assertNull(ChatText.sender("You have 3 new messages"));
    }

    @Test
    void channels() {
        assertEquals(ChatText.Channel.PARTY, ChatText.channel("Party > Alex: go"));
        assertEquals(ChatText.Channel.GUILD, ChatText.channel("Guild > Alex: hi"));
        assertEquals(ChatText.Channel.WHISPERS, ChatText.channel("From Alex: hi"));
        assertEquals(ChatText.Channel.WHISPERS, ChatText.channel("Alex whispers to you: hi"));
        assertEquals(ChatText.Channel.SYSTEM, ChatText.channel("Server restarting in 5 minutes"));
        assertEquals(ChatText.Channel.ALL, ChatText.channel("<Steve> hi"));
        assertTrue(ChatText.inTab("<Steve> hi", ChatText.Channel.ALL));
        assertFalse(ChatText.inTab("<Steve> hi", ChatText.Channel.PARTY));
    }

    @Test
    void mentionsAreWholeWordsAndNotOwnMessages() {
        List<String> w = Collections.singletonList("Nakii");
        assertTrue(ChatText.mentions("<Steve> hey nakii, come here", w, "Nakii"));
        assertFalse(ChatText.mentions("<Steve> Nakiiiii", w, "Nakii"));
        assertFalse(ChatText.mentions("<Nakii> I am Nakii", w, "Nakii"));
        assertTrue(ChatText.mentions("Nakii was slain by Zombie", w, "Nakii"));
        assertEquals("hey there", ChatText.body("[VIP] Steve: hey there"));
    }

    @Test
    void notices() {
        String[] who = new String[1];
        assertEquals(ChatText.Notice.PRIVATE_MESSAGE, ChatText.notice("From [MVP+] Alex: yo", who));
        assertEquals("Alex", who[0]);
        assertEquals(ChatText.Notice.FRIEND_JOIN, ChatText.notice("Friend > Steve joined.", who));
        assertEquals("Steve", who[0]);
        assertEquals(ChatText.Notice.FRIEND_LEAVE, ChatText.notice("Friend > Steve left.", who));
        assertEquals(ChatText.Notice.PARTY_INVITE, ChatText.notice("[MVP+] Alex has invited you to join their party!", who));
        assertEquals(ChatText.Notice.SERVER_RESTART, ChatText.notice("The server is restarting in 5 minutes", who));
        assertEquals(ChatText.Notice.AUCTION, ChatText.notice("[Auction] Steve bought your Hyperion", who));
        assertEquals(ChatText.Notice.QUEUE, ChatText.notice("Position in queue: 12", who));
        // Players typing notices do not count.
        assertNull(ChatText.notice("<Steve> the server is restarting in 5 minutes lol", who));
        assertNull(ChatText.notice("Just a normal line", who));
    }

    @Test
    void timestampsAndRegex() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        assertEquals("[14:05]", ChatText.timestamp(14 * 3600_000L + 5 * 60_000L, true, false, utc));
        assertEquals("[2:05:09 PM]", ChatText.timestamp(14 * 3600_000L + 5 * 60_000L + 9_000L, false, true, utc));
        assertNull(ChatText.regex("([unclosed"));
        assertTrue(ChatText.matchesAny("§cBuy cheap coins at SPAM.net", Arrays.asList("([bad", "spam\\.net")));
        assertEquals(Arrays.asList("me", "Nakii", "admin"), ChatText.words("me, Nakii  admin"));
    }

    @Test
    void nameReplacementIsWholeWord() {
        Overrides fx = new Overrides();
        fx.nameReplacements.put("Steve", "Hidden");
        assertEquals("<Hidden> hi Steven, Hidden", fx.replaceNames("<Steve> hi Steven, Steve"));
        assertEquals("nothing", fx.replaceNames("nothing"));
    }

    @Test
    void chatReceiveFiltersIgnoresStacksAndHighlights() {
        ModManager mm = mm("chat");
        Social w = new Social();
        ChatMods.Chat chat = (ChatMods.Chat) mm.get("chat");
        for (Setting<?> s : chat.settings()) {
            if (s.key.equals("hide")) ((Setting.Entries) s).add("coins\\.net");
            if (s.key.equals("ignored")) ((Setting.Entries) s).add("Griefer");
            if (s.key.equals("highlight")) ((Setting.Entries) s).add("diamond", "#FF00FF00");
        }
        mm.tick(w);

        Events.ChatReceive spam = receive(mm, "Buy at COINS.net now");
        assertTrue(spam.cancel);
        assertTrue(receive(mm, "<Griefer> lol").cancel);

        Events.ChatReceive normal = receive(mm, "<Steve> found a diamond");
        assertFalse(normal.cancel);
        assertNotNull(normal.prefix);
        assertTrue(normal.prefix.startsWith("["));
        assertTrue(normal.stack);
        assertEquals(0xFF00FF00, (int) normal.highlight);

        Events.ChatReceive mention = receive(mm, "<Steve> hi nakii");
        assertNotNull(mention.highlight);

        // History keeps hidden lines too (shown greyed), newest last.
        List<ChatMods.Line> log = chat.log();
        assertEquals(4, log.size());
        assertTrue(log.get(0).hidden);
        assertEquals(1, ChatMods.HistoryView.filter(log, "DIAMOND").size());
    }

    @Test
    void chatTabsSetAFilter() {
        ModManager mm = mm("chat");
        ChatMods.Chat chat = (ChatMods.Chat) mm.get("chat");
        for (Setting<?> s : chat.settings()) if (s.key.equals("tab")) ((Setting.Choice<?>) s).fromJson("PARTY");
        mm.tick(new Social());
        Overrides fx = mm.overrides();
        assertEquals("PARTY", fx.chatFilterName);
        assertTrue(fx.chatFilter.show("Party > Alex: hi"));
        assertFalse(fx.chatFilter.show("<Steve> hi"));
        assertEquals(500, fx.chatHistory);
    }

    @Test
    void chatNotificationsFireOncePerCooldown() {
        ModManager mm = mm("chatnotify");
        List<String> sounds = new ArrayList<String>(), desktop = new ArrayList<String>();
        mm.setHost(new ClientHost() {
            @Override public void playSound(String name) { sounds.add(name); }
            @Override public void desktopNotify(String title, String text) { desktop.add(title); }
        });
        Social w = new Social();
        w.focused = false;
        mm.tick(w);
        ChatMods.ChatNotifications n = (ChatMods.ChatNotifications) mm.get("chatnotify");
        assertEquals("Message from Alex", n.reason("From Alex: hey"));
        assertNull(n.reason("<Nakii> I said Nakii"));
        assertEquals("Mentioned by Steve", n.reason("<Steve> nakii look"));

        receive(mm, "<Steve> nakii look");
        receive(mm, "<Steve> nakii again");
        assertEquals(1, sounds.size());
        assertEquals(1, mm.notifications().active(1_000).size());
        assertTrue(desktop.isEmpty(), "desktop notifications are off by default");
    }

    @Test
    void tabListSortingAndBars() {
        List<GameState.TabPlayer> l = Arrays.asList(
                new GameState.TabPlayer("zed", "zed", 300, "survival", "b"),
                new GameState.TabPlayer("Amy", "Amy", 0, "survival", "a"),
                new GameState.TabPlayer("bob", "bob", 20, "survival", "a"));
        assertEquals("Amy", ChatMods.TabList.sorted(l, ChatMods.TabList.Sort.NAME).get(0).name);
        assertEquals("bob", ChatMods.TabList.sorted(l, ChatMods.TabList.Sort.PING).get(0).name);
        assertEquals("Amy", ChatMods.TabList.sorted(l, ChatMods.TabList.Sort.PING).get(2).name, "unknown ping sorts last");
        assertEquals("Amy", ChatMods.TabList.sorted(l, ChatMods.TabList.Sort.TEAM).get(0).name);
        assertEquals("zed", ChatMods.TabList.sorted(l, ChatMods.TabList.Sort.VANILLA).get(0).name);
        assertEquals(5, ChatMods.TabList.bars(20));
        assertEquals(1, ChatMods.TabList.bars(2000));
        assertEquals(0, ChatMods.TabList.bars(-1));
        ModManager mm = mm("tablist");
        mm.tick(new Social());
        assertTrue(mm.overrides().hideVanillaTabList);
    }

    @Test
    void friendsTrackOnlineNicknamesAndPersistAcrossProfiles() {
        ModManager mm = mm("friends");
        ChatMods.Friends f = (ChatMods.Friends) mm.get("friends");
        assertTrue(f.addFriend("Steve"));
        assertFalse(f.addFriend("steve"));
        f.entries().rows().get(0)[ChatMods.Friends.NICK].set("Stevie");
        Social w = new Social();
        mm.tick(w);
        assertEquals("Stevie", mm.overrides().nameReplacements.get("Steve"));

        // Joining: first sight on a server is the baseline (no toast), later joins/leaves notify.
        mm.tick(w);
        w.tab.add(new GameState.TabPlayer("Steve", "Steve", 20, "survival", ""));
        mm.tick(w);
        assertTrue(f.online().contains("Steve"));
        assertEquals(1, mm.notifications().active(w.now).size());
        List<String> l = f.lines(w);
        assertEquals("Friends online: 1", l.get(0));
        assertEquals("● Stevie (Steve)", l.get(1));

        // Friends live in mod data, not in the profile.
        assertFalse(((List<?>) f.data().getList("friends")).isEmpty());
        assertTrue(f.data().getMap("seen").containsKey("steve"));
        assertEquals("3m ago", ChatMods.Friends.ago(3 * 60_000));
    }

    @Test
    void streamerModeReplacesNames() {
        ModManager s = mm("streamer");
        s.tick(new Social());
        assertEquals("Streamer", s.overrides().nameReplacements.get("Nakii"));
        assertTrue(s.overrides().streamerMode);
    }
}
