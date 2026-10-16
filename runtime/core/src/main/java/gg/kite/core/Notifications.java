package gg.kite.core;

import java.util.ArrayList;
import java.util.List;

/** Central toast queue. Mods post, the mod manager renders (a screen corner, newest last, fades out). */
public final class Notifications {
    public enum Level { INFO, WARN, ERROR }
    public enum Corner { TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT, BOTTOM_LEFT }

    public static final class Toast {
        public final String text;
        public final Level level;
        public final long expiresMs;
        Toast(String text, Level level, long expiresMs) { this.text = text; this.level = level; this.expiresMs = expiresMs; }
    }

    private static final int FADE_MS = 400;

    private final List<Toast> toasts = new ArrayList<Toast>();

    // Presentation, set every tick by the Notifications mod; reset to defaults when it is off.
    public Corner corner;
    public float durationScale;
    public int max;
    /** Do not disturb: only errors are shown. */
    public boolean doNotDisturb;
    /** Minimum level that is shown at all. */
    public Level minLevel;
    public boolean fade;

    public Notifications() { resetConfig(); }

    public void resetConfig() {
        corner = Corner.TOP_RIGHT;
        durationScale = 1f;
        max = 5;
        doNotDisturb = false;
        minLevel = Level.INFO;
        fade = true;
    }

    public synchronized void post(long nowMs, Level level, String text, int durationMs) {
        toasts.add(new Toast(text, level, nowMs + Math.round(durationMs * durationScale)));
        while (toasts.size() > Math.max(1, max)) toasts.remove(0);
    }

    public void info(long nowMs, String text) { post(nowMs, Level.INFO, text, 3000); }
    public void warn(long nowMs, String text) { post(nowMs, Level.WARN, text, 5000); }
    public void error(long nowMs, String text) { post(nowMs, Level.ERROR, text, 6000); }

    /** Toasts still alive at {@code nowMs} that the current filters let through. */
    public synchronized List<Toast> active(long nowMs) {
        for (int i = toasts.size() - 1; i >= 0; i--) if (toasts.get(i).expiresMs <= nowMs) toasts.remove(i);
        List<Toast> out = new ArrayList<Toast>();
        for (Toast t : toasts) {
            if (t.level.ordinal() < minLevel.ordinal()) continue;
            if (doNotDisturb && t.level != Level.ERROR) continue;
            out.add(t);
        }
        return out;
    }

    public void render(UiRenderer r, long nowMs) {
        List<Toast> list = active(nowMs);
        boolean bottom = corner == Corner.BOTTOM_LEFT || corner == Corner.BOTTOM_RIGHT;
        boolean left = corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT;
        float h = r.fontHeight() + 8;
        float y = bottom ? r.screenHeight() - 6 - h : 6;
        for (int i = 0; i < list.size(); i++) {
            Toast t = list.get(bottom ? list.size() - 1 - i : i);
            float alpha = fade ? Math.min(1f, (t.expiresMs - nowMs) / (float) FADE_MS) : 1f;
            int a = Math.round(255 * alpha);
            float w = r.textWidth(t.text) + 12;
            float x = left ? 6 : r.screenWidth() - w - 6;
            r.rect(x, y, w, h, (Math.round(0xC0 * alpha) << 24) | 0x101010, 3f);
            r.rect(x, y, 2, h, (a << 24) | accent(t.level), 1f);
            r.text(t.text, x + 7, y + 4, (a << 24) | 0xFFFFFF, true);
            y += bottom ? -(h + 3) : h + 3;
        }
    }

    private static int accent(Level l) {
        switch (l) {
            case WARN: return 0xFFB020;
            case ERROR: return 0xFF4040;
            default: return 0x40C0FF;
        }
    }
}
