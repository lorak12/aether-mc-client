package gg.aether.mods;

import gg.aether.core.UiRenderer;

/** Fixed-size history of samples plus a bar-graph renderer, for FPS, ping, CPS, memory and network widgets. */
public final class Graph {
    private final float[] v;
    private int n, head;

    public Graph(int capacity) { v = new float[capacity]; }

    public void add(float sample) {
        v[head] = sample;
        head = (head + 1) % v.length;
        n = Math.min(n + 1, v.length);
    }

    public void clear() { n = head = 0; }
    public int size() { return n; }
    public int capacity() { return v.length; }

    /** i = 0 is the oldest sample kept. */
    public float get(int i) { return v[(head - n + i + v.length) % v.length]; }
    public float last() { return n == 0 ? 0 : get(n - 1); }

    public float min() {
        float m = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) m = Math.min(m, get(i));
        return n == 0 ? 0 : m;
    }

    public float max() {
        float m = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) m = Math.max(m, get(i));
        return n == 0 ? 0 : m;
    }

    public float average() {
        double s = 0;
        for (int i = 0; i < n; i++) s += get(i);
        return n == 0 ? 0 : (float) (s / n);
    }

    /** Standard deviation (jitter for ping). */
    public float stddev() {
        if (n < 2) return 0;
        float avg = average();
        double s = 0;
        for (int i = 0; i < n; i++) s += (get(i) - avg) * (get(i) - avg);
        return (float) Math.sqrt(s / n);
    }

    /** Mean of the lowest {@code fraction} of samples (at least one): "1% low" = lowAverage(0.01). */
    public float lowAverage(double fraction) {
        if (n == 0) return 0;
        float[] sorted = new float[n];
        for (int i = 0; i < n; i++) sorted[i] = get(i);
        java.util.Arrays.sort(sorted);
        int k = Math.max(1, (int) (n * fraction));
        double s = 0;
        for (int i = 0; i < k; i++) s += sorted[i];
        return (float) (s / k);
    }

    public interface Colorer { int color(float value); }

    /** Bars from the bottom of (x, y, w, h); {@code top} is the value that fills the height (<= 0: the max sample). */
    public void draw(UiRenderer r, float x, float y, float w, float h, float top, int background, Colorer color) {
        r.rect(x, y, w, h, background, 2f);
        if (n < 2) return;
        float scale = top > 0 ? top : Math.max(1e-6f, max());
        float bar = w / v.length;
        for (int i = 0; i < n; i++) {
            float val = get(i);
            float bh = Math.max(1f, Math.min(h, val / scale * h));
            r.rect(x + (v.length - n + i) * bar, y + h - bh, Math.max(1f, bar), bh, color.color(val), 0f);
        }
    }
}
