package gg.kite.mods;

import gg.kite.core.Events;
import gg.kite.core.GameState;
import gg.kite.core.HudElement;
import gg.kite.core.HudElement.Anchor;
import gg.kite.core.Hotkey;
import gg.kite.core.Keys;
import gg.kite.core.Mod;
import gg.kite.core.Setting;
import gg.kite.core.UiRenderer;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Status read-outs: food, playtime, statistics, network, debug, hotbar, durability, height, biome, rendering, server. */
public final class StatusMods {
    private StatusMods() {}

    static String dur(long ms) {
        long m = ms / 60000;
        if (m < 60) return m + "m";
        return m / 60 + "h " + m % 60 + "m";
    }

    /** Process CPU load 0-1 through com.sun.management when available, else -1. */
    static double cpuLoad() {
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            Method m = os.getClass().getMethod("getProcessCpuLoad");
            m.setAccessible(true);
            Object v = m.invoke(os);
            return v instanceof Number ? ((Number) v).doubleValue() : -1;
        } catch (Exception | LinkageError e) {
            return -1;
        }
    }

    // ---- Food ---------------------------------------------------------------------------------------------------

    public static final class Food extends HudTextMod {
        private final Setting.Bool hunger = add(new Setting.Bool("hunger", "Hunger", true));
        private final Setting.Bool saturation = add(new Setting.Bool("saturation", "Saturation", true));
        private final Setting.Bool exhaustion = add(new Setting.Bool("exhaustion", "Exhaustion", false));
        private final Setting.Bool preview = add(new Setting.Bool("preview", "Held food value", true));
        private final Setting.Bool regen = add(new Setting.Bool("regen", "Regeneration prediction", true));

        public Food() { super("food", "Saturation / Food", "Hunger, hidden saturation, exhaustion and food values", Category.HUD, Anchor.BOTTOM_CENTER, 60, -60); }

        /** "Fast" (full food with saturation), "Yes" (food >= 18), or "No". */
        static String regenState(int food, float sat, float health, float max) {
            if (health >= max) return "Full";
            if (food >= 20 && sat > 0) return "Fast";
            if (food >= 18) return "Yes";
            return "No";
        }

        @Override protected List<String> lines(GameState s) {
            if (!s.inWorld() && !previewing()) return Collections.emptyList();
            if ("creative".equals(s.gameMode()) || "spectator".equals(s.gameMode())) return Collections.emptyList();
            List<String> l = new ArrayList<String>();
            if (hunger.on()) l.add("Hunger: " + s.food() + "/20");
            if (saturation.on()) l.add(String.format(Locale.ROOT, "Saturation: %.1f", s.saturation()));
            if (exhaustion.on()) l.add(String.format(Locale.ROOT, "Exhaustion: %.2f/4", s.exhaustion()));
            float[] f = s.heldFood();
            if (preview.on() && f != null) {
                int newFood = Math.min(20, s.food() + (int) f[0]);
                float newSat = Math.min(newFood, s.saturation() + f[1]);
                l.add(String.format(Locale.ROOT, "Eat: +%d hunger, +%.1f sat -> %d / %.1f", (int) f[0], f[1], newFood, newSat));
            }
            if (regen.on()) l.add("Regen: " + regenState(s.food(), s.saturation(), s.health(), s.maxHealth()));
            return l;
        }

        @Override protected int lineColor(int i, GameState s) {
            if (i == 0 && hunger.on()) return s.food() <= 6 ? bad() : s.food() < 18 ? okay() : textColor.argb();
            return textColor.argb();
        }
    }

    // ---- Playtime -----------------------------------------------------------------------------------------------

    public static final class Playtime extends HudTextMod {
        private final Setting.Bool session = add(new Setting.Bool("session", "Session", true));
        private final Setting.Bool today = add(new Setting.Bool("today", "Today", true));
        private final Setting.Bool week = add(new Setting.Bool("week", "This week (7 days)", false));
        private final Setting.Bool month = add(new Setting.Bool("month", "This month (30 days)", false));
        private final Setting.Bool total = add(new Setting.Bool("total", "Total", false));
        private final Setting.Bool server = add(new Setting.Bool("server", "On this server / world", false));
        private final Setting.Bool graph = add(new Setting.Bool("graph", "Last 7 days graph", false));
        private long lastTick, sessionMs;

        public Playtime() { super("playtime", "Playtime", "Time played this session, today, this week and in total", Category.UTILITY, Anchor.TOP_RIGHT, -4, 64); }

        @Override protected void onEnable() {
            lastTick = 0;
            sessionMs = 0;
            events.on(Events.WorldJoin.class, this, e -> sessionMs = 0);
        }

        static String dayKey(long ms) { return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(ms)); }

        @Override public void onTick(GameState s) {
            long now = s.nowMillis();
            long dt = lastTick == 0 ? 0 : Math.min(1000, Math.max(0, now - lastTick));
            lastTick = now;
            if (!s.inWorld() || dt == 0) return;
            sessionMs += dt;
            data.add("total", dt);
            Map<String, Object> days = data.getMap("days");
            String k = dayKey(now);
            days.put(k, (days.get(k) instanceof Number ? ((Number) days.get(k)).longValue() : 0L) + dt);
            if (days.size() > 62) days.remove(new TreeMap<String, Object>(days).firstKey());
            data.put("days", days);
            Map<String, Object> servers = data.getMap("servers");
            String w = worldKey(s);
            servers.put(w, (servers.get(w) instanceof Number ? ((Number) servers.get(w)).longValue() : 0L) + dt);
            data.put("servers", servers);
        }

        static String worldKey(GameState s) { return s.serverAddress().isEmpty() ? "sp:" + s.worldName() : s.serverAddress().toLowerCase(Locale.ROOT); }

        long lastDays(long now, int n) {
            Map<String, Object> days = data.getMap("days");
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            long sum = 0;
            for (int i = 0; i < n; i++) {
                Object v = days.get(dayKey(c.getTimeInMillis()));
                if (v instanceof Number) sum += ((Number) v).longValue();
                c.add(Calendar.DAY_OF_MONTH, -1);
            }
            return sum;
        }

        @Override protected List<String> lines(GameState s) {
            long now = s.nowMillis();
            List<String> l = new ArrayList<String>();
            if (session.on()) l.add("Session: " + dur(sessionMs));
            if (today.on()) l.add("Today: " + dur(lastDays(now, 1)));
            if (week.on()) l.add("Week: " + dur(lastDays(now, 7)));
            if (month.on()) l.add("Month: " + dur(lastDays(now, 30)));
            if (total.on()) l.add("Total: " + dur(data.getLong("total", 0)));
            if (server.on() && s.inWorld()) {
                Object v = data.getMap("servers").get(worldKey(s));
                l.add("Here: " + dur(v instanceof Number ? ((Number) v).longValue() : 0));
            }
            return l;
        }

        @Override protected float minInnerWidth() { return graph.on() ? 84 : 0; }
        @Override protected float extraHeight(UiRenderer r) { return graph.on() ? 22 : 0; }

        @Override protected void drawExtra(UiRenderer r, GameState s, float x, float y, float w) {
            Graph g = new Graph(7);
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(s.nowMillis());
            c.add(Calendar.DAY_OF_MONTH, -6);
            Map<String, Object> days = data.getMap("days");
            for (int i = 0; i < 7; i++) {
                Object v = days.get(dayKey(c.getTimeInMillis()));
                g.add(v instanceof Number ? ((Number) v).floatValue() : 0f);
                c.add(Calendar.DAY_OF_MONTH, 1);
            }
            g.draw(r, x, y + 2, w, 18, 0, withOpacity(0x55000000), v -> withOpacity(0xFF8B5CF6));
        }
    }

    // ---- Statistics ---------------------------------------------------------------------------------------------

    // ---- Network ------------------------------------------------------------------------------------------------

    // ---- Debug HUD ----------------------------------------------------------------------------------------------

    // ---- Hotbar -------------------------------------------------------------------------------------------------

    // ---- Item durability ----------------------------------------------------------------------------------------

    public static final class ItemDurability extends HudTextMod {
        public enum Format { PERCENT, NUMBER, BOTH }

        private final Setting.Choice<Format> format = add(new Setting.Choice<Format>("format", "Format", Format.PERCENT));
        private final Setting.Bool tools = add(new Setting.Bool("tools", "Held tools and weapons", true));
        private final Setting.Bool offhandS = add(new Setting.Bool("offhand", "Offhand", true));
        private final Setting.Bool armor = add(new Setting.Bool("armor", "Armor", false));
        private final Setting.Bool gradientS = add(new Setting.Bool("gradient", "Color gradient", true));
        private final Setting.Int warn = add(new Setting.Int("warn", "Warning threshold (%)", 10, 0, 90));
        private final Setting.Bool breakWarning = add(new Setting.Bool("breakWarning", "Warn when about to break", true));
        private final List<Integer> fractions = new ArrayList<Integer>();
        private final Set<String> warned = new HashSet<String>();

        public ItemDurability() { super("durability", "Item Durability", "Durability of held tools, offhand and armor", Category.INVENTORY, Anchor.BOTTOM_CENTER, -100, -40); }

        @Override protected List<String> lines(GameState s) {
            List<String> l = new ArrayList<String>();
            fractions.clear();
            if (tools.on()) add(l, s.heldItem(), "", s);
            if (offhandS.on()) add(l, s.offhandItem(), "Offhand: ", s);
            if (armor.on()) for (GameState.ArmorPiece a : s.armor()) if (a != null) add(l, new GameState.ItemStack(a.itemId, 1, a.durability, a.maxDurability), "", s);
            return l;
        }

        private void add(List<String> l, GameState.ItemStack st, String prefix, GameState s) {
            if (st == null || st.maxDurability <= 0) return;
            int pct = Math.round(100f * st.durability / st.maxDurability);
            String v = format.val() == Format.PERCENT ? pct + "%" : format.val() == Format.NUMBER ? st.durability + "/" + st.maxDurability : st.durability + " (" + pct + "%)";
            l.add(prefix + (st.name != null ? st.name : prettyId(st.itemId)) + ": " + v);
            fractions.add(pct);
            String key = st.itemId + ":" + st.maxDurability;
            if (breakWarning.on() && pct <= warn.val()) {
                if (warned.add(key)) toasts.warn(s.nowMillis(), prettyId(st.itemId) + " is about to break (" + pct + "%)");
            } else {
                warned.remove(key);
            }
        }

        @Override protected int lineColor(int i, GameState s) {
            if (i >= fractions.size()) return textColor.argb();
            int pct = fractions.get(i);
            if (pct <= warn.val()) return bad();
            return gradientS.on() ? gradient(pct / 100f) : textColor.argb();
        }
    }

    // ---- Height limit -------------------------------------------------------------------------------------------

    // ---- Biome / structure information --------------------------------------------------------------------------

    // ---- Rendering diagnostics ----------------------------------------------------------------------------------

    // ---- Server status ------------------------------------------------------------------------------------------

    // ---- Achievements -------------------------------------------------------------------------------------------

}
