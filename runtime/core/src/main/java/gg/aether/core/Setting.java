package gg.aether.core;

/**
 * A persisted, UI-renderable option. Bad or out-of-range stored values fall back to / clamp toward the
 * default instead of throwing, so a hand-edited config can never crash the game.
 */
public abstract class Setting<T> {
    public final String key;
    public final String label;
    protected T value;
    protected final T def;

    protected Setting(String key, String label, T def) {
        this.key = key;
        this.label = label;
        this.def = def;
        this.value = def;
    }

    public T get() { return value; }
    public T getDefault() { return def; }
    public void reset() { value = def; }

    public abstract Object toJson();
    public abstract void fromJson(Object o);

    public static final class Bool extends Setting<Boolean> {
        public Bool(String key, String label, boolean def) { super(key, label, def); }
        public boolean on() { return value; }
        public void set(boolean v) { value = v; }
        @Override public Object toJson() { return value; }
        @Override public void fromJson(Object o) { if (o instanceof Boolean) value = (Boolean) o; }
    }

    public static final class Int extends Setting<Integer> {
        public final int min, max;
        public Int(String key, String label, int def, int min, int max) {
            super(key, label, def);
            this.min = min;
            this.max = max;
        }
        public int val() { return value; }
        public void set(int v) { value = Math.max(min, Math.min(max, v)); }
        @Override public Object toJson() { return (long) value; }
        @Override public void fromJson(Object o) { if (o instanceof Number) set((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, ((Number) o).longValue()))); }
    }

    public static final class Dbl extends Setting<Double> {
        public final double min, max;
        public Dbl(String key, String label, double def, double min, double max) {
            super(key, label, def);
            this.min = min;
            this.max = max;
        }
        public double val() { return value; }
        public void set(double v) {
            if (Double.isNaN(v)) return;
            value = Math.max(min, Math.min(max, v));
        }
        @Override public Object toJson() { return value; }
        @Override public void fromJson(Object o) { if (o instanceof Number) set(((Number) o).doubleValue()); }
    }

    /** ARGB color. */
    public static final class Color extends Setting<Integer> {
        public Color(String key, String label, int argb) { super(key, label, argb); }
        public int argb() { return value; }
        public void set(int argb) { value = argb; }
        @Override public Object toJson() { return String.format("#%08X", value); }
        @Override public void fromJson(Object o) {
            if (!(o instanceof String)) return;
            String s = (String) o;
            if (s.startsWith("#")) s = s.substring(1);
            try {
                if (s.length() == 6) value = 0xFF000000 | (int) Long.parseLong(s, 16);
                else if (s.length() == 8) value = (int) Long.parseLong(s, 16);
            } catch (NumberFormatException ignored) { }
        }
    }

    public static final class Choice<E extends Enum<E>> extends Setting<E> {
        private final Class<E> type;
        public Choice(String key, String label, E def) {
            super(key, label, def);
            this.type = def.getDeclaringClass();
        }
        public E val() { return value; }
        public void set(E v) { if (v != null) value = v; }
        public E[] options() { return type.getEnumConstants(); }
        public void next() {
            E[] all = options();
            value = all[(value.ordinal() + 1) % all.length];
        }
        @Override public Object toJson() { return value.name(); }
        @Override public void fromJson(Object o) {
            if (!(o instanceof String)) return;
            for (E e : options()) if (e.name().equals(o)) { value = e; return; }
        }
    }

    public static final class Text extends Setting<String> {
        public final int maxLength;
        public Text(String key, String label, String def, int maxLength) {
            super(key, label, def);
            this.maxLength = maxLength;
        }
        public String str() { return value; }
        public void set(String v) { if (v != null) value = v.length() > maxLength ? v.substring(0, maxLength) : v; }
        @Override public Object toJson() { return value; }
        @Override public void fromJson(Object o) { if (o instanceof String) set((String) o); }
    }
}
