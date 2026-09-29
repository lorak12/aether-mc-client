package gg.aether.core;

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

    public HudElement(Anchor anchor, float offsetX, float offsetY) {
        this.anchor = anchor;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

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
    }

    private static float finite(float v, float fallback) {
        return Float.isNaN(v) || Float.isInfinite(v) ? fallback : v;
    }
}
