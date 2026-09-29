package gg.aether.mods;

import gg.aether.core.ClickCounter;
import gg.aether.core.Events;
import gg.aether.core.GameState;
import gg.aether.core.HudElement.Anchor;
import gg.aether.core.Setting;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The one-to-few-line HUD mods. */
public final class SimpleHudMods {
    private SimpleHudMods() {}

    private static List<String> one(String s) { return Collections.singletonList(s); }

    public static final class Fps extends HudTextMod {
        public Fps() { super("fps", "FPS", "Frames per second", Anchor.TOP_LEFT, 4, 4); }
        @Override protected List<String> lines(GameState s) { return one(s.fps() + " FPS"); }
    }

    public static final class Ping extends HudTextMod {
        public Ping() { super("ping", "Ping", "Latency to the server", Anchor.TOP_LEFT, 4, 24); }
        @Override protected List<String> lines(GameState s) { return s.pingMs() < 0 ? one("-- ms") : one(s.pingMs() + " ms"); }
    }

    public static final class Coordinates extends HudTextMod {
        private final Setting.Int decimals = add(new Setting.Int("decimals", "Decimals", 0, 0, 3));
        private final Setting.Bool showBiome = add(new Setting.Bool("biome", "Show biome", false));
        public Coordinates() { super("coords", "Coordinates", "XYZ position", Anchor.TOP_LEFT, 4, 44); }
        @Override protected List<String> lines(GameState s) {
            String f = "%." + decimals.val() + "f";
            List<String> l = new ArrayList<String>();
            l.add(String.format(Locale.ROOT, "XYZ: " + f + " / " + f + " / " + f, s.x(), s.y(), s.z()));
            if (showBiome.on()) l.add(s.biome());
            return l;
        }
    }

    public static final class Direction extends HudTextMod {
        public Direction() { super("direction", "Direction", "Compass heading", Anchor.TOP_CENTER, 0, 4); }
        @Override protected List<String> lines(GameState s) { return one(compass(s.yaw())); }

        /** Minecraft yaw: 0 = south, 90 = west, 180 = north, 270 = east. */
        public static String compass(float yaw) {
            String[] names = { "S", "SW", "W", "NW", "N", "NE", "E", "SE" };
            float y = ((yaw % 360f) + 360f) % 360f;
            return names[Math.round(y / 45f) % 8];
        }
    }

    public static final class Clock extends HudTextMod {
        private final Setting.Bool h24 = add(new Setting.Bool("h24", "24-hour", true));
        public Clock() { super("clock", "Clock", "Real-world time", Anchor.TOP_RIGHT, -4, 4); }
        @Override protected List<String> lines(GameState s) {
            return one(new SimpleDateFormat(h24.on() ? "HH:mm" : "h:mm a", Locale.ROOT).format(new Date(s.nowMillis())));
        }
    }

    public static final class Memory extends HudTextMod {
        public Memory() { super("memory", "Memory", "JVM memory use", Anchor.TOP_RIGHT, -4, 24); }
        @Override protected List<String> lines(GameState s) {
            long mb = 1024 * 1024;
            return one("Mem: " + s.usedMemoryBytes() / mb + "/" + s.maxMemoryBytes() / mb + " MB");
        }
    }

    public static final class Cps extends HudTextMod {
        private final ClickCounter left = new ClickCounter(), right = new ClickCounter();
        private final Setting.Bool showRight = add(new Setting.Bool("right", "Show right click", true));
        public Cps() { super("cps", "CPS", "Clicks per second", Anchor.TOP_LEFT, 4, 64); }

        @Override protected void onEnable() {
            events.on(Events.Click.class, this, e -> {
                if (e.button == 0) left.record(e.timeMs);
                else if (e.button == 1) right.record(e.timeMs);
            });
        }

        @Override protected List<String> lines(GameState s) {
            long now = s.nowMillis();
            return one(showRight.on() ? left.count(now) + " | " + right.count(now) + " CPS" : left.count(now) + " CPS");
        }
    }

    public static final class Potions extends HudTextMod {
        public Potions() { super("potions", "Potion Effects", "Active effects with timers", Anchor.MIDDLE_LEFT, 4, 0); }
        @Override protected List<String> lines(GameState s) {
            List<String> l = new ArrayList<String>();
            for (GameState.Effect e : s.effects()) {
                int secs = e.ticksLeft / 20;
                l.add(e.name + " " + roman(e.amplifier + 1) + " " + secs / 60 + ":" + (secs % 60 < 10 ? "0" : "") + secs % 60);
            }
            return l;
        }

        static String roman(int n) {
            String[] r = { "", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X" };
            return n >= 1 && n < r.length ? r[n] : String.valueOf(n);
        }
    }
}
