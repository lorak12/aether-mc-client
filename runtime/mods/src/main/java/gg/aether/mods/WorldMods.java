package gg.aether.mods;

import gg.aether.core.Events;
import gg.aether.core.GameState;
import gg.aether.core.HudElement;
import gg.aether.core.HudElement.Anchor;
import gg.aether.core.Hotkey;
import gg.aether.core.Keys;
import gg.aether.core.Mod;
import gg.aether.core.Setting;
import gg.aether.core.UiRenderer;
import gg.aether.core.WorldRenderer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Overlays drawn in the world (chunk borders, light levels, waypoints, block helpers) and the maps. */
public final class WorldMods {
    private WorldMods() {}

    static int bx(GameState s) { return (int) Math.floor(s.x()); }
    static int by(GameState s) { return (int) Math.floor(s.y()); }
    static int bz(GameState s) { return (int) Math.floor(s.z()); }

    /** "#RRGGBB", "#AARRGGBB" or a colour name; {@code def} when unparsable. */
    static int parseColor(String s, int def) {
        if (s == null) return def;
        String t = s.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "red": return 0xFFFF5555;
            case "green": return 0xFF55FF55;
            case "blue": return 0xFF5599FF;
            case "yellow": return 0xFFFFFF55;
            case "purple": return 0xFFA78BFA;
            case "aqua": case "cyan": return 0xFF55FFFF;
            case "orange": return 0xFFFFAA00;
            case "white": return 0xFFFFFFFF;
            case "pink": return 0xFFFF77CC;
            default: break;
        }
        if (t.startsWith("#")) t = t.substring(1);
        try {
            if (t.length() == 6) return 0xFF000000 | Integer.parseInt(t, 16);
            if (t.length() == 8) return (int) Long.parseLong(t, 16);
        } catch (NumberFormatException ignored) { }
        return def;
    }

    static int alpha(int argb, int a) { return (a << 24) | (argb & 0xFFFFFF); }

    // ---- Chunk borders ------------------------------------------------------------------------------------------

    public static final class ChunkBorders extends Mod {
        public enum Style { VANILLA, CUSTOM }

        private final Setting.Choice<Style> style = add(new Setting.Choice<Style>("style", "Style", Style.CUSTOM));
        // Everything below the style only applies to the custom lines.
        private final Setting.Color color = add(new Setting.Color("color", "Current chunk color", 0xFFFFDD55)).visibleWhen(this::custom);
        private final Setting.Color neighborColor = add(new Setting.Color("neighborColor", "Neighbour chunks color", 0x8855AAFF)).visibleWhen(this::custom);
        private final Setting.Int width = add(new Setting.Int("width", "Line thickness", 2, 1, 6)).visibleWhen(this::custom);
        private final Setting.Bool subchunks = add(new Setting.Bool("subchunks", "Subchunk (16-high) lines", true)).visibleWhen(this::custom);
        private final Setting.Bool grid = add(new Setting.Bool("grid", "Block grid on the walls", false)).visibleWhen(this::custom);
        private final Setting.Bool coords = add(new Setting.Bool("coords", "Chunk coordinates label", true)).visibleWhen(this::custom);
        private final Setting.Bool slime = add(new Setting.Bool("slime", "Slime chunks (singleplayer)", false)).visibleWhen(this::custom);
        private final Setting.Color slimeColor = add(new Setting.Color("slimeColor", "Slime chunk color", 0x4455FF55), slime);
        private final Hotkey key = hotkey("key", "Toggle key", Keys.NONE, Hotkey.Mode.TOGGLE);

        private boolean custom() { return style.val() == Style.CUSTOM; }
        private boolean off;

        public ChunkBorders() { super("chunkborders", "Chunk Borders", "Chunk and subchunk boundaries, slime chunks and chunk coordinates", Category.WORLD, true); }

        @Override protected void onEnable() { off = false; }
        @Override protected void onHotkey(Hotkey h) { off = key.active(); }

        @Override public void onTick(GameState s) { if (!off && style.val() == Style.VANILLA) fx.showChunkBorders = true; }

        @Override public void renderWorld(WorldRenderer w, GameState s) {
            if (off || style.val() != Style.CUSTOM || !s.inWorld()) return;
            int cx = bx(s) >> 4, cz = bz(s) >> 4, py = by(s);
            double minY = Math.max(s.minBuildY(), py - 48), maxY = Math.min(s.maxBuildY() + 1, py + 48);
            float wd = width.val();
            // Neighbour chunk corners.
            for (int dx = -1; dx <= 2; dx++) {
                for (int dz = -1; dz <= 2; dz++) {
                    if ((dx == 0 || dx == 1) && (dz == 0 || dz == 1)) continue;
                    double x = (cx + dx) * 16, z = (cz + dz) * 16;
                    w.line(x, minY, z, x, maxY, z, neighborColor.argb(), 1f, false);
                }
            }
            double x0 = cx * 16, z0 = cz * 16, x1 = x0 + 16, z1 = z0 + 16;
            int c = color.argb();
            w.line(x0, minY, z0, x0, maxY, z0, c, wd, false);
            w.line(x1, minY, z0, x1, maxY, z0, c, wd, false);
            w.line(x0, minY, z1, x0, maxY, z1, c, wd, false);
            w.line(x1, minY, z1, x1, maxY, z1, c, wd, false);
            int step = grid.on() ? 2 : 16;
            int yStart = (int) Math.ceil(minY / step) * step;
            for (int y = yStart; y <= maxY; y += step) {
                if (!subchunks.on() && y % 16 == 0 && !grid.on()) continue;
                int lc = y % 16 == 0 ? c : alpha(c, 0x66);
                w.line(x0, y, z0, x1, y, z0, lc, 1f, false);
                w.line(x1, y, z0, x1, y, z1, lc, 1f, false);
                w.line(x1, y, z1, x0, y, z1, lc, 1f, false);
                w.line(x0, y, z1, x0, y, z0, lc, 1f, false);
            }
            if (grid.on()) {
                for (int i = 2; i < 16; i += 2) {
                    int gc = alpha(c, 0x44);
                    w.line(x0 + i, minY, z0, x0 + i, maxY, z0, gc, 1f, false);
                    w.line(x0 + i, minY, z1, x0 + i, maxY, z1, gc, 1f, false);
                    w.line(x0, minY, z0 + i, x0, maxY, z0 + i, gc, 1f, false);
                    w.line(x1, minY, z0 + i, x1, maxY, z0 + i, gc, 1f, false);
                }
            }
            if (slime.on()) {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        Boolean sl = s.slimeChunk(cx + dx, cz + dz);
                        if (sl == null) break;
                        if (sl) w.box((cx + dx) * 16, py - 0.99, (cz + dz) * 16, (cx + dx) * 16 + 16, py - 0.95, (cz + dz) * 16 + 16, 0, 1f, slimeColor.argb(), false);
                    }
                }
            }
            if (coords.on()) w.text("Chunk " + cx + ", " + cz + "  (section " + (py >> 4) + ")", x0 + 8, s.y() + 2.8, z0 + 8, c, 1f, false);
        }
    }

    // ---- Light overlay ------------------------------------------------------------------------------------------

    public static final class LightOverlay extends Mod {
        public enum Style { CROSSES, NUMBERS, SQUARES }

        private final Setting.Color danger = add(new Setting.Color("danger", "Always spawnable", 0xFFFF3030));
        private final Setting.Int opacity = add(new Setting.Int("opacity", "Opacity (%)", 80, 10, 100));
        private final Setting.Choice<Style> style = add(new Setting.Choice<Style>("style", "Style", Style.CROSSES));
        private final Setting.Int radius = add(new Setting.Int("radius", "Radius", 16, 4, 32));
        private final Setting.Int threshold = add(new Setting.Int("threshold", "Spawn at block light <= (0 since 1.18)", 0, 0, 14));
        private final Setting.Bool nightOnly = add(new Setting.Bool("nightOnly", "Show sky-lit spots (spawn at night)", true));
        private final Setting.Color night = add(new Setting.Color("night", "Spawnable at night", 0xFFFFD030), nightOnly);
        private final Hotkey key = hotkey("key", "Toggle key", Keys.F10, Hotkey.Mode.TOGGLE);
        private boolean on = true;
        private final List<int[]> spots = new ArrayList<int[]>(); // x, y, z, blockLight, skyLight
        private int lastX = Integer.MIN_VALUE, lastY, lastZ, ticks;

        public LightOverlay() { super("lightoverlay", "Light Overlay", "Marks blocks where mobs can spawn (light level)", Category.WORLD, true); }

        @Override protected void onEnable() { on = true; spots.clear(); lastX = Integer.MIN_VALUE; }
        @Override protected void onHotkey(Hotkey h) { on = !key.active(); }

        @Override public void onTick(GameState s) {
            if (!on || !s.inWorld()) return;
            ticks++;
            int x = bx(s), y = by(s), z = bz(s);
            if (x == lastX && y == lastY && z == lastZ && ticks % 20 != 0) return;
            lastX = x;
            lastY = y;
            lastZ = z;
            spots.clear();
            int r = radius.val();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int yy = y + 4; yy >= y - 8; yy--) {
                        if (!s.spawnable(x + dx, yy, z + dz)) continue;
                        int bl = s.blockLight(x + dx, yy, z + dz), sl = s.skyLight(x + dx, yy, z + dz);
                        if (bl > threshold.val()) continue;
                        if (sl > threshold.val() && !nightOnly.on()) continue;
                        spots.add(new int[] { x + dx, yy, z + dz, bl, sl });
                    }
                }
            }
        }

        List<int[]> spots() { return spots; }

        @Override public void renderWorld(WorldRenderer w, GameState s) {
            if (!on) return;
            int a = Math.round(255 * opacity.val() / 100f);
            for (int[] p : spots) {
                int c = alpha(p[4] > threshold.val() ? night.argb() : danger.argb(), a);
                double y = p[1] + 0.02;
                switch (style.val()) {
                    case NUMBERS:
                        w.text(String.valueOf(p[3]), p[0] + 0.5, p[1] + 0.3, p[2] + 0.5, c, 0.8f, false);
                        break;
                    case SQUARES:
                        w.box(p[0] + 0.05, y - 0.01, p[2] + 0.05, p[0] + 0.95, y, p[2] + 0.95, 0, 1f, alpha(c, a / 2), false);
                        break;
                    default:
                        w.line(p[0] + 0.1, y, p[2] + 0.1, p[0] + 0.9, y, p[2] + 0.9, c, 2f, false);
                        w.line(p[0] + 0.9, y, p[2] + 0.1, p[0] + 0.1, y, p[2] + 0.9, c, 2f, false);
                        break;
                }
            }
        }
    }

    // ---- Waypoints ----------------------------------------------------------------------------------------------

    /** Waypoints per world (and dimension), with beams, labels, a nearest-waypoint HUD and death waypoints. */
    public static final class Waypoints extends HudTextMod {
        static final int NAME = 0, X = 1, Y = 2, Z = 3, DIM = 4, COLOR = 5;

        private final Setting.Int hudCount = add(new Setting.Int("hudCount", "Nearest waypoints in the HUD (0 = off)", 3, 0, 10));
        private final Setting.Bool beams = add(new Setting.Bool("beams", "Beams", true));
        private final Setting.Bool labels = add(new Setting.Bool("labels", "Floating labels", true));
        private final Setting.Bool showDistance = add(new Setting.Bool("distance", "Distance in labels", true), labels);
        private final Setting.Bool throughWalls = add(new Setting.Bool("throughWalls", "Show through walls", true));
        private final Setting.Int maxDistance = add(new Setting.Int("maxDistance", "Hide beyond (blocks, 0 = never)", 0, 0, 100000));
        private final Setting.Int autoHide = add(new Setting.Int("autoHide", "Hide when closer than (blocks)", 3, 0, 64));
        private final Setting.Bool death = add(new Setting.Bool("death", "Create a waypoint on death", true));
        private final Setting.Bool deathDelete = add(new Setting.Bool("deathDelete", "Remove death waypoints when reached", true), death);
        private final Setting.Entries list = add(new Setting.Entries("list", "Waypoints in this world",
                new String[] { "name", "x", "y", "z", "dim", "color" }, new String[] { "Name", "X", "Y", "Z", "Dimension", "Color" }, 200));
        private final Hotkey addKey = hotkey("addKey", "Add waypoint key", Keys.B, Hotkey.Mode.TOGGLE);
        /** "+ Add here" in the menu's Waypoints tab. */
        private final Setting.Action addHere = add(new Setting.Action("addHere", "Add a waypoint at your position", "Add here", this::addAtPlayer));
        private String world = "", fingerprint = "";

        public Waypoints() {
            super("waypoints", "Waypoints", "Waypoints per world with beams, labels, HUD list and death points", Category.WORLD, Anchor.TOP_RIGHT, -4, 300);
            list.persist = false; // lives in per-world data, not in profiles
            sensitive = true;
        }

        @Override protected void onEnable() {
            events.on(Events.Death.class, this, e -> {
                if (!death.on()) return;
                list.add("☠ Death", String.valueOf(Math.round(e.x)), String.valueOf(Math.round(e.y)), String.valueOf(Math.round(e.z)), e.dimension, "red");
                toasts.info(e.timeMs, String.format(Locale.ROOT, "Death waypoint at %.0f %.0f %.0f", e.x, e.y, e.z));
            });
        }

        @Override protected void onHotkey(Hotkey h) {
            if (h == addKey) addAtPlayer();
        }

        private void addAtPlayer() {
            if (lastState == null || !lastState.inWorld()) return;
            GameState s = lastState;
            String name = "Waypoint " + (list.size() + 1);
            if (list.add(name, String.valueOf(bx(s)), String.valueOf(by(s)), String.valueOf(bz(s)), s.dimension(), "aqua") != null) {
                toasts.info(s.nowMillis(), "Added \"" + name + "\" (edit it in the menu)");
            }
        }

        private GameState lastState;

        /** Keep the table and the per-world data in sync: load on world change, save after edits. */
        @Override public void onTick(GameState s) {
            lastState = s;
            if (!s.inWorld()) return;
            String wk = StatusMods.Playtime.worldKey(s);
            if (!wk.equals(world)) {
                world = wk;
                list.clear();
                for (Object o : data.getList("w:" + wk)) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) o;
                    list.add(str(m.get("name")), str(m.get("x")), str(m.get("y")), str(m.get("z")), str(m.get("dim")), str(m.get("color")));
                }
                fingerprint = list.fingerprint();
            }
            if (deathDelete.on()) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (!list.cell(i, NAME).startsWith("☠") || !list.cell(i, DIM).equals(s.dimension())) continue;
                    double d = dist(s, i);
                    if (d >= 0 && d < 4) list.remove(i);
                }
            }
            String fp = list.fingerprint();
            if (!fp.equals(fingerprint)) {
                fingerprint = fp;
                data.put("w:" + world, list.toJson());
            }
        }

        private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

        static double num(String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        /** Distance from the player to waypoint {@code i}, -1 if its coordinates are not numbers. */
        double dist(GameState s, int i) {
            double x = num(list.cell(i, X)), y = num(list.cell(i, Y)), z = num(list.cell(i, Z));
            if (Double.isNaN(x) || Double.isNaN(z)) return -1;
            if (Double.isNaN(y)) y = s.y();
            double dx = x + 0.5 - s.x(), dy = y - s.y(), dz = z + 0.5 - s.z();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        boolean inDimension(GameState s, int i) {
            String d = list.cell(i, DIM).trim();
            return d.isEmpty() || d.equals(s.dimension()) || s.dimension().endsWith(d);
        }

        /** Waypoints of the current dimension, as {x, y, z, color, index}. */
        List<double[]> visible(GameState s) {
            List<double[]> out = new ArrayList<double[]>();
            for (int i = 0; i < list.size(); i++) {
                if (!inDimension(s, i)) continue;
                double x = num(list.cell(i, X)), y = num(list.cell(i, Y)), z = num(list.cell(i, Z));
                if (Double.isNaN(x) || Double.isNaN(z)) continue;
                out.add(new double[] { x, Double.isNaN(y) ? s.y() : y, z, parseColor(list.cell(i, COLOR), 0xFF55FFFF), i });
            }
            return out;
        }

        String name(int i) { return list.cell(i, NAME); }
        Setting.Entries entries() { return list; }

        @Override public void renderWorld(WorldRenderer w, GameState s) {
            for (double[] p : visible(s)) {
                int i = (int) p[4];
                double d = dist(s, i);
                if (d < autoHide.val() || (maxDistance.val() > 0 && d > maxDistance.val())) continue;
                int c = (int) p[3];
                double x = p[0] + 0.5, z = p[2] + 0.5;
                if (beams.on()) w.line(x, p[1], z, x, Math.max(p[1] + 1, s.maxBuildY() + 64), z, alpha(c, 0xAA), 3f, throughWalls.on());
                if (!labels.on()) continue;
                // Far labels are pulled toward the camera so they stay readable; the text still says the real distance.
                double cx = w.cameraX(), cy = w.cameraY(), cz = w.cameraZ();
                double lx = x, ly = p[1] + 1.5, lz = z, ld = Math.sqrt((lx - cx) * (lx - cx) + (ly - cy) * (ly - cy) + (lz - cz) * (lz - cz));
                double maxLabel = 48;
                if (ld > maxLabel) {
                    double f = maxLabel / ld;
                    lx = cx + (lx - cx) * f;
                    ly = cy + (ly - cy) * f;
                    lz = cz + (lz - cz) * f;
                    ld = maxLabel;
                }
                String t = name(i) + (showDistance.on() ? String.format(Locale.ROOT, " (%.0fm)", d) : "");
                w.text(t, lx, ly, lz, c, (float) Math.max(1.0, ld / 10.0), throughWalls.on());
            }
        }

        @Override protected List<String> lines(GameState s) {
            if (hudCount.val() == 0 || !s.inWorld()) return Collections.emptyList();
            List<double[]> v = visible(s);
            Collections.sort(v, new Comparator<double[]>() {
                @Override public int compare(double[] a, double[] b) { return Double.compare(dist(s, (int) a[4]), dist(s, (int) b[4])); }
            });
            List<String> l = new ArrayList<String>();
            for (int k = 0; k < v.size() && k < hudCount.val(); k++) {
                double[] p = v.get(k);
                l.add(arrow(s, p[0] + 0.5, p[2] + 0.5) + " " + name((int) p[4]) + String.format(Locale.ROOT, "  %.0fm", dist(s, (int) p[4])));
            }
            return l;
        }

        /** Arrow pointing from the player's view direction toward (x, z). */
        static String arrow(GameState s, double x, double z) {
            double angle = Math.toDegrees(Math.atan2(-(x - s.x()), z - s.z())); // Minecraft yaw of the direction
            double rel = ((angle - s.yaw()) % 360 + 540) % 360 - 180; // -180..180, 0 = straight ahead
            String[] arrows = { "↑", "↗", "→", "↘", "↓", "↙", "←", "↖" };
            return arrows[(int) Math.round((rel + 360) % 360 / 45.0) % 8];
        }
    }

    // ---- Block overlay / selection helper -----------------------------------------------------------------------

    public static final class BlockOverlay extends Mod {
        private final Setting.Color outline = add(new Setting.Color("outline", "Outline color", 0xFF8B5CF6));
        private final Setting.Color fill = add(new Setting.Color("fill", "Fill color", 0x228B5CF6));
        private final Setting.Int width = add(new Setting.Int("width", "Outline thickness", 3, 1, 8));
        private final Setting.Bool face = add(new Setting.Bool("face", "Highlight the targeted face", true));
        private final Setting.Color faceColor = add(new Setting.Color("faceColor", "Face color", 0x448B5CF6), face);
        private final Setting.Bool throughWalls = add(new Setting.Bool("throughWalls", "Show through walls", false));
        private final Setting.Bool hideVanilla = add(new Setting.Bool("hideVanilla", "Hide the vanilla outline", true));
        private final Setting.Bool entities = add(new Setting.Bool("entities", "Also outline targeted entities", false));

        public BlockOverlay() { super("blockoverlay", "Block Overlay", "Custom outline, fill and face highlight for the block you look at", Category.VISUAL, true); }

        @Override public void onTick(GameState s) { if (hideVanilla.on()) fx.hideBlockOutline = true; }

        @Override public void renderWorld(WorldRenderer w, GameState s) {
            GameState.Target t = s.target();
            if (t.kind == GameState.Target.Kind.BLOCK) {
                draw(w, t, outline.argb(), width.val(), fill.argb(), face.on() ? faceColor.argb() : 0, throughWalls.on());
            } else if (t.kind == GameState.Target.Kind.ENTITY && entities.on()) {
                for (GameState.EntityInfo en : s.entities(8)) {
                    if (Math.floor(en.x) != t.x || Math.floor(en.z) != t.z) continue;
                    double hw = Math.max(0.3, en.width / 2);
                    w.box(en.x - hw, en.y, en.z - hw, en.x + hw, en.y + en.height, en.z + hw, outline.argb(), width.val(), fill.argb(), throughWalls.on());
                    break;
                }
            }
        }

        /** How far everything sits off the block surface so nothing z-fights; the face highlight sits twice as far. */
        static final double EPS = 0.002;

        /**
         * The targeted block's real outline (slabs, stairs, fences... not just the cube): {@code stroke} lines along the
         * shape's edges, {@code fill} on its outer faces, {@code faceArgb} on the face region the crosshair is on.
         */
        static void draw(WorldRenderer w, GameState.Target t, int stroke, float width, int fill, int faceArgb, boolean walls) {
            List<double[]> boxes = t.shapeBoxes();
            Shape sh = Shape.of(boxes);
            if (sh == null) {
                // Too many boxes to grid: outline each box on its own.
                for (double[] b : boxes) w.box(b[0] - EPS, b[1] - EPS, b[2] - EPS, b[3] + EPS, b[4] + EPS, b[5] + EPS, stroke, width, fill, walls);
                return;
            }
            if ((fill >>> 24) != 0) for (double[] f : sh.faces()) face(w, f, EPS, fill, walls);
            if ((stroke >>> 24) != 0) {
                for (double[] e : t.edges.isEmpty() ? sh.edges() : t.edges) {
                    w.line(sh.out(0, e[0]), sh.out(1, e[1]), sh.out(2, e[2]), sh.out(0, e[3]), sh.out(1, e[4]), sh.out(2, e[5]), stroke, width, walls);
                }
            }
            if ((faceArgb >>> 24) != 0) for (double[] f : sh.faceRegion(t.face, t.hitX, t.hitY, t.hitZ)) face(w, f, 2 * EPS, faceArgb, walls);
        }

        /** Face {minX, minY, minZ, maxX, maxY, maxZ, side} moved {@code out} along its normal. */
        private static void face(WorldRenderer w, double[] f, double out, int argb, boolean walls) {
            int side = (int) f[6], a = Shape.AXIS[side];
            double d = (side & 1) == 1 ? out : -out;
            double[] m = f.clone();
            m[a] += d;
            m[a + 3] += d;
            w.face(m[0], m[1], m[2], m[3], m[4], m[5], Shape.SIDES[side], argb, walls);
        }

        /**
         * A block shape (a few world-space boxes) on the grid of its distinct box coordinates, each cell solid or not. Outer
         * faces, visible edges and coplanar face regions fall out of it with no internal seams: a stair's two boxes
         * outline and fill as one solid, like vanilla's outline.
         */
        static final class Shape {
            /** Face sides; odd = the positive direction of the axis. */
            static final String[] SIDES = { "down", "up", "north", "south", "west", "east" };
            /** The axis (0 x, 1 y, 2 z) each side faces along. */
            static final int[] AXIS = { 1, 1, 2, 2, 0, 0 };
            private static final int MAX_CELLS = 4096;
            private static final double TOL = 1e-6;

            /** Sorted distinct coordinates per axis; cell (i, j, k) spans c[0][i]..c[0][i + 1] and so on. */
            final double[][] c;
            private final boolean[] solid;
            private final double[] center = new double[3];

            private Shape(double[][] c, List<double[]> boxes) {
                this.c = c;
                for (int a = 0; a < 3; a++) center[a] = (c[a][0] + c[a][c[a].length - 1]) / 2;
                solid = new boolean[n(0) * n(1) * n(2)];
                for (int i = 0; i < n(0); i++) for (int j = 0; j < n(1); j++) for (int k = 0; k < n(2); k++) {
                    double x = mid(0, i), y = mid(1, j), z = mid(2, k);
                    for (double[] b : boxes) {
                        if (x > b[0] && x < b[3] && y > b[1] && y < b[4] && z > b[2] && z < b[5]) {
                            solid[(i * n(1) + j) * n(2) + k] = true;
                            break;
                        }
                    }
                }
            }

            /** The grid of {@code boxes}, or null when there are none or too many cells to grid cheaply. */
            static Shape of(List<double[]> boxes) {
                if (boxes.isEmpty()) return null;
                double[][] c = new double[3][];
                long cells = 1;
                for (int a = 0; a < 3; a++) {
                    double[] v = new double[boxes.size() * 2];
                    for (int i = 0; i < boxes.size(); i++) {
                        v[2 * i] = boxes.get(i)[a];
                        v[2 * i + 1] = boxes.get(i)[a + 3];
                    }
                    Arrays.sort(v);
                    int n = 0;
                    for (double x : v) if (n == 0 || x - v[n - 1] > TOL) v[n++] = x;
                    if (n < 2) return null;
                    c[a] = Arrays.copyOf(v, n);
                    cells *= n - 1;
                }
                return cells > MAX_CELLS ? null : new Shape(c, boxes);
            }

            int n(int a) { return c[a].length - 1; }
            private double mid(int a, int i) { return (c[a][i] + c[a][i + 1]) / 2; }

            boolean solid(int i, int j, int k) {
                if (i < 0 || j < 0 || k < 0 || i >= n(0) || j >= n(1) || k >= n(2)) return false;
                return solid[(i * n(1) + j) * n(2) + k];
            }
            private boolean solid(int[] p) { return solid(p[0], p[1], p[2]); }

            /** {@code v} on axis {@code a} pushed {@link #EPS} away from the shape's centre. */
            double out(int a, double v) { return v + EPS * Math.signum(v - center[a]); }

            /** Every outer face as {minX, minY, minZ, maxX, maxY, maxZ, side}, one per exposed cell face. */
            List<double[]> faces() {
                List<double[]> out = new ArrayList<double[]>();
                int[] p = new int[3];
                for (int side = 0; side < 6; side++) {
                    for (p[0] = 0; p[0] < n(0); p[0]++) for (p[1] = 0; p[1] < n(1); p[1]++) for (p[2] = 0; p[2] < n(2); p[2]++) {
                        if (exposed(p, side)) out.add(rect(p, side));
                    }
                }
                return out;
            }

            /** Cell {@code p} is solid and its neighbour on {@code side} is not. */
            private boolean exposed(int[] p, int side) {
                if (!solid(p)) return false;
                int a = AXIS[side], dir = (side & 1) == 1 ? 1 : -1;
                p[a] += dir;
                boolean open = !solid(p);
                p[a] -= dir;
                return open;
            }

            /** The {@code side} face of cell {@code p}. */
            private double[] rect(int[] p, int side) {
                int a = AXIS[side];
                double[] r = new double[7];
                for (int b = 0; b < 3; b++) {
                    r[b] = c[b][p[b]];
                    r[b + 3] = c[b][p[b] + 1];
                }
                r[a] = r[a + 3] = c[a][p[a] + (side & 1)];
                r[6] = side;
                return r;
            }

            /**
             * The visible edges {x1, y1, z1, x2, y2, z2}: grid lines where the four cells around them are not a flat
             * surface (one or three solid, or two diagonal), merged into straight runs. The same rule as vanilla's outline.
             */
            List<double[]> edges() {
                List<double[]> out = new ArrayList<double[]>();
                int[] p = new int[3];
                for (int a = 0; a < 3; a++) {
                    int b = (a + 1) % 3, d = (a + 2) % 3;
                    for (int u = 0; u <= n(b); u++) for (int v = 0; v <= n(d); v++) {
                        int start = -1;
                        for (int s = 0; s <= n(a); s++) {
                            boolean edge = false;
                            if (s < n(a)) {
                                p[a] = s;
                                p[b] = u - 1; p[d] = v - 1;
                                boolean s00 = solid(p);
                                p[b] = u;
                                boolean s10 = solid(p);
                                p[d] = v;
                                boolean s11 = solid(p);
                                p[b] = u - 1;
                                boolean s01 = solid(p);
                                int count = (s00 ? 1 : 0) + (s10 ? 1 : 0) + (s01 ? 1 : 0) + (s11 ? 1 : 0);
                                edge = count == 1 || count == 3 || (count == 2 && s00 == s11);
                            }
                            if (edge && start < 0) start = s;
                            else if (!edge && start >= 0) {
                                double[] e = new double[6];
                                e[a] = c[a][start];
                                e[a + 3] = c[a][s];
                                e[b] = e[b + 3] = c[b][u];
                                e[d] = e[d + 3] = c[d][v];
                                out.add(e);
                                start = -1;
                            }
                        }
                    }
                }
                return out;
            }

            /**
             * The outer faces on {@code sideName} the crosshair is on: the connected region of faces in the hit point's
             * plane. Without a usable hit point, every face in the outermost plane on that side.
             */
            List<double[]> faceRegion(String sideName, double hx, double hy, double hz) {
                int side = Arrays.asList(SIDES).indexOf(sideName);
                if (side < 0) return Collections.emptyList();
                int a = AXIS[side], b = (a + 1) % 3, d = (a + 2) % 3;
                double[] hit = { hx, hy, hz };
                int[] p = new int[3];
                // The layer of cells whose `side` faces lie in the hit plane.
                int layer = -1;
                if (!Double.isNaN(hit[a])) {
                    for (int l = 0; l < n(a) && layer < 0; l++) {
                        if (Math.abs(c[a][l + (side & 1)] - hit[a]) < 1e-3 && anyExposed(p, l, side)) layer = l;
                    }
                }
                int su = -1, sv = -1;
                if (layer >= 0 && !Double.isNaN(hit[b]) && !Double.isNaN(hit[d])) {
                    // The hit cell; on a cell boundary, the neighbour below may be the exposed one.
                    p[a] = layer;
                    int cu = cellAt(b, hit[b]), cv = cellAt(d, hit[d]);
                    for (int du = 0; du >= -1 && su < 0; du--) for (int dv = 0; dv >= -1 && su < 0; dv--) {
                        int u = cu + du, v = cv + dv;
                        if (u < 0 || v < 0) continue;
                        if (du < 0 && Math.abs(c[b][cu] - hit[b]) > 1e-3 || dv < 0 && Math.abs(c[d][cv] - hit[d]) > 1e-3) continue;
                        if (exposedAt(p, side, u, v)) { su = u; sv = v; }
                    }
                }
                if (layer < 0) {
                    for (int l = 0; l < n(a) && layer < 0; l++) {
                        int ll = (side & 1) == 1 ? n(a) - 1 - l : l;
                        if (anyExposed(p, ll, side)) layer = ll;
                    }
                    if (layer < 0) return Collections.emptyList();
                }
                p[a] = layer;
                boolean[] in = new boolean[n(b) * n(d)];
                if (su >= 0 && sv >= 0 && exposedAt(p, side, su, sv)) {
                    // Flood the connected region from the hit cell.
                    ArrayDeque<int[]> todo = new ArrayDeque<int[]>();
                    in[su * n(d) + sv] = true;
                    todo.add(new int[] { su, sv });
                    while (!todo.isEmpty()) {
                        int[] q = todo.poll();
                        int[][] next = { { q[0] - 1, q[1] }, { q[0] + 1, q[1] }, { q[0], q[1] - 1 }, { q[0], q[1] + 1 } };
                        for (int[] nq : next) {
                            if (nq[0] < 0 || nq[1] < 0 || nq[0] >= n(b) || nq[1] >= n(d) || in[nq[0] * n(d) + nq[1]]) continue;
                            if (!exposedAt(p, side, nq[0], nq[1])) continue;
                            in[nq[0] * n(d) + nq[1]] = true;
                            todo.add(nq);
                        }
                    }
                } else {
                    for (int u = 0; u < n(b); u++) for (int v = 0; v < n(d); v++) in[u * n(d) + v] = exposedAt(p, side, u, v);
                }
                List<double[]> out = new ArrayList<double[]>();
                for (int u = 0; u < n(b); u++) for (int v = 0; v < n(d); v++) {
                    if (!in[u * n(d) + v]) continue;
                    p[b] = u;
                    p[d] = v;
                    out.add(rect(p, side));
                }
                return out;
            }

            /** {@link #exposed} for the cell at in-plane indices (u, v) of the layer already set in {@code p}. */
            private boolean exposedAt(int[] p, int side, int u, int v) {
                int a = AXIS[side];
                p[(a + 1) % 3] = u;
                p[(a + 2) % 3] = v;
                return exposed(p, side);
            }

            private boolean anyExposed(int[] p, int layer, int side) {
                int a = AXIS[side];
                p[a] = layer;
                for (int u = 0; u < n((a + 1) % 3); u++) for (int v = 0; v < n((a + 2) % 3); v++) if (exposedAt(p, side, u, v)) return true;
                return false;
            }

            /** The cell on axis {@code a} containing {@code v}, clamped to the grid. */
            private int cellAt(int a, double v) {
                int i = 0;
                while (i < n(a) - 1 && v >= c[a][i + 1]) i++;
                return i;
            }
        }
    }

    // ---- Minimap ------------------------------------------------------------------------------------------------

    static final MapStore MAP = new MapStore();

    public static final class Minimap extends Mod {
        public enum Shape { SQUARE, CIRCLE }
        public enum Cave { OFF, AUTO, ON }

        private final Setting.Bool border = add(new Setting.Bool("border", "Border", true));
        private final Setting.Int size = add(new Setting.Int("size", "Size", 100, 48, 256));
        private final Setting.Dbl zoom = add(new Setting.Dbl("zoom", "Zoom (pixels per block)", 1.0, 0.25, 4.0));
        private final Setting.Choice<Shape> shape = add(new Setting.Choice<Shape>("shape", "Shape", Shape.SQUARE));
        private final Setting.Bool rotate = add(new Setting.Bool("rotate", "Rotate with the player", false));
        private final Setting.Bool fairPlay = add(new Setting.Bool("fairPlay", "Fair play (no cave map, no mob radar)", false));
        // Fair play turns the cave map and the mob radar off, so their options go with it.
        private final Setting.Choice<Cave> cave = add(new Setting.Choice<Cave>("cave", "Cave map", Cave.AUTO)).visibleWhen(() -> !fairPlay.on());
        private final Setting.Bool players = add(new Setting.Bool("players", "Players", true));
        private final Setting.Bool hostiles = add(new Setting.Bool("hostiles", "Hostile mobs", true)).visibleWhen(() -> !fairPlay.on());
        private final Setting.Bool animals = add(new Setting.Bool("animals", "Passive mobs", false)).visibleWhen(() -> !fairPlay.on());
        private final Setting.Bool items = add(new Setting.Bool("items", "Items", false)).visibleWhen(() -> !fairPlay.on());
        private final Setting.Bool waypoints = add(new Setting.Bool("waypoints", "Waypoints", true));
        private final Setting.Bool coords = add(new Setting.Bool("coords", "Coordinates below", true));
        private final Setting.Bool biome = add(new Setting.Bool("biome", "Biome below", false));
        private final Hotkey zoomIn = hotkey("zoomIn", "Zoom in key", Keys.NONE, Hotkey.Mode.TOGGLE);
        private final Hotkey zoomOut = hotkey("zoomOut", "Zoom out key", Keys.NONE, Hotkey.Mode.TOGGLE);

        private int[] pixels = new int[0];
        private long builtVersion = -1, imageVersion;
        private int builtX = Integer.MIN_VALUE, builtZ, builtSize, builtYaw;
        private double builtZoom;
        private boolean builtCave, builtRotate, builtCircle;
        // Cave colours sampled live (not stored), centred on caveX/caveZ.
        private int[] caveGrid = new int[0];
        private int caveX, caveZ, caveR, caveTicks;

        public Minimap() {
            super("minimap", "Minimap", "Terrain, cave map, entities and waypoints around you", Category.WORLD, true);
            hud = new HudElement(Anchor.TOP_RIGHT, -4, 4);
            sensitive = true;
        }

        @Override protected void onHotkey(Hotkey h) {
            if (h == zoomIn) zoom.set(zoom.val() * 1.25);
            else if (h == zoomOut) zoom.set(zoom.val() / 1.25);
        }

        boolean caveActive(GameState s) {
            if (fairPlay.on()) return false;
            if (cave.val() == Cave.ON) return true;
            if (cave.val() == Cave.OFF) return false;
            int x = bx(s), y = by(s) + 1, z = bz(s);
            return s.skyLight(x, y, z) == 0 && s.topY(x, z) > y + 3;
        }

        @Override public void onTick(GameState s) {
            MAP.tick(s, dataDir(), StatusMods.Playtime.worldKey(s));
            if (caveActive(s) && ++caveTicks % 20 == 1) sampleCave(s);
        }

        private void sampleCave(GameState s) {
            caveR = (int) Math.ceil(size.val() / zoom.val() / 2 * 1.5) + 2;
            int n = caveR * 2 + 1;
            caveGrid = new int[n * n];
            caveX = bx(s);
            caveZ = bz(s);
            int y = by(s) + 2;
            for (int dz = -caveR; dz <= caveR; dz++) {
                for (int dx = -caveR; dx <= caveR; dx++) {
                    int c = s.caveColor(caveX + dx, caveZ + dz, y);
                    caveGrid[(dz + caveR) * n + dx + caveR] = c < 0 ? 0 : MapStore.shade(c, 0, (dx + dz) & 1);
                }
            }
        }

        private int sample(int x, int z, boolean caveMode) {
            if (caveMode) {
                int n = caveR * 2 + 1, gx = x - caveX + caveR, gz = z - caveZ + caveR;
                return gx < 0 || gz < 0 || gx >= n || gz >= n ? 0 : caveGrid[gz * n + gx];
            }
            return MAP.color(x, z);
        }

        @Override public void renderHud(UiRenderer r, GameState s) {
            if (!s.inWorld() && !previewing()) return;
            int n = size.val();
            float textH = (coords.on() ? r.fontHeight() + 2 : 0) + (biome.on() ? r.fontHeight() + 2 : 0);
            float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), n, n + textH);
            boolean caveMode = caveActive(s), circle = shape.val() == Shape.CIRCLE, rot = rotate.on();
            double z = zoom.val();
            int px = bx(s), pz = bz(s), yaw = rot ? Math.round(s.yaw()) : 0;
            if (MAP.version() != builtVersion || px != builtX || pz != builtZ || n != builtSize || z != builtZoom || caveMode != builtCave
                    || rot != builtRotate || yaw != builtYaw || circle != builtCircle || caveMode) {
                build(s, n, z, caveMode, circle, rot);
                builtVersion = MAP.version();
                builtX = px;
                builtZ = pz;
                builtSize = n;
                builtZoom = z;
                builtCave = caveMode;
                builtRotate = rot;
                builtYaw = yaw;
                builtCircle = circle;
                imageVersion++;
            }
            r.push(p[0], p[1], hud.scale());
            if (border.on()) r.rect(-1, -1, n + 2, n + 2, fade(0xCC000000), circle ? n / 2f : 2f);
            if (!r.image("minimap", n, n, pixels, imageVersion, 0, 0, n, n)) r.rect(0, 0, n, n, fade(0x88000000), 0);
            drawMarkers(r, s, n, z, rot, circle);
            float ty = n + 2;
            if (coords.on()) {
                String c = bx(s) + ", " + by(s) + ", " + bz(s);
                r.text(c, (n - r.textWidth(c)) / 2f, ty, fade(0xFFFFFFFF), true);
                ty += r.fontHeight() + 2;
            }
            if (biome.on()) r.text(s.biome(), (n - r.textWidth(s.biome())) / 2f, ty, fade(0xFFCCCCCC), true);
            r.pop();
        }

        private void build(GameState s, int n, double z, boolean caveMode, boolean circle, boolean rot) {
            if (pixels.length != n * n) pixels = new int[n * n];
            double yawRad = Math.toRadians(s.yaw());
            double fx = -Math.sin(yawRad), fz = Math.cos(yawRad), rx = -Math.cos(yawRad), rz = -Math.sin(yawRad);
            double half = n / 2.0, r2 = half * half;
            int alphaMask = Math.round(255 * hud.opacity()) << 24, unknown = (Math.round(0x80 * hud.opacity()) << 24) | 0x101010;
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    double u = i + 0.5 - half, v = j + 0.5 - half;
                    if (circle && u * u + v * v > r2) {
                        pixels[j * n + i] = 0;
                        continue;
                    }
                    double wx, wz;
                    if (rot) {
                        wx = s.x() + (rx * u - fx * v) / z;
                        wz = s.z() + (rz * u - fz * v) / z;
                    } else {
                        wx = s.x() + u / z;
                        wz = s.z() + v / z;
                    }
                    int c = sample((int) Math.floor(wx), (int) Math.floor(wz), caveMode);
                    pixels[j * n + i] = c == 0 ? unknown : (c & 0xFFFFFF) | alphaMask;
                }
            }
        }

        /** World offset (dx, dz) to minimap pixel coordinates; null when outside. */
        private float[] toMap(GameState s, double dx, double dz, int n, double z, boolean rot, boolean circle, boolean clamp) {
            double u, v;
            if (rot) {
                double yawRad = Math.toRadians(s.yaw());
                double fx = -Math.sin(yawRad), fz = Math.cos(yawRad), rx = -Math.cos(yawRad), rz = -Math.sin(yawRad);
                u = (dx * rx + dz * rz) * z;
                v = -(dx * fx + dz * fz) * z;
            } else {
                u = dx * z;
                v = dz * z;
            }
            double half = n / 2.0 - 2;
            if (circle) {
                double len = Math.sqrt(u * u + v * v);
                if (len > half) {
                    if (!clamp) return null;
                    u = u / len * half;
                    v = v / len * half;
                }
            } else if (Math.abs(u) > half || Math.abs(v) > half) {
                if (!clamp) return null;
                double m = Math.max(Math.abs(u), Math.abs(v)) / half;
                u /= m;
                v /= m;
            }
            return new float[] { (float) (n / 2.0 + u), (float) (n / 2.0 + v) };
        }

        private void drawMarkers(UiRenderer r, GameState s, int n, double z, boolean rot, boolean circle) {
            double radius = n / z / 2 * 1.5;
            if (!fairPlay.on() || players.on()) {
                for (GameState.EntityInfo e : s.entities(radius)) {
                    int c;
                    if (e.player) { if (!players.on()) continue; c = 0xFFFFFFFF; }
                    else if (fairPlay.on()) continue;
                    else if (e.item) { if (!items.on()) continue; c = 0xFFFFDD55; }
                    else if (e.hostile) { if (!hostiles.on()) continue; c = 0xFFFF4040; }
                    else if (e.living) { if (!animals.on()) continue; c = 0xFF55FF55; }
                    else continue;
                    float[] m = toMap(s, e.x - s.x(), e.z - s.z(), n, z, rot, circle, false);
                    if (m != null) r.rect(m[0] - 1.5f, m[1] - 1.5f, 3, 3, fade(c), 1.5f);
                }
            }
            if (waypoints.on()) {
                Waypoints wp = waypointsMod;
                if (wp != null && wp.enabled()) {
                    for (double[] w : wp.visible(s)) {
                        float[] m = toMap(s, w[0] + 0.5 - s.x(), w[2] + 0.5 - s.z(), n, z, rot, circle, true);
                        r.rect(m[0] - 2, m[1] - 2, 4, 4, fade(0xFF000000), 1);
                        r.rect(m[0] - 1.5f, m[1] - 1.5f, 3, 3, fade((int) w[3]), 1);
                    }
                }
            }
            // Player arrow: a small triangle made of rows pointing along the facing direction.
            float cx = n / 2f, cy = n / 2f;
            double a = rot ? Math.PI : Math.toRadians(s.yaw()) + Math.PI;
            for (int k = 0; k < 5; k++) {
                double len = 4 - k;
                float tx = (float) (cx + Math.sin(a) * -(k - 2)), ty = (float) (cy - Math.cos(a) * -(k - 2));
                r.rect(tx - (float) len / 2, ty - 0.5f, (float) len, 1, fade(0xFFFFFFFF), 0);
            }
            r.rect(cx - 1, cy - 1, 2, 2, fade(0xFFFF3030), 0);
            if (!rot) r.text("N", cx - r.textWidth("N") / 2f, 2, fade(0xFFFF5555), true);
            else {
                float[] north = toMap(s, 0, -1000, n, z, true, circle, true);
                r.text("N", north[0] - r.textWidth("N") / 2f, north[1] - r.fontHeight() / 2f, fade(0xFFFF5555), true);
            }
        }

        private int fade(int argb) { return alpha(argb, Math.round(((argb >>> 24) & 0xFF) * hud.opacity())); }
    }

    /** Set by BuiltinMods so the maps can draw waypoints. */
    static Waypoints waypointsMod;

    // ---- World map ----------------------------------------------------------------------------------------------

    public static final class WorldMap extends Mod {
        private final Setting.Bool showWaypoints = add(new Setting.Bool("waypoints", "Waypoints", true));
        private final Setting.Bool showPlayers = add(new Setting.Bool("players", "Nearby players", true));
        private final Setting.Bool showEntities = add(new Setting.Bool("entities", "Nearby mobs", false));
        private final Hotkey key = hotkey("key", "Open key", Keys.M, Hotkey.Mode.TOGGLE);
        private GameState last;

        public WorldMap() { super("worldmap", "World Map", "Full-screen map of the explored world: zoom, pan, waypoints, export", Category.WORLD, true); }

        @Override protected void onHotkey(Hotkey h) { if (last != null && last.inWorld()) openView(new WorldMapView(this, last)); }

        @Override public void onTick(GameState s) {
            last = s;
            // The minimap normally feeds the tile store; keep exploring when it is off.
            if (minimapMod == null || !minimapMod.enabled()) MAP.tick(s, dataDir(), StatusMods.Playtime.worldKey(s));
        }

        java.nio.file.Path dir() { return dataDir(); }
        boolean waypoints() { return showWaypoints.on(); }
        boolean players() { return showPlayers.on(); }
        boolean entities() { return showEntities.on(); }
    }

    static Minimap minimapMod;
}
