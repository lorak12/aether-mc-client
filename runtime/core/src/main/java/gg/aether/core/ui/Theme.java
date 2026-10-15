package gg.aether.core.ui;

/** Colours shared by every Aether screen (brand/tokens.json), plus small colour helpers. */
public final class Theme {
    private Theme() {}

    public static final int SURFACE = 0xF00E0E11, WINDOW = 0xE40E0E11, RAISED = 0xFF1C1C22, RAISED_HOVER = 0xFF24242C, BORDER = 0xFF2A2A33,
            BORDER_HOVER = 0xFF3E3E4A, TEXT = 0xFFF5F5F7, LABEL = 0xFFD6D6DE, MUTED = 0xFFA4A4B2, FAINT = 0xFF80808E, ACCENT = 0xFF8B5CF6,
            ACCENT_ALT = 0xFFA78BFA, SUCCESS = 0xFF3DDC97, WARNING = 0xFFF5B942, DANGER = 0xFFE07A84, TRACK = 0xFF2A2A33, SWITCH_OFF = 0xFF3A3A46;

    /** {@code argb} with its alpha multiplied by {@code a} (0..1). */
    public static int alpha(int argb, float a) {
        int al = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a)));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    /** {@code rgb} with alpha {@code a} (0..255). */
    public static int withAlpha(int rgb, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (rgb & 0xFFFFFF); }

    /** Linear blend from {@code a} (t = 0) to {@code b} (t = 1), per channel including alpha. */
    public static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
            out |= (Math.round(ca + (cb - ca) * t) & 0xFF) << shift;
        }
        return out;
    }
}
