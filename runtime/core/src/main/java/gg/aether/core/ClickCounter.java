package gg.aether.core;

/** Counts events in a sliding one-second window (CPS). Fixed-size ring, no allocation per click. */
public final class ClickCounter {
    private final long[] times = new long[64];
    private int head, size;

    public void record(long nowMs) {
        if (size == times.length) { head = (head + 1) % times.length; size--; }
        times[(head + size) % times.length] = nowMs;
        size++;
    }

    public int count(long nowMs) {
        while (size > 0 && nowMs - times[head] >= 1000) { head = (head + 1) % times.length; size--; }
        return size;
    }
}
