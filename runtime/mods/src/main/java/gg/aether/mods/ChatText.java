package gg.aether.mods;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Plain-text chat parsing shared by the chat mods: who sent a line, which channel it belongs to, mentions, and the
 * server notices (private messages, party invites, restarts...) worth a notification. Formatting codes are stripped
 * first, so both vanilla and server-formatted lines work.
 */
public final class ChatText {
    private ChatText() {}

    public enum Channel { ALL, PARTY, GUILD, WHISPERS, SYSTEM }

    /** Server notices the chat notifier recognises. */
    public enum Notice { PRIVATE_MESSAGE, PARTY_INVITE, FRIEND_JOIN, FRIEND_LEAVE, SERVER_RESTART, AUCTION, QUEUE }

    private static final String NAME = "([A-Za-z0-9_]{1,16})";
    private static final String RANKS = "(?:\\[[^\\]]{1,24}\\] ?)*";
    private static final Pattern CODES = Pattern.compile("§.");
    private static final Pattern VANILLA = Pattern.compile("^<" + NAME + "> ");
    private static final Pattern CHANNEL_PREFIX = Pattern.compile("^(?:(?:Party|Guild|Officer|Co-op|Friend|Team|Staff|Global|Local) ?> ?|\\[(?:Party|Guild|Team|Global|Local|G|L|P)\\] ?|(?:From|To) )");
    private static final Pattern RANKED = Pattern.compile("^" + RANKS + NAME + "(?: ?\\[[^\\]]{1,24}\\])? ?(?::|»|>|->) ");
    private static final Pattern WHISPER = Pattern.compile("^(?:From|To) " + RANKS + NAME + ": |^" + NAME + " whispers to you: |^You whisper to " + NAME + ": |^\\[" + NAME + " -> me\\]|^\\[me -> " + NAME + "\\]");
    private static final Pattern INCOMING_WHISPER = Pattern.compile("^From " + RANKS + NAME + ": |^" + NAME + " whispers to you: |^\\[" + NAME + " -> me\\]");
    private static final Pattern PARTY = Pattern.compile("^(?:Party ?> ?|\\[Party\\]|\\[P\\] |P > )");
    private static final Pattern GUILD = Pattern.compile("^(?:Guild ?> ?|Officer ?> ?|\\[Guild\\]|\\[G\\] |G > )");
    private static final Pattern PARTY_INVITE = Pattern.compile("(?i)(?:has )?invited you to (?:join )?(?:their|the|a|[A-Za-z0-9_]{1,16}'s) party|party invite from");
    private static final Pattern FRIEND = Pattern.compile("^Friend ?> ?" + NAME + " (joined|left)\\.?$");
    private static final Pattern RESTART = Pattern.compile("(?i)\\b(?:server|proxy|instance|lobby) (?:is )?(?:restarting|rebooting|will restart|restart(?:s)? in)|\\brestarting in \\d|\\breboot(?:ing)? in \\d");
    private static final Pattern AUCTION = Pattern.compile("(?i)\\[auction\\]|\\byou (?:were|have been) outbid\\b|\\bauction\\b.*\\b(?:bought|sold|won|ended|expired|outbid)\\b|\\b(?:bought|sold)\\b.*\\bauction\\b");
    private static final Pattern QUEUE = Pattern.compile("(?i)position in (?:the )?queue|\\bqueue position\\b|you are (?:now )?(?:in|#\\d+ in) (?:the )?queue|\\bsending you to\\b|\\bjoined the queue\\b");

    /** "§aHello §lworld" -> "Hello world". */
    public static String strip(String s) { return s == null ? "" : CODES.matcher(s).replaceAll(""); }

    /** Name of the player who wrote a chat line, or null for server / system lines. */
    public static String sender(String line) {
        String s = strip(line).trim();
        Matcher m = VANILLA.matcher(s);
        if (m.find()) return m.group(1);
        m = WHISPER.matcher(s);
        if (m.find()) return firstGroup(m);
        Matcher c = CHANNEL_PREFIX.matcher(s);
        if (c.find()) s = s.substring(c.end());
        m = RANKED.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    public static Channel channel(String line) {
        String s = strip(line).trim();
        if (PARTY.matcher(s).find()) return Channel.PARTY;
        if (GUILD.matcher(s).find()) return Channel.GUILD;
        if (WHISPER.matcher(s).find()) return Channel.WHISPERS;
        return sender(s) == null ? Channel.SYSTEM : Channel.ALL;
    }

    /** True if the line belongs in {@code tab} (ALL shows everything). */
    public static boolean inTab(String line, Channel tab) { return tab == Channel.ALL || channel(line) == tab; }

    /** Text after the sender prefix ("<Steve> hi there" -> "hi there"); the whole line when there is no sender. */
    public static String body(String line) {
        String s = strip(line).trim();
        String who = sender(s);
        if (who == null) return s;
        int i = s.indexOf(who);
        if (i < 0) return s;
        int colon = s.indexOf(": ", i + who.length());
        int angle = s.indexOf("> ", i + who.length());
        int cut = colon < 0 ? angle : angle < 0 ? colon : Math.min(colon, angle);
        return cut < 0 ? s : s.substring(cut + 2);
    }

    /** Whole-word, case-insensitive: does the message body mention any of {@code words}? Own messages never count. */
    public static boolean mentions(String line, List<String> words, String self) {
        String who = sender(line);
        if (who != null && self != null && who.equalsIgnoreCase(self)) return false;
        String body = body(line).toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (w == null) continue;
            String t = w.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            int from = 0;
            while (true) {
                int i = body.indexOf(t, from);
                if (i < 0) break;
                boolean startOk = i == 0 || !wordChar(body.charAt(i - 1));
                int end = i + t.length();
                boolean endOk = end >= body.length() || !wordChar(body.charAt(end));
                if (startOk && endOk) return true;
                from = i + 1;
            }
        }
        return false;
    }

    private static boolean wordChar(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    /** The notice a server line represents, or null. {@code FRIEND_*} lines put the friend's name in {@code who[0]}. */
    public static Notice notice(String line, String[] who) {
        String s = strip(line).trim();
        Matcher m = FRIEND.matcher(s);
        if (m.find()) {
            if (who != null && who.length > 0) who[0] = m.group(1);
            return m.group(2).equals("joined") ? Notice.FRIEND_JOIN : Notice.FRIEND_LEAVE;
        }
        m = INCOMING_WHISPER.matcher(s);
        if (m.find()) {
            if (who != null && who.length > 0) who[0] = firstGroup(m);
            return Notice.PRIVATE_MESSAGE;
        }
        // Players can type anything; only system lines count for the rest.
        if (sender(s) != null) return null;
        if (PARTY_INVITE.matcher(s).find()) return Notice.PARTY_INVITE;
        if (RESTART.matcher(s).find()) return Notice.SERVER_RESTART;
        if (AUCTION.matcher(s).find()) return Notice.AUCTION;
        if (QUEUE.matcher(s).find()) return Notice.QUEUE;
        return null;
    }

    private static String firstGroup(Matcher m) {
        for (int g = 1; g <= m.groupCount(); g++) if (m.group(g) != null) return m.group(g);
        return null;
    }

    /** "[14:05]" / "[2:05:09 PM]". */
    public static String timestamp(long ms, boolean h24, boolean seconds, TimeZone tz) {
        String pattern = (h24 ? "HH:mm" : "h:mm") + (seconds ? ":ss" : "") + (h24 ? "" : " a");
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.ENGLISH);
        f.setTimeZone(tz == null ? TimeZone.getDefault() : tz);
        return "[" + f.format(new Date(ms)) + "]";
    }

    private static final Map<String, Pattern> CACHE = new HashMap<String, Pattern>();

    /** Case-insensitive compiled pattern, null for blank or invalid input (a typo must not break chat). */
    public static synchronized Pattern regex(String src) {
        if (src == null || src.trim().isEmpty()) return null;
        if (CACHE.containsKey(src)) return CACHE.get(src);
        Pattern p;
        try {
            p = Pattern.compile(src, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            p = null;
        }
        if (CACHE.size() > 256) CACHE.clear();
        CACHE.put(src, p);
        return p;
    }

    public static boolean matchesAny(String line, List<String> patterns) {
        String s = strip(line);
        for (String src : patterns) {
            Pattern p = regex(src);
            if (p != null && p.matcher(s).find()) return true;
        }
        return false;
    }

    /** Non-blank cells of column {@code col}. */
    public static List<String> column(gg.aether.core.Setting.Entries e, int col) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < e.size(); i++) {
            String v = e.cell(i, col).trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** Comma/space separated words ("me, Nakii  admin") as a list. */
    public static List<String> words(String csv) {
        List<String> out = new ArrayList<String>();
        if (csv == null) return out;
        for (String w : csv.split("[,;\\s]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }
}
