package gg.kite.mods;

import gg.kite.core.GameState;
import gg.kite.core.HudElement;
import gg.kite.core.HudElement.Anchor;
import gg.kite.core.Mod;
import gg.kite.core.Setting;
import gg.kite.core.UiRenderer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Movable, restyled replacements for vanilla HUD pieces: boss bars, titles and the scoreboard sidebar. */
public final class VanillaHudMods {
    private VanillaHudMods() {}

    private static int fade(HudElement hud, int argb, float extra) {
        int a = Math.round(((argb >>> 24) & 0xFF) * hud.opacity() * extra);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Vanilla boss bar colours by name. */
    static int barColor(String name) {
        switch (name) {
            case "PINK": return 0xFFEC00B8;
            case "BLUE": return 0xFF00B7EC;
            case "RED": return 0xFFEC3500;
            case "GREEN": return 0xFF1DEC00;
            case "YELLOW": return 0xFFE9EC00;
            case "PURPLE": return 0xFF7B00EC;
            default: return 0xFFECECEC;
        }
    }

    public static final class BossBar extends Mod {
        private final Setting.Bool shadow = add(new Setting.Bool("shadow", "Text shadow", true));
        private final Setting.Bool background = add(new Setting.Bool("background", "Background", false));
        private final Setting.Bool customColor = add(new Setting.Bool("customColor", "Custom bar color", false));
        private final Setting.Color color = add(new Setting.Color("color", "Bar color", 0xFFEC00B8), customColor);
        private final Setting.Color track = add(new Setting.Color("track", "Track color", 0x88000000));
        private final Setting.Int width = add(new Setting.Int("width", "Width", 182, 60, 400));
        private final Setting.Int height = add(new Setting.Int("height", "Bar height", 5, 2, 12));
        private final Setting.Int max = add(new Setting.Int("max", "Max bars", 4, 1, 10));
        private final Setting.Bool showText = add(new Setting.Bool("text", "Show name", true));
        private final Setting.Bool showPercent = add(new Setting.Bool("percent", "Show percent", false));
        private final Setting.Bool smooth = add(new Setting.Bool("smooth", "Animate progress", true));
        private final List<Float> shown = new ArrayList<Float>();

        public BossBar() {
            super("bossbar", "Boss Bar", "Move, resize and restyle boss bars", Category.HUD, true);
            hud = new HudElement(Anchor.TOP_CENTER, 0, 2);
        }

        @Override public void onTick(GameState s) { fx.hideVanillaBossBar = true; }

        @Override public void renderHud(UiRenderer r, GameState s) {
            List<GameState.BossBar> bars = s.bossBars();
            if (bars.isEmpty()) {
                if (!previewing()) return;
                bars = Collections.singletonList(new GameState.BossBar("Ender Dragon", 0.7f, "PINK"));
            }
            int n = Math.min(max.val(), bars.size());
            float rowH = (showText.on() ? r.fontHeight() + 2 : 0) + height.val() + 4;
            float w = width.val(), h = n * rowH;
            float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), w, h);
            r.push(p[0], p[1], hud.scale());
            if (background.on()) r.rect(-3, -2, w + 6, h + 2, fade(hud, 0x66000000, 1f), 3f);
            while (shown.size() < n) shown.add(bars.get(shown.size()).progress);
            float y = 0;
            for (int i = 0; i < n; i++) {
                GameState.BossBar b = bars.get(i);
                float prog = b.progress;
                if (smooth.on() && !fx.reducedMotion) prog = shown.get(i) + (b.progress - shown.get(i)) * 0.2f;
                shown.set(i, prog);
                if (showText.on()) {
                    String t = b.name + (showPercent.on() ? "  " + Math.round(b.progress * 100) + "%" : "");
                    r.text(t, (w - r.textWidth(t)) / 2f, y, fade(hud, 0xFFFFFFFF, 1f), shadow.on());
                    y += r.fontHeight() + 2;
                }
                r.rect(0, y, w, height.val(), fade(hud, track.argb(), 1f), height.val() / 2f);
                r.rect(0, y, w * Math.max(0, Math.min(1, prog)), height.val(), fade(hud, customColor.on() ? color.argb() : barColor(b.color), 1f), height.val() / 2f);
                y += height.val() + 4;
            }
            r.pop();
        }
    }

    public static final class Titles extends Mod {
        private final Setting.Color color = add(new Setting.Color("color", "Title color (white = keep)", 0xFFFFFFFF));
        private final Setting.Bool shadow = add(new Setting.Bool("shadow", "Text shadow", true));
        private final Setting.Bool background = add(new Setting.Bool("background", "Background", false));
        private final Setting.Dbl titleScale = add(new Setting.Dbl("titleScale", "Title size", 3.0, 1.0, 6.0));
        private final Setting.Dbl subtitleScale = add(new Setting.Dbl("subtitleScale", "Subtitle size", 1.5, 0.5, 4.0));
        private final Setting.Int spacing = add(new Setting.Int("spacing", "Subtitle spacing", 4, 0, 30));
        private final Setting.Bool fade = add(new Setting.Bool("fade", "Fade in/out", true));

        public Titles() {
            super("titles", "Titles", "Move, resize and restyle titles and subtitles", Category.HUD, true);
            hud = new HudElement(Anchor.CENTER, 0, -30);
        }

        @Override public void onTick(GameState s) { fx.hideVanillaTitle = true; }

        @Override public void renderHud(UiRenderer r, GameState s) {
            GameState.Title t = s.title();
            if (t == null) {
                if (!previewing()) return;
                t = new GameState.Title("Victory!", "Kite title preview", 1f);
            }
            float alpha = fade.on() && !fx.reducedMotion ? t.alpha : 1f;
            if (alpha <= 0.02f) return;
            float ts = (float) titleScale.val(), ss = (float) subtitleScale.val();
            float tw = r.textWidth(t.title) * ts, sw = t.subtitle.isEmpty() ? 0 : r.textWidth(t.subtitle) * ss;
            float w = Math.max(tw, sw) + 8, h = r.fontHeight() * ts + (t.subtitle.isEmpty() ? 0 : spacing.val() + r.fontHeight() * ss) + 6;
            float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), w, h);
            r.push(p[0], p[1], hud.scale());
            if (background.on()) r.rect(0, 0, w, h, fade(hud, 0x66000000, alpha), 4f);
            r.push((w - tw) / 2f, 3, ts);
            r.text(t.title, 0, 0, fade(hud, color.argb(), alpha), shadow.on());
            r.pop();
            if (!t.subtitle.isEmpty()) {
                r.push((w - sw) / 2f, 3 + r.fontHeight() * ts + spacing.val(), ss);
                r.text(t.subtitle, 0, 0, fade(hud, 0xFFFFFFFF, alpha), shadow.on());
                r.pop();
            }
            r.pop();
        }
    }

    public static final class Scoreboard extends HudTextMod {
        private final Setting.Bool numbers = add(new Setting.Bool("numbers", "Show score numbers", false));
        private final Setting.Color numberColor = add(new Setting.Color("numberColor", "Number color", 0xFFFF5555), numbers);
        private final Setting.Bool header = add(new Setting.Bool("header", "Show title", true));
        private final Setting.Bool hideFooter = add(new Setting.Bool("hideFooter", "Hide last line (server ad)", false));
        private final Setting.Int maxLines = add(new Setting.Int("maxLines", "Max lines", 15, 1, 15));
        private final Setting.Int lineSpacing = add(new Setting.Int("lineSpacing", "Line spacing", 1, 0, 6));
        private GameState.Sidebar last;

        public Scoreboard() {
            super("scoreboard", "Scoreboard", "Move, resize and restyle the sidebar scoreboard", Anchor.MIDDLE_RIGHT, -2, 0);
        }

        @Override public void onTick(GameState s) { fx.hideVanillaScoreboard = true; }

        @Override protected List<String> lines(GameState s) { return Collections.emptyList(); }

        @Override public void renderHud(UiRenderer r, GameState s) {
            GameState.Sidebar sb = s.sidebar();
            if (sb == null) {
                if (!previewing()) return;
                sb = new GameState.Sidebar("KITE", Arrays.asList("Kills: 3", "Coins: 1,240", "", "play.example.net"), Arrays.asList("4", "3", "2", "1"));
            }
            last = sb;
            int n = Math.min(maxLines.val(), sb.names.size() - (hideFooter.on() && !sb.names.isEmpty() ? 1 : 0));
            float lh = r.fontHeight() + lineSpacing.val();
            float w = header.on() ? r.textWidth(sb.title) : 0;
            for (int i = 0; i < n; i++) {
                float lw = r.textWidth(sb.names.get(i)) + (numbers.on() ? r.textWidth(" " + sb.scores.get(i)) + 6 : 0);
                w = Math.max(w, lw);
            }
            w += PAD * 2;
            float h = n * lh + PAD * 2 + (header.on() ? lh + 2 : 0);
            hud.natural(w, h);
            w = Math.max(w, hud.boxWidth());
            h = Math.max(h, hud.boxHeight());
            float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), w, h);
            r.push(p[0], p[1], hud.scale());
            if (background.on()) r.rect(0, 0, w, h, withOpacity(bgColor.argb()), 3f);
            if (border.on()) outline(r, 0, 0, w, h, withOpacity(borderColor.argb()));
            float y = PAD;
            if (header.on()) {
                if (background.on()) r.rect(0, 0, w, lh + PAD + 1, withOpacity(0x33000000), 3f);
                r.text(sb.title, (w - r.textWidth(sb.title)) / 2f, y, withOpacity(textColor.argb()), shadow.on());
                y += lh + 2;
            }
            for (int i = 0; i < n; i++) {
                float nameRoom = w - PAD * 2 - (numbers.on() ? r.textWidth(" " + sb.scores.get(i)) + 6 : 0);
                r.text(sb.names.get(i), PAD + alignOffset(nameRoom, r.textWidth(sb.names.get(i))), y, withOpacity(textColor.argb()), shadow.on());
                if (numbers.on()) {
                    String sc = sb.scores.get(i);
                    r.text(sc, w - PAD - r.textWidth(sc), y, withOpacity(numberColor.argb()), shadow.on());
                }
                y += lh;
            }
            r.pop();
        }

        GameState.Sidebar lastShown() { return last; }
    }
}
