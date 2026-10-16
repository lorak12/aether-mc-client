package gg.kite.mods;

import gg.kite.core.Events;
import gg.kite.core.GameState;
import gg.kite.core.HudElement.Anchor;
import gg.kite.core.Setting;
import gg.kite.core.UiRenderer;
import gg.kite.core.WorldRenderer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Combat read-outs. Display only: nothing here acts on the game. */
public final class PvpMods {
    private PvpMods() {}

    public static final class ComboCounter extends HudTextMod {
        private final Setting.Int timeout = add(new Setting.Int("timeout", "Reset after (s)", 3, 1, 10));
        private final Setting.Bool showBest = add(new Setting.Bool("best", "Show best combo", true));
        private final Setting.Bool hideZero = add(new Setting.Bool("hideZero", "Hide when no combo", false));
        private final Setting.Bool pulse = add(new Setting.Bool("pulse", "Pulse on hit", true));
        private final Setting.Bool milestones = add(new Setting.Bool("milestones", "Notify at 5, 10, 20 hits", false));
        private int combo, best;
        private long lastHit;

        public ComboCounter() { super("combo", "Combo Counter", "Consecutive hits without being hit", Category.PVP, Anchor.MIDDLE_RIGHT, -6, 70); }

        @Override protected void onEnable() {
            combo = best = 0;
            events.on(Events.Attack.class, this, e -> {
                combo++;
                best = Math.max(best, combo);
                lastHit = e.timeMs;
                if (milestones.on() && (combo == 5 || combo == 10 || combo == 20)) toasts.info(e.timeMs, combo + " hit combo!");
            });
            events.on(Events.Hurt.class, this, e -> combo = 0);
        }

        @Override protected List<String> lines(GameState s) {
            if (combo > 0 && s.nowMillis() - lastHit > timeout.val() * 1000L) combo = 0;
            if (hideZero.on() && combo == 0 && !previewing()) return Collections.emptyList();
            List<String> l = new ArrayList<String>();
            l.add("Combo: " + combo);
            if (showBest.on()) l.add("Best: " + best);
            return l;
        }

        @Override public void renderHud(UiRenderer r, GameState s) {
            float age = s.nowMillis() - lastHit;
            boolean bump = pulse.on() && !fx.reducedMotion && combo > 0 && age < 150;
            if (!bump) {
                super.renderHud(r, s);
                return;
            }
            // A short scale pulse around the element's own scale.
            float base = hud.scale();
            hud.setScale(base * (1f + 0.15f * (1f - age / 150f)));
            try {
                super.renderHud(r, s);
            } finally {
                hud.setScale(base);
            }
        }
    }

    public static final class ReachDisplay extends HudTextMod {
        private final Setting.Int decimals = add(new Setting.Int("decimals", "Decimals", 2, 0, 3));
        private final Setting.Bool average = add(new Setting.Bool("average", "Average", true));
        private final Setting.Bool maximum = add(new Setting.Bool("maximum", "Maximum", false));
        private final Setting.Bool current = add(new Setting.Bool("current", "Distance to the target you look at", false));
        private final Setting.Bool attackOnly = add(new Setting.Bool("attackOnly", "Only show after a hit", false));
        private final Setting.Int hideAfter = add(new Setting.Int("hideAfter", "Hide after (s, attack-only)", 5, 1, 30), attackOnly);
        private final Setting.Bool graph = add(new Setting.Bool("graph", "Graph", false));
        private final Graph recent = new Graph(20);
        private double last, max;
        private long lastHit;

        public ReachDisplay() { super("reach", "Reach Display", "Distance of your hits: last, average, max", Category.PVP, Anchor.MIDDLE_RIGHT, -6, 110); }

        @Override protected void onEnable() {
            recent.clear();
            last = max = 0;
            events.on(Events.Attack.class, this, e -> {
                last = e.distance;
                max = Math.max(max, e.distance);
                lastHit = e.timeMs;
                recent.add((float) e.distance);
            });
        }

        @Override protected List<String> lines(GameState s) {
            if (attackOnly.on() && !previewing() && (recent.size() == 0 || s.nowMillis() - lastHit > hideAfter.val() * 1000L)) return Collections.emptyList();
            String f = "%." + decimals.val() + "f";
            List<String> l = new ArrayList<String>();
            l.add(recent.size() == 0 ? "Reach: --" : String.format(Locale.ROOT, "Reach: " + f, last));
            if (recent.size() > 0) {
                if (average.on()) l.add(String.format(Locale.ROOT, "Avg: " + f, recent.average()));
                if (maximum.on()) l.add(String.format(Locale.ROOT, "Max: " + f, max));
            }
            GameState.Target t = s.target();
            if (current.on() && t.kind == GameState.Target.Kind.ENTITY) l.add(String.format(Locale.ROOT, "Target: " + f, t.distance));
            return l;
        }

        @Override protected float minInnerWidth() { return graph.on() ? 70 : 0; }
        @Override protected float extraHeight(UiRenderer r) { return graph.on() && recent.size() > 1 ? 16 : 0; }

        @Override protected void drawExtra(UiRenderer r, GameState s, float x, float y, float w) {
            recent.draw(r, x, y + 2, w, 12, 3.5f, withOpacity(0x55000000), v -> withOpacity(v > 3.0f ? bad() : good()));
        }
    }

    /** Health of the entity under the crosshair (or the last one you hit). */
    public static final class HealthDisplay extends HudTextMod {
        public enum Style { NUMBER, HEARTS, PERCENT }

        private final Setting.Choice<Style> style = add(new Setting.Choice<Style>("style", "Style", Style.NUMBER));
        private final Setting.Bool name = add(new Setting.Bool("name", "Name", true));
        private final Setting.Bool bar = add(new Setting.Bool("bar", "Health bar", true));
        private final Setting.Color barColor = add(new Setting.Color("barColor", "Bar color (clear = by health)", 0x00000000), bar);
        private final Setting.Bool distance = add(new Setting.Bool("distance", "Distance", false));
        private final Setting.Int keep = add(new Setting.Int("keep", "Keep showing after looking away (s)", 2, 0, 10));
        private GameState.Target shown;
        private long seen;

        public HealthDisplay() { super("healthdisplay", "Health Display", "Name, health and bar of the mob or player you look at", Category.PVP, Anchor.CENTER, 0, 40); }

        @Override protected List<String> lines(GameState s) {
            GameState.Target t = s.target();
            if (t.kind == GameState.Target.Kind.ENTITY && t.maxHealth > 0) {
                shown = t;
                seen = s.nowMillis();
            } else if (shown != null && s.nowMillis() - seen > keep.val() * 1000L) {
                shown = null;
            }
            if (shown == null && previewing()) {
                shown = new GameState.Target(GameState.Target.Kind.ENTITY, "Steve", "minecraft:player", 3.1);
                shown.health = 18;
                shown.maxHealth = 20;
                seen = s.nowMillis();
            }
            if (shown == null) return Collections.emptyList();
            List<String> l = new ArrayList<String>();
            if (name.on()) l.add(shown.name);
            l.add(format(style.val(), shown.health, shown.maxHealth));
            if (distance.on()) l.add(String.format(Locale.ROOT, "%.1f m", shown.distance));
            return l;
        }

        static String format(Style st, float hp, float max) {
            switch (st) {
                case HEARTS: return String.format(Locale.ROOT, "❤ %.1f / %.1f", hp / 2, max / 2);
                case PERCENT: return "❤ " + Math.round(100 * hp / Math.max(1, max)) + "%";
                default: return String.format(Locale.ROOT, "❤ %.0f/%.0f", Math.ceil(hp), max);
            }
        }

        @Override protected int lineColor(int i, GameState s) {
            boolean healthLine = i == (name.on() ? 1 : 0);
            return healthLine && shown != null ? gradient(shown.health / Math.max(1, shown.maxHealth)) : textColor.argb();
        }

        @Override protected float minInnerWidth() { return bar.on() ? 60 : 0; }
        @Override protected float extraHeight(UiRenderer r) { return bar.on() && shown != null ? 5 : 0; }

        @Override protected void drawExtra(UiRenderer r, GameState s, float x, float y, float w) {
            float f = Math.max(0, Math.min(1, shown.health / Math.max(1, shown.maxHealth)));
            r.rect(x, y + 1, w, 3, withOpacity(0x88000000), 1);
            int c = (barColor.argb() >>> 24) == 0 ? gradient(f) : barColor.argb();
            r.rect(x, y + 1, w * f, 3, withOpacity(c), 1);
        }
    }

    /** Primed TNT and swelling creepers nearby, with time to explosion. */
    public static final class TntTimer extends HudTextMod {
        private final Setting.Int radius = add(new Setting.Int("radius", "Radius (blocks)", 16, 4, 64));
        private final Setting.Bool creepers = add(new Setting.Bool("creepers", "Include creepers", true));
        private final Setting.Bool showDistance = add(new Setting.Bool("distance", "Distance", true));
        private final Setting.Dbl warn = add(new Setting.Dbl("warn", "Warning below (s)", 1.0, 0.1, 4.0));
        private final Setting.Bool alert = add(new Setting.Bool("alert", "Notify when TNT is primed near you", false));
        private final Setting.Bool floating = add(new Setting.Bool("floating", "Floating timer above the TNT", true));
        private final Setting.Bool hud = add(new Setting.Bool("hudList", "List in the HUD", true));
        private final List<GameState.EntityInfo> shown = new ArrayList<GameState.EntityInfo>();
        private final java.util.Set<Integer> alerted = new java.util.HashSet<Integer>();

        public TntTimer() { super("tnttimer", "TNT Timer", "Fuse time of primed TNT and swelling creepers nearby", Category.PVP, Anchor.CENTER, -80, -20); }

        @Override protected List<String> lines(GameState s) {
            shown.clear();
            for (GameState.EntityInfo e : s.entities(radius.val())) {
                if (e.fuse < 0) continue;
                boolean tnt = e.type.endsWith("tnt");
                if (!tnt && !creepers.on()) continue;
                shown.add(e);
                if (tnt && alert.on() && alerted.add(e.id)) toasts.warn(s.nowMillis(), String.format(Locale.ROOT, "TNT primed %.0fm away", e.distance));
            }
            if (alerted.size() > 200) alerted.clear();
            List<String> l = new ArrayList<String>();
            if (!hud.on()) return l;
            for (GameState.EntityInfo e : shown) {
                String t = (e.type.endsWith("tnt") ? "TNT" : "Creeper") + String.format(Locale.ROOT, " %.1fs", e.fuse / 20.0);
                if (showDistance.on()) t += String.format(Locale.ROOT, "  %.0fm", e.distance);
                l.add(t);
            }
            return l;
        }

        @Override protected int lineColor(int i, GameState s) {
            if (i >= shown.size()) return textColor.argb();
            return shown.get(i).fuse / 20.0 <= warn.val() ? bad() : okay();
        }

        @Override public void renderWorld(WorldRenderer w, GameState s) {
            if (!floating.on()) return;
            for (GameState.EntityInfo e : s.entities(radius.val())) {
                if (e.fuse < 0 || (!e.type.endsWith("tnt") && !creepers.on())) continue;
                int c = e.fuse / 20.0 <= warn.val() ? bad() : okay();
                w.text(String.format(Locale.ROOT, "%.1fs", e.fuse / 20.0), e.x, e.y + e.height + 0.6, e.z, c, 1.5f, true);
            }
        }
    }

}
