package gg.kite.mods;

import gg.kite.core.GameState;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Explored terrain colours per world and dimension, one 16x16 ARGB tile per chunk (0 = unknown / void), shaded like
 * vanilla maps. Fed a few chunks per tick around the player; read by the minimap and the world map. Saved as a gzip
 * file per world/dimension in the owning mod's data folder.
 */
final class MapStore {
    private static final int MAX_CHUNKS = 40000;
    private static final int PER_TICK = 6;
    /** Spiral of chunk offsets, nearest first, out to 32 chunks. */
    private static final int[][] SPIRAL = spiral(32);

    private final Map<Long, int[]> tiles = new HashMap<Long, int[]>();
    private final Map<Long, Long> sampled = new HashMap<Long, Long>();
    private String key = "";
    private Path file;
    private boolean dirty;
    private long version, lastSave;
    private int cursor;

    static long pack(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    long version() { return version; }
    String key() { return key; }
    int chunkCount() { return tiles.size(); }

    /** All known dimension keys of the current world ("world|dim" files next to the current one). */
    List<String> knownDimensions(Path dir, String world) {
        List<String> out = new ArrayList<String>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        String prefix = safe(world) + "__";
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String n = p.getFileName().toString();
                if (n.startsWith(prefix) && n.endsWith(".map.gz")) out.add(n.substring(prefix.length(), n.length() - ".map.gz".length()));
            }
        } catch (IOException ignored) { }
        Collections.sort(out);
        return out;
    }

    /** ARGB of the block column, 0 if unknown. */
    int color(int x, int z) {
        int[] t = tiles.get(pack(x >> 4, z >> 4));
        return t == null ? 0 : t[(z & 15) * 16 + (x & 15)];
    }

    /** Switch to (world, dimension), load its file, and sample chunks around the player. */
    void tick(GameState s, Path dir, String world) {
        if (!s.inWorld()) {
            save();
            return;
        }
        String k = world + "|" + s.dimension();
        if (!k.equals(key)) {
            save();
            key = k;
            file = dir == null ? null : dir.resolve(safe(world) + "__" + s.dimension() + ".map.gz");
            load();
        }
        long now = s.nowMillis();
        int pcx = (int) Math.floor(s.x()) >> 4, pcz = (int) Math.floor(s.z()) >> 4;
        int radius = Math.min(32, Math.max(2, s.renderDistance()));
        int done = 0, checked = 0;
        while (done < PER_TICK && checked < SPIRAL.length) {
            int[] o = SPIRAL[cursor];
            cursor = (cursor + 1) % SPIRAL.length;
            checked++;
            if (Math.abs(o[0]) > radius || Math.abs(o[1]) > radius) {
                // Outside the loaded area: restart near the player so close chunks refresh often.
                cursor = 0;
                continue;
            }
            int cx = pcx + o[0], cz = pcz + o[1];
            long pk = pack(cx, cz);
            Long at = sampled.get(pk);
            // Close chunks refresh every 5 s, far ones every 60 s.
            long maxAge = Math.max(Math.abs(o[0]), Math.abs(o[1])) <= 2 ? 5000 : 60000;
            if (at != null && now - at < maxAge) continue;
            if (sample(s, cx, cz)) {
                sampled.put(pk, now);
                done++;
            }
        }
        if (dirty && now - lastSave > 120000) save();
    }

    private boolean sample(GameState s, int cx, int cz) {
        int x0 = cx << 4, z0 = cz << 4;
        if (s.mapColor(x0, z0) < 0) return false; // chunk not loaded
        int[] heights = new int[17 * 16];
        for (int dz = -1; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) heights[(dz + 1) * 16 + dx] = s.topY(x0 + dx, z0 + dz);
        int[] tile = new int[256];
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int c = s.mapColor(x0 + dx, z0 + dz);
                if (c <= 0) continue;
                int h = heights[(dz + 1) * 16 + dx], north = heights[dz * 16 + dx];
                tile[dz * 16 + dx] = shade(c, north == Integer.MIN_VALUE ? 0 : h - north, (dx + dz) & 1);
            }
        }
        if (tiles.size() >= MAX_CHUNKS && !tiles.containsKey(pack(cx, cz))) return true;
        tiles.put(pack(cx, cz), tile);
        dirty = true;
        version++;
        return true;
    }

    /** Vanilla-map style relief: brighter when higher than the block to the north, darker when lower. */
    static int shade(int rgb, int dh, int dither) {
        double d = dh + (dither - 0.5) * 0.4;
        float f = d > 0.6 ? 1.0f : d < -0.6 ? 0.71f : 0.86f;
        int r = Math.round(((rgb >> 16) & 0xFF) * f), g = Math.round(((rgb >> 8) & 0xFF) * f), b = Math.round((rgb & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    void save() {
        if (!dirty || file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream os = Files.newOutputStream(tmp); DataOutputStream out = new DataOutputStream(new GZIPOutputStream(os))) {
                out.writeInt(1);
                out.writeInt(tiles.size());
                for (Map.Entry<Long, int[]> e : tiles.entrySet()) {
                    out.writeLong(e.getKey());
                    for (int v : e.getValue()) out.writeInt(v);
                }
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
            lastSave = System.currentTimeMillis();
        } catch (IOException ignored) {
            // map persistence is best effort
        }
    }

    private void load() {
        tiles.clear();
        sampled.clear();
        cursor = 0;
        dirty = false;
        version++;
        if (file == null || !Files.exists(file)) return;
        try (InputStream is = Files.newInputStream(file); DataInputStream in = new DataInputStream(new GZIPInputStream(is))) {
            if (in.readInt() != 1) return;
            int n = Math.min(in.readInt(), MAX_CHUNKS);
            for (int i = 0; i < n; i++) {
                long k = in.readLong();
                int[] t = new int[256];
                for (int j = 0; j < 256; j++) t[j] = in.readInt();
                tiles.put(k, t);
            }
        } catch (IOException | RuntimeException ignored) {
            tiles.clear();
        }
    }

    /** Load another dimension of the same world read-only (world map tabs). */
    static MapStore readOnly(Path dir, String world, String dimension) {
        MapStore m = new MapStore();
        m.key = world + "|" + dimension;
        m.file = dir == null ? null : dir.resolve(safe(world) + "__" + dimension + ".map.gz");
        m.load();
        m.file = null; // never write
        return m;
    }

    static String safe(String s) { return s.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private static int[][] spiral(int r) {
        List<int[]> l = new ArrayList<int[]>();
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) l.add(new int[] { dx, dz });
        Collections.sort(l, (a, b) -> Integer.compare(Math.max(Math.abs(a[0]), Math.abs(a[1])), Math.max(Math.abs(b[0]), Math.abs(b[1]))));
        return l.toArray(new int[0][]);
    }
}
