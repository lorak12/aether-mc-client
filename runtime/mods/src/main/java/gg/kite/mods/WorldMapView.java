package gg.kite.mods;

import gg.kite.core.GameState;
import gg.kite.core.Keys;
import gg.kite.core.UiRenderer;
import gg.kite.core.ui.View;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen world map: drag to pan, scroll to zoom, right-click to add a waypoint, dimension tabs, a waypoint list
 * with search, "x z" + Enter to jump, C to centre on the player, E to export a PNG.
 */
final class WorldMapView implements View {
    private final WorldMods.WorldMap mod;
    private final GameState state;
    private Runnable closer = () -> { };

    private double cx, cz; // map centre in blocks
    private double zoom = 1; // screen pixels per block
    private String search = "", message = "";
    private long messageUntil;
    private float mouseX, mouseY, dragX, dragY;
    private boolean dragging;
    private String dimension; // shown dimension; null = the current one (live store)
    private MapStore other;
    private final List<float[]> hits = new ArrayList<float[]>(); // tabs / list rows: {x, y, w, h, kind, index}
    private List<String> dims = new ArrayList<String>();

    private int[] pixels = new int[0];
    private int iw, ih;
    private long builtVersion = -1, imageVersion;
    private double builtCx, builtCz, builtZoom;
    private float sw, sh;

    WorldMapView(WorldMods.WorldMap mod, GameState s) {
        this.mod = mod;
        this.state = s;
        cx = s.x();
        cz = s.z();
        dims = WorldMods.MAP.knownDimensions(mod.dir(), StatusMods.Playtime.worldKey(s));
    }

    @Override public void setCloser(Runnable close) { closer = close; }
    @Override public boolean transparent() { return false; }

    private MapStore store() { return dimension == null ? WorldMods.MAP : other; }

    private void flash(String m) {
        message = m;
        messageUntil = System.currentTimeMillis() + 4000;
    }

    // ---- input ------------------------------------------------------------------------------------------------------

    @Override public void mouseMoved(float x, float y) {
        mouseX = x;
        mouseY = y;
    }

    @Override public boolean mouseDown(float x, float y, int button) {
        mouseMoved(x, y);
        for (float[] h : hits) {
            if (x < h[0] || y < h[1] || x >= h[0] + h[2] || y >= h[1] + h[3]) continue;
            if (h[4] == 0) selectDimension((int) h[5]);
            else if (h[4] == 1) centerOnWaypoint((int) h[5]);
            return true;
        }
        if (button == 0) {
            dragging = true;
            dragX = x;
            dragY = y;
        } else if (button == 1) {
            addWaypointAt(x, y);
        }
        return true;
    }

    @Override public boolean mouseDrag(float x, float y) {
        mouseMoved(x, y);
        if (!dragging) return false;
        cx -= (x - dragX) / zoom;
        cz -= (y - dragY) / zoom;
        dragX = x;
        dragY = y;
        return true;
    }

    @Override public boolean mouseUp(float x, float y, int button) {
        dragging = false;
        return true;
    }

    @Override public boolean scroll(float x, float y, float delta) {
        // Zoom around the cursor.
        double bxAt = blockX(x), bzAt = blockZ(y);
        zoom = Math.max(0.0625, Math.min(16, zoom * (delta > 0 ? 1.25 : 0.8)));
        cx = bxAt - (x - sw / 2) / zoom;
        cz = bzAt - (y - sh / 2) / zoom;
        return true;
    }

    @Override public boolean keyDown(int code) {
        if (code == Keys.ESCAPE) {
            closer.run();
            return true;
        }
        if (code == Keys.BACKSPACE) {
            if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
            return true;
        }
        if (code == Keys.ENTER) {
            jump();
            return true;
        }
        double step = 64 / zoom;
        if (code == Keys.LEFT) cx -= step;
        else if (code == Keys.RIGHT) cx += step;
        else if (code == Keys.UP) cz -= step;
        else if (code == Keys.DOWN) cz += step;
        else return false;
        return true;
    }

    @Override public boolean charTyped(char c) {
        if (search.isEmpty() && (c == 'c' || c == 'C')) {
            cx = state.x();
            cz = state.z();
            return true;
        }
        if (search.isEmpty() && (c == 'e' || c == 'E')) {
            export();
            return true;
        }
        if (c < 32 || search.length() > 40) return false;
        search += c;
        return true;
    }

    @Override public void closed() { }

    /** "x z" (or "x y z") jumps there; anything else centres on the first matching waypoint. */
    private void jump() {
        String[] parts = search.trim().split("[ ,]+");
        try {
            if (parts.length == 2 || parts.length == 3) {
                cx = Double.parseDouble(parts[0]);
                cz = Double.parseDouble(parts[parts.length - 1]);
                search = "";
                return;
            }
        } catch (NumberFormatException ignored) { }
        List<double[]> list = waypoints();
        if (!list.isEmpty()) centerOnWaypoint(0);
    }

    private void selectDimension(int i) {
        String d = i < 0 ? null : dims.get(i);
        if (d != null && d.equals(state.dimension())) d = null;
        dimension = d;
        other = d == null ? null : MapStore.readOnly(mod.dir(), StatusMods.Playtime.worldKey(state), d);
        builtVersion = -1;
    }

    private List<double[]> waypoints() {
        List<double[]> out = new ArrayList<double[]>();
        WorldMods.Waypoints wp = WorldMods.waypointsMod;
        if (wp == null || !wp.enabled() || !mod.waypoints() || dimension != null) return out;
        String q = search.toLowerCase(Locale.ROOT).trim();
        for (double[] w : wp.visible(state)) if (q.isEmpty() || wp.name((int) w[4]).toLowerCase(Locale.ROOT).contains(q)) out.add(w);
        return out;
    }

    private void centerOnWaypoint(int listIndex) {
        List<double[]> list = waypoints();
        if (listIndex < 0 || listIndex >= list.size()) return;
        cx = list.get(listIndex)[0] + 0.5;
        cz = list.get(listIndex)[2] + 0.5;
    }

    private void addWaypointAt(float x, float y) {
        WorldMods.Waypoints wp = WorldMods.waypointsMod;
        if (wp == null || !wp.enabled() || dimension != null) {
            flash("Enable the Waypoints mod to add waypoints");
            return;
        }
        int bx = (int) Math.floor(blockX(x)), bz = (int) Math.floor(blockZ(y));
        int top = state.topY(bx, bz);
        String name = "Waypoint " + (wp.entries().size() + 1);
        wp.entries().add(name, String.valueOf(bx), String.valueOf(top == Integer.MIN_VALUE ? (int) state.y() : top + 1), String.valueOf(bz), state.dimension(), "aqua");
        flash("Added " + name + " at " + bx + ", " + bz);
    }

    private void export() {
        Path dir = mod.dir();
        if (dir == null) {
            flash("Export is not available");
            return;
        }
        try {
            Files.createDirectories(dir);
            Path out = dir.resolve("map-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".png");
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(iw, ih, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, iw, ih, pixels, 0, iw);
            javax.imageio.ImageIO.write(img, "png", out.toFile());
            flash("Saved " + out.getFileName());
        } catch (Exception | LinkageError e) {
            flash("Export failed: " + e.getClass().getSimpleName());
        }
    }

    // ---- rendering --------------------------------------------------------------------------------------------------

    private double blockX(float sx) { return cx + (sx - sw / 2) / zoom; }
    private double blockZ(float sy) { return cz + (sy - sh / 2) / zoom; }

    @Override public void render(UiRenderer r, GameState s) {
        sw = r.screenWidth();
        sh = r.screenHeight();
        hits.clear();
        r.rect(0, 0, sw, sh, 0xFF0E0E11, 0);
        MapStore st = store();
        // Image at most 480 px wide; each image pixel covers one or more blocks.
        int niw = (int) Math.min(sw, 480), nih = (int) Math.min(sh, 480 * sh / sw);
        if (st.version() != builtVersion || cx != builtCx || cz != builtCz || zoom != builtZoom || niw != iw || nih != ih) {
            iw = niw;
            ih = nih;
            if (pixels.length != iw * ih) pixels = new int[iw * ih];
            for (int j = 0; j < ih; j++) {
                double wz = blockZ(j * sh / ih);
                for (int i = 0; i < iw; i++) {
                    int c = st.color((int) Math.floor(blockX(i * sw / iw)), (int) Math.floor(wz));
                    pixels[j * iw + i] = c == 0 ? 0xFF15181F : c;
                }
            }
            builtVersion = st.version();
            builtCx = cx;
            builtCz = cz;
            builtZoom = zoom;
            imageVersion++;
        }
        r.image("worldmap", iw, ih, pixels, imageVersion, 0, 0, sw, sh);

        // Entities and the player (current dimension only).
        if (dimension == null) {
            if (mod.players() || mod.entities()) {
                for (GameState.EntityInfo e : s.entities(256)) {
                    if (e.player ? !mod.players() : !mod.entities() || !e.living) continue;
                    float x = (float) ((e.x - cx) * zoom + sw / 2), y = (float) ((e.z - cz) * zoom + sh / 2);
                    r.rect(x - 2, y - 2, 4, 4, e.player ? 0xFFFFFFFF : e.hostile ? 0xFFFF4040 : 0xFF55FF55, 2);
                    if (e.player) r.text(e.name, x + 4, y - 4, 0xFFFFFFFF, true);
                }
            }
            float px = (float) ((s.x() - cx) * zoom + sw / 2), py = (float) ((s.z() - cz) * zoom + sh / 2);
            double a = Math.toRadians(s.yaw()) + Math.PI;
            for (int k = 0; k < 7; k++) {
                float len = 7 - k;
                float tx = (float) (px + Math.sin(a) * -(k - 3)), ty = (float) (py - Math.cos(a) * -(k - 3));
                r.rect(tx - len / 2, ty - 0.5f, len, 1.5f, 0xFFFFFFFF, 0);
            }
            r.rect(px - 1.5f, py - 1.5f, 3, 3, 0xFFFF3030, 0);
        }

        // Waypoints on the map.
        List<double[]> wps = waypoints();
        WorldMods.Waypoints wp = WorldMods.waypointsMod;
        for (double[] w : wps) {
            float x = (float) ((w[0] + 0.5 - cx) * zoom + sw / 2), y = (float) ((w[2] + 0.5 - cz) * zoom + sh / 2);
            if (x < -20 || y < -20 || x > sw + 20 || y > sh + 20) continue;
            r.rect(x - 3, y - 3, 6, 6, 0xFF000000, 1);
            r.rect(x - 2, y - 2, 4, 4, (int) w[3], 1);
            r.text(wp.name((int) w[4]), x + 5, y - 4, (int) w[3], true);
        }

        // Top bar: dimension tabs, cursor coordinates, help.
        r.rect(0, 0, sw, 16, 0xCC0E0E11, 0);
        float tx = 4;
        List<String> tabs = new ArrayList<String>(dims);
        if (!tabs.contains(s.dimension())) tabs.add(0, s.dimension());
        for (int i = 0; i < tabs.size(); i++) {
            String d = tabs.get(i);
            boolean sel = dimension == null ? d.equals(s.dimension()) : d.equals(dimension);
            String label = HudTextMod.prettyId(d);
            float w = r.textWidth(label) + 10;
            r.rect(tx, 2, w, 12, sel ? 0xFF8B5CF6 : 0xFF2A2A33, 3);
            r.text(label, tx + 5, 4, 0xFFFFFFFF, false);
            int di = dims.indexOf(d);
            hits.add(new float[] { tx, 2, w, 12, 0, d.equals(s.dimension()) ? -1 : di });
            tx += w + 3;
        }
        String cur = String.format(Locale.ROOT, "%d, %d", (int) Math.floor(blockX(mouseX)), (int) Math.floor(blockZ(mouseY)));
        r.text(cur, sw - r.textWidth(cur) - 4, 4, 0xFFF5F5F7, false);

        // Right panel: search + waypoint list.
        float pw = Math.min(150, sw / 4), px0 = sw - pw - 4, py0 = 20;
        r.rect(px0, py0, pw, 16, 0xE01C1C22, 3);
        r.text(search.isEmpty() ? "Search / x z + Enter" : search + "_", px0 + 4, py0 + 4, search.isEmpty() ? 0xFFA4A4B2 : 0xFFFFFFFF, false);
        float ly = py0 + 20;
        for (int i = 0; i < wps.size() && ly < sh - 30; i++) {
            double[] w = wps.get(i);
            String t = wp.name((int) w[4]) + "  " + (int) w[0] + ", " + (int) w[2];
            r.rect(px0, ly, pw, 12, 0xC01C1C22, 2);
            r.rect(px0 + 2, ly + 3, 6, 6, (int) w[3], 1);
            r.text(t, px0 + 11, ly + 2, 0xFFF5F5F7, false);
            hits.add(new float[] { px0, ly, pw, 12, 1, i });
            ly += 13;
        }

        String help = "Drag: pan · Scroll: zoom · Right-click: waypoint · C: centre · E: export PNG · Esc: close";
        r.rect(0, sh - 14, sw, 14, 0xCC0E0E11, 0);
        r.text(help, 4, sh - 11, 0xFFA4A4B2, false);
        if (System.currentTimeMillis() < messageUntil) r.text(message, sw - r.textWidth(message) - 4, sh - 11, 0xFF3DDC97, false);
        String scale = zoom >= 1 ? "1 block = " + Math.round(zoom) + " px" : Math.round(1 / zoom) + " blocks/px";
        r.text(scale, 4, sh - 24, 0xFFA4A4B2, true);
    }
}
