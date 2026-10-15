package gg.aether.core.ui;

import gg.aether.core.ClientHost;
import gg.aether.core.Keys;
import gg.aether.core.Setting;
import gg.aether.core.UiRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static gg.aether.core.ui.Theme.*;

/**
 * Immediate-mode widget toolkit shared by the Aether screens. Each frame a view calls {@link #begin}, draws its
 * widgets (which record clickable regions), then {@link #end} (which draws the open popup on top). Input methods
 * hit the regions recorded by the latest frame, topmost first. Coordinates are GUI-scaled.
 */
public final class Ui {
    public interface Action { void click(float mx, float my, int button); }
    public interface Drag { void to(float mx, float my); }
    public interface FloatSetter { void set(float frac); }

    /** Text a field edits. */
    public interface Field {
        String get();
        void set(String v);
        /** Longest text the field takes; typing and pasting stop there. */
        default int max() { return Integer.MAX_VALUE; }
    }

    /** A field editing a text setting. */
    public static Field field(final Setting.Text t) {
        return new Field() {
            @Override public String get() { return t.str(); }
            @Override public void set(String v) { t.set(v); }
        };
    }

    /** A plain editable string (search boxes, rename fields). */
    public static final class Text implements Field {
        public String value;
        public final int max;
        public Text(String value, int max) { this.value = value; this.max = max; }
        @Override public String get() { return value; }
        @Override public void set(String v) { value = v.length() > max ? v.substring(0, max) : v; }
        @Override public int max() { return max; }
    }

    /** Something drawn above everything else (dropdown, colour picker, editor). Clicking outside closes it. */
    public interface Popup {
        void render(Ui ui);
        default void closed() {}
    }

    /** Scroll position of one scrollable area; eases toward its target. */
    public static final class Scroll {
        float value, target, max;
        public float value() { return value; }
        public void reset() { value = target = 0; }
        public void toTop() { target = 0; }
    }

    public enum Style { PRIMARY, SECONDARY, GHOST, DANGER, STORE }

    /** Interface sounds (host sound name, pitch, loudness before the volume setting). */
    public enum Sound {
        CLICK("click", 1f, 0.32f), SELECT("select", 1.3f, 0.24f), TOGGLE_ON("toggle_on", 1.55f, 0.4f), TOGGLE_OFF("toggle_off", 1.2f, 0.4f),
        TICK("tick", 1f, 0.16f), OPEN("open", 1.5f, 0.35f), CLOSE("close", 1.5f, 0.3f);

        final String id;
        final float pitch, loudness;
        Sound(String id, float pitch, float loudness) {
            this.id = id;
            this.pitch = pitch;
            this.loudness = loudness;
        }
    }

    public static final int ROW = 22;

    public UiRenderer r;
    private final ClientHost host;
    private final List<Hit> hits = new ArrayList<Hit>();
    private final List<Area> areas = new ArrayList<Area>();
    private float mouseX, mouseY;
    private long frameTime, lastFrame;
    private float dt;
    private boolean reducedMotion;

    private float[] clip;
    private Popup popup;
    private boolean popupPass;
    private Drag dragging;

    private Object focus;
    private Field focusField;
    private Runnable focusCommit;
    private String focusAccept;
    /** Caret and selection anchor in the focused field (character indexes); equal means nothing is selected. */
    private int caret, anchor;
    /** First character drawn in the focused field: it scrolls sideways to keep the caret in view. */
    private int viewStart;
    /** Last click in a field, for double-click (select all). */
    private Object lastFieldClick;
    private long lastFieldClickAt;
    private float lastFieldClickX;
    private Setting.Key listening;
    private boolean ctrl, shift;

    private String confirming;
    private long confirmUntil;
    private float soundVolume = 0.7f;
    /** A sound already played for the click being handled (so the default click stays quiet). */
    private boolean clickSounded;
    private int tickStep = -1;
    private long tickAt;

    private String flash;
    private int flashColor;
    private long flashUntil;

    /** Colours saved in the colour picker, shared by every colour setting; null hides the saved row. */
    private List<Integer> palette;

    public Ui(ClientHost host) { this.host = host; }

    /** Use {@code saved} (kept by the caller, e.g. {@link gg.aether.core.ClientSettings#savedColors}) as the picker's saved colours. */
    public void palette(List<Integer> saved) { palette = saved; }
    List<Integer> palette() { return palette; }

    /** Ctrl (or Cmd) is held. */
    public boolean ctrlDown() { return ctrl; }

    // ---- frame ------------------------------------------------------------------------------------------------------

    public void begin(UiRenderer r, boolean reducedMotion) {
        this.r = r;
        this.reducedMotion = reducedMotion;
        hits.clear();
        areas.clear();
        clip = null;
        frameTime = System.currentTimeMillis();
        dt = lastFrame == 0 ? 0 : Math.min(0.1f, (frameTime - lastFrame) / 1000f);
        lastFrame = frameTime;
        r.font(UiRenderer.Font.UI);
    }

    /** Draws the open popup above the frame. */
    public void end() {
        if (popup != null) {
            r.layer();
            Hit outside = new Hit(-10000, -10000, 30000, 30000, (mx, my, b) -> closePopup(), null);
            outside.sound = Sound.CLOSE;
            hits.add(outside);
            popupPass = true;
            Popup p = popup;
            p.render(this);
            popupPass = false;
        }
        r.font(UiRenderer.Font.VANILLA);
    }

    /** Interface sound volume 0..1 (0 = silent); views set it from the client settings every frame. */
    public void soundVolume(float v) { soundVolume = Math.max(0f, Math.min(1f, v)); }

    public void play(Sound s) { play(s, s.pitch); }

    public void play(Sound s, float pitch) {
        clickSounded = true;
        if (host == null || soundVolume <= 0f) return;
        host.playUiSound(s.id, pitch, s.loudness * soundVolume);
    }

    /** A pitched tick each time a dragged value crosses one of 24 steps (sliders), at most every 35 ms. */
    public void tick(float frac) {
        int step = Math.round(Math.max(0f, Math.min(1f, frac)) * 24);
        long now = System.currentTimeMillis();
        if (step == tickStep || now - tickAt < 35) return;
        tickStep = step;
        tickAt = now;
        play(Sound.TICK, 0.75f + step / 24f * 0.9f);
    }

    public long now() { return frameTime; }
    /** Seconds since the previous frame (0 on the first). */
    public float dt() { return dt; }
    public boolean reducedMotion() { return reducedMotion; }
    public float mouseX() { return mouseX; }
    public float mouseY() { return mouseY; }

    // ---- input ------------------------------------------------------------------------------------------------------

    public void mouseMoved(float x, float y) {
        mouseX = x;
        mouseY = y;
    }

    /** Returns true if a widget took the click. */
    public boolean mouseDown(float x, float y, int button) {
        mouseMoved(x, y);
        if (listening != null) {
            listening = null;
            return true;
        }
        boolean hadFocus = focus != null;
        // Clicking the focused field again moves its caret; it must not commit and refocus.
        Hit top = null;
        for (int i = hits.size() - 1; i >= 0 && top == null; i--) if (hits.get(i).contains(x, y)) top = hits.get(i);
        if (top == null || top.field == null || top.field != focus) blur();
        tickStep = -1;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.contains(x, y)) continue;
            clickSounded = false;
            h.action.click(x, y, button);
            if (!clickSounded && h.sound != null) play(h.sound);
            if (h.drag != null && (button == 0 || h.anyButton)) dragging = h.drag;
            return true;
        }
        return hadFocus;
    }

    public boolean mouseDrag(float x, float y) {
        mouseMoved(x, y);
        if (dragging == null) return false;
        dragging.to(x, y);
        return true;
    }

    public boolean mouseUp(float x, float y, int button) {
        mouseMoved(x, y);
        boolean was = dragging != null;
        dragging = null;
        return was;
    }

    public boolean dragging() { return dragging != null; }

    /** Wheel over a scroll area. Positive delta = up. */
    public boolean scroll(float x, float y, float delta) {
        mouseMoved(x, y);
        for (int i = areas.size() - 1; i >= 0; i--) {
            Area a = areas.get(i);
            if (x < a.x || y < a.y || x >= a.x + a.w || y >= a.y + a.h) continue;
            if (popup != null && !a.popup) return true;
            a.s.target = Math.max(0, Math.min(a.s.max, a.s.target - delta * 26));
            if (reducedMotion) a.s.value = a.s.target;
            return true;
        }
        return false;
    }

    /** True if the key was used by a focused field or a keybind being set. */
    public boolean keyDown(int code) {
        if (code == Keys.LEFT_CONTROL || code == Keys.RIGHT_CONTROL || code == Keys.LEFT_SUPER || code == Keys.RIGHT_SUPER) ctrl = true;
        if (code == Keys.LEFT_SHIFT || code == Keys.RIGHT_SHIFT) shift = true;
        if (listening != null) {
            // Backspace/Delete unbind, Escape cancels, anything else binds unless the setting refuses it.
            if (code == Keys.BACKSPACE || code == Keys.DELETE) listening.unbind();
            else if (code != Keys.ESCAPE && !listening.set(code)) flash(Keys.name(code) + " can't be used for " + listening.label, WARNING);
            listening = null;
            return true;
        }
        if (focusField == null) {
            if (code == Keys.ESCAPE && popup != null) {
                play(Sound.CLOSE);
                closePopup();
                return true;
            }
            return false;
        }
        String v = focusField.get();
        clampCaret(v);
        int lo = Math.min(caret, anchor), hi = Math.max(caret, anchor);
        boolean sel = lo != hi;
        if (code == Keys.BACKSPACE) {
            if (sel) replace(lo, hi, "");
            else if (caret > 0) replace(ctrl ? wordLeft(v, caret) : caret - 1, caret, "");
        } else if (code == Keys.DELETE) {
            if (sel) replace(lo, hi, "");
            else if (caret < v.length()) replace(caret, ctrl ? wordRight(v, caret) : caret + 1, "");
        } else if (code == Keys.LEFT || code == Keys.RIGHT) {
            boolean left = code == Keys.LEFT;
            // Without Shift an arrow first collapses a selection to its edge.
            if (sel && !shift && !ctrl) caret = left ? lo : hi;
            else if (left) caret = ctrl ? wordLeft(v, caret) : Math.max(0, caret - 1);
            else caret = ctrl ? wordRight(v, caret) : Math.min(v.length(), caret + 1);
            if (!shift) anchor = caret;
        } else if (code == Keys.HOME || code == Keys.UP) {
            caret = 0;
            if (!shift) anchor = caret;
        } else if (code == Keys.END || code == Keys.DOWN) {
            caret = v.length();
            if (!shift) anchor = caret;
        } else if (code == Keys.ENTER || code == Keys.KP_ENTER || code == Keys.ESCAPE || code == Keys.TAB) {
            blur();
        } else if (ctrl && code == Keys.A) {
            anchor = 0;
            caret = v.length();
        } else if (ctrl && (code == Keys.C || code == Keys.X)) {
            if (sel) {
                copy(v.substring(lo, hi));
                if (code == Keys.X) replace(lo, hi, "");
            }
        } else if (ctrl && code == Keys.V) {
            insert(paste());
        }
        return true;
    }

    public void keyUp(int code) {
        if (code == Keys.LEFT_CONTROL || code == Keys.RIGHT_CONTROL || code == Keys.LEFT_SUPER || code == Keys.RIGHT_SUPER) ctrl = false;
        if (code == Keys.LEFT_SHIFT || code == Keys.RIGHT_SHIFT) shift = false;
    }

    public boolean charTyped(char c) {
        if (focusField == null || c < 32 || c == 127) return false;
        insert(String.valueOf(c));
        return true;
    }

    /** Type {@code s} at the caret, replacing the selection; characters the field doesn't accept are dropped. */
    private void insert(String s) {
        StringBuilder ok = new StringBuilder();
        for (char c : s.toCharArray()) if (c >= 32 && c != 127 && (focusAccept == null || focusAccept.indexOf(c) >= 0)) ok.append(c);
        String v = focusField.get();
        clampCaret(v);
        int lo = Math.min(caret, anchor), hi = Math.max(caret, anchor);
        int room = Math.max(0, focusField.max() - (v.length() - (hi - lo)));
        if (ok.length() == 0 || room == 0) return;
        replace(lo, hi, ok.length() > room ? ok.substring(0, room) : ok.toString());
    }

    /** Replace characters {@code from..to} of the focused field with {@code s} and put the caret after it. */
    private void replace(int from, int to, String s) {
        String v = focusField.get();
        focusField.set(v.substring(0, from) + s + v.substring(to));
        caret = anchor = Math.min(from + s.length(), focusField.get().length());
    }

    private void clampCaret(String v) {
        caret = Math.max(0, Math.min(caret, v.length()));
        anchor = Math.max(0, Math.min(anchor, v.length()));
    }

    static boolean wordChar(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    /** Start of the word left of {@code i} (Ctrl+Left, Ctrl+Backspace). */
    static int wordLeft(String v, int i) {
        while (i > 0 && !wordChar(v.charAt(i - 1))) i--;
        while (i > 0 && wordChar(v.charAt(i - 1))) i--;
        return i;
    }

    /** End of the word right of {@code i} (Ctrl+Right, Ctrl+Delete). */
    static int wordRight(String v, int i) {
        while (i < v.length() && !wordChar(v.charAt(i))) i++;
        while (i < v.length() && wordChar(v.charAt(i))) i++;
        return i;
    }

    /** A text field or keybind is taking keys: hosts must not treat keys as shortcuts. */
    public boolean typing() { return focusField != null || listening != null; }

    public boolean focused(Object key) { return key != null && key == focus; }

    /** Start editing {@code f}; {@code commit} runs when it loses focus. {@code accept} limits characters (null = any). */
    public void focus(Object key, Field f, String accept, Runnable commit) {
        blur();
        focus = key;
        focusField = f;
        focusAccept = accept;
        focusCommit = commit;
        caret = anchor = f.get().length();
        viewStart = 0;
    }

    /** Like {@link #focus}, but with all the text selected, so the first keystroke replaces it. */
    public void focusSelected(Object key, Field f, String accept, Runnable commit) {
        focus(key, f, accept, commit);
        anchor = 0;
    }

    public void blur() {
        Runnable c = focusCommit;
        focus = null;
        focusField = null;
        focusCommit = null;
        focusAccept = null;
        if (c != null) c.run();
    }

    public void openPopup(Popup p) {
        closePopup();
        popup = p;
        play(Sound.OPEN);
    }

    public void closePopup() {
        Popup p = popup;
        popup = null;
        if (p != null) p.closed();
    }

    public boolean popupOpen() { return popup != null; }

    // ---- regions ----------------------------------------------------------------------------------------------------

    /** Register a clickable region (topmost = registered last). Clicking it plays {@link Sound#CLICK}. */
    public void hit(float x, float y, float w, float h, Action a) { hits.add(new Hit(x, y, w, h, a, clip)); }

    /** A clickable region with its own sound (null = silent). */
    public void hit(float x, float y, float w, float h, Sound sound, Action a) {
        Hit hit = new Hit(x, y, w, h, a, clip);
        hit.sound = sound;
        hits.add(hit);
    }

    /** A silent region that only stops clicks reaching what is underneath (panels, popup backgrounds). */
    public void absorb(float x, float y, float w, float h) { hit(x, y, w, h, null, (mx, my, b) -> { }); }

    /** A draggable region; silent (drags give their own feedback, see {@link #tick}). */
    public void hitDrag(float x, float y, float w, float h, Action a, Drag drag) {
        Hit hit = new Hit(x, y, w, h, a, clip);
        hit.drag = drag;
        hit.sound = null;
        hits.add(hit);
    }

    /** {@link #hitDrag} that also drags with the right (or middle) button held, e.g. erasing in a pixel editor. */
    public void hitDragAnyButton(float x, float y, float w, float h, Action a, Drag drag) {
        hitDrag(x, y, w, h, a, drag);
        hits.get(hits.size() - 1).anyButton = true;
    }

    /** Mouse over the region (and not hidden behind a popup or outside the current clip). */
    public boolean over(float x, float y, float w, float h) {
        if (popup != null && !popupPass) return false;
        if (clip != null && (mouseX < clip[0] || mouseY < clip[1] || mouseX >= clip[0] + clip[2] || mouseY >= clip[1] + clip[3])) return false;
        return mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h;
    }

    public void clip(float x, float y, float w, float h) {
        clip = new float[] { x, y, w, h };
        r.clip(x, y, w, h);
    }

    public void unclip() {
        clip = null;
        r.unclip();
    }

    /**
     * Begin a scrollable area of {@code contentH} inside (x, y, w, h): clips and returns the scroll offset to subtract
     * from content y. Pair with {@link #endScroll}.
     */
    public float beginScroll(Scroll s, float x, float y, float w, float h, float contentH) {
        Area a = new Area(s, x, y, w, h);
        a.popup = popupPass;
        areas.add(a);
        s.max = Math.max(0, contentH - h);
        s.target = Math.max(0, Math.min(s.max, s.target));
        if (reducedMotion || dt == 0) s.value = s.target;
        else s.value += (s.target - s.value) * Math.min(1f, dt * 16f);
        if (Math.abs(s.value - s.target) < 0.25f) s.value = s.target;
        s.value = Math.max(0, Math.min(s.max, s.value));
        clip(x, y, w, h);
        return s.value;
    }

    public void endScroll(Scroll s, float x, float y, float w, float h, float contentH) {
        unclip();
        if (contentH <= h) return;
        float th = Math.max(16, h * h / contentH);
        float ty = y + (h - th) * (s.max <= 0 ? 0 : s.value / s.max);
        r.rect(x + w + 2, ty, 2, th, 0x66FFFFFF, 1);
    }

    // ---- text -------------------------------------------------------------------------------------------------------

    public int fh() { return r.fontHeight(); }
    public int tw(String s) { return r.textWidth(s); }

    public void text(String s, float x, float y, int color) { r.text(s, x, y, color, false); }

    /** Text vertically centred in a box of height {@code h} starting at {@code y}. */
    public void textMid(String s, float x, float y, float h, int color) { r.text(s, x, y + (h - fh()) / 2f + 1, color, false); }

    public void textCentered(String s, float cx, float y, int color) { r.text(s, cx - tw(s) / 2f, y, color, false); }

    /** Display face (Sora) text at {@code scale}. Returns its scaled width. */
    public float display(String s, float x, float y, float scale, int color) {
        r.font(UiRenderer.Font.DISPLAY);
        float w = r.textWidth(s) * scale;
        r.push(x, y, scale);
        r.text(s, 0, 0, color, false);
        r.pop();
        r.font(UiRenderer.Font.UI);
        return w;
    }

    public float displayWidth(String s, float scale) {
        r.font(UiRenderer.Font.DISPLAY);
        float w = r.textWidth(s) * scale;
        r.font(UiRenderer.Font.UI);
        return w;
    }

    /** Truncate with an ellipsis to fit {@code w}. */
    public String fit(String s, float w) { return clipText(r, s, w); }

    public static String clipText(UiRenderer r, String s, float w) {
        if (s == null) return "";
        if (r.textWidth(s) <= w) return s;
        String dots = "...";
        int n = s.length();
        while (n > 0 && r.textWidth(s.substring(0, n) + dots) > w) n--;
        return s.substring(0, n) + dots;
    }

    /** Word-wrap into at most {@code maxLines} lines (the last one ellipsised). */
    public List<String> wrap(String s, float w, int maxLines) {
        List<String> out = new ArrayList<String>();
        String line = "";
        for (String word : s.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (tw(next) <= w || line.isEmpty()) {
                line = next;
                continue;
            }
            out.add(line);
            line = word;
            if (out.size() == maxLines) break;
        }
        if (out.size() < maxLines && !line.isEmpty()) out.add(line);
        else if (out.size() == maxLines && !line.isEmpty()) out.set(maxLines - 1, fit(out.get(maxLines - 1) + " " + line, w));
        for (int i = 0; i < out.size(); i++) out.set(i, fit(out.get(i), w));
        return out;
    }

    /** "SCREEN_SHOT" -> "Screen shot". */
    public static String title(String enumName) {
        String s = enumName.replace('_', ' ').toLowerCase(Locale.ROOT);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Draw a Minecraft item icon at {@code size} pixels. */
    public void item(String id, float x, float y, float size) {
        if (id == null) return;
        if (size == 16) {
            r.item(id, x, y);
            return;
        }
        r.push(x, y, size / 16f);
        r.item(id, 0, 0);
        r.pop();
    }

    /** Draw an {@link Icons} icon in a {@code size} square, tinted {@code color}; a plain rounded tile when unsupported. */
    public void icon(String name, float x, float y, float size, int color) {
        if (Icons.draw(r, name, x, y, size, color)) return;
        float in = size * 0.2f;
        r.rect(x + in, y + in, size - in * 2, size - in * 2, Theme.alpha(color, 0.6f), size * 0.15f);
    }

    public boolean blink() { return reducedMotion || (frameTime / 500) % 2 == 0; }

    // ---- flash messages ---------------------------------------------------------------------------------------------

    public void flash(String msg) { flash(msg, SUCCESS); }

    public void flash(String msg, int color) {
        flash = msg;
        flashColor = color;
        flashUntil = System.currentTimeMillis() + 3500;
    }

    /** The current flash message, or null once it has expired. */
    public String flashText() { return flash != null && frameTime < flashUntil ? flash : null; }
    public int flashColor() { return flashColor; }

    /** A pill toast with the flash message, centred at (cx, y). */
    public void drawFlash(float cx, float y) {
        String f = flashText();
        if (f == null) return;
        float w = tw(f) + 16;
        r.rect(cx - w / 2f, y, w, fh() + 8, 0xF01C1C22, 5);
        r.rect(cx - w / 2f, y, 3, fh() + 8, flashColor, 1.5f);
        text(f, cx - w / 2f + 9, y + 5, TEXT);
    }

    // ---- basic widgets ----------------------------------------------------------------------------------------------

    /** Returns {@code x}, so buttons can be laid out right-to-left. */
    public float button(String text, float x, float y, float w, float h, Style style, Action a) {
        boolean hover = over(x, y, w, h);
        int bg, fg;
        switch (style) {
            case PRIMARY: bg = hover ? 0xFF819DFF : ACCENT; fg = 0xFFFFFFFF; break;
            case STORE: bg = hover ? 0xFFC385FF : ACCENT_ALT; fg = 0xFFFFFFFF; break;
            case DANGER: bg = hover ? 0xFFFF7684 : DANGER; fg = 0xFFFFFFFF; break;
            case GHOST: bg = hover ? 0x1FFFFFFF : 0; fg = hover ? TEXT : LABEL; break;
            default: bg = hover ? RAISED_HOVER : RAISED; fg = hover ? TEXT : LABEL; break;
        }
        if (style == Style.SECONDARY) r.rect(x, y, w, h, hover ? BORDER_HOVER : BORDER, 4);
        if (bg != 0) r.rect(style == Style.SECONDARY ? x + 1 : x, style == Style.SECONDARY ? y + 1 : y, style == Style.SECONDARY ? w - 2 : w,
                style == Style.SECONDARY ? h - 2 : h, bg, style == Style.SECONDARY ? 3 : 4);
        String t = fit(text, w - 6);
        text(t, x + (w - tw(t)) / 2f, y + (h - fh()) / 2f + 1, fg);
        hit(x, y, w, h, a);
        return x;
    }

    /** Width a {@link #button} needs for {@code text}. */
    public float buttonWidth(String text) { return tw(text) + 14; }

    /** A right-aligned (ending at {@code right}) button that asks "Confirm?" before running {@code run}. Returns its left edge. */
    public float confirmButton(String id, String text, float right, float y, float h, Runnable run) {
        boolean armed = id.equals(confirming) && frameTime < confirmUntil;
        String t = armed ? "Confirm?" : text;
        float w = tw(t) + 14, x = right - w;
        button(t, x, y, w, h, armed ? Style.DANGER : Style.SECONDARY, (mx, my, b) -> {
            if (id.equals(confirming) && System.currentTimeMillis() < confirmUntil) {
                confirming = null;
                run.run();
            } else {
                confirming = id;
                confirmUntil = System.currentTimeMillis() + 3000;
            }
        });
        return x;
    }

    public void switchAt(float x, float y, boolean on) {
        r.rect(x, y, 24, 12, on ? ACCENT : SWITCH_OFF, 6);
        r.rect(on ? x + 13 : x + 1, y + 1, 10, 10, 0xFFFFFFFF, 5);
    }

    public void toggle(float x, float y, boolean on, Runnable flip) {
        switchAt(x, y, on);
        hit(x - 2, y - 2, 28, 16, on ? Sound.TOGGLE_OFF : Sound.TOGGLE_ON, (mx, my, b) -> flip.run());
    }

    public void slider(float x, float y, float w, float frac, String value, FloatSetter set) {
        float vw = Math.max(30, tw(value) + 4), trackW = w - vw - 4;
        float ty = y + ROW / 2f - 2;
        frac = Math.max(0, Math.min(1, frac));
        boolean hover = over(x - 4, y + 3, trackW + 8, ROW - 6);
        r.rect(x, ty, trackW, 4, TRACK, 2);
        r.rect(x, ty, Math.max(4, trackW * frac), 4, ACCENT, 2);
        r.rect(x + trackW * frac - 4, ty - 3, 8, 10, hover ? 0xFFFFFFFF : 0xFFDDE3EE, 3);
        text(value, x + w - tw(value), y + (ROW - fh()) / 2f + 1, TEXT);
        final float fx = x, fw = trackW;
        Drag drag = (mx, my) -> {
            float f = Math.max(0, Math.min(1, (mx - fx) / fw));
            set.set(f);
            tick(f);
        };
        hitDrag(x - 4, y + 3, trackW + 8, ROW - 6, (mx, my, b) -> drag.to(mx, my), drag);
    }

    /** A single-line text box bound to {@code f}; {@code key} identifies it for focus. */
    public void textField(Object key, Field f, float x, float y, float w, float h, String placeholder, String accept, Runnable commit) {
        boolean focus = focused(key);
        boolean hover = over(x, y, w, h);
        r.rect(x, y, w, h, focus ? ACCENT : hover ? BORDER_HOVER : BORDER, 4);
        r.rect(x + 1, y + 1, w - 2, h - 2, RAISED, 3);
        final boolean selected = selectOnFocus;
        selectOnFocus = false;
        editable(key, f, accept, commit, selected, x, y, w, h, x + 4, w - 8, placeholder);
    }

    /**
     * The text, selection and caret of a field drawn at {@code tx} within {@code avail} px, plus its mouse handling:
     * click places the caret, drag or Shift+click selects, double-click selects all, right-click clears.
     */
    private void editable(final Object key, final Field f, final String accept, final Runnable commit, final boolean selectAll,
                          float x, float y, float w, float h, final float tx, float avail, String placeholder) {
        final boolean focus = focused(key);
        final String v = f.get();
        float ty = y + (h - fh()) / 2f + 1;
        if (!focus) {
            boolean empty = v.isEmpty();
            text(fit(empty ? placeholder : v, avail), tx, ty, empty ? FAINT : TEXT);
        } else {
            clampCaret(v);
            // Scroll so the caret stays inside the box, without leaving blank space when the text fits.
            if (caret < viewStart) viewStart = caret;
            while (viewStart < caret && tw(v.substring(viewStart, caret)) > avail - 2) viewStart++;
            while (viewStart > 0 && tw(v.substring(viewStart - 1)) <= avail - 2) viewStart--;
            int end = viewStart;
            while (end < v.length() && tw(v.substring(viewStart, end + 1)) <= avail) end++;
            int lo = Math.max(Math.min(caret, anchor), viewStart), hi = Math.min(Math.max(caret, anchor), end);
            if (hi > lo) r.rect(tx + tw(v.substring(viewStart, lo)), ty - 2, tw(v.substring(lo, hi)), fh() + 2, (ACCENT & 0x00FFFFFF) | 0x70000000, 0);
            text(v.substring(viewStart, end), tx, ty, TEXT);
            if (blink() || dragging != null) r.rect(tx + tw(v.substring(viewStart, Math.min(caret, end))), ty - 2, 1, fh() + 2, TEXT, 0);
        }
        final int from = focus ? viewStart : 0;
        Hit hit = new Hit(x, y, w, h, (mx, my, b) -> {
            boolean was = focused(key);
            if (b == 1) f.set("");
            if (!was) {
                if (selectAll) focusSelected(key, f, accept, commit);
                else focus(key, f, accept, commit);
                if (selectAll || b == 1) return;
            }
            long now = System.currentTimeMillis();
            if (lastFieldClick == key && now - lastFieldClickAt < 350 && Math.abs(mx - lastFieldClickX) < 4) {
                anchor = 0;
                caret = f.get().length();
                lastFieldClick = null;
                return;
            }
            lastFieldClick = key;
            lastFieldClickAt = now;
            lastFieldClickX = mx;
            caret = indexAt(f.get(), from, mx - tx);
            if (!(was && shift)) anchor = caret;
        }, clip);
        hit.field = key;
        hit.sound = focus ? null : Sound.CLICK;
        hit.drag = (mx, my) -> { if (focused(key)) caret = indexAt(f.get(), viewStart, mx - tx); };
        hits.add(hit);
    }

    /** Character index nearest to {@code dx} px right of where {@code v} is drawn from character {@code from}. */
    private int indexAt(String v, int from, float dx) {
        from = Math.min(from, v.length());
        if (dx < 0) return Math.max(0, from - 1);
        for (int i = from; i < v.length(); i++) {
            float a = tw(v.substring(from, i)), b = tw(v.substring(from, i + 1));
            if (dx < (a + b) / 2f) return i;
        }
        return v.length();
    }

    /** The next {@link #textField} starts "selected" when clicked. */
    private boolean selectOnFocus;

    /** Make the next {@link #textField} start "selected": the first keystroke replaces its text. */
    public void selectNextField() { selectOnFocus = true; }

    public void copy(String text) { if (host != null) host.copyText(text); }

    /** Clipboard text, "" when empty or unsupported. */
    public String paste() {
        String s = host == null ? null : host.clipboardText();
        return s == null ? "" : s;
    }

    public void textField(Setting.Text t, float x, float y, float w) { textField(t, field(t), x, y, w, 16, "-", null, null); }

    /** Search box with a magnifier glyph; right-click clears. */
    public void searchBox(Text t, float x, float y, float w, float h, String hint) {
        boolean focus = focused(t);
        boolean hover = over(x, y, w, h);
        r.rect(x, y, w, h, focus ? ACCENT : hover ? BORDER_HOVER : BORDER, h / 2f);
        r.rect(x + 1, y + 1, w - 2, h - 2, RAISED, h / 2f - 1);
        // Magnifier: a ring and a handle.
        float cx = x + 9, cy = y + h / 2f - 1;
        r.rect(cx - 3, cy - 3, 6, 6, MUTED, 3);
        r.rect(cx - 2, cy - 2, 4, 4, RAISED, 2);
        r.rect(cx + 2, cy + 2, 2, 2, MUTED, 0);
        editable(t, t, null, null, false, x, y, w, h, x + 17, w - 26, hint);
    }

    /**
     * Click to listen for a key (Backspace/Delete = unbind, Escape = cancel); right-click or the "x" unbinds.
     * Listening shows "Press a key..." in place of the key name.
     */
    public void keybind(Setting.Key k, float x, float y, float w, boolean conflict) {
        boolean l = listening == k;
        boolean clear = !l && k.bound() && w > 40;
        float bw = clear ? w - 18 : w;
        String text = l ? "Press a key..." : Keys.name(k.code());
        button(text, x, y, bw, 16, l ? Style.PRIMARY : Style.SECONDARY, (mx, my, b) -> {
            if (b == 1) k.unbind();
            else {
                blur();
                listening = k;
            }
        });
        if (clear) button("x", x + w - 16, y, 16, 16, Style.GHOST, (mx, my, b) -> k.unbind());
        if (conflict && !l) outline(x, y, bw, 16, WARNING);
    }

    public void outline(float x, float y, float w, float h, int c) {
        r.rect(x, y, w, 1, c, 0);
        r.rect(x, y + h - 1, w, 1, c, 0);
        r.rect(x, y + 1, 1, h - 2, c, 0);
        r.rect(x + w - 1, y + 1, 1, h - 2, c, 0);
    }

    /** Small rounded label ("NEW", "SOON"). Returns its width. */
    public float badge(String t, float x, float y, int color) {
        float w = tw(t) + 6;
        r.rect(x, y, w, fh() + 2, withAlpha(color, 0x40), 3);
        text(t, x + 3, y + 2, color);
        return w;
    }

    // ---- setting controls -------------------------------------------------------------------------------------------

    /** The editor for {@code st} inside (x, y, w) of a {@link #ROW}-high row. Tables are drawn by {@link #table}. */
    public void control(final Setting<?> st, float x, float y, float w) {
        switch (st.kind()) {
            case TOGGLE: {
                final Setting.Bool b = (Setting.Bool) st;
                toggle(x + w - 24, y + 5, b.on(), () -> b.set(!b.on()));
                break;
            }
            case SLIDER: slider(st, x, y, w); break;
            case NUMBER_VALUE: number(st, x, y, w); break;
            case OPTION: {
                final Setting.Choice<?> c = (Setting.Choice<?>) st;
                dropdown(c, x, y + 3, w, 16);
                break;
            }
            case COLOR_PICKER: colorSwatch((Setting.Color) st, x, y, w); break;
            case PIXEL_GRID: PixelEditor.control(this, (Setting.PixelGrid) st, x, y, w); break;
            case TEXT_VALUE: textField((Setting.Text) st, x, y + 3, w); break;
            case KEYBIND: keybind((Setting.Key) st, x, y + 3, w, false); break;
            case BUTTON: {
                final Setting.Action a = (Setting.Action) st;
                button(a.button(), x, y + 3, w, 16, Style.PRIMARY, (mx, my, b) -> a.run());
                break;
            }
            default: break;
        }
    }

    private void slider(Setting<?> st, float x, float y, float w) {
        if (st instanceof Setting.Int) {
            final Setting.Int s = (Setting.Int) st;
            slider(x, y, w, (s.val() - s.min) / (float) Math.max(1, s.max - s.min), String.valueOf(s.val()), f -> s.set(Math.round(s.min + f * (s.max - s.min))));
        } else {
            final Setting.Dbl s = (Setting.Dbl) st;
            final double range = s.max - s.min;
            slider(x, y, w, (float) ((s.val() - s.min) / range), fmt(s.val(), range), f -> s.set(Math.round((s.min + f * range) * 100) / 100.0));
        }
    }

    static String fmt(double v, double range) { return String.format(Locale.ROOT, range >= 20 ? "%.1f" : "%.2f", v); }

    /** Typed number with -/+ steppers; commits (clamped) on Enter or click-away. */
    private void number(final Setting<?> st, float x, float y, float w) {
        final boolean isInt = st instanceof Setting.Int;
        final String current = isInt ? String.valueOf(((Setting.Int) st).val()) : fmt(((Setting.Dbl) st).val(), ((Setting.Dbl) st).max - ((Setting.Dbl) st).min);
        float bw = 16, fw = w - bw * 2 - 4;
        button("-", x, y + 3, bw, 16, Style.SECONDARY, (mx, my, b) -> step(st, -1, ctrl ? 10 : 1));
        final Text buffer = focused(st) && focusField instanceof Text ? (Text) focusField : new Text(current, 16);
        selectOnFocus = true;
        textField(st, buffer, x + bw + 2, y + 3, fw, 16, current, "-0123456789.", () -> {
            try {
                if (isInt) ((Setting.Int) st).set((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(Double.parseDouble(buffer.value)))));
                else ((Setting.Dbl) st).set(Double.parseDouble(buffer.value));
            } catch (NumberFormatException ignored) { }
        });
        button("+", x + w - bw, y + 3, bw, 16, Style.SECONDARY, (mx, my, b) -> step(st, 1, ctrl ? 10 : 1));
    }

    private static void step(Setting<?> st, int dir, int mult) {
        if (st instanceof Setting.Int) {
            Setting.Int s = (Setting.Int) st;
            long range = (long) s.max - s.min;
            int step = range > 100000 ? 100 : range > 1000 ? 10 : 1;
            s.set(s.val() + dir * step * mult);
        } else {
            Setting.Dbl s = (Setting.Dbl) st;
            double range = s.max - s.min;
            s.set(s.val() + dir * (range > 1000 ? 10 : range > 20 ? 1 : 0.1) * mult);
        }
    }

    /** Button showing the current option; opens a list. Right-click steps backwards. */
    public void dropdown(final Setting.Choice<?> c, final float x, final float y, final float w, final float h) {
        boolean hover = over(x, y, w, h);
        r.rect(x, y, w, h, hover ? BORDER_HOVER : BORDER, 4);
        r.rect(x + 1, y + 1, w - 2, h - 2, hover ? RAISED_HOVER : RAISED, 3);
        text(fit(title(c.val().name()), w - 18), x + 5, y + (h - fh()) / 2f + 1, TEXT);
        chevron(x + w - 10, y + h / 2f - 1, MUTED);
        hit(x, y, w, h, (mx, my, b) -> {
            if (b == 1) {
                for (int i = 0; i < c.options().length - 1; i++) c.next();
                return;
            }
            openPopup(new OptionList(c, x, y + h + 2, w));
        });
    }

    /** A small down-pointing chevron centred at (cx, cy). */
    public void chevron(float cx, float cy, int color) {
        // Anti-aliased icon (6 x 3 units, like the pixel version below, which renderers without images get).
        if (Icons.draw(r, "chevron-down", cx - 6, cy - 5.5f, 12, color)) return;
        r.rect(cx - 3, cy - 1, 2, 1, color, 0);
        r.rect(cx + 1, cy - 1, 2, 1, color, 0);
        r.rect(cx - 2, cy, 2, 1, color, 0);
        r.rect(cx, cy, 2, 1, color, 0);
        r.rect(cx - 1, cy + 1, 2, 1, color, 0);
    }

    private final class OptionList implements Popup {
        final Setting.Choice<?> c;
        final float x, y, w;
        final Scroll scroll = new Scroll();

        OptionList(Setting.Choice<?> c, float x, float y, float w) {
            this.c = c;
            this.x = x;
            this.y = y;
            this.w = w;
        }

        @Override public void render(Ui ui) {
            Enum<?>[] all = c.options();
            float rowH = fh() + 7, h = Math.min(all.length * rowH + 4, 160);
            float py = y;
            if (py + h > r.screenHeight() - 4) py = Math.max(4, y - h - 22);
            float pw = w;
            for (Enum<?> e : all) pw = Math.max(pw, tw(title(e.name())) + 16);
            float px = Math.min(x, r.screenWidth() - pw - 4);
            r.rect(px - 1, py - 1, pw + 2, h + 2, BORDER_HOVER, 5);
            r.rect(px, py, pw, h, 0xFA1C1C22, 4);
            absorb(px, py, pw, h);
            float off = beginScroll(scroll, px, py + 2, pw, h - 4, all.length * rowH);
            float ry = py + 2 - off;
            for (final Enum<?> e : all) {
                boolean sel = e == c.val(), hover = over(px + 2, ry, pw - 4, rowH);
                if (sel || hover) r.rect(px + 2, ry, pw - 4, rowH, sel ? 0x338B5CF6 : 0x14FFFFFF, 3);
                textMid(title(e.name()), px + 7, ry, rowH, sel ? TEXT : LABEL);
                hit(px + 2, ry, pw - 4, rowH, Sound.SELECT, (mx, my, b) -> {
                    select(c, e);
                    closePopup();
                });
                ry += rowH;
            }
            endScroll(scroll, px, py + 2, pw - 4, h - 4, all.length * rowH);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void select(Setting.Choice c, Enum e) { c.set(e); }

    private void colorSwatch(final Setting.Color c, final float x, final float y, float w) {
        int argb = c.argb();
        boolean open = popup instanceof ColorPicker && ((ColorPicker) popup).target == c;
        checker(x, y + 4, 28, 14);
        r.rect(x, y + 4, 28, 14, argb, 3);
        outline(x, y + 4, 28, 14, open ? ACCENT : 0x40FFFFFF);
        boolean hover = over(x, y + 3, w, 16);
        if (hover && !open) outline(x, y + 4, 28, 14, 0xFFFFFFFF);
        String label = c.chroma() ? "Chroma  #" + ColorPicker.hex(c.base()) : "#" + ColorPicker.hex(argb);
        text(label, x + 34, y + (ROW - fh()) / 2f + 1, open ? ACCENT : hover ? TEXT : MUTED);
        hit(x, y + 3, w, 16, (mx, my, b) -> {
            if (b == 1) c.reset();
            else openPopup(new ColorPicker(this, c, x, y + ROW));
        });
    }

    /** Grey checkerboard (shows transparency behind colour swatches). */
    public void checker(float x, float y, float w, float h) {
        r.rect(x, y, w, h, 0xFF9A9A9A, 3);
        float s = Math.max(3, h / 2f);
        for (float cy = 0; cy < h; cy += s) {
            for (float cx = ((cy / s) % 2 == 0 ? 0 : s); cx < w; cx += s * 2) {
                r.rect(x + cx, y + cy, Math.min(s, w - cx), Math.min(s, h - cy), 0xFFD8D8D8, 0);
            }
        }
    }

    /** Rows (with the header and add row) a table needs. */
    public static int tableRows(Setting.Entries e) { return 2 + e.size(); }

    /** An editable table: label + Add, column headers, one line per row with a remove button. */
    public void table(final Setting.Entries e, float x, float y, float w) {
        textMid(e.label + " (" + e.size() + ")", x, y, ROW, LABEL);
        if (e.size() < e.maxRows) button("+ Add", x + w - 50, y + 3, 50, 16, Style.PRIMARY, (mx, my, b) -> e.add());
        float cellsW = w - 22, colW = (cellsW - (e.columns.length - 1) * 3) / e.columns.length;
        float hy = y + ROW;
        for (int c = 0; c < e.columns.length; c++) textMid(fit(e.headers[c], colW), x + c * (colW + 3), hy, ROW, MUTED);
        float ry = hy + ROW;
        for (int i = 0; i < e.size(); i++) {
            final int row = i;
            Setting.Text[] cells = e.rows().get(i);
            for (int c = 0; c < cells.length; c++) textField(cells[c], x + c * (colW + 3), ry + 3, colW);
            button("x", x + w - 18, ry + 3, 18, 16, Style.SECONDARY, (mx, my, b) -> e.remove(row));
            ry += ROW;
        }
    }

    // ---- internals --------------------------------------------------------------------------------------------------

    private static final class Hit {
        final float x, y, w, h;
        final Action action;
        final float[] clip;
        Drag drag;
        /** Drags with any mouse button, not just the left. */
        boolean anyButton;
        Sound sound = Sound.CLICK;
        /** Focus key of the text field this hit belongs to: clicking it again moves the caret instead of blurring. */
        Object field;

        Hit(float x, float y, float w, float h, Action action, float[] clip) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.action = action;
            this.clip = clip;
        }

        boolean contains(float px, float py) {
            if (clip != null && (px < clip[0] || py < clip[1] || px >= clip[0] + clip[2] || py >= clip[1] + clip[3])) return false;
            return px >= x && py >= y && px < x + w && py < y + h;
        }
    }

    private static final class Area {
        final Scroll s;
        final float x, y, w, h;
        boolean popup;
        Area(Scroll s, float x, float y, float w, float h) {
            this.s = s;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }
}
