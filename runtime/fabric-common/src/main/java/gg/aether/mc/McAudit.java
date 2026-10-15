package gg.aether.mc;

/**
 * Mixin audit for {@code -Daether.audit=true} (Gradle task {@code runAudit}): once the title screen is up, Mixin
 * loads every class any mixin targets, so a hook that no longer matches this Minecraft version fails loudly instead
 * of when a player first reaches it. Prints "[aether-audit] ..." lines and the adapter quits. Version independent:
 * only Mixin and stdout (which every Minecraft version routes into its log).
 */
public final class McAudit {
    private McAudit() {}

    public static final boolean ACTIVE = Boolean.getBoolean("aether.audit");
    private static int ticks;
    private static boolean done;

    /** Call every client tick while the title screen (no world) is showing; true once the audit ran and the game should quit. */
    public static boolean tick() {
        if (!ACTIVE || done || ++ticks < 60) return false;
        done = true;
        System.out.println("[aether-audit] loading every mixin target");
        try {
            org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
            System.out.println("[aether-audit] finished");
        } catch (Throwable t) {
            System.out.println("[aether-audit] FAILED: " + t);
            t.printStackTrace(System.out);
        }
        return true;
    }
}
