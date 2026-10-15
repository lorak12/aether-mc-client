package gg.aether.mc;

/** Account helpers for legacy versions. Live account switching is only offered on 1.18+; here the launcher relaunches. */
public final class McSession {
    private McSession() {}

    /** Mojang ids come without dashes. */
    public static String dashed(String s) {
        if (s == null) return null;
        String h = s.replace("-", "");
        if (h.length() != 32) return s;
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-" + h.substring(16, 20) + "-" + h.substring(20);
    }
}
