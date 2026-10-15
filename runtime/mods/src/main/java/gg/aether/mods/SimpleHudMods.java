package gg.aether.mods;

import gg.aether.core.Events;
import gg.aether.core.GameState;
import gg.aether.core.HudElement.Anchor;
import gg.aether.core.Setting;
import gg.aether.core.UiRenderer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** The classic small HUD widgets: FPS, ping, CPS, coordinates, direction, clock, memory, potion effects. */
public final class SimpleHudMods {
    private SimpleHudMods() {}

    private static List<String> one(String s) { return Collections.singletonList(s); }

    // ---- FPS ----------------------------------------------------------------------------------------------------

    public static final class Fps extends HudTextMod {
        private final Setting.Bool label = add(new Setting.Bool("label", "Show \"FPS\" text", true));
        private final Setting.Bool compact = add(new Setting.Bool("compact", "Compact (one line)", true));
        private final Setting.Bool showAvg = add(new Setting.Bool("avg", "Average FPS", false));
        private final Setting.Bool showMin = add(new Setting.Bool("min", "Minimum FPS", false));
        private final Setting.Bool showMax = add(new Setting.Bool("max", "Maximum FPS", false));
        private final Setting.Bool showLow1 = add(new Setting.Bool("low1", "1% low", false));
        private final Setting.Int window = add(new Setting.Int("window", "Statistics window (s)", 30, 5, 120));
        private final Setting.Int updateMs = add(new Setting.Int("updateMs", "Update interval (ms)", 250, 0, 2000));
        private final Setting.Bool colorByFps = add(new Setting.Bool("colorByFps", "Color by FPS", false));
        private final Setting.Int target = add(new Setting.Int("target", "Good FPS (color)", 120, 30, 500), colorByFps);
        private final Setting.Bool graph = add(new Setting.Bool("graph", "FPS graph", false));
        private final Graph samples = new Graph(120 * 20);
        private final Graph history = new Graph(100);
        private long lastUpdate;
        private List<String> shown = Collections.emptyList();
        private int current;

        public Fps() { super("fps", "FPS", "Frames per second with min, max, average and lows", Anchor.TOP_LEFT, 4, 4); }

        @Override protected void onEnable() { samples.clear(); history.clear(); lastUpdate = 0; }

        @Override public void onTick(GameState s) {
            samples.add(Math.max(0, s.fps()));
            history.add(Math.max(0, s.fps()));
        }

        /** Stats over the configured window only. */
        private Graph windowed() {
            int keep = Math.min(samples.size(), window.val() * 20);
            Graph g = new Graph(Math.max(1, keep));
            for (int i = samples.size() - keep; i < samples.size(); i++) g.add(samples.get(i));
            return g;
        }

        @Override protected List<String> lines(GameState s) {
            long now = s.nowMillis();
            if (now - lastUpdate < updateMs.val() && !shown.isEmpty()) return shown;
            lastUpdate = now;
            current = s.fps();
            Graph w = windowed();
            List<String> parts = new ArrayList<String>();
            if (showAvg.on()) parts.add("AVG " + Math.round(w.average()));
            if (showMin.on()) parts.add("MIN " + Math.round(w.min()));
            if (showMax.on()) parts.add("MAX " + Math.round(w.max()));
            if (showLow1.on()) parts.add("1% LOW " + Math.round(w.lowAverage(0.01)));
            List<String> l = new ArrayList<String>();
            if (compact.on()) {
                StringBuilder b = new StringBuilder(label.on() ? current + " FPS" : String.valueOf(current));
                for (String p : parts) b.append("  ").append(p);
                l.add(b.toString());
            } else {
                l.add(label.on() ? "FPS: " + current : String.valueOf(current));
                for (String p : parts) {
                    int sp = p.lastIndexOf(' ');
                    l.add(p.substring(0, sp) + ": " + p.substring(sp + 1));
                }
            }
            return shown = l;
        }

        @Override protected int lineColor(int i, GameState s) {
            if (!colorByFps.on() || i != 0) return textColor.argb();
            return gradient(current / (float) target.val());
        }

        @Override protected float minInnerWidth() { return graph.on() ? 100 : 0; }
        @Override protected float extraHeight(UiRenderer r) { return graph.on() ? 22 : 0; }

        @Override protected void drawExtra(UiRenderer r, GameState s, float x, float y, float w) {
            history.draw(r, x, y + 2, w, 18, 0, withOpacity(0x55000000), v -> withOpacity(gradient(v / (float) target.val())));
        }
    }

    // ---- Ping ---------------------------------------------------------------------------------------------------

    public static final class Ping extends HudTextMod {
        private final Setting.Bool label = add(new Setting.Bool("label", "Show \"ms\" text", true));
        private final Setting.Bool graph = add(new Setting.Bool("graph", "Ping graph", false));
        private final Graph samples = new Graph(120);
        private long lastSample;

        public Ping() { super("ping", "Ping", "Your latency to the server", Anchor.TOP_LEFT, 4, 24); sensitive = false; }

        @Override protected void onEnable() { samples.clear(); lastSample = 0; }

        @Override public void onTick(GameState s) {
            if (s.pingMs() < 0) return;
            if (s.nowMillis() - lastSample >= 1000L) {
                lastSample = s.nowMillis();
                samples.add(s.pingMs());
            }
        }

        @Override protected List<String> lines(GameState s) {
            String v = s.pingMs() < 0 ? "--" : String.valueOf(s.pingMs());
            return one(label.on() ? v + " ms" : v);
        }

        @Override protected float minInnerWidth() { return graph.on() ? 90 : 0; }
        @Override protected float extraHeight(UiRenderer r) { return graph.on() ? 20 : 0; }

        @Override protected void drawExtra(UiRenderer r, GameState s, float x, float y, float w) {
            samples.draw(r, x, y + 2, w, 16, Math.max(150, samples.max()), withOpacity(0x55000000), v -> withOpacity(textColor.argb()));
        }
    }

    // ---- CPS ----------------------------------------------------------------------------------------------------

    /** Timestamps of recent clicks (up to 10 s), for 1/5/10-second windows. */
    static final class Clicks {
        private final long[] t = new long[512];
        private int head, n;

        void record(long ms) {
            t[head] = ms;
            head = (head + 1) % t.length;
            n = Math.min(n + 1, t.length);
        }

        /** Clicks per second averaged over the last {@code seconds}. */
        float rate(long now, int seconds) {
            int c = 0;
            for (int i = 0; i < n; i++) if (now - t[(head - 1 - i + t.length) % t.length] < seconds * 1000L) c++;
            return c / (float) seconds;
        }

        void clear() { head = n = 0; }
    }

    public static final class Cps extends HudTextMod {
        private final Setting.Bool showLeft = add(new Setting.Bool("left", "Left CPS", true));
        private final Setting.Bool showRight = add(new Setting.Bool("right", "Right CPS", true));
        private final Setting.Bool showMiddle = add(new Setting.Bool("middle", "Middle CPS", false));
        private final Setting.Bool label = add(new Setting.Bool("label", "Show \"CPS\" text", true));
        private final Clicks left = new Clicks(), right = new Clicks(), middle = new Clicks();

        public Cps() { super("cps", "CPS", "Clicks per second for the left, right and middle buttons", Anchor.TOP_LEFT, 4, 64); }

        @Override protected void onEnable() {
            left.clear();
            right.clear();
            middle.clear();
            events.on(Events.Click.class, this, e -> {
                if (e.button == 0) left.record(e.timeMs);
                else if (e.button == 1) right.record(e.timeMs);
                else if (e.button == 2) middle.record(e.timeMs);
            });
        }

        @Override protected List<String> lines(GameState s) {
            long now = s.nowMillis();
            List<String> parts = new ArrayList<String>();
            if (showLeft.on()) parts.add(String.valueOf(Math.round(left.rate(now, 1))));
            if (showRight.on()) parts.add(String.valueOf(Math.round(right.rate(now, 1))));
            if (showMiddle.on()) parts.add(String.valueOf(Math.round(middle.rate(now, 1))));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) b.append(i == 0 ? "" : " | ").append(parts.get(i));
            if (b.length() == 0) return one(label.on() ? "CPS" : "0");
            return one(label.on() ? b + " CPS" : b.toString());
        }
    }

    // ---- Coordinates --------------------------------------------------------------------------------------------

    public static final class Coordinates extends HudTextMod {
        private final Setting.Bool oneLine = add(new Setting.Bool("oneLine", "Single line (XYZ: x / y / z)", true));
        private final Setting.Bool labels = add(new Setting.Bool("labels", "Show labels (XYZ:)", true));
        private final Setting.Int decimals = add(new Setting.Int("decimals", "Decimals (0 = block coordinates)", 0, 0, 3));
        private final Setting.Bool chunk = add(new Setting.Bool("chunk", "Chunk coordinates", false));
        private final Setting.Bool local = add(new Setting.Bool("local", "Position inside the chunk", false));
        private final Setting.Bool facing = add(new Setting.Bool("facing", "Facing direction", false));
        private final Setting.Bool rotation = add(new Setting.Bool("rotation", "Yaw / pitch", false));
        private final Setting.Bool showBiome = add(new Setting.Bool("biome", "Biome", false));
        private final Setting.Bool dimension = add(new Setting.Bool("dimension", "Dimension", false));
        private final Setting.Bool light = add(new Setting.Bool("light", "Light level", false));
        private final Setting.Bool nether = add(new Setting.Bool("nether", "Overworld/Nether equivalent", false));

        public Coordinates() { super("coords", "Coordinates", "XYZ, chunk, facing, biome, dimension and light", Anchor.TOP_LEFT, 4, 44); sensitive = true; }

        private String num(double v) {
            if (decimals.val() == 0) return String.valueOf((int) Math.floor(v));
            return String.format(Locale.ROOT, "%." + decimals.val() + "f", v);
        }

        @Override protected List<String> lines(GameState s) {
            List<String> l = new ArrayList<String>();
            boolean lb = labels.on();
            if (oneLine.on()) l.add((lb ? "XYZ: " : "") + num(s.x()) + " / " + num(s.y()) + " / " + num(s.z()));
            else {
                l.add((lb ? "X: " : "") + num(s.x()));
                l.add((lb ? "Y: " : "") + num(s.y()));
                l.add((lb ? "Z: " : "") + num(s.z()));
            }
            int bx = (int) Math.floor(s.x()), by = (int) Math.floor(s.y()), bz = (int) Math.floor(s.z());
            if (chunk.on()) l.add("Chunk: " + (bx >> 4) + " " + (by >> 4) + " " + (bz >> 4));
            if (local.on()) l.add("In chunk: " + (bx & 15) + " " + (by & 15) + " " + (bz & 15));
            if (facing.on()) l.add("Facing: " + Direction.full(s.yaw()) + " (" + Direction.axis(s.yaw()) + ")");
            if (rotation.on()) l.add(String.format(Locale.ROOT, "Yaw %.1f  Pitch %.1f", wrap(s.yaw()), s.pitch()));
            if (showBiome.on()) l.add("Biome: " + s.biome());
            if (dimension.on()) l.add("Dimension: " + HudTextMod.prettyId(s.dimension()));
            if (light.on()) l.add("Light: " + Math.max(s.blockLight(bx, by, bz), s.skyLight(bx, by, bz)) + " (block " + s.blockLight(bx, by, bz) + ")");
            if (nether.on()) {
                if (s.dimension().equals("the_nether")) l.add("Overworld: " + (bx * 8) + " / " + (bz * 8));
                else if (s.dimension().equals("overworld")) l.add("Nether: " + (bx >> 3) + " / " + (bz >> 3));
            }
            return l;
        }

        static float wrap(float yaw) {
            float y = yaw % 360f;
            if (y >= 180f) y -= 360f;
            if (y < -180f) y += 360f;
            return y;
        }
    }

    // ---- Direction ----------------------------------------------------------------------------------------------

    public static final class Direction extends HudTextMod {
        public enum Style { LETTERS, FULL, DEGREES, COMPASS }

        private final Setting.Choice<Style> style = add(new Setting.Choice<Style>("style", "Style", Style.LETTERS));
        private final Setting.Color marker = add(new Setting.Color("marker", "Compass marker", 0xFFFF5D6C)).visibleWhen(() -> style.val() == Style.COMPASS);
        private final Setting.Bool degrees = add(new Setting.Bool("degrees", "Add degrees", false)).visibleWhen(() -> style.val() != Style.DEGREES);
        private final Setting.Bool axis = add(new Setting.Bool("axis", "Add coordinate axis (+X/-Z)", false)).visibleWhen(() -> style.val() != Style.COMPASS);

        /** Compass strip width when the HUD editor has not resized it, and the narrowest it can be. */
        static final float COMPASS_W = 140, COMPASS_MIN_W = 60;

        public Direction() { super("direction", "Direction", "Heading as letters, words, degrees or a compass strip (resize it in the HUD editor)", Anchor.TOP_CENTER, 0, 4); }

        @Override protected List<String> lines(GameState s) {
            if (style.val() == Style.COMPASS) return Collections.emptyList();
            String t;
            switch (style.val()) {
                case FULL: t = full(s.yaw()); break;
                case DEGREES: t = Math.round(bearing(s.yaw())) + "°"; break;
                default: t = compass(s.yaw()); break;
            }
            if (degrees.on() && style.val() != Style.DEGREES) t += " " + Math.round(bearing(s.yaw())) + "°";
            if (axis.on()) t += " (" + axis(s.yaw()) + ")";
            return one(t);
        }

        @Override public void renderHud(UiRenderer r, GameState s) {
            if (style.val() != Style.COMPASS) {
                super.renderHud(r, s);
                return;
            }
            float strip = r.fontHeight() + 10, h = strip + (degrees.on() ? r.fontHeight() + 2 : 0);
            hud.natural(COMPASS_MIN_W, h);
            float w = hud.boxWidth() > 0 ? hud.boxWidth() : COMPASS_W;
            h = Math.max(h, hud.boxHeight());
            float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), w, h);
            r.push(p[0], p[1], hud.scale());
            if (background.on()) r.rect(0, 0, w, h, withOpacity(bgColor.argb()), 3f);
            if (border.on()) outline(r, 0, 0, w, h, withOpacity(borderColor.argb()));
            // Clip rectangles are in the current (pushed) coordinates, like drawing.
            r.clip(0, 0, w, strip);
            float b = bearing(s.yaw());
            float pxPerDeg = w / 180f; // show +-90 degrees
            String[] names = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };
            for (int deg = 0; deg < 360; deg += 15) {
                float d = deg - b;
                while (d < -180) d += 360;
                while (d > 180) d -= 360;
                if (Math.abs(d) > 95) continue;
                float x = w / 2f + d * pxPerDeg;
                if (deg % 45 == 0) {
                    String n = names[deg / 45];
                    r.text(n, x - r.textWidth(n) / 2f, 2, withOpacity(n.length() == 1 ? textColor.argb() : 0xFFAAAAAA), shadow.on());
                } else {
                    r.rect(x, strip - 5, 1, 3, withOpacity(0x99FFFFFF), 0);
                }
            }
            r.unclip();
            r.rect(w / 2f - 0.5f, strip - 4, 1, 4, withOpacity(marker.argb()), 0);
            if (degrees.on()) {
                String d = Math.round(b) + "°";
                r.text(d, w / 2f - r.textWidth(d) / 2f, strip + 1, withOpacity(textColor.argb()), shadow.on());
            }
            r.pop();
        }

        /** 0 = north, clockwise, 0-360 (Minecraft yaw 0 = south). */
        static float bearing(float yaw) { return ((yaw + 180f) % 360f + 360f) % 360f; }

        /** Minecraft yaw: 0 = south, 90 = west, 180 = north, 270 = east. */
        public static String compass(float yaw) {
            String[] names = { "S", "SW", "W", "NW", "N", "NE", "E", "SE" };
            float y = ((yaw % 360f) + 360f) % 360f;
            return names[Math.round(y / 45f) % 8];
        }

        static String full(float yaw) {
            String[] names = { "South", "South-West", "West", "North-West", "North", "North-East", "East", "South-East" };
            float y = ((yaw % 360f) + 360f) % 360f;
            return names[Math.round(y / 45f) % 8];
        }

        /** Axis the player is walking along: "+Z" south, "-X" west, "-Z" north, "+X" east. */
        static String axis(float yaw) {
            String[] a = { "+Z", "-X", "-Z", "+X" };
            float y = ((yaw % 360f) + 360f) % 360f;
            return a[Math.round(y / 90f) % 4];
        }
    }

    // ---- Clock --------------------------------------------------------------------------------------------------

    public static final class Clock extends HudTextMod {
        private final Setting.Bool h24 = add(new Setting.Bool("h24", "24-hour", true));
        private final Setting.Bool real = add(new Setting.Bool("real", "Real-world time", true));
        private final Setting.Bool seconds = add(new Setting.Bool("seconds", "Seconds", false), real);
        private final Setting.Bool date = add(new Setting.Bool("date", "Date", false), real);
        private final Setting.Text timezone = add(new Setting.Text("timezone", "Timezone (empty = local, e.g. UTC)", "", 40), real);
        private final Setting.Bool game = add(new Setting.Bool("game", "Minecraft time", false));
        private final Setting.Bool day = add(new Setting.Bool("day", "Minecraft day number", false));

        public Clock() { super("clock", "Clock", "Real-world and Minecraft time, date and timezone", Anchor.TOP_RIGHT, -4, 4); }

        @Override protected List<String> lines(GameState s) {
            List<String> l = new ArrayList<String>();
            if (real.on()) {
                String pattern = (date.on() ? "yyyy-MM-dd " : "") + (h24.on() ? "HH:mm" : "h:mm") + (seconds.on() ? ":ss" : "") + (h24.on() ? "" : " a");
                SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.ROOT);
                if (!timezone.str().trim().isEmpty()) f.setTimeZone(TimeZone.getTimeZone(timezone.str().trim()));
                l.add(f.format(new Date(s.nowMillis())));
            }
            if (game.on()) l.add("MC " + gameTime(s.dayTime(), h24.on()));
            if (day.on()) l.add("Day " + (s.dayTime() / 24000 + 1));
            return l;
        }

        /** Tick 0 is 06:00. */
        static String gameTime(long dayTime, boolean h24) {
            int t = (int) (((dayTime % 24000) + 24000) % 24000);
            int minutes = (t * 60 / 1000 + 6 * 60) % (24 * 60);
            int h = minutes / 60, m = minutes % 60;
            if (h24) return String.format(Locale.ROOT, "%02d:%02d", h, m);
            return String.format(Locale.ROOT, "%d:%02d %s", h % 12 == 0 ? 12 : h % 12, m, h < 12 ? "AM" : "PM");
        }
    }

    // ---- Memory -------------------------------------------------------------------------------------------------

    public static final class Memory extends HudTextMod {
        private final Setting.Bool label = add(new Setting.Bool("label", "Show \"Mem\" text", true));
        private final Setting.Bool percent = add(new Setting.Bool("percent", "Percentage", true));
        private final Setting.Bool allocated = add(new Setting.Bool("allocated", "Allocated", false));
        private final Setting.Bool graph = add(new Setting.Bool("graph", "Memory graph", false));
        private final Graph history = new Graph(100);
        private int ticks;

        public Memory() { super("memory", "Memory", "Used, allocated and max RAM", Anchor.TOP_RIGHT, -4, 24); }

        @Override protected void onEnable() { history.clear(); }

        @Override public void onTick(GameState s) {
            if (++ticks % 10 == 0) history.add(s.usedMemoryBytes() / (float) Math.max(1, s.maxMemoryBytes()));
        }

        @Override protected List<String> lines(GameState s) {
            long mb = 1024 * 1024;
            long used = s.usedMemoryBytes(), max = Math.max(1, s.maxMemoryBytes());
            String t = (label.on() ? "Mem: " : "") + used / mb + "/" + max / mb + " MB";
            if (percent.on()) t += " (" + Math.round(100.0 * used / max) + "%)";
            List<String> l = new ArrayList<String>();
            l.add(t);
            if (allocated.on()) l.add((label.on() ? "Allocated: " : "") + Runtime.getRuntime().totalMemory() / mb + " MB");
            return l;
        }

        @Override protected float minInnerWidth() { return graph.on() ? 90 : 0; }
        @Override protected float extraHeight(UiRenderer r) { return graph.on() ? 18 : 0; }

        @Override protected void drawExtra(UiRenderer r, GameState s, float x, float y, float w) {
            history.draw(r, x, y + 2, w, 14, 1f, withOpacity(0x55000000), v -> withOpacity(gradient(1f - v)));
        }
    }

    // ---- Potion effects -----------------------------------------------------------------------------------------

    public static final class Potions extends HudTextMod {
        public enum Sort { NONE, DURATION, NAME }

        private final Setting.Choice<Sort> sort = add(new Setting.Choice<Sort>("sort", "Sort by", Sort.NONE));
        private final Setting.Bool showLevel = add(new Setting.Bool("level", "Level", true));
        private final Setting.Bool showDuration = add(new Setting.Bool("duration", "Remaining time", true));
        private final Setting.Bool bar = add(new Setting.Bool("bar", "Duration bar", false));
        private final Setting.Bool hideNegative = add(new Setting.Bool("hideNegative", "Hide negative effects", false));
        private final Setting.Bool hideInfinite = add(new Setting.Bool("hideInfinite", "Hide infinite effects", false));
        private final Setting.Text hidden = add(new Setting.Text("hidden", "Hide these (comma-separated)", "", 200));
        private final Setting.Int warnSeconds = add(new Setting.Int("warn", "Warn below (s)", 10, 0, 60));
        private final Setting.Color warnColor = add(new Setting.Color("warnColor", "Warning color", 0xFFFF5555)).visibleWhen(() -> warnSeconds.val() > 0);
        private final Setting.Bool colorNegative = add(new Setting.Bool("colorNegative", "Negative effects in red", true));
        private final Setting.Bool hideVanilla = add(new Setting.Bool("hideVanilla", "Hide vanilla effect icons", false));
        private List<GameState.Effect> shown = Collections.emptyList();

        public Potions() { super("potions", "Potion Effects", "Active effects with timers, sorting and filters", Anchor.MIDDLE_LEFT, 4, 0); }

        @Override public void onTick(GameState s) { if (hideVanilla.on()) fx.hideVanillaEffects = true; }

        static final int INFINITE = 20 * 5999;

        private List<GameState.Effect> visible(GameState s) {
            List<String> hide = new ArrayList<String>();
            for (String h : hidden.str().split(",")) if (!h.trim().isEmpty()) hide.add(h.trim().toLowerCase(Locale.ROOT));
            List<GameState.Effect> out = new ArrayList<GameState.Effect>();
            for (GameState.Effect e : s.effects()) {
                if (hideNegative.on() && !e.beneficial) continue;
                if (hideInfinite.on() && e.ticksLeft >= INFINITE) continue;
                if (hide.contains(e.name.toLowerCase(Locale.ROOT)) || (!e.id.isEmpty() && hide.contains(e.id.substring(e.id.indexOf(':') + 1)))) continue;
                out.add(e);
            }
            if (sort.val() == Sort.DURATION) Collections.sort(out, new Comparator<GameState.Effect>() {
                @Override public int compare(GameState.Effect a, GameState.Effect b) { return Integer.compare(a.ticksLeft, b.ticksLeft); }
            });
            else if (sort.val() == Sort.NAME) Collections.sort(out, new Comparator<GameState.Effect>() {
                @Override public int compare(GameState.Effect a, GameState.Effect b) { return a.name.compareToIgnoreCase(b.name); }
            });
            return out;
        }

        @Override protected List<String> lines(GameState s) {
            shown = visible(s);
            List<String> l = new ArrayList<String>();
            for (GameState.Effect e : shown) {
                StringBuilder b = new StringBuilder(e.name);
                if (showLevel.on() && e.amplifier > 0) b.append(' ').append(roman(e.amplifier + 1));
                if (showDuration.on()) b.append("  ").append(e.ticksLeft >= INFINITE ? "∞" : time(e.ticksLeft));
                l.add(b.toString());
            }
            return l;
        }

        @Override protected int lineColor(int i, GameState s) {
            if (i >= shown.size()) return textColor.argb();
            GameState.Effect e = shown.get(i);
            if (e.ticksLeft < INFINITE && e.ticksLeft / 20 < warnSeconds.val()) return warnColor.argb();
            if (colorNegative.on() && !e.beneficial) return 0xFFFF7070;
            return textColor.argb();
        }

        @Override protected float extraHeight(UiRenderer r) { return bar.on() && !shown.isEmpty() ? shown.size() * 3 + 2 : 0; }

        @Override protected void drawExtra(UiRenderer r, GameState s, float x, float y, float w) {
            y += 1;
            for (GameState.Effect e : shown) {
                float f = e.ticksLeft >= INFINITE ? 1f : Math.min(1f, e.ticksLeft / (20f * 180f));
                r.rect(x, y, w, 2, withOpacity(0x44FFFFFF), 0);
                r.rect(x, y, w * f, 2, withOpacity(e.beneficial ? good() : bad()), 0);
                y += 3;
            }
        }

        static String time(int ticks) {
            int secs = ticks / 20;
            return secs / 60 + ":" + (secs % 60 < 10 ? "0" : "") + secs % 60;
        }

        static String roman(int n) {
            String[] r = { "", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X" };
            return n >= 1 && n < r.length ? r[n] : String.valueOf(n);
        }
    }
}
