package gg.aether.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Operating-system helpers adapters use to implement {@link ClientHost}: image clipboard, desktop notifications and
 * opening files. The game runs AWT-headless on some platforms, so everything goes through small OS commands run on a
 * background thread; failures are ignored (these are conveniences).
 */
public final class Desktop {
    private Desktop() {}

    public enum Os { WINDOWS, MAC, LINUX }

    public static Os os() {
        String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (n.contains("win")) return Os.WINDOWS;
        if (n.contains("mac") || n.contains("darwin")) return Os.MAC;
        return Os.LINUX;
    }

    /** PowerShell / AppleScript single-quoted string literal. */
    static String psQuote(String s) { return "'" + s.replace("'", "''") + "'"; }

    static String asQuote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    /** Command that puts a PNG on the clipboard as an image, or null if the platform has no way. */
    public static List<String> copyImageCommand(Os os, Path png) {
        String p = png.toAbsolutePath().toString();
        switch (os) {
            case WINDOWS:
                return Arrays.asList("powershell", "-NoProfile", "-STA", "-Command",
                        "Add-Type -AssemblyName System.Windows.Forms,System.Drawing; $i=[System.Drawing.Image]::FromFile(" + psQuote(p) + "); [System.Windows.Forms.Clipboard]::SetImage($i); $i.Dispose()");
            case MAC:
                return Arrays.asList("osascript", "-e", "set the clipboard to (read (POSIX file " + asQuote(p) + ") as «class PNGf»)");
            default:
                return Arrays.asList("xclip", "-selection", "clipboard", "-t", "image/png", "-i", p);
        }
    }

    /** Command that shows a desktop notification. */
    public static List<String> notifyCommand(Os os, String title, String text) {
        switch (os) {
            case WINDOWS: {
                String xml = "<toast><visual><binding template='ToastGeneric'><text>" + xml(title) + "</text><text>" + xml(text) + "</text></binding></visual></toast>";
                String script = "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null;"
                        + "[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] > $null;"
                        + "$x = New-Object Windows.Data.Xml.Dom.XmlDocument; $x.LoadXml(" + psQuote(xml) + ");"
                        + "$t = New-Object Windows.UI.Notifications.ToastNotification $x;"
                        + "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe').Show($t)";
                return Arrays.asList("powershell", "-NoProfile", "-Command", script);
            }
            case MAC:
                return Arrays.asList("osascript", "-e", "display notification " + asQuote(text) + " with title " + asQuote(title));
            default:
                return Arrays.asList("notify-send", title, text);
        }
    }

    /** Command that opens a file or folder with its default program. */
    public static List<String> openCommand(Os os, Path file) {
        String p = file.toAbsolutePath().toString();
        switch (os) {
            case WINDOWS: return Arrays.asList("explorer", p);
            case MAC: return Arrays.asList("open", p);
            default: return Arrays.asList("xdg-open", p);
        }
    }

    /** Opens the folder containing {@code file} with the file selected where the platform supports it. */
    public static List<String> revealCommand(Os os, Path file) {
        String p = file.toAbsolutePath().toString();
        switch (os) {
            case WINDOWS: return Arrays.asList("explorer", "/select," + p);
            case MAC: return Arrays.asList("open", "-R", p);
            default: return Arrays.asList("xdg-open", file.toAbsolutePath().getParent().toString());
        }
    }

    /**
     * Commands that show an open-file dialog and print the chosen path (one per line, UTF-8), nothing if cancelled.
     * Several are tried in order (Linux: zenity, then kdialog). {@code extensions} without dots.
     */
    public static List<List<String>> pickFileCommands(Os os, String title, String[] extensions) {
        List<List<String>> out = new ArrayList<List<String>>();
        switch (os) {
            case WINDOWS: {
                StringBuilder pattern = new StringBuilder();
                for (String e : extensions) pattern.append(pattern.length() == 0 ? "" : ";").append("*.").append(e);
                String filter = "Cosmetics (" + pattern.toString().replace(";", ", ") + ")|" + pattern + "|All files (*.*)|*.*";
                // A topmost owner window keeps the dialog in front of the (possibly fullscreen) game.
                String script = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; Add-Type -AssemblyName System.Windows.Forms;"
                        + "$o=New-Object System.Windows.Forms.Form -Property @{TopMost=$true; ShowInTaskbar=$false};"
                        + "$d=New-Object System.Windows.Forms.OpenFileDialog; $d.Title=" + psQuote(title) + "; $d.Filter=" + psQuote(filter) + ";"
                        + "if($d.ShowDialog($o) -eq [System.Windows.Forms.DialogResult]::OK){ $d.FileName }";
                out.add(Arrays.asList("powershell", "-NoProfile", "-STA", "-Command", script));
                break;
            }
            case MAC: {
                StringBuilder types = new StringBuilder();
                for (String e : extensions) types.append(types.length() == 0 ? "" : ", ").append(asQuote(e));
                out.add(Arrays.asList("osascript", "-e", "POSIX path of (choose file with prompt " + asQuote(title) + " of type {" + types + "})"));
                break;
            }
            default: {
                StringBuilder globs = new StringBuilder();
                for (String e : extensions) globs.append(globs.length() == 0 ? "" : " ").append("*.").append(e);
                out.add(Arrays.asList("zenity", "--file-selection", "--title=" + title, "--file-filter=Cosmetics | " + globs, "--file-filter=All files | *"));
                out.add(Arrays.asList("kdialog", "--title", title, "--getopenfilename", System.getProperty("user.home", "."), globs.toString()));
                break;
            }
        }
        return out;
    }

    /**
     * Show an open-file dialog on a daemon thread; {@code done} gets the picked files (empty when cancelled), or null
     * when no dialog tool could run (Linux without zenity or kdialog).
     */
    public static void pickFiles(final String title, final String[] extensions, final java.util.function.Consumer<List<Path>> done) {
        Thread t = new Thread(() -> {
            List<Path> picked = new ArrayList<Path>();
            boolean ran = false;
            for (List<String> cmd : pickFileCommands(os(), title, extensions)) {
                try {
                    Process p = new ProcessBuilder(new ArrayList<String>(cmd)).start();
                    p.getOutputStream().close();
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    java.io.InputStream in = p.getInputStream();
                    int n;
                    while ((n = in.read(b)) >= 0) buf.write(b, 0, n);
                    p.waitFor();
                    for (String line : new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).split("\r?\n")) {
                        String l = line.trim();
                        if (!l.isEmpty() && l.charAt(0) == 0xFEFF) l = l.substring(1);
                        if (l.isEmpty()) continue;
                        Path f = java.nio.file.Paths.get(l);
                        if (java.nio.file.Files.isRegularFile(f)) picked.add(f);
                    }
                    ran = true;
                    break; // the tool ran: an empty answer means cancelled
                } catch (Exception missing) {
                    // Try the next tool (kdialog when zenity is missing).
                }
            }
            done.accept(ran ? picked : null);
        }, "aether-file-dialog");
        t.setDaemon(true);
        t.start();
    }

    private static String xml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;").replace("\"", "&quot;");
    }

    /** Runs a command on a daemon thread, discarding its output. */
    public static void run(final List<String> cmd) {
        if (cmd == null || cmd.isEmpty()) return;
        Thread t = new Thread(() -> {
            try {
                Process p = new ProcessBuilder(new ArrayList<String>(cmd)).redirectErrorStream(true).start();
                byte[] buf = new byte[4096];
                java.io.InputStream in = p.getInputStream();
                while (in.read(buf) >= 0) { /* drain so the process never blocks on a full pipe */ }
                p.waitFor();
            } catch (Exception ignored) {
                // Missing tool (xclip, notify-send...) or no desktop session: nothing to do.
            }
        }, "aether-desktop");
        t.setDaemon(true);
        t.start();
    }
}
