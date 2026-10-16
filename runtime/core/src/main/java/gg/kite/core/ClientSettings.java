package gg.kite.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client-wide options (not per profile): the menu's Settings tab, General section. Saved in config.json. */
public final class ClientSettings {
    public enum ModLayout { LIST, CARDS }

    public final Setting.Bool customMainMenu = new Setting.Bool("customMainMenu", "Kite main menu", true);
    public final Setting.Bool menuMusic = new Setting.Bool("menuMusic", "Main menu music", true);
    public final Setting.Bool announcements = new Setting.Bool("announcements", "Main menu announcements", true);
    public final Setting.Choice<ModLayout> layout = new Setting.Choice<ModLayout>("modLayout", "Mod list layout", ModLayout.LIST);
    public final Setting.Int dim = new Setting.Int("menuDim", "Menu background dim (%)", 45, 0, 90);
    public final Setting.Bool competitive = new Setting.Bool("competitive", "Competitive mode (blocks mods that give an edge)", false);

    // Sounds.
    public final Setting.Bool uiSounds = new Setting.Bool("uiSounds", "Interface sounds", true);
    public final Setting.Int uiVolume = new Setting.Int("uiVolume", "Interface sound volume (%)", 70, 0, 100).dependsOn(uiSounds);

    // Accessibility (read by the menus, the HUD and the version adapters through Overrides).
    public final Setting.Bool reducedMotion = new Setting.Bool("reducedMotion", "Reduced motion", false);
    public final Setting.Bool highContrast = new Setting.Bool("highContrast", "High-contrast menu", false);
    public final Setting.Dbl uiScale = new Setting.Dbl("uiScale", "Menu scale", 1.0, 0.75, 2.0);
    public final Setting.Bool colorblind = new Setting.Bool("colorblind", "Colorblind-safe colors", false);
    public final Setting.Bool reducedFlashing = new Setting.Bool("reducedFlashing", "Reduced flashing (lightning, darkness)", false);
    public final Setting.Bool reducedParticles = new Setting.Bool("reducedParticles", "Reduced particles", false);
    public final Setting.Bool noScreenEffects = new Setting.Bool("noScreenEffects", "No nausea / portal wobble", false);

    private final List<Setting<?>> all = new ArrayList<Setting<?>>();

    /** Colours the player saved in the colour picker (ARGB), reusable in every mod. Newest first. */
    public final List<Integer> savedColors = new ArrayList<Integer>();
    public static final int MAX_SAVED_COLORS = 16;

    public ClientSettings() {
        all.add(customMainMenu);
        all.add(menuMusic);
        all.add(announcements);
        all.add(layout);
        all.add(dim);
        all.add(competitive);
        all.add(uiSounds);
        all.add(uiVolume);
        all.add(reducedMotion);
        all.add(highContrast);
        all.add(uiScale);
        all.add(colorblind);
        all.add(reducedFlashing);
        all.add(reducedParticles);
        all.add(noScreenEffects);
    }

    /** Section heading the settings page shows before {@code s}, or null. */
    public String sectionBefore(Setting<?> s) {
        if (s == customMainMenu) return "Menus";
        if (s == competitive) return "Gameplay";
        if (s == uiSounds) return "Sounds";
        if (s == reducedMotion) return "Accessibility";
        return null;
    }

    /** Push the accessibility options into this tick's overrides (after they were reset). */
    public void apply(Overrides fx) {
        fx.reducedMotion = reducedMotion.on();
        fx.highContrast = highContrast.on();
        fx.uiScale = (float) uiScale.val();
        fx.colorblind = colorblind.on();
        if (reducedFlashing.on()) {
            fx.option(Overrides.OPT_HIDE_LIGHTNING, true);
            fx.option(Overrides.OPT_DARKNESS, 0.0);
        }
        if (reducedParticles.on()) fx.option(Overrides.OPT_PARTICLES, "MINIMAL");
        if (noScreenEffects.on()) fx.option(Overrides.OPT_SCREEN_EFFECT, 0.0);
    }

    /** Interface sound volume 0..1 (0 when sounds are off). */
    public float uiSoundVolume() { return uiSounds.on() ? uiVolume.val() / 100f : 0f; }

    public List<Setting<?>> all() { return Collections.unmodifiableList(all); }

    public Map<String, Object> toJson() {
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        for (Setting<?> s : all) o.put(s.key, s.toJson());
        List<Object> saved = new ArrayList<Object>();
        for (int c : savedColors) saved.add(String.format("#%08X", c));
        o.put("savedColors", saved);
        return o;
    }

    /**
     * Accessibility used to be a mod saved per profile: take its settings from {@code mod} (the old "accessibility"
     * entry) when config.json doesn't have them yet.
     */
    public void migrateAccessibility(Object mod) {
        if (!(mod instanceof Map)) return;
        Object settings = ((Map<?, ?>) mod).get("settings");
        if (!(settings instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) settings;
        Setting<?>[] moved = { reducedMotion, highContrast, uiScale, colorblind, reducedFlashing, reducedParticles, noScreenEffects };
        for (Setting<?> s : moved) if (m.containsKey(s.key)) s.fromJson(m.get(s.key));
    }

    public void fromJson(Object o) {
        if (!(o instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) o;
        for (Setting<?> s : all) if (m.containsKey(s.key)) s.fromJson(m.get(s.key));
        if (m.get("savedColors") instanceof List) {
            savedColors.clear();
            for (Object c : (List<?>) m.get("savedColors")) {
                Setting.Color parsed = new Setting.Color("c", "c", 0);
                parsed.fromJson(c);
                if (c instanceof String && savedColors.size() < MAX_SAVED_COLORS && !savedColors.contains(parsed.base())) savedColors.add(parsed.base());
            }
        }
    }
}
