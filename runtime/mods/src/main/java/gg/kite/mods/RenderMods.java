package gg.kite.mods;

import gg.kite.core.Events;
import gg.kite.core.GameState;
import gg.kite.core.Hotkey;
import gg.kite.core.Keys;
import gg.kite.core.Mod;
import gg.kite.core.Overrides;
import gg.kite.core.Setting;

/**
 * Rendering, camera and performance mods. They only fill in {@link Overrides}; the version adapters apply them
 * (vanilla option overrides are never written to options.txt).
 */
public final class RenderMods {
    private RenderMods() {}

    // ---- FOV ----------------------------------------------------------------------------------------------------

    public static final class Fov extends Mod {
        private final Setting.Bool customBase = add(new Setting.Bool("customBase", "Custom base FOV", false));
        private final Setting.Int base = add(new Setting.Int("base", "Base FOV", 90, 30, 110), customBase);
        private final Setting.Bool dynamic = add(new Setting.Bool("dynamic", "Vanilla dynamic FOV (speed effects)", true));
        private final Setting.Dbl dynamicScale = add(new Setting.Dbl("dynamicScale", "Dynamic FOV strength", 1.0, 0.0, 1.0), dynamic);
        private final Setting.Dbl sprint = add(new Setting.Dbl("sprint", "Sprinting FOV x", 1.0, 0.8, 1.3));
        private final Setting.Dbl flying = add(new Setting.Dbl("flying", "Flying FOV x", 1.0, 0.8, 1.3));
        private final Setting.Dbl water = add(new Setting.Dbl("water", "Underwater FOV x", 1.0, 0.7, 1.2));
        private final Setting.Dbl bow = add(new Setting.Dbl("bow", "Bow draw FOV x", 1.0, 0.6, 1.0));
        private final Setting.Bool smooth = add(new Setting.Bool("smooth", "Smooth transitions", true));
        private double current = 1.0;

        public Fov() { super("fov", "FOV", "Base FOV, dynamic FOV and per-state FOV changes (sprint, fly, water, bow)", Category.VISUAL, true); }

        @Override protected void onEnable() { current = 1.0; }

        double target(GameState s) {
            double t = 1.0;
            if (s.sprinting()) t *= sprint.val();
            if (s.flying()) t *= flying.val();
            if (s.inWater()) t *= water.val();
            GameState.ItemStack held = s.heldItem();
            if (s.useTicks() > 0 && held != null && (held.itemId.endsWith(":bow") || held.itemId.endsWith(":crossbow"))) {
                t *= 1.0 - (1.0 - bow.val()) * Math.min(1.0, s.useTicks() / 20.0);
            }
            return t;
        }

        @Override public void onTick(GameState s) {
            if (customBase.on()) fx.option(Overrides.OPT_FOV, base.val());
            fx.option(Overrides.OPT_FOV_EFFECT, dynamic.on() ? dynamicScale.val() : 0.0);
            double t = target(s);
            current = smooth.on() && !fx.reducedMotion ? current + (t - current) * 0.3 : t;
            if (Math.abs(current - t) < 0.002) current = t;
            fx.fovMultiplier *= (float) current;
        }
    }

    // ---- Camera -------------------------------------------------------------------------------------------------

    public static final class Camera extends Mod {
        public enum Fire { VANILLA, LOWERED, HIDDEN }

        private final Setting.Bool bobbing = add(new Setting.Bool("bobbing", "View bobbing", true));
        private final Setting.Int hurtCam = add(new Setting.Int("hurtCam", "Hurt camera shake (%)", 100, 0, 100));
        private final Setting.Int screenEffects = add(new Setting.Int("screenEffects", "Nausea / portal distortion (%)", 100, 0, 100));
        private final Setting.Int darkness = add(new Setting.Int("darkness", "Darkness effect pulsing (%)", 100, 0, 100));
        private final Setting.Choice<Fire> fire = add(new Setting.Choice<Fire>("fire", "Fire overlay", Fire.VANILLA));
        private final Setting.Bool water = add(new Setting.Bool("water", "Underwater overlay", true));
        private final Setting.Bool smoothCamera = add(new Setting.Bool("smoothCamera", "Camera smoothing (cinematic)", false));
        private final Setting.Dbl distance = add(new Setting.Dbl("distance", "Third-person distance", 4.0, 1.0, 16.0));
        private final Setting.Bool clip = add(new Setting.Bool("clip", "Third-person camera collides with blocks", true));

        public Camera() { super("camera", "Camera", "View bobbing, hurt camera, overlays, smoothing and third-person distance", Category.VISUAL, true); }

        @Override public void onTick(GameState s) {
            if (!bobbing.on()) fx.option(Overrides.OPT_BOBBING, false);
            if (hurtCam.val() != 100) fx.option(Overrides.OPT_HURT_TILT, hurtCam.val() / 100.0);
            if (screenEffects.val() != 100) fx.option(Overrides.OPT_SCREEN_EFFECT, screenEffects.val() / 100.0);
            if (darkness.val() != 100) fx.option(Overrides.OPT_DARKNESS, darkness.val() / 100.0);
            if (fire.val() == Fire.HIDDEN) fx.hideFireOverlay = true;
            else if (fire.val() == Fire.LOWERED) fx.fireOverlayOffset = 0.3f;
            if (!water.on()) fx.hideWaterOverlay = true;
            if (smoothCamera.on()) fx.smoothCamera = true;
            if (distance.val() != 4.0) fx.cameraDistance = (float) distance.val();
            if (!clip.on()) fx.noCameraClip = true;
        }
    }

    // ---- Animations ---------------------------------------------------------------------------------------------

    public static final class Animations extends Mod {
        private final Setting.Bool equip = add(new Setting.Bool("equip", "Item switch animation", true));
        private final Setting.Dbl swing = add(new Setting.Dbl("swing", "Swing duration x (lower = faster)", 1.0, 0.25, 3.0));
        private final Setting.Int glint = add(new Setting.Int("glint", "Enchantment glint strength (%)", 100, 0, 100));
        private final Setting.Int glintSpeed = add(new Setting.Int("glintSpeed", "Enchantment glint speed (%)", 100, 0, 100));

        // Hurt camera and view bobbing live in the Camera mod.
        public Animations() { super("animations", "Animations", "Item switch, swing speed and enchantment glint", Category.VISUAL, true); }

        @Override public void onTick(GameState s) {
            if (!equip.on()) fx.noEquipAnimation = true;
            fx.swingSpeed *= (float) swing.val();
            if (glint.val() != 100) fx.option(Overrides.OPT_GLINT_STRENGTH, glint.val() / 100.0);
            if (glintSpeed.val() != 100) fx.option(Overrides.OPT_GLINT_SPEED, glintSpeed.val() / 100.0);
        }
    }

    // ---- Sky / clouds -------------------------------------------------------------------------------------------

    // ---- Entity rendering ---------------------------------------------------------------------------------------

    // ---- Particles ----------------------------------------------------------------------------------------------

    public static final class Particles extends Mod {
        public enum Amount { VANILLA, ALL, DECREASED, MINIMAL }

        private final Setting.Choice<Amount> amount = add(new Setting.Choice<Amount>("amount", "Vanilla particle setting", Amount.VANILLA));
        private final Setting.Int keep = add(new Setting.Int("keep", "Particle multiplier (%)", 100, 0, 100));
        private final Setting.Bool block = add(new Setting.Bool("block", "Block breaking particles", true));
        private final Setting.Bool potion = add(new Setting.Bool("potion", "Potion swirls", true));
        private final Setting.Bool explosion = add(new Setting.Bool("explosion", "Explosions", true));
        private final Setting.Bool weather = add(new Setting.Bool("weather", "Rain splashes", true));
        private final Setting.Bool damage = add(new Setting.Bool("damage", "Damage indicator hearts", true));
        private final Setting.Int crits = add(new Setting.Int("crits", "Extra critical particles per hit", 0, 0, 5));
        private final Setting.Bool magic = add(new Setting.Bool("magic", "Always show sharpness particles", false));

        public Particles() { super("particles", "Particles", "Particle amount, per-type filters and extra hit particles", Category.VISUAL, true); }

        @Override public void onTick(GameState s) {
            if (amount.val() != Amount.VANILLA) fx.option(Overrides.OPT_PARTICLES, amount.val().name());
            fx.particleKeep *= keep.val() / 100f;
            if (!block.on()) fx.hiddenParticles.add("minecraft:block");
            if (!potion.on()) {
                fx.hiddenParticles.add("minecraft:entity_effect");
                fx.hiddenParticles.add("minecraft:effect");
            }
            if (!explosion.on()) {
                fx.hiddenParticles.add("minecraft:explosion");
                fx.hiddenParticles.add("minecraft:explosion_emitter");
            }
            if (!weather.on()) {
                fx.hiddenParticles.add("minecraft:rain");
                fx.hiddenParticles.add("minecraft:splash");
            }
            if (!damage.on()) fx.hiddenParticles.add("minecraft:damage_indicator");
            fx.extraCrits += crits.val();
            if (magic.on()) fx.alwaysMagicCrits = true;
        }
    }

    // ---- Hit color ----------------------------------------------------------------------------------------------

    public static final class HitColor extends Mod {
        private final Setting.Color color = add(new Setting.Color("color", "Hurt flash color (alpha = intensity)", 0xB2FF0000));

        public HitColor() { super("hitcolor", "Hit Color", "The color entities flash when they take damage", Category.PVP, true); }

        @Override public void onTick(GameState s) { fx.hitColor = color.argb(); }
    }

    // ---- Item physics -------------------------------------------------------------------------------------------

    public static final class ItemPhysics extends Mod {
        private final Setting.Dbl size = add(new Setting.Dbl("size", "Size", 1.0, 0.5, 2.0));
        private final Setting.Bool flat = add(new Setting.Bool("flat", "Lie flat on the ground", true));
        private final Setting.Dbl spin = add(new Setting.Dbl("spin", "Rotation speed", 0.0, 0.0, 3.0));
        private final Setting.Bool bob = add(new Setting.Bool("bob", "Bobbing", false));
        private final Setting.Bool shadow = add(new Setting.Bool("shadow", "Shadow", true));

        public ItemPhysics() { super("itemphysics", "Item Physics", "Dropped blocks sit on the ground as little cubes and items lie flat in a pile that grows with the stack, with adjustable spin, bobbing and size", Category.VISUAL, true); }

        @Override public void onTick(GameState s) {
            fx.itemPhysics = flat.on();
            fx.itemSpin = (float) spin.val();
            fx.itemBob = bob.on();
            fx.itemScale = (float) size.val();
            fx.hideItemShadows = !shadow.on();
        }
    }

    // ---- Mob highlight ------------------------------------------------------------------------------------------

    // ---- Nametags -----------------------------------------------------------------------------------------------

    public static final class Nametags extends Mod {
        private final Setting.Bool health = add(new Setting.Bool("health", "Show health", true));
        private final Setting.Bool own = add(new Setting.Bool("own", "Own nametag in third person", false));
        private final Setting.Int maxDistance = add(new Setting.Int("maxDistance", "Max distance (0 = vanilla)", 0, 0, 128));
        private final Setting.Bool hideNpcs = add(new Setting.Bool("hideNpcs", "Hide NPC nametags (not in tab list)", false));

        public Nametags() { super("nametags", "Nametags", "Health in nametags, own nametag, distance limit and NPC filter", Category.VISUAL, true); }

        @Override public void onTick(GameState s) {
            fx.nametagHealth = health.on();
            fx.showOwnNametag = own.on();
            fx.nametagMaxDistance = maxDistance.val();
            fx.hideNpcNametags = hideNpcs.on();
        }
    }

    // ---- Hitboxes -----------------------------------------------------------------------------------------------

    public static final class Hitboxes extends Mod {
        public enum Style { VANILLA, CUSTOM }

        private final Setting.Choice<Style> style = add(new Setting.Choice<Style>("style", "Style", Style.CUSTOM));
        // Everything below the style only applies to the custom boxes.
        private final Setting.Bool players = add(new Setting.Bool("players", "Players", true)).visibleWhen(this::custom);
        private final Setting.Color playerColor = add(new Setting.Color("playerColor", "Player color", 0xFFFFFFFF), players);
        private final Setting.Bool mobs = add(new Setting.Bool("mobs", "Mobs", true)).visibleWhen(this::custom);
        private final Setting.Color mobColor = add(new Setting.Color("mobColor", "Mob color", 0xFFFF5555), mobs);
        private final Setting.Bool items = add(new Setting.Bool("items", "Items", false)).visibleWhen(this::custom);
        private final Setting.Color itemColor = add(new Setting.Color("itemColor", "Item color", 0xFFFFDD55), items);
        private final Setting.Bool projectiles = add(new Setting.Bool("projectiles", "Projectiles", true)).visibleWhen(this::custom);
        private final Setting.Color projectileColor = add(new Setting.Color("projectileColor", "Projectile color", 0xFF55AAFF), projectiles);
        private final Setting.Bool other = add(new Setting.Bool("other", "Other entities", false)).visibleWhen(this::custom);
        private final Setting.Int width = add(new Setting.Int("width", "Line width", 2, 1, 6)).visibleWhen(this::custom);
        private final Setting.Int fill = add(new Setting.Int("fill", "Fill opacity (%)", 0, 0, 60)).visibleWhen(this::custom);
        private final Setting.Int radius = add(new Setting.Int("radius", "Radius", 32, 4, 96)).visibleWhen(this::custom);
        private final Setting.Bool throughWalls = add(new Setting.Bool("throughWalls", "Through walls", false)).visibleWhen(this::custom);
        private final Hotkey key = hotkey("key", "Toggle key", Keys.NONE, Hotkey.Mode.TOGGLE);
        private boolean off;

        private boolean custom() { return style.val() == Style.CUSTOM; }

        public Hitboxes() { super("hitboxes", "Hitboxes", "Entity hitboxes by category with colors, fill and width (or vanilla F3+B)", Category.PVP, true); }

        @Override protected void onEnable() { off = false; }
        @Override protected void onHotkey(Hotkey h) { off = key.active(); }
        @Override public void onTick(GameState s) { if (!off && style.val() == Style.VANILLA) fx.showHitboxes = true; }

        @Override public void renderWorld(gg.kite.core.WorldRenderer w, GameState s) {
            if (off || style.val() != Style.CUSTOM) return;
            for (GameState.EntityInfo e : s.entities(radius.val())) {
                int c;
                if (e.player) { if (!players.on()) continue; c = playerColor.argb(); }
                else if (e.item) { if (!items.on()) continue; c = itemColor.argb(); }
                else if (e.projectile) { if (!projectiles.on()) continue; c = projectileColor.argb(); }
                else if (e.living) { if (!mobs.on()) continue; c = mobColor.argb(); }
                else { if (!other.on()) continue; c = 0xFFAAAAAA; }
                double hw = (e.width > 0 ? e.width : 0.6) / 2;
                int f = fill.val() == 0 ? 0 : (Math.round(255 * fill.val() / 100f) << 24) | (c & 0xFFFFFF);
                w.box(e.x - hw, e.y, e.z - hw, e.x + hw, e.y + e.height, e.z + hw, c, width.val(), f, throughWalls.on());
            }
        }
    }

    // ---- Inventory blur -----------------------------------------------------------------------------------------

    public static final class InventoryBlur extends Mod {
        private final Setting.Int blur = add(new Setting.Int("blur", "Menu blur amount (0 = off)", 5, 0, 10));

        public InventoryBlur() { super("blur", "Menu Blur", "Background blur behind menus", Category.VISUAL, true); }

        @Override public void onTick(GameState s) { fx.option(Overrides.OPT_MENU_BLUR, blur.val()); }
    }

    // ---- Cinematic camera ---------------------------------------------------------------------------------------

    // ---- FPS limiter / dynamic FPS / profiles -------------------------------------------------------------------

}
