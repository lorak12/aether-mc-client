package gg.aether.core;

/** Events adapters post onto the {@link EventBus}. */
public final class Events {
    private Events() {}

    /** Mouse button press. button: 0 = left, 1 = right, 2 = middle. */
    public static final class Click {
        public final int button;
        public final long timeMs;
        public Click(int button, long timeMs) { this.button = button; this.timeMs = timeMs; }
    }
}
