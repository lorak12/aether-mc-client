package gg.kite.mods;

import gg.kite.core.ClientHost;
import gg.kite.core.GameState;
import gg.kite.core.Keys;
import gg.kite.core.ModData;
import gg.kite.core.UiRenderer;
import gg.kite.core.ui.View;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-game screenshot browser: thumbnails, search, sort, favorites, and copy / open / reveal / delete for the selected
 * picture. Thumbnails are decoded off-thread with ImageIO.
 */
final class ScreenshotGallery implements View {
    enum Sort { NEWEST, OLDEST, NAME }

    static final class Shot {
        final Path path;
        final String name;
        final long time;
        Shot(Path path, long time) {
            this.path = path;
            this.name = path.getFileName().toString();
            this.time = time;
        }
    }

    /** A decoded thumbnail (ARGB pixels). */
    static final class Thumb {
        final int w, h;
        final int[] argb;
        Thumb(int w, int h, int[] argb) { this.w = w; this.h = h; this.argb = argb; }
    }

    private static final int TW = 160, TH = 90;
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kite-thumbnails");
        t.setDaemon(true);
        return t;
    });
    private static final Thumb FAILED = new Thumb(0, 0, new int[0]);

    private final Path root;
    private final ClientHost host;
    private final ModData data;
    private final Map<Path, Thumb> thumbs = new ConcurrentHashMap<Path, Thumb>();
    private final Map<Path, Boolean> queued = new ConcurrentHashMap<Path, Boolean>();
    private List<Shot> all = new ArrayList<Shot>();
    private Runnable closer = () -> { };
    private String query = "", flash = "";
    private long flashUntil;
    private Sort sort = Sort.NEWEST;
    private boolean favoritesOnly;
    private int selected = -1, scrollRows, confirmDelete = -1;
    private float mx, my;
    private final List<float[]> hitBoxes = new ArrayList<float[]>();
    private final List<Runnable> hitActions = new ArrayList<Runnable>();

    ScreenshotGallery(Path root, ClientHost host, ModData data) {
        this.root = root;
        this.host = host;
        this.data = data;
        reload();
    }

    void reload() {
        List<Shot> out = new ArrayList<Shot>();
        scan(root, out, 0);
        all = out;
    }

    private static void scan(Path dir, List<Shot> out, int depth) {
        if (!Files.isDirectory(dir) || depth > 2) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) scan(p, out, depth + 1);
                else if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) out.add(new Shot(p, Files.getLastModifiedTime(p).toMillis()));
            }
        } catch (IOException ignored) {
            // unreadable folder: show what we have
        }
    }

    /** Shots after search, favorites filter and sort. */
    static List<Shot> filter(List<Shot> all, String query, Sort sort, boolean favoritesOnly, ModData data) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Shot> out = new ArrayList<Shot>();
        for (Shot s : all) {
            if (!q.isEmpty() && !s.name.toLowerCase(Locale.ROOT).contains(q) && !s.path.getParent().getFileName().toString().toLowerCase(Locale.ROOT).contains(q)) continue;
            if (favoritesOnly && !CaptureMods.isFavorite(data, s.name)) continue;
            out.add(s);
        }
        Comparator<Shot> c;
        switch (sort) {
            case OLDEST: c = (a, b) -> Long.compare(a.time, b.time); break;
            case NAME: c = (a, b) -> a.name.compareToIgnoreCase(b.name); break;
            default: c = (a, b) -> Long.compare(b.time, a.time); break;
        }
        Collections.sort(out, c);
        return out;
    }

    private Thumb thumb(final Path p) {
        Thumb t = thumbs.get(p);
        if (t != null || queued.putIfAbsent(p, Boolean.TRUE) != null) return t;
        DECODER.execute(() -> thumbs.put(p, decode(p)));
        return null;
    }

    /** Box-filtered down-scale to at most TW x TH. */
    static Thumb decode(Path p) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(p.toFile());
            if (img == null) return FAILED;
            int sw = img.getWidth(), sh = img.getHeight();
            float scale = Math.min(TW / (float) sw, TH / (float) sh);
            int w = Math.max(1, Math.round(sw * scale)), h = Math.max(1, Math.round(sh * scale));
            int[] out = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int x0 = x * sw / w, x1 = Math.max(x0 + 1, (x + 1) * sw / w), y0 = y * sh / h, y1 = Math.max(y0 + 1, (y + 1) * sh / h);
                    long r = 0, g = 0, b = 0, n = 0;
                    for (int yy = y0; yy < y1; yy += Math.max(1, (y1 - y0) / 4)) {
                        for (int xx = x0; xx < x1; xx += Math.max(1, (x1 - x0) / 4)) {
                            int c = img.getRGB(xx, yy);
                            r += (c >> 16) & 0xFF;
                            g += (c >> 8) & 0xFF;
                            b += c & 0xFF;
                            n++;
                        }
                    }
                    out[y * w + x] = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
                }
            }
            return new Thumb(w, h, out);
        } catch (IOException | RuntimeException | LinkageError e) {
            return FAILED;
        }
    }

    private void flash(String s) {
        flash = s;
        flashUntil = System.currentTimeMillis() + 2000;
    }

    private void button(UiRenderer r, String label, float x, float y, float w, boolean on, Runnable run) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 16;
        r.rect(x, y, w, 16, on ? 0xFF4A6CFF : hover ? 0xFF3A4150 : 0xFF262C38, 3f);
        r.text(label, x + (w - r.textWidth(label)) / 2f, y + (16 - r.fontHeight()) / 2f, 0xFFFFFFFF, false);
        hitBoxes.add(new float[] { x, y, w, 16 });
        hitActions.add(run);
    }

    @Override public void render(UiRenderer r, GameState s) {
        hitBoxes.clear();
        hitActions.clear();
        int W = r.screenWidth(), H = r.screenHeight();
        r.rect(0, 0, W, H, 0xF0101218, 0f);
        List<Shot> shots = filter(all, query, sort, favoritesOnly, data);
        if (selected >= shots.size()) selected = shots.size() - 1;

        // Top bar: search, sort, favorites, reload.
        float pad = 10;
        r.rect(pad, pad, 180, 16, 0xFF1C2130, 3f);
        r.text(query.isEmpty() ? "Search screenshots..." : query + "_", pad + 5, pad + 4, query.isEmpty() ? 0xFFA4A4B2 : 0xFFFFFFFF, false);
        float bx = pad + 186;
        button(r, "Sort: " + HudTextMod.prettyId(sort.name().toLowerCase(Locale.ROOT)), bx, pad, 90, false,
                () -> sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length]);
        button(r, "★ Favorites", bx + 94, pad, 80, favoritesOnly, () -> favoritesOnly = !favoritesOnly);
        button(r, "Reload", bx + 178, pad, 56, false, this::reload);
        String count = shots.size() + " screenshot" + (shots.size() == 1 ? "" : "s");
        r.text(count, W - pad - r.textWidth(count), pad + 4, 0xFFA4A4B2, false);

        // Grid of thumbnails.
        float gridTop = pad + 24, gridBottom = H - 34;
        float cellW = TW + 8, cellH = TH + 8 + r.fontHeight() + 2;
        int cols = Math.max(1, (int) ((W - pad * 2) / cellW));
        int visibleRows = Math.max(1, (int) ((gridBottom - gridTop) / cellH));
        int totalRows = (shots.size() + cols - 1) / cols;
        scrollRows = Math.max(0, Math.min(scrollRows, Math.max(0, totalRows - visibleRows)));
        float gx = (W - cols * cellW) / 2f;
        r.clip(0, gridTop, W, gridBottom - gridTop);
        for (int i = scrollRows * cols; i < shots.size() && i < (scrollRows + visibleRows + 1) * cols; i++) {
            final int idx = i;
            Shot sh = shots.get(i);
            int row = i / cols - scrollRows, col = i % cols;
            float x = gx + col * cellW, y = gridTop + row * cellH;
            boolean sel = i == selected;
            r.rect(x, y, cellW - 4, cellH - 4, sel ? 0xFF4A6CFF : (mx >= x && mx < x + cellW - 4 && my >= y && my < y + cellH - 4) ? 0xFF2E3544 : 0xFF1C2130, 3f);
            Thumb t = thumb(sh.path);
            if (t != null && t != FAILED) {
                float dx = x + 2 + (TW - t.w) / 2f, dy = y + 2 + (TH - t.h) / 2f;
                if (!r.image("shot:" + sh.path, t.w, t.h, t.argb, 1, dx, dy, t.w, t.h)) r.text("(no preview)", x + 8, y + TH / 2f, 0xFFA4A4B2, false);
            } else {
                r.text(t == FAILED ? "(unreadable)" : "loading...", x + 8, y + TH / 2f, 0xFFA4A4B2, false);
            }
            String label = (CaptureMods.isFavorite(data, sh.name) ? "★ " : "") + sh.name;
            while (label.length() > 4 && r.textWidth(label) > TW) label = label.substring(0, label.length() - 2);
            r.text(label, x + 3, y + TH + 5, 0xFFF5F5F7, false);
            hitBoxes.add(new float[] { x, Math.max(y, gridTop), cellW - 4, Math.min(cellH - 4, gridBottom - y) });
            hitActions.add(() -> {
                if (selected == idx) host.openFile(shots.get(idx).path);
                selected = idx;
                confirmDelete = -1;
            });
        }
        r.unclip();
        if (shots.isEmpty()) r.text(all.isEmpty() ? "No screenshots yet (F2 takes one)" : "Nothing matches", gx, gridTop + 10, 0xFFA4A4B2, false);

        // Bottom bar: actions for the selected screenshot.
        float by = H - 26;
        if (selected >= 0 && selected < shots.size()) {
            final Shot sh = shots.get(selected);
            float x = pad;
            button(r, "Open", x, by, 50, false, () -> host.openFile(sh.path));
            button(r, "Copy", x += 54, by, 50, false, () -> flash(host.copyImage(sh.path) ? "Copied image" : "Copy not supported"));
            button(r, "Show in folder", x += 54, by, 90, false, () -> host.revealFile(sh.path));
            boolean fav = CaptureMods.isFavorite(data, sh.name);
            button(r, fav ? "★ Unfavorite" : "☆ Favorite", x += 94, by, 84, fav, () -> CaptureMods.setFavorite(data, sh.name, !fav));
            final int sel = selected;
            button(r, confirmDelete == sel ? "Really delete?" : "Delete", x += 88, by, 90, confirmDelete == sel, () -> {
                if (confirmDelete != sel) {
                    confirmDelete = sel;
                    return;
                }
                try {
                    Files.deleteIfExists(sh.path);
                    CaptureMods.setFavorite(data, sh.name, false);
                    flash("Deleted " + sh.name);
                } catch (IOException e) {
                    flash("Delete failed: " + e.getMessage());
                }
                confirmDelete = -1;
                selected = -1;
                reload();
            });
            x += 94;
            String info = flash.isEmpty() || System.currentTimeMillis() > flashUntil ? sh.name : flash;
            r.text(info, x + 4, by + 4, 0xFFA4A4B2, false);
        } else {
            r.text(flash.isEmpty() || System.currentTimeMillis() > flashUntil ? "Click a screenshot to select it, click again to open · Esc: close" : flash, pad, by + 4, 0xFFA4A4B2, false);
        }
    }

    @Override public void mouseMoved(float x, float y) {
        mx = x;
        my = y;
    }

    @Override public boolean mouseDown(float x, float y, int button) {
        for (int i = hitBoxes.size() - 1; i >= 0; i--) {
            float[] h = hitBoxes.get(i);
            if (x >= h[0] && x < h[0] + h[2] && y >= h[1] && y < h[1] + h[3]) {
                hitActions.get(i).run();
                return true;
            }
        }
        return false;
    }

    @Override public boolean mouseDrag(float x, float y) { return false; }
    @Override public boolean mouseUp(float x, float y, int button) { return false; }

    @Override public boolean scroll(float x, float y, float delta) {
        scrollRows = Math.max(0, scrollRows - (int) Math.signum(delta));
        return true;
    }

    @Override public boolean keyDown(int code) {
        if (code == Keys.ESCAPE) {
            closer.run();
            return true;
        }
        if (code == Keys.BACKSPACE) {
            if (!query.isEmpty()) query = query.substring(0, query.length() - 1);
            selected = -1;
            return true;
        }
        if (code == Keys.RIGHT) selected++;
        else if (code == Keys.LEFT) selected = Math.max(0, selected - 1);
        else return false;
        return true;
    }

    @Override public boolean charTyped(char c) {
        if (c < 32 || query.length() > 40) return false;
        query += c;
        selected = -1;
        scrollRows = 0;
        return true;
    }

    @Override public void closed() { }
    @Override public void setCloser(Runnable close) { closer = close; }
    @Override public boolean transparent() { return false; }
}
