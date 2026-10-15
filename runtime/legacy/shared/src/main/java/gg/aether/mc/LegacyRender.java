package gg.aether.mc;

import gg.aether.core.Overrides;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.sound.SoundCategory;

/**
 * Rendering / camera / performance {@link Overrides} for the legacy adapters. Legacy options are plain fields: an
 * override is written into the field and the player's own value is put back when it ends and around
 * GameOptions.save(), so options.txt never gets an override in it.
 */
public final class LegacyRender {
    private LegacyRender() {}

    private static final Map<String, Object> REAL = new HashMap<String, Object>();
    private static final String GAMMA = "gamma";
    private static final String[] KEYS = { Overrides.OPT_FOV, Overrides.OPT_BOBBING, Overrides.OPT_PARTICLES, Overrides.OPT_ENTITY_SHADOWS,
            Overrides.OPT_CLOUDS, Overrides.OPT_RENDER_DISTANCE, GAMMA };
    private static final String[] PARTICLES = { "ALL", "DECREASED", "MINIMAL" };
    private static final String[] CLOUDS = { "OFF", "FAST", "FANCY" };

    private static volatile boolean saving;
    private static volatile float volumeScale = 1f, weatherScale = 1f;
    private static boolean smoothApplied, smoothSaved;

    private static double num(Object o) { return ((Number) o).doubleValue(); }

    private static int index(String[] names, Object name, int fallback) {
        for (int i = 0; i < names.length; i++) if (names[i].equals(String.valueOf(name))) return i;
        return fallback;
    }

    private static Object get(GameOptions o, String k) {
        if (k.equals(Overrides.OPT_FOV)) return (double) o.fov;
        if (k.equals(Overrides.OPT_BOBBING)) return o.bobView;
        if (k.equals(Overrides.OPT_PARTICLES)) return o.particle;
        if (k.equals(Overrides.OPT_RENDER_DISTANCE)) return o.viewDistance;
//#if >=1.8
        if (k.equals(Overrides.OPT_ENTITY_SHADOWS)) return o.entityShadows;
        if (k.equals(Overrides.OPT_CLOUDS)) return o.cloudMode;
//#else
        if (k.equals(Overrides.OPT_CLOUDS)) return o.renderClouds ? 2 : 0;
//#endif
        if (k.equals(GAMMA)) return (double) o.gamma;
        return null;
    }

    private static void set(GameOptions o, String k, Object v) {
        try {
            if (k.equals(Overrides.OPT_FOV)) o.fov = (float) num(v);
            else if (k.equals(Overrides.OPT_BOBBING)) o.bobView = v instanceof Boolean ? (Boolean) v : o.bobView;
            else if (k.equals(Overrides.OPT_PARTICLES)) o.particle = v instanceof Integer ? (Integer) v : index(PARTICLES, v, o.particle);
            else if (k.equals(Overrides.OPT_RENDER_DISTANCE)) o.viewDistance = (int) num(v);
//#if >=1.8
            else if (k.equals(Overrides.OPT_ENTITY_SHADOWS)) o.entityShadows = v instanceof Boolean ? (Boolean) v : o.entityShadows;
            else if (k.equals(Overrides.OPT_CLOUDS)) o.cloudMode = v instanceof Integer ? (Integer) v : index(CLOUDS, v, o.cloudMode);
//#else
            else if (k.equals(Overrides.OPT_CLOUDS)) o.renderClouds = (v instanceof Integer ? (Integer) v : index(CLOUDS, v, 2)) > 0;
//#endif
            else if (k.equals(GAMMA)) o.gamma = (float) num(v);
        } catch (ClassCastException e) {
            // A value of the wrong type from a mod: keep the player's own.
        }
    }

    private static void restore(GameOptions o) {
        for (Map.Entry<String, Object> e : REAL.entrySet()) set(o, e.getKey(), e.getValue());
        REAL.clear();
    }

    /** GameOptions.save() brackets: while saving, every option holds the player's real value. */
    public static void setSaving(boolean on) {
        saving = on;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (on && mc.options != null) restore(mc.options);
    }

    /** After mods ticked: write the option overrides into the fields and apply camera smoothing. */
    public static void afterTick(MinecraftClient mc, Overrides fx) {
        GameOptions o = mc.options;
        if (o == null) return;
        Map<String, Object> want = new HashMap<String, Object>(fx.options);
        if (fx.gamma != null) want.put(GAMMA, fx.gamma);
        for (String k : KEYS) {
            Object w = want.get(k);
            if (w != null && !saving) {
                if (!REAL.containsKey(k)) {
                    Object real = get(o, k);
                    if (real != null) REAL.put(k, real);
                }
                set(o, k, w);
            } else if (REAL.containsKey(k)) {
                set(o, k, REAL.remove(k));
            }
        }
        volumeScale = fx.volumeScale;
        weatherScale = fx.weatherVolumeScale;
        // Camera smoothing is a plain field toggled by a vanilla key: remember the player's own state.
        if (fx.smoothCamera && !smoothApplied) {
            smoothSaved = o.smoothCameraEnabled;
            o.smoothCameraEnabled = true;
            smoothApplied = true;
        } else if (!fx.smoothCamera && smoothApplied) {
            o.smoothCameraEnabled = smoothSaved;
            smoothApplied = false;
        }
    }

    /** GameOptions.getSoundVolume return hook: the Streamer / Recording volume scales. */
    public static float volume(SoundCategory category, float value) {
        if (saving) return value;
        if (category == SoundCategory.MASTER) return value * volumeScale;
        if (category == SoundCategory.WEATHER) return value * weatherScale;
        return value;
    }

    // ---- nametags ---------------------------------------------------------------------------------------------------

    /** The label to draw above {@code e}, or null to draw none. */
    public static String nameTag(net.minecraft.entity.Entity e, String text) {
        Overrides fx = gg.aether.client.AetherClient.fx();
        if (fx.hideNametags) return null;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (fx.nametagMaxDistance > 0 && mc.player != null && e.squaredDistanceTo(mc.player) > (double) fx.nametagMaxDistance * fx.nametagMaxDistance) return null;
        boolean player = e instanceof net.minecraft.entity.player.PlayerEntity;
        if (fx.hideNpcNametags && player && e != mc.player && !LegacyCompat.inTabList((net.minecraft.entity.player.PlayerEntity) e)) return null;
        String shown = McEvents.replaceFormatted(text, fx);
        if (player) shown = LegacyCosmetics.badge(e.getUuid(), shown);
        if (fx.nametagHealth && e instanceof net.minecraft.entity.LivingEntity) {
            net.minecraft.entity.LivingEntity le = (net.minecraft.entity.LivingEntity) e;
            float hp = le.getHealth() + le.getAbsorption();
            float f = le.getMaxHealth() <= 0 ? 1 : le.getHealth() / le.getMaxHealth();
            String color = f > 0.6f ? "\u00a7a" : f > 0.3f ? "\u00a7e" : "\u00a7c";
            shown = shown + " " + color + String.format(java.util.Locale.ROOT, "%.1f", hp / 2f) + "\u2764";
        }
        return shown;
    }

    /** MinecraftClient.getMaxFramerate return hook: the FPS limiter / dynamic FPS. */
    public static int framerate(int vanilla) {
        Integer limit = gg.aether.client.AetherClient.fx().fpsLimit;
        if (limit == null) return vanilla;
        // Vanilla returns 30 in menus and 'unlimited' (260) in game; a lower value than the player's own is throttling.
        MinecraftClient mc = MinecraftClient.getInstance();
        int own = mc.options == null ? vanilla : mc.options.maxFramerate;
        boolean throttled = own > 0 && vanilla < own;
        return throttled ? Math.min(vanilla, limit) : Math.max(1, limit);
    }
}
