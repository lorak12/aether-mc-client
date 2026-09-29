package gg.aether.mods;

import gg.aether.core.GameState;
import gg.aether.core.HudElement;
import gg.aether.core.Mod;
import gg.aether.core.Setting;
import gg.aether.core.UiRenderer;

/** WASD + space + mouse buttons, highlighted while held. */
public final class Keystrokes extends Mod {
    private final Setting.Color idle = add(new Setting.Color("idle", "Idle color", 0x66000000));
    private final Setting.Color pressed = add(new Setting.Color("pressed", "Pressed color", 0xAAFFFFFF));
    private final Setting.Color textIdle = add(new Setting.Color("textIdle", "Idle text", 0xFFFFFFFF));
    private final Setting.Color textPressed = add(new Setting.Color("textPressed", "Pressed text", 0xFF000000));
    private final Setting.Bool mouse = add(new Setting.Bool("mouse", "Show mouse buttons", true));
    private final Setting.Bool space = add(new Setting.Bool("space", "Show space bar", true));

    static final int SIZE = 20, GAP = 2;

    public Keystrokes() {
        super("keystrokes", "Keystrokes", "Shows held movement keys and mouse buttons", Category.HUD, true);
        hud = new HudElement(HudElement.Anchor.BOTTOM_LEFT, 8, -8);
    }

    /** Unscaled size of the widget. */
    float width() { return SIZE * 3 + GAP * 2; }
    float height() {
        float h = SIZE * 2 + GAP;
        if (mouse.on()) h += GAP + SIZE;
        if (space.on()) h += GAP + 10;
        return h;
    }

    @Override
    public void renderHud(UiRenderer r, GameState s) {
        float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), width(), height());
        r.push(p[0], p[1], hud.scale());
        float step = SIZE + GAP;
        cell(r, s.isDown(GameState.Key.FORWARD), "W", step, 0, SIZE, SIZE);
        cell(r, s.isDown(GameState.Key.LEFT), "A", 0, step, SIZE, SIZE);
        cell(r, s.isDown(GameState.Key.BACK), "S", step, step, SIZE, SIZE);
        cell(r, s.isDown(GameState.Key.RIGHT), "D", step * 2, step, SIZE, SIZE);
        float y = step * 2;
        if (mouse.on()) {
            float w = (width() - GAP) / 2f;
            cell(r, s.isDown(GameState.Key.ATTACK), "LMB", 0, y, w, SIZE);
            cell(r, s.isDown(GameState.Key.USE), "RMB", w + GAP, y, w, SIZE);
            y += step;
        }
        if (space.on()) cell(r, s.isDown(GameState.Key.JUMP), "", 0, y, width(), 10);
        r.pop();
    }

    private void cell(UiRenderer r, boolean down, String label, float x, float y, float w, float h) {
        r.rect(x, y, w, h, fade(down ? pressed.argb() : idle.argb()), 2f);
        if (label.isEmpty()) return;
        float tx = x + (w - r.textWidth(label)) / 2f, ty = y + (h - r.fontHeight()) / 2f;
        r.text(label, tx, ty, fade(down ? textPressed.argb() : textIdle.argb()), false);
    }

    private int fade(int argb) {
        int a = Math.round(((argb >>> 24) & 0xFF) * hud.opacity());
        return (a << 24) | (argb & 0xFFFFFF);
    }
}
