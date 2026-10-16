package gg.kite.mc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** The "kite" logger. Legacy Minecraft ships log4j (no slf4j). */
public final class Log {
    private Log() {}

    public static final Logger LOG = LogManager.getLogger("kite");

    public static void info(String msg) { LOG.info(msg); }
    public static void warn(String msg) { LOG.warn(msg); }
    public static void warn(String msg, Throwable t) { LOG.warn(msg, t); }
}
