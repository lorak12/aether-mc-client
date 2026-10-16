package gg.kite.mods;

import gg.kite.core.Events;
import gg.kite.core.GameState;
import gg.kite.core.HudElement;
import gg.kite.core.Mod;
import gg.kite.core.Setting;
import gg.kite.core.UiRenderer;
import java.util.EnumMap;
import java.util.Map;

/** WASD, jump, sneak, sprint and mouse buttons, highlighted while held (with an optional fade). */
public final class Keystrokes extends Mod {
    private final Setting.Color textIdle = add(new Setting.Color("textIdle", "Idle text", 0xFFFFFFFF));
    private final Setting.Color textPressed = add(new Setting.Color("textPressed", "Pressed text", 0xFF000000));
    private final Setting.Color idle = add(new Setting.Color("idle", "Idle color", 0x66000000));
    private final Setting.Color pressed = add(new Setting.Color("pressed", "Pressed color", 0xAAFFFFFF));
    private final Setting.Bool border = add(new Setting.Bool("border", "Border", false));
    private final Setting.Color borderColor = add(new Setting.Color("borderColor", "Border color", 0x88FFFFFF), border);
    private final Setting.Int radius = add(new Setting.Int("radius", "Corner radius", 2, 0, 10));
    private final Setting.Int size = add(new Setting.Int("size", "Key size", 20, 12, 40));
    private final Setting.Int gap = add(new Setting.Int("gap", "Spacing", 2, 0, 8));
    private final Setting.Bool wasd = add(new Setting.Bool("wasd", "Show WASD", true));
    private final Setting.Bool mouse = add(new Setting.Bool("mouse", "Show mouse buttons", true));
    private final Setting.Bool cps = add(new Setting.Bool("cps", "CPS in mouse buttons", true), mouse);
    private final Setting.Bool space = add(new Setting.Bool("space", "Show space bar", true));
    private final Setting.Bool modifiers = add(new Setting.Bool("modifiers", "Show Shift and Ctrl", false));
    private final Setting.Bool animate = add(new Setting.Bool("animate", "Fade key presses", true));
    private final Setting.Int fadeMs = add(new Setting.Int("fadeMs", "Fade time (ms)", 120, 20, 500), animate);

    private final SimpleHudMods.Clicks left = new SimpleHudMods.Clicks(), right = new SimpleHudMods.Clicks();
    /** 0-1 "how pressed" per key, eased toward the real state each frame. */
    private final Map<GameState.Key, Float> level = new EnumMap<GameState.Key, Float>(GameState.Key.class);
    private long lastFrame;

    public Keystrokes() {
        super("keystrokes", "Keystrokes", "Movement keys, jump, sneak, sprint and mouse buttons with CPS", Category.HUD, true);
        hud = new HudElement(HudElement.Anchor.BOTTOM_LEFT, 8, -8);
    }

    @Override protected void onEnable() {
        level.clear();
        events.on(Events.Click.class, this, e -> {
            if (e.button == 0) left.record(e.timeMs);
            else if (e.button == 1) right.record(e.timeMs);
        });
    }

    float width() { return size.val() * 3 + gap.val() * 2; }

    float height() {
        int s = size.val(), g = gap.val();
        float h = 0;
        if (wasd.on()) h += s * 2 + g;
        if (mouse.on()) h += (h > 0 ? g : 0) + s;
        if (modifiers.on()) h += (h > 0 ? g : 0) + s * 0.6f;
        if (space.on()) h += (h > 0 ? g : 0) + s * 0.5f;
        return Math.max(h, 1);
    }

    @Override
    public void renderHud(UiRenderer r, GameState s) {
        long now = s.nowMillis();
        float dt = lastFrame == 0 ? 1000 : Math.min(1000, now - lastFrame);
        lastFrame = now;
        float step = Math.min(1f, animate.on() && !fx.reducedMotion ? dt / fadeMs.val() : 1f);
        for (GameState.Key k : GameState.Key.values()) {
            float cur = level.containsKey(k) ? level.get(k) : 0f, target = s.isDown(k) ? 1f : 0f;
            level.put(k, cur + (target - cur) * step);
        }
        float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), width(), height());
        r.push(p[0], p[1], hud.scale());
        int sz = size.val(), g = gap.val();
        float stepPx = sz + g, y = 0;
        if (wasd.on()) {
            cell(r, GameState.Key.FORWARD, "W", stepPx, 0, sz, sz);
            cell(r, GameState.Key.LEFT, "A", 0, stepPx, sz, sz);
            cell(r, GameState.Key.BACK, "S", stepPx, stepPx, sz, sz);
            cell(r, GameState.Key.RIGHT, "D", stepPx * 2, stepPx, sz, sz);
            y = stepPx * 2;
        }
        if (mouse.on()) {
            float w = (width() - g) / 2f;
            String l = cps.on() ? left.rate(now, 1) > 0 ? Math.round(left.rate(now, 1)) + " CPS" : "LMB" : "LMB";
            String rr = cps.on() ? right.rate(now, 1) > 0 ? Math.round(right.rate(now, 1)) + " CPS" : "RMB" : "RMB";
            cell(r, GameState.Key.ATTACK, l, 0, y, w, sz);
            cell(r, GameState.Key.USE, rr, w + g, y, w, sz);
            y += stepPx;
        }
        if (modifiers.on()) {
            float w = (width() - g) / 2f, h = sz * 0.6f;
            cell(r, GameState.Key.SNEAK, "Shift", 0, y, w, h);
            cell(r, GameState.Key.SPRINT, "Ctrl", w + g, y, w, h);
            y += h + g;
        }
        if (space.on()) {
            float h = sz * 0.5f;
            cell(r, GameState.Key.JUMP, "", 0, y, width(), h);
            float bw = width() * 0.4f;
            r.rect((width() - bw) / 2f, y + h / 2f - 0.5f, bw, 1, fade(lerp(textIdle.argb(), textPressed.argb(), level(GameState.Key.JUMP))), 0);
        }
        r.pop();
    }

    private float level(GameState.Key k) { return level.containsKey(k) ? level.get(k) : 0f; }

    private void cell(UiRenderer r, GameState.Key k, String label, float x, float y, float w, float h) {
        float t = level(k);
        if (border.on()) HudTextMod.outline(r, x - 1, y - 1, w + 2, h + 2, fade(borderColor.argb()));
        r.rect(x, y, w, h, fade(lerp(idle.argb(), pressed.argb(), t)), radius.val());
        if (label.isEmpty()) return;
        float tx = x + (w - r.textWidth(label)) / 2f, ty = y + (h - r.fontHeight()) / 2f + 1;
        r.text(label, tx, ty, fade(lerp(textIdle.argb(), textPressed.argb(), t)), false);
    }

    /** Per-channel ARGB blend. */
    static int lerp(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int out = 0;
        for (int sh = 0; sh < 32; sh += 8) {
            int ca = (a >>> sh) & 0xFF, cb = (b >>> sh) & 0xFF;
            out |= (Math.round(ca + (cb - ca) * t) & 0xFF) << sh;
        }
        return out;
    }

    private int fade(int argb) {
        int a = Math.round(((argb >>> 24) & 0xFF) * hud.opacity());
        return (a << 24) | (argb & 0xFFFFFF);
    }
}
