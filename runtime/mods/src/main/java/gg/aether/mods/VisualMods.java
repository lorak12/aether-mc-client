package gg.aether.mods;

import gg.aether.core.Events;
import gg.aether.core.GameState;
import gg.aether.core.Hotkey;
import gg.aether.core.Keys;
import gg.aether.core.Mod;
import gg.aether.core.Overrides;
import gg.aether.core.Setting;

/** Mods that change how the game looks or how the camera behaves, via {@link Overrides}. No HUD of their own. */
public final class VisualMods {
    private VisualMods() {}

    public static final class Zoom extends Mod {
        private final Setting.Dbl level = add(new Setting.Dbl("level", "Zoom level", 4.0, 1.5, 30.0));
        private final Setting.Bool smooth = add(new Setting.Bool("smooth", "Smooth zoom", true));
        private final Setting.Dbl speed = add(new Setting.Dbl("speed", "Zoom speed", 0.35, 0.05, 1.0), smooth);
        private final Setting.Bool scroll = add(new Setting.Bool("scroll", "Scroll to change level", true));
        private final Setting.Bool lowerSens = add(new Setting.Bool("lowerSens", "Lower sensitivity while zoomed", true));
        private final Setting.Dbl sensitivity = add(new Setting.Dbl("sensitivity", "Zoomed sensitivity x", 1.0, 0.1, 2.0));
        private final Setting.Bool hideHand = add(new Setting.Bool("hideHand", "Hide hand", false));
        private final Setting.Bool hideHud = add(new Setting.Bool("hideHud", "Hide HUD while zoomed", false));
        private final Setting.Bool cinematic = add(new Setting.Bool("cinematic", "Smooth camera while zoomed", false));
        private final Hotkey key = hotkey("key", "Zoom key", Keys.C, Hotkey.Mode.HOLD);
        private double current = 1.0;

        public Zoom() { super("zoom", "Zoom", "Hold a key to zoom in; scroll adjusts the level", Category.VISUAL, true); }

        @Override protected void onEnable() {
            current = 1.0;
            events.on(Events.Scroll.class, this, e -> {
                if (scroll.on() && key.active()) level.set(level.val() * (e.delta > 0 ? 1.1 : 1 / 1.1));
            });
        }

        /** The zoom factor currently applied (1 = none). Exposed for tests. */
        double factor() { return current; }

        @Override public void onTick(GameState s) {
            double target = key.active() ? level.val() : 1.0;
            current = smooth.on() ? current + (target - current) * speed.val() : target;
            if (Math.abs(current - target) < 0.01) current = target;
            if (current <= 1.0) return;
            fx.fovMultiplier *= (float) (1.0 / current);
            if (lowerSens.on()) fx.sensitivityMultiplier *= (float) (1.0 / current);
            fx.sensitivityMultiplier *= (float) sensitivity.val();
            if (hideHand.on()) fx.hideHand = true;
            if (hideHud.on()) {
                fx.hideAllHud = true;
                fx.hideVanillaHud = true;
            }
            if (cinematic.on()) fx.smoothCamera = true;
        }
    }

    public static final class Fullbright extends Mod {
        private final Setting.Dbl gamma = add(new Setting.Dbl("gamma", "Brightness", 12.0, 1.0, 16.0));
        private final Setting.Bool cavesOnly = add(new Setting.Bool("cavesOnly", "Only underground / in caves", false));
        private final Hotkey key = hotkey("key", "Toggle key", Keys.G, Hotkey.Mode.TOGGLE);

        // Off-by-hotkey, not off-by-mod: the mod is enabled in the menu, the key flips it in-game.
        private boolean suppressed;

        public Fullbright() { super("fullbright", "Fullbright", "Maximum brightness everywhere", Category.VISUAL, false); }

        @Override protected void onEnable() { suppressed = false; }
        @Override protected void onHotkey(Hotkey h) { if (h == key) suppressed = key.active(); }

        @Override public void onTick(GameState s) {
            if (suppressed) return;
            if (cavesOnly.on() && !underground(s)) return;
            fx.gamma = gamma.val();
        }

        /** No sky light where the player's head is, or well below sea level. */
        static boolean underground(GameState s) {
            int x = (int) Math.floor(s.x()), y = (int) Math.floor(s.y()) + 1, z = (int) Math.floor(s.z());
            return s.skyLight(x, y, z) == 0 || s.y() < 40;
        }
    }

    public static final class Freelook extends Mod {
        private final Setting.Dbl sensitivity = add(new Setting.Dbl("sensitivity", "Horizontal sensitivity", 1.0, 0.1, 5.0));
        private final Setting.Dbl verticalSens = add(new Setting.Dbl("verticalSens", "Vertical sensitivity", 1.0, 0.1, 5.0));
        private final Setting.Int maxYaw = add(new Setting.Int("maxYaw", "Max yaw (180 = free)", 180, 10, 180));
        private final Setting.Int maxPitch = add(new Setting.Int("maxPitch", "Max pitch", 90, 10, 90));
        private final Setting.Bool invertY = add(new Setting.Bool("invertY", "Invert Y", false));
        private final Setting.Bool smoothing = add(new Setting.Bool("smoothing", "Camera smoothing", false));
        private final Setting.Bool thirdPerson = add(new Setting.Bool("thirdPerson", "Switch to third person", true));
        private final Hotkey key = hotkey("key", "Freelook key", Keys.V, Hotkey.Mode.HOLD);
        private float yaw, pitch;

        private static final float DEGREES_PER_PIXEL = 0.15f;

        public Freelook() { super("freelook", "Freelook", "Look around without turning your character", Category.VISUAL, true); }

        @Override protected void onEnable() {
            yaw = pitch = 0;
            events.on(Events.MouseMove.class, this, e -> {
                if (!key.active()) return;
                yaw += e.dx * DEGREES_PER_PIXEL * sensitivity.val();
                if (maxYaw.val() < 180) yaw = Math.max(-maxYaw.val(), Math.min(maxYaw.val(), yaw));
                float dy = e.dy * DEGREES_PER_PIXEL * (float) verticalSens.val() * (invertY.on() ? -1f : 1f);
                pitch = Math.max(-maxPitch.val(), Math.min(maxPitch.val(), pitch + dy));
                apply(); // mouse moves arrive every frame; waiting for the next tick would make the camera stutter
            });
        }

        @Override protected void onHotkey(Hotkey h) {
            if (key.active()) { apply(); return; }
            yaw = pitch = 0;
            fx.freelook = fx.forceThirdPerson = false;
            fx.cameraYawOffset = fx.cameraPitchOffset = 0;
        }

        @Override public void onTick(GameState s) {
            if (!key.active()) return;
            apply();
            if (smoothing.on()) fx.smoothCamera = true;
        }

        private void apply() {
            fx.freelook = true;
            fx.forceThirdPerson = thirdPerson.on();
            fx.cameraYawOffset = yaw;
            fx.cameraPitchOffset = pitch;
        }
    }

    public static final class TimeChanger extends Mod {
        public enum Preset {
            SUNRISE(23000), DAY(1000), NOON(6000), SUNSET(12500), NIGHT(13000), MIDNIGHT(18000), CUSTOM(0);
            public final int ticks;
            Preset(int ticks) { this.ticks = ticks; }
        }
        private final Setting.Choice<Preset> preset = add(new Setting.Choice<Preset>("preset", "Time", Preset.DAY));
        private final Setting.Int custom = add(new Setting.Int("custom", "Custom time (ticks)", 6000, 0, 23999)).visibleWhen(() -> preset.val() == Preset.CUSTOM);
        private final Setting.Bool cycle = add(new Setting.Bool("cycle", "Let time pass", false));
        private final Setting.Dbl cycleSpeed = add(new Setting.Dbl("cycleSpeed", "Cycle speed", 1.0, 0.1, 50.0), cycle);
        private double clock;

        public TimeChanger() { super("time", "Time Changer", "Client-side time of day (does not affect the server)", Category.VISUAL, true); }

        @Override protected void onEnable() { clock = base(); }

        private int base() { return preset.val() == Preset.CUSTOM ? custom.val() : preset.val().ticks; }

        @Override public void onTick(GameState s) {
            if (cycle.on()) clock = (clock + cycleSpeed.val()) % 24000;
            else clock = base();
            fx.timeOfDay = (int) clock;
        }
    }

    public static final class WeatherChanger extends Mod {
        private final Setting.Choice<Overrides.Weather> weather = add(new Setting.Choice<Overrides.Weather>("weather", "Weather", Overrides.Weather.CLEAR));
        private final Setting.Int intensity = add(new Setting.Int("intensity", "Rain / snow intensity (%)", 100, 10, 100)).visibleWhen(() -> weather.val() != Overrides.Weather.CLEAR);
        private final Setting.Int volume = add(new Setting.Int("volume", "Rain volume (%)", 100, 0, 100)).visibleWhen(() -> weather.val() != Overrides.Weather.CLEAR);

        public WeatherChanger() { super("weather", "Weather Changer", "Client-side weather", Category.VISUAL, true); }

        @Override public void onTick(GameState s) {
            fx.weather = weather.val();
            fx.weatherIntensity = intensity.val() / 100f;
            fx.weatherVolumeScale *= volume.val() / 100f;
        }
    }
}
