package gg.aether.mods;

import gg.aether.core.Events;
import gg.aether.core.GameState;
import gg.aether.core.Hotkey;
import gg.aether.core.HudElement;
import gg.aether.core.HudElement.Anchor;
import gg.aether.core.Keys;
import gg.aether.core.Mod;
import gg.aether.core.Setting;
import gg.aether.core.UiRenderer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Gameplay-helper and capture mods that mostly act through {@link gg.aether.core.Overrides}. */
public final class UtilityMods {
    private UtilityMods() {}

    public static final class ToggleSprint extends HudTextMod {
        private final Setting.Bool status = add(new Setting.Bool("status", "Show status", true));
        private final Setting.Bool always = add(new Setting.Bool("always", "Always sprint", true));
        private final Setting.Bool inScreens = add(new Setting.Bool("inScreens", "Keep sprinting with inventory / menus open", false));
        private final Setting.Bool alwaysSneak = add(new Setting.Bool("alwaysSneak", "Always sneak", false));
        private final Hotkey sprint = hotkey("sprintKey", "Sprint key", Keys.NONE, Hotkey.Mode.TOGGLE);
        private final Hotkey sneak = hotkey("sneakKey", "Sneak key", Keys.NONE, Hotkey.Mode.TOGGLE);

        public ToggleSprint() { super("togglesprint", "Toggle Sprint", "Sprint (and sneak) without holding the key", Category.GAMEPLAY, Anchor.BOTTOM_LEFT, 8, -100); }

        @Override public void onTick(GameState s) {
            fx.forceSprint = always.on() || sprint.active();
            fx.forceSneak = sneak.active() || alwaysSneak.on();
            fx.forceSprintInScreens = inScreens.on();
        }

        @Override protected List<String> lines(GameState s) {
            if (!status.on()) return Collections.emptyList();
            if (sneak.active() || alwaysSneak.on()) return Collections.singletonList("[Sneaking (toggled)]");
            if (always.on() || sprint.active()) return Collections.singletonList("[Sprinting (toggled)]");
            return Collections.emptyList();
        }
    }

    /** Hides anything flagged sensitive (coordinates, server address) while streaming or recording. */
    public static final class StreamerMode extends Mod {
        private final Setting.Bool hideToasts = add(new Setting.Bool("hideToasts", "Hide notifications", false));
        private final Setting.Bool hideVanillaToasts = add(new Setting.Bool("hideVanillaToasts", "Hide vanilla toasts", false));
        private final Setting.Bool hideName = add(new Setting.Bool("hideName", "Hide my username", true));
        private final Setting.Text alias = add(new Setting.Text("alias", "Show my username as", "Streamer", 16), hideName);
        private final Setting.Bool hideChat = add(new Setting.Bool("hideChat", "Hide chat", false));
        private final Setting.Entries names = add(new Setting.Entries("names", "Replace names (chat, tab list, nametags)",
                new String[] { "real", "shown" }, new String[] { "Real name", "Shown as" }, 100));

        public StreamerMode() { super("streamer", "Streamer Mode", "Hide your name, coordinates, server address, friends and other sensitive info", Category.UTILITY, true); }

        @Override public void onTick(GameState s) {
            fx.streamerMode = true;
            fx.hideNotifications |= hideToasts.on();
            fx.hideVanillaToasts |= hideVanillaToasts.on();
            fx.hideChat |= hideChat.on();
            for (int i = 0; i < names.size(); i++) {
                String real = names.cell(i, 0).trim();
                if (!real.isEmpty()) fx.nameReplacements.put(real, names.cell(i, 1).trim());
            }
            String me = s.playerName();
            if (hideName.on() && me != null && !me.isEmpty()) fx.nameReplacements.put(me, alias.str().trim().isEmpty() ? "Streamer" : alias.str().trim());
        }
    }

    /** Warns once (per cooldown) when worn armor or the held tool is nearly broken. */
    public static final class DurabilityWarnings extends Mod {
        private final Setting.Int threshold = add(new Setting.Int("threshold", "Warn below (%)", 10, 1, 90));
        private final Setting.Int cooldown = add(new Setting.Int("cooldown", "Repeat every (s)", 60, 5, 600));
        private final Setting.Bool armor = add(new Setting.Bool("armor", "Armor", true));
        private final Setting.Bool held = add(new Setting.Bool("held", "Held item", true));
        private final Map<String, Long> lastWarn = new HashMap<String, Long>();

        public DurabilityWarnings() { super("durabilitywarn", "Durability Warnings", "Alerts you before gear breaks", Category.UTILITY, true); }

        @Override protected void onEnable() { lastWarn.clear(); }

        @Override public void onTick(GameState s) {
            if (!s.inWorld()) return;
            long now = s.nowMillis();
            if (armor.on()) {
                List<GameState.ArmorPiece> a = s.armor();
                for (int i = 0; i < a.size(); i++) {
                    GameState.ArmorPiece p = a.get(i);
                    if (p != null) check("armor" + i, p.itemId, p.durability, p.maxDurability, now);
                }
            }
            GameState.ItemStack h = held.on() ? s.heldItem() : null;
            if (h != null) check("held:" + h.itemId, h.itemId, h.durability, h.maxDurability, now);
        }

        private void check(String slot, String itemId, int cur, int max, long now) {
            if (max <= 0) return;
            int pct = Math.round(100f * cur / max);
            if (pct > threshold.val()) { lastWarn.remove(slot); return; }
            Long last = lastWarn.get(slot);
            if (last != null && now - last < cooldown.val() * 1000L) return;
            lastWarn.put(slot, now);
            toasts.warn(now, HudTextMod.prettyId(itemId) + ": " + pct + "% durability");
        }
    }

    /** The vanilla action-bar text, redrawn where you want it. */
    public static final class ActionBar extends Mod {
        private final Setting.Bool shadow = add(new Setting.Bool("shadow", "Text shadow", true));
        private final Setting.Bool background = add(new Setting.Bool("background", "Background", true));
        private final Setting.Dbl seconds = add(new Setting.Dbl("seconds", "Show for (s)", 3.0, 1.0, 15.0));
        private String text;
        private long until;

        public ActionBar() {
            super("actionbar", "Action Bar", "Move and restyle the action-bar message", Category.HUD, true);
            hud = new HudElement(Anchor.BOTTOM_CENTER, 0, -60);
        }

        @Override protected void onEnable() {
            text = null;
            events.on(Events.ActionBar.class, this, e -> {
                text = e.text;
                until = e.timeMs + (long) (seconds.val() * 1000);
            });
        }

        @Override public void onTick(GameState s) { fx.hideVanillaActionBar = true; }

        @Override public void renderHud(UiRenderer r, GameState s) {
            long now = s.nowMillis();
            String text = this.text;
            float fade;
            if (text == null || text.isEmpty() || now >= until) {
                if (!previewing()) return;
                text = name;
                fade = hud.opacity();
            } else {
                fade = Math.min(1f, (until - now) / 500f) * hud.opacity();
            }
            float w = r.textWidth(text) + 10, h = r.fontHeight() + 8;
            float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), w, h);
            r.push(p[0], p[1], hud.scale());
            if (background.on()) r.rect(0, 0, w, h, (Math.round(0x80 * fade) << 24), 3f);
            r.text(text, 5, 4, (Math.round(255 * fade) << 24) | 0xFFFFFF, shadow.on());
            r.pop();
        }
    }
}
