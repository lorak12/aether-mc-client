package gg.kite.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What mods ask the game to do differently this tick. Reset at the start of every {@link ModManager#tick},
 * then filled in by active mods; version adapters read it every frame. Mods never touch the game directly.
 */
public final class Overrides {
    public enum Weather { VANILLA, CLEAR, RAIN, THUNDER, SNOW }

    /** Decides the glow outline colour of an entity (0 = no highlight). Called by the adapter per rendered entity. */
    public interface Highlighter {
        int color(String typeId, boolean hostile, boolean player, boolean named, boolean boss, double distance);
    }

    /** Vanilla option keys understood by the adapters (values: Integer, Double, Boolean or an enum constant name). */
    public static final String OPT_FOV = "fov", OPT_FOV_EFFECT = "fovEffectScale", OPT_BOBBING = "bobView", OPT_HURT_TILT = "damageTiltStrength",
            OPT_SCREEN_EFFECT = "screenEffectScale", OPT_GLINT_STRENGTH = "glintStrength", OPT_GLINT_SPEED = "glintSpeed", OPT_PARTICLES = "particles",
            OPT_ENTITY_DISTANCE = "entityDistanceScaling", OPT_ENTITY_SHADOWS = "entityShadows", OPT_CLOUDS = "cloudStatus", OPT_CLOUD_RANGE = "cloudRange",
            OPT_RENDER_DISTANCE = "renderDistance", OPT_MENU_BLUR = "menuBackgroundBlurriness", OPT_HIDE_LIGHTNING = "hideLightningFlash",
            OPT_DARKNESS = "darknessEffectScale", OPT_MAX_FPS = "framerateLimit";

    /** Multiplied together by every mod that wants to change FOV (zoom, etc.). */
    public float fovMultiplier = 1f;
    public float sensitivityMultiplier = 1f;
    /** Null leaves the player's own gamma setting alone. */
    public Double gamma;
    public boolean forceSprint, forceSneak;
    /** Camera looks around independently of the body. Offsets are degrees relative to the player's rotation. */
    public boolean freelook, forceThirdPerson;
    public float cameraYawOffset, cameraPitchOffset;
    /** Client-side world time in ticks (0-24000), null = server time. */
    public Integer timeOfDay;
    public Weather weather = Weather.VANILLA;
    public boolean hideHand, hideVanillaActionBar, hideVanillaCrosshair, hideVanillaHud, hideNametags;
    /** Vanilla HUD pieces a mod redraws itself. */
    public boolean hideVanillaBossBar, hideVanillaTitle, hideVanillaScoreboard, hideVanillaEffects, hideVanillaHotbar, hideVanillaTabList;
    /** Hide every Kite HUD element (screenshot mode). */
    public boolean hideAllHud;
    /** Hide mods flagged sensitive (coordinates, server address) and optionally toasts. */
    public boolean streamerMode, hideNotifications;

    // Accessibility (read by the menu, the HUD and the adapters).
    /** No blinking cursors, fades or animated transitions. */
    public boolean reducedMotion;
    /** Opaque, higher-contrast menu colours. */
    public boolean highContrast;
    /** Extra scale for the Kite menu (1 = normal). */
    public float uiScale = 1f;
    /** Red/green status colours swapped for blue/orange. */
    public boolean colorblind;

    // Rendering / camera / performance (task 11 mods). Every field is reset each tick.
    /** Vanilla option values used instead of the player's own while set; never written to options.txt. */
    public final Map<String, Object> options = new HashMap<String, Object>();
    /** Maximum frame rate, null = the game's own limit. */
    public Integer fpsLimit;
    /** Third-person camera distance (vanilla 4), null = vanilla; noCameraClip lets the camera pass through blocks. */
    public Float cameraDistance;
    public boolean noCameraClip;
    public boolean smoothCamera;
    public boolean hideFireOverlay, hideWaterOverlay, noEquipAnimation;
    /** Fire overlay moved down by this fraction of the screen (0 = vanilla). */
    public float fireOverlayOffset;
    /** Arm swing duration multiplier (1 = vanilla, lower = faster). */
    public float swingSpeed = 1f;
    /** Entity type ids ("minecraft:armor_stand") that are not rendered. */
    public final Set<String> hiddenEntities = new HashSet<String>();
    public Highlighter highlighter;
    public boolean nametagHealth, showOwnNametag, hideNpcNametags;
    /** Nametags beyond this distance are hidden (0 = vanilla). */
    public float nametagMaxDistance;
    public boolean showHitboxes;
    /** ARGB tint entities flash when hurt, null = vanilla red. */
    public Integer hitColor;
    public boolean itemPhysics;
    public float itemSpin = 1f, itemScale = 1f;
    public boolean itemBob = true, hideItemShadows;

    /**
     * Item Physics: how many 1px layers a flat item stack is piled from while lying on the ground, by stack size
     * (1-8: 1, 9-32: 2, 33-48: 3, 49-64: 4, more: 5).
     */
    public static int itemLayers(int stackSize) {
        return stackSize <= 8 ? 1 : stackSize <= 32 ? 2 : stackSize <= 48 ? 3 : stackSize <= 64 ? 4 : 5;
    }
    /** Chance (0-1) that a particle is kept; hiddenParticles are particle type ids that never spawn. */
    public float particleKeep = 1f;
    public final Set<String> hiddenParticles = new HashSet<String>();
    /** Extra crit / enchanted-hit particle bursts per hit, and "always show enchanted-hit particles". */
    public int extraCrits;
    public boolean alwaysMagicCrits;
    /** Rain/snow strength 0-1 while a weather is forced. */
    public float weatherIntensity = 1f;
    public boolean forceSprintInScreens;
    public boolean showChunkBorders;
    public boolean hideStars, hideSun, hideMoon;
    /** Block Overlay draws its own outline. */
    public boolean hideBlockOutline;

    /** Chat display filter (chat tabs). {@code name} changes whenever the filter does, so the chat can be rebuilt. */
    public interface ChatFilter { boolean show(String plainText); }
    public ChatFilter chatFilter;
    public String chatFilterName = "";
    /** Chat history length (vanilla 100). */
    public int chatHistory = 100;
    public boolean hideChat;
    /** Text replaced in chat, nametags and the tab list (streamer mode): real name -> shown name. */
    public final Map<String, String> nameReplacements = new HashMap<String, String>();
    public boolean hideVanillaToasts;
    /** Select this hotbar slot (0-8) this tick, null to leave it. */
    public Integer selectSlot;
    /** Container screens get a search box. */
    public boolean containerSearch;
    /** Container search: colour over non-matching slots and frame around matching ones. */
    public int searchDim = 0xC0101010, searchMatch = 0xA055FF55;
    /** Tooltip colours drawn instead of the vanilla sprites (null = vanilla). */
    public Integer tooltipBackground, tooltipBorder;
    /** Multipliers on the player's master and weather volume. */
    public float volumeScale = 1f, weatherVolumeScale = 1f;

    public void reset() {
        fovMultiplier = 1f;
        sensitivityMultiplier = 1f;
        gamma = null;
        forceSprint = forceSneak = false;
        freelook = forceThirdPerson = false;
        cameraYawOffset = cameraPitchOffset = 0f;
        timeOfDay = null;
        weather = Weather.VANILLA;
        hideHand = hideVanillaActionBar = hideVanillaCrosshair = hideVanillaHud = hideNametags = false;
        hideVanillaBossBar = hideVanillaTitle = hideVanillaScoreboard = hideVanillaEffects = hideVanillaHotbar = hideVanillaTabList = false;
        hideAllHud = false;
        streamerMode = hideNotifications = false;
        reducedMotion = highContrast = colorblind = false;
        uiScale = 1f;
        options.clear();
        fpsLimit = null;
        cameraDistance = null;
        noCameraClip = smoothCamera = false;
        hideFireOverlay = hideWaterOverlay = noEquipAnimation = false;
        fireOverlayOffset = 0f;
        swingSpeed = 1f;
        hiddenEntities.clear();
        highlighter = null;
        nametagHealth = showOwnNametag = hideNpcNametags = false;
        nametagMaxDistance = 0f;
        showHitboxes = false;
        hitColor = null;
        itemPhysics = false;
        itemSpin = itemScale = 1f;
        itemBob = true;
        hideItemShadows = false;
        particleKeep = 1f;
        hiddenParticles.clear();
        extraCrits = 0;
        alwaysMagicCrits = false;
        weatherIntensity = 1f;
        forceSprintInScreens = false;
        showChunkBorders = false;
        hideStars = hideSun = hideMoon = false;
        hideBlockOutline = false;
        chatFilter = null;
        chatFilterName = "";
        chatHistory = 100;
        hideChat = false;
        nameReplacements.clear();
        hideVanillaToasts = false;
        selectSlot = null;
        containerSearch = false;
        searchDim = 0xC0101010;
        searchMatch = 0xA055FF55;
        tooltipBackground = tooltipBorder = null;
        volumeScale = weatherVolumeScale = 1f;
    }

    /**
     * Applies {@link #nameReplacements} to plain text. Only whole names are replaced ("Steve" leaves "Steven" alone),
     * case-sensitively like Minecraft names are displayed.
     */
    public String replaceNames(String s) {
        if (s == null || nameReplacements.isEmpty()) return s;
        String out = s;
        for (Map.Entry<String, String> e : nameReplacements.entrySet()) {
            String from = e.getKey();
            if (from == null || from.isEmpty() || e.getValue() == null) continue;
            int i = out.indexOf(from);
            if (i < 0) continue;
            StringBuilder b = new StringBuilder();
            int last = 0;
            while (i >= 0) {
                int end = i + from.length();
                boolean startOk = i == 0 || !nameChar(out.charAt(i - 1));
                boolean endOk = end >= out.length() || !nameChar(out.charAt(end));
                if (startOk && endOk) {
                    b.append(out, last, i).append(e.getValue());
                    last = end;
                }
                i = out.indexOf(from, end);
            }
            out = b.append(out.substring(last)).toString();
        }
        return out;
    }

    private static boolean nameChar(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    /** Sets a vanilla option override unless another mod already did (first mod in registration order wins). */
    public void option(String key, Object value) { if (!options.containsKey(key)) options.put(key, value); }
}
