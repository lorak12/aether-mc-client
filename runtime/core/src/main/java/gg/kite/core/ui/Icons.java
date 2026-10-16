package gg.kite.core.ui;

import gg.kite.core.UiRenderer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Kite's own icon set: 24x24 line icons written as SVG path data, rasterised with anti-aliasing at the exact pixel
 * size they are drawn at (so they stay crisp at every GUI scale) and drawn white, tinted by the renderer. A path
 * starting with "F " is filled; every other path is stroked with round caps and joins.
 */
public final class Icons {
    private Icons() {}

    private static final Map<String, String[]> SHAPES = new LinkedHashMap<String, String[]>();
    private static final Map<String, int[]> MASKS = new HashMap<String, int[]>();
    private static final float STROKE = 2f;

    /** True when {@code name} is in the set. */
    public static boolean has(String name) { return name != null && SHAPES.containsKey(name); }

    public static Set<String> names() { return Collections.unmodifiableSet(SHAPES.keySet()); }

    /**
     * Draw icon {@code name} in a {@code size} square at (x, y), coloured {@code argb} (its alpha fades it). False when
     * the icon is unknown or the renderer cannot draw images.
     */
    public static boolean draw(UiRenderer r, String name, float x, float y, float size, int argb) {
        if (!has(name) || (argb >>> 24) == 0) return false;
        int px = Math.max(6, Math.min(256, Math.round(size * Math.max(0.25f, r.pixelScale()))));
        int[] mask = mask(name, px);
        return r.image("kite:icon/" + name + "/" + px, px, px, mask, 1, x, y, size, size, argb);
    }

    /** White ARGB pixels ({@code px * px}) of icon {@code name}; alpha is coverage. Cached. */
    public static synchronized int[] mask(String name, int px) {
        String key = name + "@" + px;
        int[] m = MASKS.get(key);
        if (m == null) {
            m = rasterize(SHAPES.get(name), px);
            MASKS.put(key, m);
        }
        return m;
    }

    // ---- rasteriser -------------------------------------------------------------------------------------------------

    static int[] rasterize(String[] paths, int px) { return rasterize(paths, px, STROKE); }

    /** {@link #rasterize(String[], int)} with lines {@code stroke} grid units wide. */
    static int[] rasterize(String[] paths, int px, float stroke) {
        float scale = px / 24f;
        float halfStroke = Math.max(1.4f, stroke * scale) / 2f;
        float[] cover = new float[px * px];
        for (String p : paths) {
            boolean fill = p.startsWith("F ");
            List<float[]> polys = flatten(fill ? p.substring(2) : p, scale);
            if (fill) fill(polys, px, cover);
            else stroke(polys, px, halfStroke, cover);
        }
        int[] out = new int[px * px];
        for (int i = 0; i < out.length; i++) {
            int a = Math.round(Math.max(0f, Math.min(1f, cover[i])) * 255);
            out[i] = a == 0 ? 0 : (a << 24) | 0xFFFFFF;
        }
        return out;
    }

    /** Coverage of round-capped lines along every polyline: half the width minus the distance, smoothed over a pixel. */
    private static void stroke(List<float[]> polys, int px, float hw, float[] cover) {
        for (float[] pl : polys) {
            int n = pl.length / 2;
            if (n == 1) pl = new float[] { pl[0], pl[1], pl[0], pl[1] };
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i < pl.length; i += 2) {
                minX = Math.min(minX, pl[i]);
                maxX = Math.max(maxX, pl[i]);
                minY = Math.min(minY, pl[i + 1]);
                maxY = Math.max(maxY, pl[i + 1]);
            }
            int x0 = Math.max(0, (int) Math.floor(minX - hw - 1)), x1 = Math.min(px - 1, (int) Math.ceil(maxX + hw + 1));
            int y0 = Math.max(0, (int) Math.floor(minY - hw - 1)), y1 = Math.min(px - 1, (int) Math.ceil(maxY + hw + 1));
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    float cx = x + 0.5f, cy = y + 0.5f, d = Float.MAX_VALUE;
                    for (int i = 0; i + 3 < pl.length; i += 2) d = Math.min(d, segDist(cx, cy, pl[i], pl[i + 1], pl[i + 2], pl[i + 3]));
                    float c = hw - d + 0.5f;
                    if (c > cover[y * px + x]) cover[y * px + x] = c;
                }
            }
        }
    }

    /** Non-zero fill of the closed polylines with a one-pixel anti-aliased edge. */
    private static void fill(List<float[]> polys, int px, float[] cover) {
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                float cx = x + 0.5f, cy = y + 0.5f, d = Float.MAX_VALUE;
                int winding = 0;
                for (float[] pl : polys) {
                    int n = pl.length;
                    for (int i = 0; i < n; i += 2) {
                        float ax = pl[i], ay = pl[i + 1], bx = pl[(i + 2) % n], by = pl[(i + 3) % n];
                        d = Math.min(d, segDist(cx, cy, ax, ay, bx, by));
                        if (ay <= cy) {
                            if (by > cy && cross(ax, ay, bx, by, cx, cy) > 0) winding++;
                        } else if (by <= cy && cross(ax, ay, bx, by, cx, cy) < 0) {
                            winding--;
                        }
                    }
                }
                float c = winding != 0 ? 0.5f + d : 0.5f - d;
                if (c > cover[y * px + x]) cover[y * px + x] = c;
            }
        }
    }

    private static float cross(float ax, float ay, float bx, float by, float px, float py) { return (bx - ax) * (py - ay) - (px - ax) * (by - ay); }

    private static float segDist(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
        float t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len));
        float ex = ax + t * dx - px, ey = ay + t * dy - py;
        return (float) Math.sqrt(ex * ex + ey * ey);
    }

    // ---- SVG path data ----------------------------------------------------------------------------------------------

    /** Path data -> polylines (x0, y0, x1, y1...) in pixels. Supports M L H V C Q A Z, absolute and relative. */
    static List<float[]> flatten(String d, float scale) {
        List<float[]> out = new ArrayList<float[]>();
        List<Float> cur = new ArrayList<Float>();
        float x = 0, y = 0, sx = 0, sy = 0;
        int i = 0;
        char cmd = 'M';
        float[] num = new float[1];
        while (true) {
            i = skip(d, i);
            if (i >= d.length()) break;
            char c = d.charAt(i);
            if (Character.isLetter(c)) {
                cmd = c;
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    if (!cur.isEmpty()) {
                        cur.add(sx * scale);
                        cur.add(sy * scale);
                        out.add(toArray(cur));
                        cur = new ArrayList<Float>();
                    }
                    x = sx;
                    y = sy;
                    continue;
                }
            }
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    i = read(d, i, num); float nx = num[0] + ox;
                    i = read(d, i, num); float ny = num[0] + oy;
                    if (cur.size() > 2) out.add(toArray(cur));
                    cur = new ArrayList<Float>();
                    x = sx = nx;
                    y = sy = ny;
                    add(cur, x, y, scale);
                    cmd = rel ? 'l' : 'L';
                    break;
                }
                case 'L': {
                    i = read(d, i, num); x = num[0] + ox;
                    i = read(d, i, num); y = num[0] + oy;
                    add(cur, x, y, scale);
                    break;
                }
                case 'H': i = read(d, i, num); x = num[0] + ox; add(cur, x, y, scale); break;
                case 'V': i = read(d, i, num); y = num[0] + oy; add(cur, x, y, scale); break;
                case 'C': {
                    float[] v = new float[6];
                    for (int k = 0; k < 6; k++) {
                        i = read(d, i, num);
                        v[k] = num[0] + (k % 2 == 0 ? ox : oy);
                    }
                    for (int s = 1; s <= 12; s++) {
                        float t = s / 12f, u = 1 - t;
                        add(cur, u * u * u * x + 3 * u * u * t * v[0] + 3 * u * t * t * v[2] + t * t * t * v[4],
                                u * u * u * y + 3 * u * u * t * v[1] + 3 * u * t * t * v[3] + t * t * t * v[5], scale);
                    }
                    x = v[4];
                    y = v[5];
                    break;
                }
                case 'Q': {
                    float[] v = new float[4];
                    for (int k = 0; k < 4; k++) {
                        i = read(d, i, num);
                        v[k] = num[0] + (k % 2 == 0 ? ox : oy);
                    }
                    for (int s = 1; s <= 10; s++) {
                        float t = s / 10f, u = 1 - t;
                        add(cur, u * u * x + 2 * u * t * v[0] + t * t * v[2], u * u * y + 2 * u * t * v[1] + t * t * v[3], scale);
                    }
                    x = v[2];
                    y = v[3];
                    break;
                }
                case 'A': {
                    float[] v = new float[7];
                    for (int k = 0; k < 7; k++) {
                        i = read(d, i, num);
                        v[k] = num[0];
                    }
                    float ex = v[5] + ox, ey = v[6] + oy;
                    arc(cur, x, y, v[0], v[1], v[2], v[3] != 0, v[4] != 0, ex, ey, scale);
                    x = ex;
                    y = ey;
                    break;
                }
                default: throw new IllegalArgumentException("Unsupported path command '" + cmd + "' in " + d);
            }
        }
        if (cur.size() > 2 || (cur.size() == 2 && out.isEmpty())) out.add(toArray(cur));
        return out;
    }

    /** SVG elliptical arc (endpoint form, SVG spec F.6.5), radii grown when too small to reach the end point. */
    private static void arc(List<Float> cur, float x1, float y1, float rx, float ry, float rotDeg, boolean large, boolean sweep, float x2, float y2, float scale) {
        if (rx == 0 || ry == 0 || (x1 == x2 && y1 == y2)) {
            add(cur, x2, y2, scale);
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        double phi = Math.toRadians(rotDeg), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x1 - x2) / 2.0, dy = (y1 - y2) / 2.0;
        double x1p = cos * dx + sin * dy, y1p = -sin * dx + cos * dy;
        double lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry);
        if (lambda > 1) {
            rx *= Math.sqrt(lambda);
            ry *= Math.sqrt(lambda);
        }
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double co = Math.sqrt(Math.max(0, num / den)) * (large == sweep ? -1 : 1);
        double cxp = co * rx * y1p / ry, cyp = -co * ry * x1p / rx;
        double cx = cos * cxp - sin * cyp + (x1 + x2) / 2.0, cy = sin * cxp + cos * cyp + (y1 + y2) / 2.0;
        double a1 = Math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx), a2 = Math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx);
        double delta = a2 - a1;
        if (sweep && delta < 0) delta += Math.PI * 2;
        else if (!sweep && delta > 0) delta -= Math.PI * 2;
        int steps = Math.max(4, (int) Math.ceil(Math.abs(delta) / (Math.PI / 16)));
        for (int s = 1; s <= steps; s++) {
            double a = a1 + delta * s / steps;
            double px = rx * Math.cos(a), py = ry * Math.sin(a);
            add(cur, (float) (cos * px - sin * py + cx), (float) (sin * px + cos * py + cy), scale);
        }
    }

    private static void add(List<Float> l, float x, float y, float scale) {
        l.add(x * scale);
        l.add(y * scale);
    }

    private static float[] toArray(List<Float> l) {
        float[] a = new float[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private static int skip(String d, int i) {
        while (i < d.length() && (d.charAt(i) == ' ' || d.charAt(i) == ',')) i++;
        return i;
    }

    private static int read(String d, int i, float[] out) {
        i = skip(d, i);
        int s = i;
        if (i < d.length() && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
        boolean dot = false;
        while (i < d.length()) {
            char c = d.charAt(i);
            if (Character.isDigit(c)) i++;
            else if (c == '.' && !dot) {
                dot = true;
                i++;
            } else break;
        }
        if (s == i) throw new IllegalArgumentException("Expected a number at " + s + " in " + d);
        out[0] = Float.parseFloat(d.substring(s, i));
        return i;
    }

    // ---- shape helpers ----------------------------------------------------------------------------------------------

    static String circle(float cx, float cy, float r) {
        return String.format(Locale.ROOT, "M%s %s A%s %s 0 1 1 %s %s A%s %s 0 1 1 %s %s Z", f(cx - r), f(cy), f(r), f(r), f(cx + r), f(cy), f(r), f(r), f(cx - r), f(cy));
    }

    static String dot(float cx, float cy, float r) { return "F " + circle(cx, cy, r); }

    static String rect(float x, float y, float w, float h, float rad) {
        if (rad <= 0) return String.format(Locale.ROOT, "M%s %s H%s V%s H%s Z", f(x), f(y), f(x + w), f(y + h), f(x));
        return String.format(Locale.ROOT, "M%s %s H%s A%s %s 0 0 1 %s %s V%s A%s %s 0 0 1 %s %s H%s A%s %s 0 0 1 %s %s V%s A%s %s 0 0 1 %s %s Z",
                f(x + rad), f(y), f(x + w - rad), f(rad), f(rad), f(x + w), f(y + rad), f(y + h - rad), f(rad), f(rad), f(x + w - rad), f(y + h),
                f(x + rad), f(rad), f(rad), f(x), f(y + h - rad), f(y + rad), f(rad), f(rad), f(x + rad), f(y));
    }

    /** A cog outline: {@code teeth} flat-topped teeth between radii {@code inner} and {@code outer}. */
    static String gear(float cx, float cy, float outer, float inner, int teeth) {
        StringBuilder sb = new StringBuilder();
        for (int t = 0; t < teeth; t++) {
            double base = Math.PI * 2 * t / teeth, step = Math.PI * 2 / teeth;
            double[] angles = { base - step * 0.25, base - step * 0.15, base + step * 0.15, base + step * 0.25 };
            float[] radii = { inner, outer, outer, inner };
            for (int k = 0; k < 4; k++) {
                sb.append(sb.length() == 0 ? "M" : " L").append(f((float) (cx + Math.cos(angles[k]) * radii[k]))).append(' ')
                        .append(f((float) (cy + Math.sin(angles[k]) * radii[k])));
            }
        }
        return sb.append(" Z").toString();
    }

    private static String f(float v) { return String.format(Locale.ROOT, "%.3f", v); }

    private static void icon(String name, String... paths) { SHAPES.put(name, paths); }

    static {
        // ---- client / menu ----
        icon("kite", "M4.5 19.5 L12 4.5 L19.5 19.5", "M9 19.5 L12 13.5 L15 19.5");
        icon("close", "M6 6 L18 18", "M18 6 L6 18");
        icon("chevron-down", "M6 9 L12 15 L18 9");
        icon("settings", gear(12, 12, 9.5f, 7.2f, 8), circle(12, 12, 3));
        icon("wardrobe", "M8 3 L3.5 5.5 L2.5 10 L6 11 V21 H18 V11 L21.5 10 L20.5 5.5 L16 3 C15.5 4.7 14 5.8 12 5.8 C10 5.8 8.5 4.7 8 3 Z");
        icon("cart", "M2.5 4 H5.5 L8 15.5 H18.5 L21 7.5 H6.5", circle(9.5f, 19.5f, 1.6f), circle(17, 19.5f, 1.6f));
        icon("lock", "M7.5 11 V8 A4.5 4.5 0 0 1 16.5 8 V11", "M5 11 H19 V21 H5 Z", "M12 15 V17.5");
        icon("upload", "M12 15.5 V4", "M7 8.5 L12 3.5 L17 8.5", "M4 14.5 V20 H20 V14.5");
        icon("sliders", "M4 6 H13", "M17 6 H20", circle(15, 6, 2), "M4 12 H7", "M11 12 H20", circle(9, 12, 2), "M4 18 H15", "M19 18 H20", circle(17, 18, 2));
        icon("realms", "M4 21 V7 H7.5 V9.5 H10.25 V7 H13.75 V9.5 H16.5 V7 H20 V21 Z", "M10 21 V17 A2 2 0 0 1 14 17 V21");
        icon("package", "M12 2.8 L20.5 7.3 V16.7 L12 21.2 L3.5 16.7 V7.3 Z", "M3.5 7.3 L12 11.8 L20.5 7.3", "M12 11.8 V21.2");
        icon("emote", circle(12, 12, 9), "M8.5 14.5 C9.5 16 10.6 16.6 12 16.6 C13.4 16.6 14.5 16 15.5 14.5", dot(9, 9.8f, 1.2f), dot(15, 9.8f, 1.2f));
        icon("mods", rect(3.5f, 3.5f, 7, 7, 1.5f), rect(13.5f, 3.5f, 7, 7, 1.5f), rect(3.5f, 13.5f, 7, 7, 1.5f), rect(13.5f, 13.5f, 7, 7, 1.5f));
        icon("chat", "M4 5.5 A2 2 0 0 1 6 3.5 H18 A2 2 0 0 1 20 5.5 V15 A2 2 0 0 1 18 17 H9 L4 21 Z");

        // ---- category fallbacks ----
        icon("layout", rect(3, 3, 18, 18, 2.5f), "M3 9 H21", "M9 9 V21");
        icon("eye", "M2 12 C4.5 7 8 5 12 5 C16 5 19.5 7 22 12 C19.5 17 16 19 12 19 C8 19 4.5 17 2 12 Z", circle(12, 12, 3));
        icon("gamepad", "M17.3 5 H6.7 A4.7 4.7 0 0 0 2 9.7 V14 A3.6 3.6 0 0 0 8.6 16 L10 14.5 H14 L15.4 16 A3.6 3.6 0 0 0 22 14 V9.7 A4.7 4.7 0 0 0 17.3 5 Z",
                "M6 10 H10", "M8 8 V12", dot(15.2f, 11, 1.1f), dot(17.8f, 9, 1.1f));
        icon("gauge", "M4.5 17 A8.5 8.5 0 1 1 19.5 17", "M12 13 L16 9", dot(12, 13, 1.6f));
        icon("sword", "M14.5 17.5 L3 6 V3 H6 L17.5 14.5", "M13 19 L19 13", "M16 16 L20 20", "M19 21 L21 19");
        icon("globe", circle(12, 12, 9), "M3 12 H21", "M12 3 C14.5 5.5 15.5 8.7 15.5 12 C15.5 15.3 14.5 18.5 12 21 C9.5 18.5 8.5 15.3 8.5 12 C8.5 8.7 9.5 5.5 12 3 Z");

        // ---- mods ----
        icon("mouse", rect(6, 3, 12, 18, 6), "M12 7 V10");
        icon("signal", "M5 20 V16", "M10 20 V12", "M15 20 V8", "M20 20 V4");
        icon("pin", "M12 21 C12 21 19 14.5 19 9.5 A7 7 0 0 0 5 9.5 C5 14.5 12 21 12 21 Z", circle(12, 9.5f, 2.5f));
        icon("compass", circle(12, 12, 9), "F M15.5 8.5 L13.4 13.4 L8.5 15.5 L10.6 10.6 Z");
        icon("clock", circle(12, 12, 9), "M12 7 V12 L15.5 14");
        icon("chip", rect(6, 6, 12, 12, 2), rect(9.5f, 9.5f, 5, 5, 1), "M9 2.5 V6", "M15 2.5 V6", "M9 18 V21.5", "M15 18 V21.5", "M2.5 9 H6", "M2.5 15 H6",
                "M18 9 H21.5", "M18 15 H21.5");
        icon("flask", "M9 3 H15", "M10 3 V9 L5.2 17.5 A2.4 2.4 0 0 0 7.3 21 H16.7 A2.4 2.4 0 0 0 18.8 17.5 L14 9 V3", "M7.5 15 H16.5");
        icon("keys", rect(9.5f, 3.5f, 5, 5, 1.2f), rect(1.5f, 11.5f, 5, 5, 1.2f), rect(9.5f, 11.5f, 5, 5, 1.2f), rect(17.5f, 11.5f, 5, 5, 1.2f), "M6 20.5 H18");
        icon("shield", "M12 2.5 L19.5 5.5 V11 C19.5 16 16.2 19.5 12 21.5 C7.8 19.5 4.5 16 4.5 11 V5.5 Z");
        icon("crosshair", circle(12, 12, 7.5f), "M12 2 V7", "M12 17 V22", "M2 12 H7", "M17 12 H22", dot(12, 12, 1.3f));
        icon("zoom", circle(10.5f, 10.5f, 6.5f), "M15.5 15.5 L21 21", "M10.5 7.5 V13.5", "M7.5 10.5 H13.5");
        icon("sun", circle(12, 12, 4), "M12 2 V4", "M12 20 V22", "M2 12 H4", "M20 12 H22", "M4.9 4.9 L6.3 6.3", "M17.7 17.7 L19.1 19.1", "M4.9 19.1 L6.3 17.7",
                "M17.7 6.3 L19.1 4.9");
        icon("moon", "M12 3 A6.5 6.5 0 0 0 21 12 A9 9 0 1 1 12 3 Z", "M18.5 3.5 V6.5", "M17 5 H20");
        icon("rain", "M17.5 15 H7 A4 4 0 1 1 8 7.1 A5.5 5.5 0 0 1 18.4 8.6 A3.3 3.3 0 0 1 17.5 15 Z", "M8 18.5 L7 21", "M12 18.5 L11 21", "M16 18.5 L15 21");
        icon("forward", "M5 6 L11 12 L5 18", "M13 6 L19 12 L13 18");
        icon("broadcast", dot(12, 12, 2), "M7.8 7.8 A6 6 0 0 0 7.8 16.2", "M16.2 7.8 A6 6 0 0 1 16.2 16.2", "M4.9 4.9 A10 10 0 0 0 4.9 19.1",
                "M19.1 4.9 A10 10 0 0 1 19.1 19.1");
        icon("alert", "M12 3.5 L21.5 20 H2.5 Z", "M12 10 V14", dot(12, 17, 1.1f));
        icon("actionbar", "M6 8.5 H18", rect(3, 14, 18, 6, 2.5f), "M8 17 H8.01", "M12 17 H12.01", "M16 17 H16.01");
        icon("zap", "M13 2.5 L4.5 13.5 H12 L11 21.5 L19.5 10.5 H12 Z");
        icon("reach", "M3 12 H21", "M6 9 L3 12 L6 15", "M18 9 L21 12 L18 15", "M12 9 V15");
        icon("layers", "M12 3 L21 7.5 L12 12 L3 7.5 Z", "M3 12 L12 16.5 L21 12", "M3 16.5 L12 21 L21 16.5");
        icon("server", rect(3, 3.5f, 18, 7, 2), rect(3, 13.5f, 18, 7, 2), dot(7, 7, 1.1f), dot(7, 17, 1.1f), "M11 7 H17", "M11 17 H17");
        icon("scan", "M3 8 V5 A2 2 0 0 1 5 3 H8", "M16 3 H19 A2 2 0 0 1 21 5 V8", "M21 16 V19 A2 2 0 0 1 19 21 H16", "M8 21 H5 A2 2 0 0 1 3 19 V16",
                "M12 11 V16.5", dot(12, 7.8f, 1.2f));
        icon("grid", rect(3, 3, 18, 18, 2.5f), "M9 3 V21", "M15 3 V21", "M3 9 H21", "M3 15 H21");
        icon("skull", "M12 3 C7.3 3 4.5 6.2 4.5 10.2 C4.5 12.8 5.8 14.6 7.5 15.6 V19 A1.5 1.5 0 0 0 9 20.5 H15 A1.5 1.5 0 0 0 16.5 19 V15.6 C18.2 14.6 19.5 12.8 19.5 10.2 C19.5 6.2 16.7 3 12 3 Z",
                dot(9.3f, 11, 1.7f), dot(14.7f, 11, 1.7f), "M10.5 17.5 V20.5", "M13.5 17.5 V20.5");
        icon("title", "M4 5 H20", "M12 5 V20", "M9 20 H15");
        icon("scoreboard", rect(3, 3.5f, 18, 17, 2.5f), "M7 8.5 H17", "M7 12 H17", "M7 15.5 H13");
        icon("apple", "M12 7.5 C10 6 6 6 4.8 9.2 C3.6 12.5 5.5 18 8.5 20 C10 21 11 20.5 12 20 C13 20.5 14 21 15.5 20 C18.5 18 20.4 12.5 19.2 9.2 C18 6 14 6 12 7.5 Z",
                "M12 7.5 C12 5.5 12.8 4 14.5 3");
        icon("pickaxe", "M4 20 L15.5 8.5", "M8.5 3.5 Q19 5 20.5 15.5");
        icon("hourglass", "M6 3 H18", "M6 21 H18", "M7 3 V6.5 C7 9 12 10.5 12 12 C12 13.5 7 15 7 17.5 V21", "M17 3 V6.5 C17 9 12 10.5 12 12 C12 13.5 17 15 17 17.5 V21");
        icon("heart", "M12 20.5 C12 20.5 3 15 3 8.8 A4.8 4.8 0 0 1 12 6.5 A4.8 4.8 0 0 1 21 8.8 C21 15 12 20.5 12 20.5 Z");
        icon("bomb", circle(10.5f, 14, 6.5f), "M15.2 9.3 L17.5 7", "M17.5 7 C18.5 5.5 19.5 4.5 21 4.5", "M8 12 A3 3 0 0 1 10.5 10.5");
        icon("fov", "M12 20 L4 6", "M12 20 L20 6", "M4 6 A16.1 16.1 0 0 1 20 6", dot(12, 20, 1.3f));
        icon("camera", "M4.5 7.5 H7.5 L9 5 H15 L16.5 7.5 H19.5 A2 2 0 0 1 21.5 9.5 V17.5 A2 2 0 0 1 19.5 19.5 H4.5 A2 2 0 0 1 2.5 17.5 V9.5 A2 2 0 0 1 4.5 7.5 Z",
                circle(12, 13, 3.5f));
        icon("wand", "M4 20 L14 10", "M17 3 V7", "M15 5 H19", "M20 10 V13", "M18.5 11.5 H21.5", "M9 3.5 V6", "M7.75 4.75 H10.25");
        icon("sparkles", "M12 3 C12.6 8 14 9.4 19 10 C14 10.6 12.6 12 12 17 C11.4 12 10 10.6 5 10 C10 9.4 11.4 8 12 3 Z", "M18.5 15.5 V20.5", "M16 18 H21",
                "M5 17 V19", "M4 18 H6");
        icon("droplet", "M12 3 C12 3 5.5 10 5.5 14.5 A6.5 6.5 0 0 0 18.5 14.5 C18.5 10 12 3 12 3 Z");
        icon("drop", "M12 3 L17 8 L12 13 L7 8 Z", "M4 20.5 H20", "M9 16 V17.5", "M12 15.5 V17.5", "M15 16 V17.5");
        icon("tag", "M3 3 H11 L21 13 L13 21 L3 11 Z", dot(7.5f, 7.5f, 1.5f));
        icon("wirebox", "M3 8 H16 V21 H3 Z", "M8 3 H21 V16 H16", "M3 8 L8 3", "M16 8 L21 3", "M16 21 L21 16");
        icon("blur", dot(6, 6, 1), dot(12, 6, 1.6f), dot(18, 6, 1), dot(6, 12, 1.6f), dot(12, 12, 2.4f), dot(18, 12, 1.6f), dot(6, 18, 1), dot(12, 18, 1.6f), dot(18, 18, 1));
        icon("minimap", circle(12, 12, 9), "F M12 6.5 L15.5 16 L12 14 L8.5 16 Z");
        icon("map", "M3 6 L9 3.5 L15 6 L21 3.5 V18 L15 20.5 L9 18 L3 20.5 Z", "M9 3.5 V18", "M15 6 V20.5");
        icon("flag", "M5 21 V3.5", "M5 4 H18 L15.5 8.25 L18 12.5 H5");
        icon("chunks", rect(3, 3, 18, 18, 1.5f), "M12 3 V21", "M3 12 H21");
        icon("bulb", "M9 18 H15", "M10 21.5 H14", "M9 15 C9 13 7 12 7 9.5 A5 5 0 0 1 17 9.5 C17 12 15 13 15 15 V18 H9 Z");
        icon("blocksel", "M12 7 L16.5 9.5 V14.5 L12 17 L7.5 14.5 V9.5 Z", "M7.5 9.5 L12 12 L16.5 9.5", "M12 12 V17", "M3 7 V3 H7", "M17 3 H21 V7", "M21 17 V21 H17",
                "M7 21 H3 V17");
        icon("chatalert", "M4 5.5 A2 2 0 0 1 6 3.5 H18 A2 2 0 0 1 20 5.5 V15 A2 2 0 0 1 18 17 H9 L4 21 Z", "M12 7 V10.5", dot(12, 13.3f, 1.1f));
        icon("list", "M8 6 H21", "M8 12 H21", "M8 18 H21", dot(4, 6, 1.4f), dot(4, 12, 1.4f), dot(4, 18, 1.4f));
        icon("users", circle(9, 8, 3.5f), "M2.5 20 C2.5 16 5.5 14 9 14 C12.5 14 15.5 16 15.5 20", "M16 4.6 A3.5 3.5 0 0 1 16 11.4", "M18 14.3 C20.2 15 21.5 17 21.5 20");
        icon("image", rect(3, 4, 18, 16, 2.5f), dot(8.5f, 9.5f, 1.8f), "M3 17 L8.5 12 L12 15 L15.5 11 L21 16");
        icon("bug", rect(7.5f, 8, 9, 12.5f, 4.5f), "M12 11 V20.5", "M9 8 A3 3 0 0 1 15 8", "M3.5 13 H7.5", "M16.5 13 H20.5", "M4.5 19 L7.6 17", "M19.5 19 L16.4 17",
                "M5 6.5 L8 9", "M19 6.5 L16 9");
        icon("bell", "M6 16 V10.5 A6 6 0 0 1 18 10.5 V16 L19.5 18 H4.5 L6 16 Z", "M10 21 H14");
    }
}
