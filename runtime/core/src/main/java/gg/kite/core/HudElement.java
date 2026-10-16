package gg.kite.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Position/scale/opacity of a HUD widget. Positions are stored as (anchor, offset) so layouts survive
 * window resizes and GUI-scale changes, like Lunar/Badlion.
 */
public final class HudElement {
    public enum Anchor {
        TOP_LEFT(0f, 0f), TOP_CENTER(.5f, 0f), TOP_RIGHT(1f, 0f),
        MIDDLE_LEFT(0f, .5f), CENTER(.5f, .5f), MIDDLE_RIGHT(1f, .5f),
        BOTTOM_LEFT(0f, 1f), BOTTOM_CENTER(.5f, 1f), BOTTOM_RIGHT(1f, 1f);

        public final float fx, fy;
        Anchor(float fx, float fy) { this.fx = fx; this.fy = fy; }

        static Anchor nearest(float px, float py) {
            Anchor best = TOP_LEFT;
            float bd = Float.MAX_VALUE;
            for (Anchor a : values()) {
                float d = (a.fx - px) * (a.fx - px) + (a.fy - py) * (a.fy - py);
                if (d < bd) { bd = d; best = a; }
            }
            return best;
        }
    }

    public static final float MIN_SCALE = 0.25f, MAX_SCALE = 4f;

    public Anchor anchor;
    public float offsetX, offsetY;
    private float scale = 1f;
    private float opacity = 1f;
    /**
     * Box size (unscaled) set by the HUD editor's side handles, 0 = fit the content. Only elements marked
     * {@link #resizable} honour it (text widgets pad their panel and align their text inside it).
     */
    private float boxW, boxH;
    /** The owner draws into a {@link #boxWidth()} x {@link #boxHeight()} box; otherwise side handles scale it. */
    public boolean resizable;
    /** Universal visibility rules, applied by the mod manager (never while the HUD editor is open). */
    public boolean hideInDebug, hideInChat, hideInScreens;
    private final Anchor defAnchor;
    private final float defX, defY;

    // Where the element was last drawn (screen space, scaled), for the HUD editor's hit-testing.
    private float lastX, lastY, lastW, lastH, naturalW, naturalH;
    private boolean drawn;

    public HudElement(Anchor anchor, float offsetX, float offsetY) {
        this.anchor = anchor;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.defAnchor = anchor;
        this.defX = offsetX;
        this.defY = offsetY;
    }

    /** Back to the built-in position, scale and opacity. */
    public void reset() {
        anchor = defAnchor;
        offsetX = defX;
        offsetY = defY;
        scale = 1f;
        opacity = 1f;
        boxW = boxH = 0;
        hideInDebug = hideInChat = hideInScreens = false;
    }

    /** True if a visibility rule hides this element in the given game state. */
    public boolean hiddenBy(GameState s) {
        return (hideInDebug && s.debugOpen()) || (hideInChat && s.chatOpen()) || (hideInScreens && s.screenOpen() && !s.chatOpen());
    }

    /** Called by the mod manager before the owning mod renders. */
    void beginFrame() { drawn = false; }
    /** True if the element was resolved (drawn) during the latest HUD pass. */
    public boolean drawn() { return drawn; }
    /** {x, y, w, h} on screen from the latest draw, size already scaled. */
    public float[] bounds() { return new float[] { lastX, lastY, lastW * scale, lastH * scale }; }
    /** Unscaled size from the latest draw. */
    public float lastWidth() { return lastW; }
    public float lastHeight() { return lastH; }

    /** Box size the owner should draw at least (0 = fit the content). */
    public float boxWidth() { return boxW; }
    public float boxHeight() { return boxH; }
    /** Set the box (unscaled); values at or below the content size go back to 0 (fit). */
    public void setBox(float w, float h) {
        boxW = w <= naturalW + 0.5f ? 0 : Math.min(1000, Math.round(w));
        boxH = h <= naturalH + 0.5f ? 0 : Math.min(1000, Math.round(h));
    }
    /** Content size without the box, reported by resizable owners before {@link #resolve}. */
    public void natural(float w, float h) {
        naturalW = w;
        naturalH = h;
    }
    public float naturalWidth() { return naturalW; }
    public float naturalHeight() { return naturalH; }

    public float scale() { return scale; }
    public void setScale(float s) { scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, s)); }
    public float opacity() { return opacity; }
    public void setOpacity(float o) { opacity = Math.max(0f, Math.min(1f, o)); }

    /** Top-left screen position of an element whose unscaled size is (w, h). Clamped fully on screen. */
    public float[] resolve(int screenW, int screenH, float w, float h) {
        float sw = w * scale, sh = h * scale;
        float x = anchor.fx * (screenW - sw) + offsetX;
        float y = anchor.fy * (screenH - sh) + offsetY;
        x = Math.max(0, Math.min(Math.max(0, screenW - sw), x));
        y = Math.max(0, Math.min(Math.max(0, screenH - sh), y));
        lastX = x;
        lastY = y;
        lastW = w;
        lastH = h;
        drawn = true;
        return new float[] { x, y };
    }

    /** Called by the HUD editor after a drag: re-anchors to the nearest of 9 anchors and recomputes offsets. */
    public void moveTo(float absX, float absY, int screenW, int screenH, float w, float h) {
        float sw = w * scale, sh = h * scale;
        float px = screenW <= 0 ? 0 : (absX + sw / 2f) / screenW;
        float py = screenH <= 0 ? 0 : (absY + sh / 2f) / screenH;
        anchor = Anchor.nearest(px, py);
        offsetX = absX - anchor.fx * (screenW - sw);
        offsetY = absY - anchor.fy * (screenH - sh);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("anchor", anchor.name());
        m.put("x", (double) offsetX);
        m.put("y", (double) offsetY);
        m.put("scale", (double) scale);
        m.put("opacity", (double) opacity);
        if (boxW > 0) m.put("w", (double) boxW);
        if (boxH > 0) m.put("h", (double) boxH);
        m.put("hideInDebug", hideInDebug);
        m.put("hideInChat", hideInChat);
        m.put("hideInScreens", hideInScreens);
        return m;
    }

    public void fromJson(Object o) {
        if (!(o instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) o;
        Object a = m.get("anchor");
        if (a instanceof String) {
            for (Anchor an : Anchor.values()) if (an.name().equals(a)) anchor = an;
        }
        if (m.get("x") instanceof Number) offsetX = finite(((Number) m.get("x")).floatValue(), offsetX);
        if (m.get("y") instanceof Number) offsetY = finite(((Number) m.get("y")).floatValue(), offsetY);
        if (m.get("scale") instanceof Number) setScale(finite(((Number) m.get("scale")).floatValue(), scale));
        if (m.get("opacity") instanceof Number) setOpacity(finite(((Number) m.get("opacity")).floatValue(), opacity));
        boxW = m.get("w") instanceof Number ? Math.max(0, Math.min(1000, finite(((Number) m.get("w")).floatValue(), 0))) : 0;
        boxH = m.get("h") instanceof Number ? Math.max(0, Math.min(1000, finite(((Number) m.get("h")).floatValue(), 0))) : 0;
        if (m.get("hideInDebug") instanceof Boolean) hideInDebug = (Boolean) m.get("hideInDebug");
        if (m.get("hideInChat") instanceof Boolean) hideInChat = (Boolean) m.get("hideInChat");
        if (m.get("hideInScreens") instanceof Boolean) hideInScreens = (Boolean) m.get("hideInScreens");
    }

    private static float finite(float v, float fallback) {
        return Float.isNaN(v) || Float.isInfinite(v) ? fallback : v;
    }
}
