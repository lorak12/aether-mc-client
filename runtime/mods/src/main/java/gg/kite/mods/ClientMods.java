package gg.kite.mods;

import gg.kite.core.GameState;
import gg.kite.core.Mod;
import gg.kite.core.Notifications;
import gg.kite.core.Setting;

/** Client-wide preferences that are configured like mods (so they live in profiles). */
public final class ClientMods {
    private ClientMods() {}

    /** Where and how Kite toasts appear. Off = defaults (top right, 3-6 s, max 5). */
    public static final class NotificationSettings extends Mod {
        private final Setting.Choice<Notifications.Corner> corner = add(new Setting.Choice<Notifications.Corner>("corner", "Position", Notifications.Corner.TOP_RIGHT));
        private final Setting.Dbl duration = add(new Setting.Dbl("duration", "Duration multiplier", 1.0, 0.25, 4.0));
        private final Setting.Int max = add(new Setting.Int("max", "Max on screen", 5, 1, 10));
        private final Setting.Choice<Notifications.Level> minLevel = add(new Setting.Choice<Notifications.Level>("minLevel", "Minimum priority", Notifications.Level.INFO));
        private final Setting.Bool fade = add(new Setting.Bool("fade", "Fade out", true));

        public NotificationSettings() { super("notifications", "Notifications", "Position, duration and priority of toasts (Minimum priority: Error = do not disturb)", Category.UTILITY, true); }

        @Override public void onTick(GameState s) {
            toasts.corner = corner.val();
            toasts.durationScale = (float) duration.val();
            toasts.max = max.val();
            toasts.minLevel = minLevel.val();
            toasts.fade = fade.on() && !fx.reducedMotion;
        }
    }

}
