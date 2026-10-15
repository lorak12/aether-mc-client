package gg.aether.core;

/**
 * A persisted, UI-renderable option. Bad or out-of-range stored values fall back to / clamp toward the
 * default instead of throwing, so a hand-edited config can never crash the game.
 *
 * <p>The menu lists a mod's settings in the order the mod {@code add()}s them. Every mod uses the same order:
 * <ol>
 *   <li>Look, in this order when present: {@link #LOOK_ORDER} (text color, text shadow, background, background
 *   color, border, border color, border thickness), then other colours, then sizes (padding, radius, scale...).</li>
 *   <li>The mod's own options (what to show, behaviour toggles).</li>
 *   <li>Tables (lists the player edits).</li>
 *   <li>Keybinds, each followed by its hold/toggle mode.</li>
 *   <li>Buttons ({@link Action}).</li>
 * </ol>
 * Options that only matter while another one is on hang under it with {@link #dependsOn}: they are hidden (but
 * still saved, loaded and reset) while the parent is off, and drawn indented under it.
 */
public abstract class Setting<T> {
    /** How the menu edits a setting. */
    public enum Kind { COLOR_PICKER, TOGGLE, NUMBER_VALUE, TEXT_VALUE, OPTION, SLIDER, KEYBIND, BUTTON, TABLE, PIXEL_GRID }

    public final String key;
    public final String label;
    protected T value;
    protected final T def;

    /** False for settings that mirror data kept elsewhere (they are shown but not saved in profiles). */
    public boolean persist = true;

    /** Keys of the shared look settings, in the order every mod declares them (see the class comment). */
    public static final java.util.List<String> LOOK_ORDER = java.util.Collections.unmodifiableList(java.util.Arrays.asList(
            "textColor", "shadow", "background", "bgColor", "border", "borderColor", "borderWidth"));

    private Setting<?> parent;
    private java.util.function.BooleanSupplier shownWhen;

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
    public abstract Kind kind();

    /**
     * Show this setting only while {@code parent} is "on" (a toggle that is on, a key that is bound), drawn indented
     * under it. Returns this, typed as the caller's field: {@code Setting.Color c = add(new Setting.Color(..)).dependsOn(border);}
     */
    @SuppressWarnings("unchecked")
    public final <S extends Setting<T>> S dependsOn(Setting<?> parent) {
        this.parent = parent;
        return (S) this;
    }

    /** Show this setting only while {@code condition} holds (e.g. a style that uses it is picked). Not indented. */
    @SuppressWarnings("unchecked")
    public final <S extends Setting<T>> S visibleWhen(java.util.function.BooleanSupplier condition) {
        this.shownWhen = condition;
        return (S) this;
    }

    public Setting<?> parent() { return parent; }

    /** True when the menu should list this setting (its parents are visible and on, its condition holds). */
    public boolean visible() {
        if (parent != null && (!parent.visible() || !parent.enables())) return false;
        return shownWhen == null || shownWhen.getAsBoolean();
    }

    /** How many parents deep this setting hangs (0 = top level); the menu indents by it. */
    public int depth() { return parent == null ? 0 : parent.depth() + 1; }

    /** Whether settings that {@link #dependsOn} this one are shown. */
    protected boolean enables() { return true; }

    public static final class Bool extends Setting<Boolean> {
        public Bool(String key, String label, boolean def) { super(key, label, def); }
        public boolean on() { return value; }
        public void set(boolean v) { value = v; }
        @Override protected boolean enables() { return value; }
        @Override public Object toJson() { return value; }
        @Override public void fromJson(Object o) { if (o instanceof Boolean) value = (Boolean) o; }
        @Override public Kind kind() { return Kind.TOGGLE; }
    }

    public static final class Int extends Setting<Integer> {
        public final int min, max;
        public Int(String key, String label, int def, int min, int max) {
            super(key, label, def);
            this.min = min;
            this.max = max;
        }
        /** Null: decided by the range (a slider unless it has more than 1000 steps). */
        private Boolean slider;
        public int val() { return value; }
        public void set(int v) { value = Math.max(min, Math.min(max, v)); }
        /** Edit as a typed number with +/- instead of a slider. */
        public Int number() { slider = false; return this; }
        public Int slider() { slider = true; return this; }
        @Override public Kind kind() { return (slider != null ? slider : (long) max - min <= 1000) ? Kind.SLIDER : Kind.NUMBER_VALUE; }
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
        private Boolean slider;
        public double val() { return value; }
        public Dbl number() { slider = false; return this; }
        public Dbl slider() { slider = true; return this; }
        /** Sliders snap to hundredths, so huge ranges are typed instead. */
        @Override public Kind kind() { return (slider != null ? slider : max - min <= 1000) ? Kind.SLIDER : Kind.NUMBER_VALUE; }
        public void set(double v) {
            if (Double.isNaN(v)) return;
            value = Math.max(min, Math.min(max, v));
        }
        @Override public Object toJson() { return value; }
        @Override public void fromJson(Object o) { if (o instanceof Number) set(((Number) o).doubleValue()); }
    }

    /** ARGB color. */
    /**
     * An ARGB colour. With chroma on, {@link #argb()} cycles the hue over time (a rainbow) from the stored base colour;
     * saved as "#AARRGGBB", or as {"color", "chroma", "speed"} while chroma is on.
     */
    public static final class Color extends Setting<Integer> {
        /** Clock for chroma (ms); tests replace it. */
        public static java.util.function.LongSupplier clock = System::currentTimeMillis;
        public static final double MIN_SPEED = 0.5, MAX_SPEED = 20, DEFAULT_SPEED = 4;

        private boolean chroma;
        /** Seconds per full hue cycle. */
        private double speed = DEFAULT_SPEED;

        public Color(String key, String label, int argb) { super(key, label, argb); }

        /** The colour to draw: the base colour, or its chroma-shifted hue when chroma is on. */
        public int argb() { return chroma ? chromaAt(value, speed, clock.getAsLong()) : value; }
        /** The stored colour (what the picker edits), ignoring chroma. */
        public int base() { return value; }
        public void set(int argb) { value = argb; }

        public boolean chroma() { return chroma; }
        public void chroma(boolean on) { chroma = on; }
        public double speed() { return speed; }
        public void speed(double secondsPerCycle) { speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, secondsPerCycle)); }

        @Override public void reset() {
            super.reset();
            chroma = false;
            speed = DEFAULT_SPEED;
        }

        /** {@code base} with its hue advanced by {@code nowMs} over a {@code speed}-second cycle (grey/dark bases get colour). */
        public static int chromaAt(int base, double speed, long nowMs) {
            float r = ((base >> 16) & 0xFF) / 255f, g = ((base >> 8) & 0xFF) / 255f, b = (base & 0xFF) / 255f;
            float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
            float h;
            if (d == 0) h = 0;
            else if (max == r) h = 60 * (((g - b) / d) % 6);
            else if (max == g) h = 60 * ((b - r) / d + 2);
            else h = 60 * ((r - g) / d + 4);
            float sat = Math.max(0.65f, max == 0 ? 0 : d / max), val = Math.max(0.75f, max);
            double cycle = Math.max(MIN_SPEED, speed) * 1000.0;
            float hue = (float) ((h + (nowMs % (long) cycle) / cycle * 360.0) % 360.0);
            float c = val * sat, x = c * (1 - Math.abs((hue / 60) % 2 - 1)), m = val - c;
            float rr, gg, bb;
            if (hue < 60) { rr = c; gg = x; bb = 0; }
            else if (hue < 120) { rr = x; gg = c; bb = 0; }
            else if (hue < 180) { rr = 0; gg = c; bb = x; }
            else if (hue < 240) { rr = 0; gg = x; bb = c; }
            else if (hue < 300) { rr = x; gg = 0; bb = c; }
            else { rr = c; gg = 0; bb = x; }
            return (base & 0xFF000000) | (Math.round((rr + m) * 255) << 16) | (Math.round((gg + m) * 255) << 8) | Math.round((bb + m) * 255);
        }

        @Override public Object toJson() {
            String hex = String.format("#%08X", value);
            if (!chroma) return hex;
            java.util.Map<String, Object> o = new java.util.LinkedHashMap<String, Object>();
            o.put("color", hex);
            o.put("chroma", true);
            o.put("speed", speed);
            return o;
        }

        @Override public void fromJson(Object o) {
            if (o instanceof java.util.Map) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
                fromJson(m.get("color"));
                chroma = Boolean.TRUE.equals(m.get("chroma"));
                if (m.get("speed") instanceof Number) speed(((Number) m.get("speed")).doubleValue());
                return;
            }
            if (!(o instanceof String)) return;
            chroma = false;
            String s = (String) o;
            if (s.startsWith("#")) s = s.substring(1);
            try {
                if (s.length() == 6) value = 0xFF000000 | (int) Long.parseLong(s, 16);
                else if (s.length() == 8) value = (int) Long.parseLong(s, 16);
            } catch (NumberFormatException ignored) { }
        }
        @Override public Kind kind() { return Kind.COLOR_PICKER; }
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
        @Override public Kind kind() { return Kind.OPTION; }
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
        @Override public Kind kind() { return Kind.TEXT_VALUE; }
    }

    /** GLFW key code, or {@link Keys#NONE} when unbound. Every key can be unbound. */
    public static final class Key extends Setting<Integer> {
        /** Keys this setting refuses (e.g. Escape for the menu key); null accepts any. */
        private java.util.function.IntPredicate refuse;

        public Key(String key, String label, int code) { super(key, label, code); }
        public int code() { return value; }
        public boolean bound() { return value != Keys.NONE; }
        /** Binds {@code code} (anything below 0 unbinds). A refused key leaves the binding as it was; returns false then. */
        public boolean set(int code) {
            if (code < 0) code = Keys.NONE;
            if (code != Keys.NONE && !accepts(code)) return false;
            value = code;
            return true;
        }
        public void unbind() { value = Keys.NONE; }
        public boolean accepts(int code) { return code == Keys.NONE || refuse == null || !refuse.test(code); }
        /** Refuse binding keys matching {@code test}; a refused stored value loads as the default. */
        public Key refuse(java.util.function.IntPredicate test) {
            refuse = test;
            return this;
        }
        @Override protected boolean enables() { return value != Keys.NONE; }
        @Override public Object toJson() { return (long) value; }
        @Override public void fromJson(Object o) {
            if (!(o instanceof Number)) return;
            int code = (int) Math.max(-1, Math.min(1000, ((Number) o).longValue()));
            value = accepts(code) ? code : def;
        }
        @Override public Kind kind() { return Kind.KEYBIND; }
    }

    /** A button in the mod's settings (open a view, reload, export...). Not saved. */
    public static final class Action extends Setting<String> {
        private final Runnable run;
        public Action(String key, String label, String button, Runnable run) {
            super(key, label, button);
            this.run = run;
            persist = false;
        }
        public String button() { return value; }
        public void run() { run.run(); }
        @Override public void reset() { }
        @Override public Object toJson() { return null; }
        @Override public void fromJson(Object o) { }
        @Override public Kind kind() { return Kind.BUTTON; }

        /** A button that copies {@code text} (computed on click) to the clipboard via {@code host}. */
        public static Action copy(String key, String label, final java.util.function.Supplier<String> text, final java.util.function.Supplier<ClientHost> host) {
            return new Action(key, label, "Copy", () -> host.get().copyText(text.get()));
        }
    }

    /**
     * An editable table (waypoints, friends, filters): rows of text cells under fixed columns. Each cell is a
     * {@link Text} so the menu can edit it in place. Stored as a JSON list of {column: value} objects.
     */
    public static final class Entries extends Setting<java.util.List<Text[]>> {
        public final String[] columns, headers;
        public final int maxRows;

        public Entries(String key, String label, String[] columns, String[] headers, int maxRows) {
            super(key, label, new java.util.ArrayList<Text[]>());
            this.columns = columns;
            this.headers = headers;
            this.maxRows = maxRows;
            value = new java.util.ArrayList<Text[]>();
        }

        @Override public Kind kind() { return Kind.TABLE; }
        public java.util.List<Text[]> rows() { return value; }
        public int size() { return value.size(); }

        /** Cell text of row {@code row}, column {@code col}. */
        public String cell(int row, int col) { return value.get(row)[col].str(); }

        public Text[] add(String... cells) {
            if (value.size() >= maxRows) return null;
            Text[] row = new Text[columns.length];
            for (int i = 0; i < columns.length; i++) {
                row[i] = new Text(columns[i], headers[i], "", 200);
                row[i].set(i < cells.length && cells[i] != null ? cells[i] : "");
            }
            value.add(row);
            return row;
        }

        public void remove(int row) { if (row >= 0 && row < value.size()) value.remove(row); }
        public void clear() { value.clear(); }

        @Override public void reset() { value.clear(); }

        /** A value-based fingerprint so owners can notice edits made in the menu. */
        public String fingerprint() {
            StringBuilder b = new StringBuilder();
            for (Text[] row : value) {
                for (Text t : row) b.append(t.str()).append('\u0001');
                b.append('\u0002');
            }
            return b.toString();
        }

        @Override public Object toJson() {
            java.util.List<Object> out = new java.util.ArrayList<Object>();
            for (Text[] row : value) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
                for (int i = 0; i < columns.length; i++) m.put(columns[i], row[i].str());
                out.add(m);
            }
            return out;
        }

        @Override public void fromJson(Object o) {
            if (!(o instanceof java.util.List)) return;
            value.clear();
            for (Object r : (java.util.List<?>) o) {
                if (!(r instanceof java.util.Map)) continue;
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) r;
                String[] cells = new String[columns.length];
                for (int i = 0; i < columns.length; i++) cells[i] = m.get(columns[i]) == null ? "" : String.valueOf(m.get(columns[i]));
                add(cells);
            }
        }
    }

    /**
     * A square on/off pixel drawing (custom crosshairs); odd sizes have a centre pixel. Saved as one string per row, top
     * first, '#' for a set pixel and '.' for a clear one. A drawing of another size loads centred.
     */
    public static final class PixelGrid extends Setting<boolean[]> {
        /** Draws the owner's real rendering of the drawing centred on (cx, cy), for the editor's 1:1 preview. */
        public interface Preview { void draw(UiRenderer r, float cx, float cy); }

        public final int size;
        /** Colour the editor paints with (usually the owner's colour setting). */
        public java.util.function.IntSupplier ink = () -> 0xFFFFFFFF;
        /** Null: the editor previews plain pixels in {@link #ink}. */
        public Preview preview;

        /** {@code rows}: the default drawing, in the saved format. */
        public PixelGrid(String key, String label, int size, String... rows) {
            super(key, label, parse(size, java.util.Arrays.asList((Object[]) rows)));
            this.size = size;
            value = def.clone();
        }

        public int center() { return size / 2; }
        public boolean at(int x, int y) { return x >= 0 && y >= 0 && x < size && y < size && value[y * size + x]; }
        public void set(int x, int y, boolean on) { if (x >= 0 && y >= 0 && x < size && y < size) value[y * size + x] = on; }
        public void clear() { java.util.Arrays.fill(value, false); }
        /** Set pixels. */
        public int count() {
            int n = 0;
            for (boolean b : value) if (b) n++;
            return n;
        }
        public boolean[] snapshot() { return value.clone(); }
        public void restore(boolean[] px) { if (px != null && px.length == value.length) System.arraycopy(px, 0, value, 0, px.length); }
        @Override public void reset() { value = def.clone(); }

        /** Horizontal runs of set pixels as {x, y, length}, top to bottom, left to right. */
        public java.util.List<int[]> runs() {
            java.util.List<int[]> out = new java.util.ArrayList<int[]>();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (!value[y * size + x]) continue;
                    int start = x;
                    while (x + 1 < size && value[y * size + x + 1]) x++;
                    out.add(new int[] { start, y, x - start + 1 });
                }
            }
            return out;
        }

        @Override public Object toJson() {
            java.util.List<Object> rows = new java.util.ArrayList<Object>();
            for (int y = 0; y < size; y++) {
                StringBuilder b = new StringBuilder(size);
                for (int x = 0; x < size; x++) b.append(value[y * size + x] ? '#' : '.');
                rows.add(b.toString());
            }
            return rows;
        }

        @Override public void fromJson(Object o) { if (o instanceof java.util.List) value = parse(size, (java.util.List<?>) o); }
        @Override public Kind kind() { return Kind.PIXEL_GRID; }

        /** Rows of '#'/'.' (also '1'/'0', 'x') into a {@code size}² grid, centred; anything else is clear. */
        static boolean[] parse(int size, java.util.List<?> rows) {
            boolean[] px = new boolean[size * size];
            int oy = (size - rows.size()) / 2;
            for (int r = 0; r < rows.size(); r++) {
                if (!(rows.get(r) instanceof String)) continue;
                String s = (String) rows.get(r);
                int y = oy + r, ox = (size - s.length()) / 2;
                if (y < 0 || y >= size) continue;
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    int x = ox + i;
                    if (x >= 0 && x < size && (c == '#' || c == '1' || c == 'x' || c == 'X')) px[y * size + x] = true;
                }
            }
            return px;
        }
    }
}
