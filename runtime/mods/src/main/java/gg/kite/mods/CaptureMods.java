package gg.kite.mods;

import gg.kite.core.Events;
import gg.kite.core.GameState;
import gg.kite.core.Hotkey;
import gg.kite.core.Keys;
import gg.kite.core.Mod;
import gg.kite.core.Setting;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Screenshot manager + gallery and the crash reporter. */
public final class CaptureMods {
    private CaptureMods() {}

    // ---- Screenshot manager ---------------------------------------------------------------------------------------

    /** Renames / sorts new screenshots, copies them to the clipboard, opens them, and hosts the gallery. */
    public static final class ScreenshotManager extends Mod {
        private final Setting.Text pattern = add(new Setting.Text("pattern", "File name ({date} {time} {world} {x} {y} {z} {dim}, empty = vanilla)", "", 120));
        private final Setting.Bool perWorld = add(new Setting.Bool("perWorld", "Sort into a folder per world / server", false));
        private final Setting.Bool copy = add(new Setting.Bool("copy", "Copy the image to the clipboard", false));
        private final Setting.Bool open = add(new Setting.Bool("open", "Open after capture", false));
        private final Setting.Bool toast = add(new Setting.Bool("toast", "Notification", true));
        private final Hotkey galleryKey = hotkey("galleryKey", "Open gallery key", Keys.NONE, Hotkey.Mode.TOGGLE);
        {
            add(new Setting.Action("gallery", "Screenshot gallery", "Open gallery", () -> openGallery()));
            add(new Setting.Action("folder", "Screenshots folder", "Open folder", () -> { Path d = dir(); if (d != null) host().openFile(d); }));
        }

        /** Screenshots waiting to be written by the game (it saves off-thread). */
        static final class Pending {
            final Path path;
            final long since;
            final String world, dim;
            final int x, y, z;
            long lastSize = -1;
            Pending(Path path, long since, String world, String dim, int x, int y, int z) {
                this.path = path;
                this.since = since;
                this.world = world;
                this.dim = dim;
                this.x = x;
                this.y = y;
                this.z = z;
            }
        }

        private final List<Pending> pending = new ArrayList<Pending>();
        private GameState last;
        private int counter;

        public ScreenshotManager() { super("screenshots", "Screenshot Manager", "Name, sort, copy and open screenshots; in-game gallery", Category.UTILITY, true); }

        Path dir() {
            Path g = host().gameDir();
            return g == null ? null : g.resolve("screenshots");
        }

        void openGallery() {
            Path d = dir();
            if (d != null) openView(new ScreenshotGallery(d, host(), data));
        }

        @Override protected void onEnable() {
            pending.clear();
            events.on(Events.Screenshot.class, this, e -> {
                GameState s = last;
                String world = s == null ? "" : StatusMods.Playtime.worldKey(s);
                pending.add(new Pending(java.nio.file.Paths.get(e.path), e.timeMs, world, s == null ? "" : s.dimension(),
                        s == null ? 0 : (int) Math.floor(s.x()), s == null ? 0 : (int) Math.floor(s.y()), s == null ? 0 : (int) Math.floor(s.z())));
            });
        }

        @Override protected void onHotkey(Hotkey h) { if (h == galleryKey && ChatMods.pressed(h)) openGallery(); }

        @Override public void onTick(GameState s) {
            last = s;
            long now = s.nowMillis();
            for (Iterator<Pending> it = pending.iterator(); it.hasNext();) {
                Pending p = it.next();
                if (now - p.since > 15_000) {
                    it.remove();
                    continue;
                }
                long size;
                try {
                    size = Files.exists(p.path) ? Files.size(p.path) : -1;
                } catch (IOException e) {
                    size = -1;
                }
                // Written once the size is non-zero and stopped changing between two ticks.
                if (size <= 0 || size != p.lastSize) {
                    p.lastSize = size;
                    continue;
                }
                it.remove();
                finish(p, now);
            }
        }

        private void finish(Pending p, long now) {
            Path file = p.path;
            try {
                Path target = target(p, now);
                if (!target.equals(file)) {
                    Files.createDirectories(target.getParent());
                    file = Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (IOException | RuntimeException e) {
                toasts.warn(now, "Could not rename screenshot: " + e.getMessage());
            }
            if (copy.on()) host().copyImage(file);
            if (open.on()) host().openFile(file);
            if (toast.on()) toasts.info(now, "Screenshot saved: " + file.getFileName() + (copy.on() ? " (copied)" : ""));
        }

        /** Where a screenshot should end up; the same path when nothing is configured. */
        Path target(Pending p, long now) {
            Path dir = p.path.getParent();
            if (perWorld.on() && !p.world.isEmpty()) dir = dir.resolve(safe(p.world));
            String base = pattern.str().trim().isEmpty() ? p.path.getFileName().toString()
                    : safe(format(pattern.str(), now, p.world, p.dim, p.x, p.y, p.z, ++counter)) + ".png";
            Path t = dir.resolve(base);
            int n = 2;
            while (Files.exists(t) && !t.equals(p.path)) t = dir.resolve(base.replaceAll("\\.png$", "") + "_" + n++ + ".png");
            return t;
        }

        static String format(String pattern, long ms, String world, String dim, int x, int y, int z, int n) {
            Date d = new Date(ms);
            return pattern.replace("{date}", new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(d))
                    .replace("{time}", new SimpleDateFormat("HH.mm.ss", Locale.ROOT).format(d))
                    .replace("{world}", world).replace("{server}", world).replace("{dim}", dim)
                    .replace("{x}", String.valueOf(x)).replace("{y}", String.valueOf(y)).replace("{z}", String.valueOf(z))
                    .replace("{n}", String.valueOf(n));
        }

        /** File-name safe: no path separators or characters Windows rejects. */
        static String safe(String s) {
            String t = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
            while (t.endsWith(".")) t = t.substring(0, t.length() - 1);
            return t.isEmpty() ? "screenshot" : t;
        }
    }

    // ---- Crash reporter -------------------------------------------------------------------------------------------

    /** What a crash report says, with personal data removed. */
    static final class CrashSummary {
        String file = "", description = "", time = "", exception = "", minecraft = "", java = "", os = "", gpu = "", cpu = "", memory = "";
        final List<String> frames = new ArrayList<String>(), mods = new ArrayList<String>();

        static final Pattern FIELD = Pattern.compile("^\\s*([A-Za-z0-9 #._()/-]+?):\\s(.*)$");

        static CrashSummary parse(String text) {
            CrashSummary c = new CrashSummary();
            String[] lines = text.split("\r?\n");
            boolean inTrace = false, inMods = false;
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                if (l.startsWith("Time: ")) c.time = l.substring(6).trim();
                else if (l.startsWith("Description: ")) {
                    c.description = l.substring(13).trim();
                    // The exception follows after a blank line.
                    for (int j = i + 1; j < lines.length && j < i + 4; j++) {
                        if (!lines[j].trim().isEmpty()) {
                            c.exception = lines[j].trim();
                            i = j;
                            inTrace = true;
                            break;
                        }
                    }
                    continue;
                }
                if (inTrace) {
                    String t = l.trim();
                    if (t.startsWith("at ") && c.frames.size() < 8) c.frames.add(t.substring(3));
                    else if (t.isEmpty() || !(t.startsWith("at ") || t.startsWith("..."))) inTrace = false;
                }
                if (l.trim().startsWith("Fabric Mods:") || l.trim().startsWith("Mod List:")) {
                    inMods = true;
                    continue;
                }
                if (inMods) {
                    // Mod lines are indented deeper than the next "Key: value" detail.
                    if (l.startsWith("\t\t") || l.startsWith("        ")) {
                        String m = l.trim();
                        if (!m.isEmpty() && c.mods.size() < 400) c.mods.add(m);
                        continue;
                    }
                    inMods = false;
                }
                Matcher m = FIELD.matcher(l);
                if (!m.find()) continue;
                String k = m.group(1).trim(), v = m.group(2).trim();
                if (k.equals("Minecraft Version") && c.minecraft.isEmpty()) c.minecraft = v;
                else if (k.equals("Java Version") && c.java.isEmpty()) c.java = v;
                else if (k.equals("Operating System") && c.os.isEmpty()) c.os = v;
                else if ((k.equals("Graphics card #0 name") || k.equals("Backend API") || k.equals("GL Renderer")) && c.gpu.isEmpty()) c.gpu = v;
                else if (k.equals("Processor Name") && c.cpu.isEmpty()) c.cpu = v;
                else if (k.equals("Memory") && c.memory.isEmpty()) c.memory = v;
            }
            return c;
        }

        /** Plain-text report that is safe to share. */
        String render() {
            StringBuilder b = new StringBuilder();
            b.append("Kite crash summary\n");
            line(b, "Report", file);
            line(b, "Time", time);
            line(b, "Description", description);
            line(b, "Exception", exception);
            for (String f : frames) b.append("    at ").append(f).append('\n');
            line(b, "Minecraft", minecraft);
            line(b, "Java", java);
            line(b, "OS", os);
            line(b, "CPU", cpu);
            line(b, "GPU", gpu);
            line(b, "Memory", memory);
            if (!mods.isEmpty()) {
                b.append("Mods (").append(mods.size()).append("):\n");
                for (String m : mods) b.append("    ").append(m).append('\n');
            }
            return b.toString();
        }

        private static void line(StringBuilder b, String k, String v) { if (v != null && !v.isEmpty()) b.append(k).append(": ").append(v).append('\n'); }
    }

    /** Removes the account name, home folder, IP addresses and session tokens. */
    static String redact(String text, String username, String home) {
        String t = text;
        t = t.replaceAll("(--accessToken|--uuid|--xuid|--clientId|--session)\\s+\\S+", "$1 <hidden>");
        if (home != null && home.length() > 3) {
            t = t.replace(home, "~");
            t = t.replace(home.replace('\\', '/'), "~");
        }
        if (username != null && username.length() > 2) t = t.replaceAll("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(username) + "(?![A-Za-z0-9_])", "<player>");
        t = t.replaceAll("(?<![0-9.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![0-9.])", "<ip>");
        t = t.replaceAll("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>");
        return t;
    }

    /** Detects a crash in the previous session and shows a privacy-safe summary to copy or save. */
    public static final class CrashReporter extends Mod {
        private final Setting.Bool notifyCrash = add(new Setting.Bool("notify", "Tell me after a crash", true));
        private final Setting.Bool includeLog = add(new Setting.Bool("includeLog", "Include the last 40 log lines", true));
        { add(new Setting.Action("view", "Last crash report", "View", () -> openReport())); }

        private boolean checked;
        private String player = "";

        public CrashReporter() { super("crashreporter", "Crash Reporter", "Summarises the last crash (versions, mods, GPU, stack) with personal data removed", Category.UTILITY, true); }

        Path newest() {
            Path g = host().gameDir();
            if (g == null) return null;
            Path dir = g.resolve("crash-reports");
            if (!Files.isDirectory(dir)) return null;
            Path best = null;
            long bestTime = Long.MIN_VALUE;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "crash-*.txt")) {
                for (Path p : ds) {
                    long t = Files.getLastModifiedTime(p).toMillis();
                    if (t > bestTime) {
                        bestTime = t;
                        best = p;
                    }
                }
            } catch (IOException e) {
                return null;
            }
            return best;
        }

        @Override public void onTick(GameState s) {
            if (s.playerName() != null && !s.playerName().isEmpty()) player = s.playerName();
            if (checked) return;
            checked = true;
            Path p = newest();
            if (p == null) return;
            String name = p.getFileName().toString();
            String seen = data.getString("seen", "");
            data.put("seen", name);
            if (!seen.isEmpty() && !seen.equals(name) && notifyCrash.on()) toasts.warn(s.nowMillis(), "Minecraft crashed last time: open Crash Reporter in the Kite menu");
        }

        String summary() {
            Path p = newest();
            if (p == null) return "No crash reports found.";
            try {
                CrashSummary c = CrashSummary.parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                c.file = p.getFileName().toString();
                StringBuilder b = new StringBuilder(c.render());
                b.append("Client: ").append(host().versionInfo()).append('\n');
                if (includeLog.on()) {
                    Path log = host().gameDir().resolve("logs").resolve("latest.log");
                    if (Files.exists(log)) {
                        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
                        b.append("Log tail:\n");
                        for (String l : lines.subList(Math.max(0, lines.size() - 40), lines.size())) b.append("    ").append(l).append('\n');
                    }
                }
                return redact(b.toString(), player, System.getProperty("user.home"));
            } catch (IOException | RuntimeException e) {
                return "Could not read " + p.getFileName() + ": " + e.getMessage();
            }
        }

        void openReport() {
            final String text = summary();
            final Path original = newest();
            openView(new TextView("Crash report (personal data removed)", text, new String[] { "Copy", "Save copy", "Open original" }, i -> {
                if (i == 0) {
                    host().copyText(text);
                    return "Copied to clipboard";
                }
                if (i == 1) {
                    Path g = host().gameDir();
                    if (g == null) return "No game folder";
                    Path out = g.resolve("crash-reports").resolve("kite-summary.txt");
                    try {
                        Files.write(out, text.getBytes(StandardCharsets.UTF_8));
                        host().revealFile(out);
                        return "Saved crash-reports/kite-summary.txt";
                    } catch (IOException e) {
                        return "Save failed: " + e.getMessage();
                    }
                }
                if (original != null) host().openFile(original);
                return original == null ? "No report" : "Opened " + original.getFileName();
            }));
        }
    }

    /** A scrollable read-only text page with buttons along the bottom. */
    static final class TextView implements gg.kite.core.ui.View {
        interface Buttons { String press(int index); }

        private final String title;
        private final List<String> lines = new ArrayList<String>();
        private final String[] buttons;
        private final Buttons action;
        private Runnable closer = () -> { };
        private int scroll;
        private String flash = "";
        private float[][] hits = new float[0][];

        TextView(String title, String text, String[] buttons, Buttons action) {
            this.title = title;
            for (String l : text.split("\n")) lines.add(l.replace("\t", "    "));
            this.buttons = buttons;
            this.action = action;
        }

        @Override public void render(gg.kite.core.UiRenderer r, GameState s) {
            int W = r.screenWidth(), H = r.screenHeight();
            r.rect(0, 0, W, H, 0xE0101218, 0f);
            r.text(title, 12, 10, 0xFFFFFFFF, false);
            float rowH = r.fontHeight() + 2, top = 26, bottom = H - 30;
            int rows = Math.max(1, (int) ((bottom - top) / rowH));
            scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - rows)));
            r.clip(10, top, W - 20, bottom - top);
            float y = top;
            for (int i = scroll; i < lines.size() && i < scroll + rows; i++) {
                r.text(lines.get(i), 12, y, 0xFFD8DEE9, false);
                y += rowH;
            }
            r.unclip();
            float x = 12;
            hits = new float[buttons.length][];
            for (int i = 0; i < buttons.length; i++) {
                float w = r.textWidth(buttons[i]) + 16;
                r.rect(x, H - 24, w, 16, 0xFF3A4A7A, 3f);
                r.text(buttons[i], x + 8, H - 24 + (16 - r.fontHeight()) / 2f, 0xFFFFFFFF, false);
                hits[i] = new float[] { x, H - 24, w, 16 };
                x += w + 6;
            }
            String info = flash.isEmpty() ? "Scroll to read · Esc to close" : flash;
            r.text(info, x + 6, H - 24 + (16 - r.fontHeight()) / 2f, 0xFFA4A4B2, false);
        }

        @Override public void mouseMoved(float x, float y) { }

        @Override public boolean mouseDown(float x, float y, int button) {
            for (int i = 0; i < hits.length; i++) {
                float[] h = hits[i];
                if (h != null && x >= h[0] && x < h[0] + h[2] && y >= h[1] && y < h[1] + h[3]) {
                    flash = action.press(i);
                    return true;
                }
            }
            return false;
        }

        @Override public boolean mouseDrag(float x, float y) { return false; }
        @Override public boolean mouseUp(float x, float y, int button) { return false; }
        @Override public boolean scroll(float x, float y, float delta) {
            scroll = Math.max(0, scroll - (int) Math.signum(delta) * 3);
            return true;
        }
        @Override public boolean keyDown(int code) {
            if (code == Keys.ESCAPE) closer.run();
            else if (code == Keys.DOWN) scroll++;
            else if (code == Keys.UP) scroll = Math.max(0, scroll - 1);
            else return false;
            return true;
        }
        @Override public boolean charTyped(char c) { return false; }
        @Override public void closed() { }
        @Override public void setCloser(Runnable close) { closer = close; }
        @Override public boolean transparent() { return false; }
    }

    /** Gallery favorites are stored as a list of file names. */
    static boolean isFavorite(gg.kite.core.ModData data, String name) {
        for (Object o : data.getList("favorites")) if (name.equals(o)) return true;
        return false;
    }

    static void setFavorite(gg.kite.core.ModData data, String name, boolean on) {
        List<Object> l = data.getList("favorites");
        l.remove(name);
        if (on) l.add(name);
        data.put("favorites", l);
    }
}
