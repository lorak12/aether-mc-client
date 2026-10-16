package gg.kite.mods;

import gg.kite.core.Mod;
import gg.kite.core.ModManager;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class BuiltinMods {
    private BuiltinMods() {}

    public static void registerAll(ModManager mm) {
        mm.register(new SimpleHudMods.Fps());
        mm.register(new SimpleHudMods.Cps());
        mm.register(new SimpleHudMods.Ping());
        mm.register(new SimpleHudMods.Coordinates());
        mm.register(new SimpleHudMods.Direction());
        mm.register(new SimpleHudMods.Clock());
        mm.register(new SimpleHudMods.Memory());
        mm.register(new SimpleHudMods.Potions());
        mm.register(new Keystrokes());
        mm.register(new ArmorStatus());

        mm.register(new Crosshair());
        mm.register(new VisualMods.Zoom());
        mm.register(new VisualMods.Fullbright());
        mm.register(new VisualMods.Freelook());
        mm.register(new VisualMods.TimeChanger());
        mm.register(new VisualMods.WeatherChanger());
        mm.register(new UtilityMods.ToggleSprint());
        mm.register(new UtilityMods.StreamerMode());
        mm.register(new UtilityMods.DurabilityWarnings());
        mm.register(new UtilityMods.ActionBar());
        mm.register(new PvpMods.ComboCounter());
        mm.register(new PvpMods.ReachDisplay());
        mm.register(new InfoMods.ItemCounter());
        mm.register(new InfoMods.ServerIp());
        mm.register(new InfoMods.TargetInfo());
        mm.register(new InfoMods.InventoryHud());

        mm.register(new VanillaHudMods.BossBar());
        mm.register(new VanillaHudMods.Titles());
        mm.register(new VanillaHudMods.Scoreboard());
        mm.register(new StatusMods.Food());
        mm.register(new StatusMods.ItemDurability());
        mm.register(new StatusMods.Playtime());
        mm.register(new PvpMods.HealthDisplay());
        mm.register(new PvpMods.TntTimer());

        mm.register(new RenderMods.Fov());
        mm.register(new RenderMods.Camera());
        mm.register(new RenderMods.Animations());
        mm.register(new RenderMods.Particles());
        mm.register(new RenderMods.HitColor());
        mm.register(new RenderMods.ItemPhysics());
        mm.register(new RenderMods.Nametags());
        mm.register(new RenderMods.Hitboxes());
        mm.register(new RenderMods.InventoryBlur());

        WorldMods.Waypoints waypoints = new WorldMods.Waypoints();
        WorldMods.Minimap minimap = new WorldMods.Minimap();
        WorldMods.waypointsMod = waypoints;
        WorldMods.minimapMod = minimap;
        mm.register(minimap);
        mm.register(new WorldMods.WorldMap());
        mm.register(waypoints);
        mm.register(new WorldMods.ChunkBorders());
        mm.register(new WorldMods.LightOverlay());
        mm.register(new WorldMods.BlockOverlay());

        ChatMods.Friends friends = new ChatMods.Friends();
        ChatMods.friends = friends;
        mm.register(new ChatMods.Chat());
        mm.register(new ChatMods.ChatNotifications());
        mm.register(new ChatMods.TabList());
        mm.register(friends);
        mm.register(new CaptureMods.ScreenshotManager());
        mm.register(new CaptureMods.CrashReporter());

        mm.register(new ClientMods.NotificationSettings());
        decorate(mm);
    }

    /** Mods made for particular servers: the menu's SERVER tab. */
    static final Set<String> SERVER = new HashSet<String>(Arrays.asList("serverip"));

    /** Menu icon ({@link gg.kite.core.ui.Icons} name) per mod id; anything missing falls back to its category's icon. */
    static final Map<String, String> ICONS = new HashMap<String, String>();

    static {
        String[] pairs = {
                "fps", "gauge", "cps", "mouse", "ping", "signal", "coords", "pin", "direction", "compass", "clock", "clock", "memory", "chip",
                "potions", "flask", "keystrokes", "keys", "armor", "shield", "crosshair", "crosshair", "zoom", "zoom", "fullbright", "sun",
                "freelook", "eye", "time", "moon", "weather", "rain", "togglesprint", "forward", "streamer", "broadcast", "durabilitywarn", "alert",
                "actionbar", "actionbar", "combo", "zap", "reach", "reach", "itemcounter", "layers", "serverip", "server", "targetinfo", "scan",
                "inventoryhud", "grid", "bossbar", "skull", "titles", "title", "scoreboard", "scoreboard", "food", "apple", "durability", "pickaxe",
                "playtime", "hourglass", "healthdisplay", "heart", "tnttimer", "bomb", "fov", "fov", "camera", "camera", "animations", "wand",
                "particles", "sparkles", "hitcolor", "droplet", "itemphysics", "drop", "nametags", "tag", "hitboxes", "wirebox", "blur", "blur",
                "minimap", "minimap", "worldmap", "map", "waypoints", "flag", "chunkborders", "chunks", "lightoverlay", "bulb", "blockoverlay", "blocksel",
                "chat", "chat", "chatnotify", "chatalert", "tablist", "list", "friends", "users", "screenshots", "image", "crashreporter", "bug",
                "notifications", "bell" };
        for (int i = 0; i + 1 < pairs.length; i += 2) ICONS.put(pairs[i], pairs[i + 1]);
    }

    /** Icons and the SERVER tab flag for the built-in mods. */
    static void decorate(ModManager mm) {
        for (Mod m : mm.all()) {
            String icon = ICONS.get(m.id);
            if (icon != null) m.icon(icon);
            if (SERVER.contains(m.id)) m.serverSpecific(true);
        }
    }
}
