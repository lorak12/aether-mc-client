package gg.aether.mc;

import gg.aether.core.Overrides;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
//#if >=1.19
import net.minecraft.client.OptionInstance;
//#endif
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * Applies the rendering / camera / performance {@link Overrides} for the mixins of every adapter. Vanilla option
 * overrides go through {@link #option}, called from OptionInstance.get(): the player's real values are untouched and
 * never saved with overrides in them.
 */
public final class McRender {
    private McRender() {}

    // ---- vanilla option overrides -------------------------------------------------------------------------------

//#if >=1.19
    private static Map<OptionInstance<?>, String> keys;
    private static OptionInstance<?> masterVolume, weatherVolume, gammaOption;
//#endif
    /** Snapshot published once per tick on the client thread; OptionInstance.get() can run on other threads. */
    private static volatile Map<String, Object> published = Collections.emptyMap();
    private static volatile float volumeScale = 1f, weatherScale = 1f;
    /** Fullbright's gamma, null = the player's own. */
    private static volatile Double gamma;
    private static volatile boolean saving;

//#if >=1.19
    private static void buildKeys(Options o) {
        Map<OptionInstance<?>, String> m = new IdentityHashMap<OptionInstance<?>, String>();
        m.put(o.fov(), Overrides.OPT_FOV);
        m.put(o.fovEffectScale(), Overrides.OPT_FOV_EFFECT);
        m.put(o.bobView(), Overrides.OPT_BOBBING);
//#if >=1.19.4
        m.put(o.damageTiltStrength(), Overrides.OPT_HURT_TILT);
//#endif
        m.put(o.screenEffectScale(), Overrides.OPT_SCREEN_EFFECT);
//#if >=1.19.4
        m.put(o.glintStrength(), Overrides.OPT_GLINT_STRENGTH);
        m.put(o.glintSpeed(), Overrides.OPT_GLINT_SPEED);
//#endif
        m.put(o.particles(), Overrides.OPT_PARTICLES);
        m.put(o.entityDistanceScaling(), Overrides.OPT_ENTITY_DISTANCE);
        m.put(o.entityShadows(), Overrides.OPT_ENTITY_SHADOWS);
        m.put(o.cloudStatus(), Overrides.OPT_CLOUDS);
//#if >=1.21.6
        m.put(o.cloudRange(), Overrides.OPT_CLOUD_RANGE);
//#endif
        m.put(o.renderDistance(), Overrides.OPT_RENDER_DISTANCE);
//#if >=1.20.5
        m.put(o.menuBackgroundBlurriness(), Overrides.OPT_MENU_BLUR);
//#endif
        m.put(o.hideLightningFlash(), Overrides.OPT_HIDE_LIGHTNING);
        m.put(o.darknessEffectScale(), Overrides.OPT_DARKNESS);
        m.put(o.framerateLimit(), Overrides.OPT_MAX_FPS);
        gammaOption = o.gamma();
//#if >=1.19.3
        masterVolume = o.getSoundSourceOptionInstance(SoundSource.MASTER);
        weatherVolume = o.getSoundSourceOptionInstance(SoundSource.WEATHER);
//#endif
        keys = m;
    }
//#endif

    /** Options.save() brackets: while saving, every option reports its real value. */
    public static void setSaving(boolean on) {
        saving = on;
//#if <1.19
        if (on && Minecraft.getInstance().options != null) legacyRestore(Minecraft.getInstance().options);
//#endif
    }

//#if <1.19.3
    /** Volume of a sound category with the Streamer / Recording scale (Options.getSoundSourceVolume hook, before 1.19.3). */
    public static float volume(net.minecraft.sounds.SoundSource source, float value) {
        if (saving) return value;
        if (source == SoundSource.MASTER) return value * volumeScale;
        if (source == SoundSource.WEATHER) return value * weatherScale;
        return value;
    }
//#endif

//#if >=1.19
    /** OptionInstance.get() return hook. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static Object option(OptionInstance<?> self, Object value) {
        if (saving || keys == null) return value;
        if (self == masterVolume && volumeScale != 1f && value instanceof Double) return (Double) value * volumeScale;
        if (self == weatherVolume && weatherScale != 1f && value instanceof Double) return (Double) value * weatherScale;
        // Fullbright: the lightmap reads gamma through get() on every version.
        if (self == gammaOption && gamma != null) return gamma;
        Map<String, Object> p = published;
        if (p.isEmpty()) return value;
        String key = keys.get(self);
        if (key == null) return value;
        Object o = p.get(key);
        if (o == null) return value;
        if (value instanceof Enum) {
            for (Object c : ((Enum) value).getDeclaringClass().getEnumConstants()) if (((Enum) c).name().equals(String.valueOf(o))) return c;
            return value;
        }
        if (value instanceof Integer && o instanceof Number) return ((Number) o).intValue();
        if (value instanceof Double && o instanceof Number) return ((Number) o).doubleValue();
        if (value instanceof Boolean && o instanceof Boolean) return o;
        return value;
    }
//#else
    // Before 1.19 options are plain fields: the override is written into the field and the player's own value is put
    // back when it ends and around Options.save().
    private static final Map<String, Object> real = new HashMap<String, Object>();
    private static final String GAMMA = "gamma";
    private static final String[] LEGACY_KEYS = { Overrides.OPT_FOV, Overrides.OPT_FOV_EFFECT, Overrides.OPT_BOBBING, Overrides.OPT_SCREEN_EFFECT,
            Overrides.OPT_PARTICLES, Overrides.OPT_ENTITY_DISTANCE, Overrides.OPT_ENTITY_SHADOWS, Overrides.OPT_CLOUDS, Overrides.OPT_RENDER_DISTANCE,
            Overrides.OPT_HIDE_LIGHTNING, Overrides.OPT_MAX_FPS, GAMMA };

    private static double num(Object o) { return ((Number) o).doubleValue(); }

    private static <E extends Enum<E>> E en(E[] values, Object name, E fallback) {
        for (E e : values) if (e.name().equals(String.valueOf(name))) return e;
        return fallback;
    }

    private static Object legacyGet(Options o, String k) {
        if (k.equals(Overrides.OPT_FOV)) return o.fov;
        if (k.equals(Overrides.OPT_FOV_EFFECT)) return o.fovEffectScale;
        if (k.equals(Overrides.OPT_BOBBING)) return o.bobView;
        if (k.equals(Overrides.OPT_SCREEN_EFFECT)) return o.screenEffectScale;
        if (k.equals(Overrides.OPT_PARTICLES)) return o.particles;
        if (k.equals(Overrides.OPT_ENTITY_DISTANCE)) return o.entityDistanceScaling;
        if (k.equals(Overrides.OPT_ENTITY_SHADOWS)) return o.entityShadows;
        if (k.equals(Overrides.OPT_CLOUDS)) return o.renderClouds;
        if (k.equals(Overrides.OPT_RENDER_DISTANCE)) return o.renderDistance;
//#if >=1.18
        if (k.equals(Overrides.OPT_HIDE_LIGHTNING)) return o.hideLightningFlashes;
//#endif
        if (k.equals(Overrides.OPT_MAX_FPS)) return o.framerateLimit;
        return o.gamma;
    }

    private static void legacySet(Options o, String k, Object v) {
        try {
            if (k.equals(Overrides.OPT_FOV)) o.fov = num(v);
            else if (k.equals(Overrides.OPT_FOV_EFFECT)) o.fovEffectScale = (float) num(v);
            else if (k.equals(Overrides.OPT_BOBBING)) o.bobView = (Boolean) v;
            else if (k.equals(Overrides.OPT_SCREEN_EFFECT)) o.screenEffectScale = (float) num(v);
            else if (k.equals(Overrides.OPT_PARTICLES)) o.particles = en(net.minecraft.client.ParticleStatus.values(), v, o.particles);
            else if (k.equals(Overrides.OPT_ENTITY_DISTANCE)) o.entityDistanceScaling = (float) num(v);
            else if (k.equals(Overrides.OPT_ENTITY_SHADOWS)) o.entityShadows = (Boolean) v;
            else if (k.equals(Overrides.OPT_CLOUDS)) o.renderClouds = en(net.minecraft.client.CloudStatus.values(), v, o.renderClouds);
            else if (k.equals(Overrides.OPT_RENDER_DISTANCE)) o.renderDistance = (int) num(v);
//#if >=1.18
            else if (k.equals(Overrides.OPT_HIDE_LIGHTNING)) o.hideLightningFlashes = (Boolean) v;
//#endif
            else if (k.equals(Overrides.OPT_MAX_FPS)) o.framerateLimit = (int) num(v);
            else o.gamma = num(v);
        } catch (ClassCastException e) {
            // A value of the wrong type from a mod: keep the player's own.
        }
    }

    private static void legacyRestore(Options o) {
        for (Map.Entry<String, Object> e : real.entrySet()) legacySet(o, e.getKey(), e.getValue());
        real.clear();
    }

    private static void legacyApply(Options o, Overrides fx) {
        Map<String, Object> want = new HashMap<String, Object>(fx.options);
        if (fx.gamma != null) want.put(GAMMA, fx.gamma);
        for (String k : LEGACY_KEYS) {
            Object w = want.get(k);
            if (w != null && !saving) {
                if (!real.containsKey(k)) real.put(k, legacyGet(o, k));
                legacySet(o, k, w);
            } else if (real.containsKey(k)) {
                legacySet(o, k, real.remove(k));
            }
        }
    }
//#endif

    // Per-tick application of state that is not read through a hook.
    private static boolean smoothApplied, smoothSaved;
    private static float appliedVolume = 1f, appliedWeather = 1f;
    private static Integer appliedHitColor;

    /** After mods ticked: publish option overrides and apply camera smoothing, volume and hit colour. */
    public static void afterTick(Minecraft mc, Overrides fx) {
        if (mc.options == null) return;
//#if >=1.19
        if (keys == null) buildKeys(mc.options);
        published = fx.options.isEmpty() ? Collections.<String, Object>emptyMap() : new HashMap<String, Object>(fx.options);
//#else
        legacyApply(mc.options, fx);
//#endif
        volumeScale = fx.volumeScale;
        gamma = fx.gamma;
        weatherScale = fx.weatherVolumeScale;
        if (volumeScale != appliedVolume) {
            appliedVolume = volumeScale;
            Compat.refreshVolume(mc, SoundSource.MASTER);
        }
        if (weatherScale != appliedWeather) {
            appliedWeather = weatherScale;
            Compat.refreshVolume(mc, SoundSource.WEATHER);
        }
        // Camera smoothing is a plain field toggled by a vanilla key: remember the player's own state.
        if (fx.smoothCamera && !smoothApplied) {
            smoothSaved = mc.options.smoothCamera;
            mc.options.smoothCamera = true;
            smoothApplied = true;
        } else if (!fx.smoothCamera && smoothApplied) {
            mc.options.smoothCamera = smoothSaved;
            smoothApplied = false;
        }
        Integer hit = fx.hitColor;
        if (hit == null ? appliedHitColor != null : !hit.equals(appliedHitColor)) {
            applyHitColor(mc, hit == null ? 0xB2FF0000 : hit);
            appliedHitColor = hit;
        }
        // Auto Tool: a hotbar slot change is sent to the server like a scroll-wheel switch.
        Integer slot = fx.selectSlot;
        if (slot != null && mc.player != null && slot >= 0 && slot <= 8 && Compat.selectedSlot(mc.player) != slot) {
            Compat.setSelectedSlot(mc.player, slot);
        }
    }

    /** Rows 0-7 of the 16x16 overlay texture are the hurt tint (ARGB). */
    private static void applyHitColor(Minecraft mc, int argb) {
        DynamicTexture t = Compat.overlayTexture(mc);
        if (t == null || t.getPixels() == null) return;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 16; x++) Pixels.set(t.getPixels(), x, y, argb);
        t.upload();
    }

    // ---- entities -----------------------------------------------------------------------------------------------

    private static final java.util.Set<String> BOSSES = new java.util.HashSet<String>(java.util.Arrays.asList(
            "minecraft:ender_dragon", "minecraft:wither", "minecraft:warden", "minecraft:elder_guardian"));

    /** Highlight outline colour for an entity (0xRRGGBB), or -1 for none. */
    public static int highlight(Entity e) {
        Overrides.Highlighter h = Compat.mods().overrides().highlighter;
        Minecraft mc = Minecraft.getInstance();
        if (h == null || mc.player == null || e == mc.player || !(e instanceof LivingEntity) || e.isInvisible()) return -1;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        boolean boss = BOSSES.contains(id);
        int c = h.color(id, e instanceof Enemy, e instanceof Player, e.hasCustomName(), boss, e.distanceTo(mc.player));
        return c == 0 ? -1 : c & 0xFFFFFF;
    }

    public static boolean hidden(Entity e) {
        java.util.Set<String> hidden = Compat.mods().overrides().hiddenEntities;
        return !hidden.isEmpty() && hidden.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
    }

    /**
     * Nametag visibility on top of vanilla's answer: distance limit, NPC filter, own nametag in third person.
     * {@code distSq} is the squared camera distance vanilla passes in.
     */
    public static boolean showName(Entity e, double distSq, boolean vanilla) {
        Overrides fx = Compat.mods().overrides();
        if (fx.hideNametags) return false;
        Minecraft mc = Minecraft.getInstance();
        if (fx.showOwnNametag && e == mc.player && !mc.options.getCameraType().isFirstPerson() && !e.isInvisible()) return true;
        if (!vanilla) return false;
        if (fx.nametagMaxDistance > 0 && distSq > fx.nametagMaxDistance * fx.nametagMaxDistance) return false;
        if (fx.hideNpcNametags && e instanceof Player) {
            ClientPacketListener c = mc.getConnection();
            if (c != null && c.getPlayerInfo(e.getUUID()) == null) return false;
        }
        return true;
    }

    /** Nametag text: client badge in front, health appended when enabled. */
    public static Component nameTag(Entity e, Component vanilla) {
        vanilla = McEvents.replaceNames(vanilla, Compat.mods().overrides());
        if (e instanceof Player) vanilla = McCosmetics.badge(e.getUUID(), vanilla);
        if (vanilla == null || !Compat.mods().overrides().nametagHealth || !(e instanceof LivingEntity)) return vanilla;
        LivingEntity le = (LivingEntity) e;
        float hp = le.getHealth() + le.getAbsorptionAmount();
        float f = le.getMaxHealth() <= 0 ? 1 : le.getHealth() / le.getMaxHealth();
        int color = f > 0.6f ? 0x55FF55 : f > 0.3f ? 0xFFFF55 : 0xFF5555;
        return vanilla.copy().append(Compat.color(Component.literal(" " + String.format(java.util.Locale.ROOT, "%.1f", hp / 2f) + "❤"), color));
    }

    /** Vanilla tab list names (streamer mode, friend nicknames) with the client badge. */
    public static Component tabName(net.minecraft.client.multiplayer.PlayerInfo info, Component vanilla) {
        Component name = McEvents.replaceNames(vanilla, Compat.mods().overrides());
        return info == null ? name : McCosmetics.badge(info.getProfile().id(), name);
    }

    /** Vanilla toasts (advancements, recipes, tutorials) hidden by streamer / recording mode. */
    public static boolean hideToasts() { return Compat.mods().overrides().hideVanillaToasts; }
}
