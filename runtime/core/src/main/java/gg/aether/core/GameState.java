package gg.aether.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Read-only view of the running game, implemented by each version adapter. Mods only see this. */
public interface GameState {
    enum Key { FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, SPRINT, ATTACK, USE, PLAYER_LIST }

    final class ArmorPiece {
        public final String itemId;
        public final int durability, maxDurability;
        public ArmorPiece(String itemId, int durability, int maxDurability) {
            this.itemId = itemId;
            this.durability = durability;
            this.maxDurability = maxDurability;
        }
    }

    final class Effect {
        public final String name;
        public final int amplifier, ticksLeft;
        /** False for harmful effects (poison, slowness...). */
        public final boolean beneficial;
        /** Registry id ("minecraft:speed"), "" when unknown. */
        public final String id;
        public Effect(String name, int amplifier, int ticksLeft) { this(name, amplifier, ticksLeft, true, ""); }
        public Effect(String name, int amplifier, int ticksLeft, boolean beneficial, String id) {
            this.name = name;
            this.amplifier = amplifier;
            this.ticksLeft = ticksLeft;
            this.beneficial = beneficial;
            this.id = id;
        }
    }

    final class ItemStack {
        public final String itemId;
        public final int count, durability, maxDurability;
        /** Display name as shown in-game (custom names included); null when unknown. */
        public final String name;
        /** "Sharpness V", "Unbreaking III"... */
        public final List<String> enchantments;
        public ItemStack(String itemId, int count, int durability, int maxDurability) {
            this(itemId, count, durability, maxDurability, null, Collections.<String>emptyList());
        }
        public ItemStack(String itemId, int count, int durability, int maxDurability, String name, List<String> enchantments) {
            this.itemId = itemId;
            this.count = count;
            this.durability = durability;
            this.maxDurability = maxDurability;
            this.name = name;
            this.enchantments = enchantments == null ? Collections.<String>emptyList() : enchantments;
        }
    }

    /** What the crosshair is over. */
    final class Target {
        public enum Kind { NONE, BLOCK, ENTITY }
        public static final Target NONE = new Target(Kind.NONE, "", "", 0);
        public final Kind kind;
        public final String name, id;
        public final double distance;
        /** Block position, or the entity's block position. */
        public int x, y, z;
        /** Blocks: hit face ("up", "north"...), hardness (-1 unbreakable), fastest tool ("pickaxe", "" = any). */
        public String face = "", tool = "";
        public float hardness;
        /** Blocks: the held item can harvest it (it drops). */
        public boolean harvestable = true;
        /** Entities: current / max health (0 when not living). */
        public float health, maxHealth;
        /** Blocks: fluid in the block ("water", "lava", "" none), light in front of the hit face. */
        public String fluid = "";
        public int blockLight, skyLight;
        /**
         * Blocks: the outline shape vanilla draws (not the collision shape), as world-space boxes
         * {minX, minY, minZ, maxX, maxY, maxZ}. Empty = unknown, treat as the full cube (see {@link #shapeBoxes()}).
         */
        public List<double[]> shape = Collections.emptyList();
        /** Blocks: the shape's visible edges {x1, y1, z1, x2, y2, z2}, the lines vanilla's outline draws. Empty = unknown. */
        public List<double[]> edges = Collections.emptyList();
        /** Where the crosshair ray meets the block or entity (world space); NaN when unknown. */
        public double hitX = Double.NaN, hitY = Double.NaN, hitZ = Double.NaN;
        public Target(Kind kind, String name, String id, double distance) {
            this.kind = kind;
            this.name = name;
            this.id = id;
            this.distance = distance;
        }
        /** The namespace of the id: "minecraft", or the mod that adds it. */
        public String namespace() {
            int c = id.indexOf(':');
            return c < 0 ? "minecraft" : id.substring(0, c);
        }
        /** {@link #shape}, or the full cube at (x, y, z) when the shape is unknown. */
        public List<double[]> shapeBoxes() {
            return shape.isEmpty() ? Collections.singletonList(new double[] { x, y, z, x + 1, y + 1, z + 1 }) : shape;
        }
    }

    /** A vanilla boss bar. {@code color} is the bar colour name ("PINK", "RED"...). */
    final class BossBar {
        public final String name, color;
        public final float progress;
        public BossBar(String name, float progress, String color) { this.name = name; this.progress = progress; this.color = color; }
    }

    /** The sidebar objective: title plus lines top to bottom (score text as shown, may be ""). */
    final class Sidebar {
        public final String title;
        public final List<String> names, scores;
        public Sidebar(String title, List<String> names, List<String> scores) { this.title = title; this.names = names; this.scores = scores; }
    }

    /** The title/subtitle currently on screen. {@code alpha} 0-1 follows vanilla's fade in/out. */
    final class Title {
        public final String title, subtitle;
        public final float alpha;
        public Title(String title, String subtitle, float alpha) { this.title = title; this.subtitle = subtitle; this.alpha = alpha; }
    }

    final class TabPlayer {
        public final String name, displayName, gameMode, team;
        public final int ping;
        public TabPlayer(String name, String displayName, int ping, String gameMode, String team) {
            this.name = name;
            this.displayName = displayName;
            this.ping = ping;
            this.gameMode = gameMode;
            this.team = team;
        }
    }

    /** An entity near the player. {@code fuse} is ticks left for primed TNT or a swelling creeper, else -1. */
    final class EntityInfo {
        public final int id;
        public final String type, name;
        public final double x, y, z, distance;
        public final float health, maxHealth, height;
        /** Hitbox width (0 when unknown). */
        public float width;
        public final boolean living, hostile, player, item, projectile, invisible;
        public final int fuse;
        public EntityInfo(int id, String type, String name, double x, double y, double z, double distance, float health, float maxHealth,
                float height, boolean living, boolean hostile, boolean player, boolean item, boolean projectile, boolean invisible, int fuse) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.distance = distance;
            this.health = health;
            this.maxHealth = maxHealth;
            this.height = height;
            this.living = living;
            this.hostile = hostile;
            this.player = player;
            this.item = item;
            this.projectile = projectile;
            this.invisible = invisible;
            this.fuse = fuse;
        }
    }

    boolean inWorld();
    int fps();
    /** -1 when unknown (singleplayer / not yet measured). */
    int pingMs();
    double x();
    double y();
    double z();
    float yaw();
    String biome();
    String serverAddress();
    boolean isDown(Key k);
    /** Helmet, chest, legs, boots (null entries for empty slots). */
    List<ArmorPiece> armor();
    List<Effect> effects();
    long usedMemoryBytes();
    long maxMemoryBytes();
    long nowMillis();

    // Optional state. Adapters override what they can; the defaults keep older adapters and test fakes working.

    /** Any GUI screen (inventory, menu, chat) is open. */
    default boolean screenOpen() { return false; }
    /** The chat input is open (counts as a screen too). */
    default boolean chatOpen() { return false; }
    /** The F3 debug overlay is showing. */
    default boolean debugOpen() { return false; }
    default boolean isDead() { return false; }
    default String dimension() { return "overworld"; }
    default ItemStack heldItem() { return null; }
    /** 36 slots: 0-8 hotbar, 9-35 main inventory; null for empty. */
    default List<ItemStack> inventory() { return Collections.emptyList(); }
    /** Entity display name -> count within {@code radius} blocks of the player. */
    default Map<String, Integer> nearbyEntities(int radius) { return Collections.emptyMap(); }
    default Target target() { return Target.NONE; }

    // ---- player -------------------------------------------------------------------------------------------------
    default float pitch() { return 0; }
    default float health() { return 20; }
    default float maxHealth() { return 20; }
    default float absorption() { return 0; }
    default int food() { return 20; }
    default float saturation() { return 5; }
    default float exhaustion() { return 0; }
    default int air() { return 300; }
    default int maxAir() { return 300; }
    default int xpLevel() { return 0; }
    /** Progress to the next level, 0-1. */
    default float xpProgress() { return 0; }
    /** Armor points (0-20+). */
    default int armorValue() { return 0; }
    /** Horizontal speed in blocks per second. */
    default double speed() { return 0; }
    default double velocityY() { return 0; }
    default boolean onGround() { return true; }
    default boolean sprinting() { return false; }
    default boolean sneaking() { return false; }
    default boolean flying() { return false; }
    default boolean inWater() { return false; }
    /** "survival", "creative", "adventure", "spectator". */
    default String gameMode() { return "survival"; }
    default boolean hardcore() { return false; }
    default ItemStack offhandItem() { return null; }
    /** Selected hotbar slot, 0-8. */
    default int selectedSlot() { return 0; }
    default String playerName() { return ""; }
    /** Hunger and saturation the held item restores when eaten; null if it is not food. */
    default float[] heldFood() { return null; }
    /** Advancements {completed, total} (recipes excluded); null when unknown. */
    default int[] advancements() { return null; }
    default boolean thirdPerson() { return false; }
    /** Mining speed of hotbar slot {@code slot} against the targeted block (1 = hand speed, 0 when no block). */
    default float hotbarDestroySpeed(int slot) { return 0; }
    /** Attack damage of hotbar slot {@code slot} (1 = fist). */
    default float hotbarAttackDamage(int slot) { return 1; }
    /** The held item places a block. */
    default boolean heldIsBlock() { return false; }

    // ---- world --------------------------------------------------------------------------------------------------
    /** Time of day in ticks including the day count; the day number is dayTime / 24000. */
    default long dayTime() { return 6000; }
    default long gameTime() { return 0; }
    default int minBuildY() { return -64; }
    /** Highest Y a block can be placed at (inclusive). */
    default int maxBuildY() { return 319; }
    default boolean raining() { return false; }
    default boolean thundering() { return false; }
    /** Singleplayer world name, or the server address. */
    default String worldName() { return ""; }
    /** Server software brand ("vanilla", "Paper"...), "" if unknown. */
    default String serverBrand() { return ""; }
    /** From the server list entry: MOTD, version name and "online/max" players; "" when unknown. */
    default String serverMotd() { return ""; }
    default String serverVersion() { return ""; }
    default String serverPlayers() { return ""; }
    /** Estimated server ticks per second from time updates, -1 until measured. */
    default float serverTps() { return -1; }
    default int renderDistance() { return 12; }
    default int blockLight(int x, int y, int z) { return 0; }
    default int skyLight(int x, int y, int z) { return 15; }
    /** Registry id of the block at a position ("minecraft:air" when unloaded). */
    default String blockId(int x, int y, int z) { return "minecraft:air"; }
    /** True if a mob could spawn standing here: sturdy top face below, nothing to collide with at the feet. */
    default boolean spawnable(int x, int y, int z) { return false; }
    /** Map colour (0xRRGGBB, vanilla map palette) of the top block, or -1 if unloaded. */
    default int mapColor(int x, int z) { return -1; }
    /** Y of the highest non-air block, or Integer.MIN_VALUE if unloaded. */
    default int topY(int x, int z) { return Integer.MIN_VALUE; }
    /** Map colour of the floor under the first air gap below {@code fromY} (cave maps); -1 if none / unloaded. */
    default int caveColor(int x, int z, int fromY) { return -1; }
    /** Slime chunk? {@code null} when the seed is unknown (multiplayer). */
    default Boolean slimeChunk(int chunkX, int chunkZ) { return null; }
    /** Structures containing the player ("Village"...); empty when none or unknown (multiplayer). */
    default List<String> structures() { return Collections.emptyList(); }

    // ---- vanilla HUD state --------------------------------------------------------------------------------------
    default List<BossBar> bossBars() { return Collections.emptyList(); }
    default Sidebar sidebar() { return null; }
    default Title title() { return null; }
    default List<TabPlayer> tabPlayers() { return Collections.emptyList(); }
    default String tabHeader() { return ""; }
    default String tabFooter() { return ""; }

    // ---- entities -----------------------------------------------------------------------------------------------
    /** Entities within {@code radius} blocks (the player excluded), nearest first. */
    default List<EntityInfo> entities(double radius) { return Collections.emptyList(); }

    // ---- screens, window, diagnostics ---------------------------------------------------------------------------
    /** Simple class name of the open screen ("InventoryScreen"...), "" when none. */
    default String screenName() { return ""; }
    /** A screen with item slots (inventory, chest...) is open. */
    default boolean containerOpen() { return false; }
    default boolean windowFocused() { return true; }
    default int renderedChunks() { return 0; }
    default int totalChunks() { return 0; }
    default int renderedEntities() { return 0; }
    default int totalEntities() { return 0; }
    default String gpuName() { return ""; }
    /** Packets per second (the game's own average), -1 when not connected. */
    default float packetsIn() { return -1; }
    default float packetsOut() { return -1; }
    /** Refresh rate of the monitor the window is on (Hz). */
    default int monitorRefreshRate() { return 60; }
    /** Ticks the player has been using (drawing, eating) the held item, 0 when not using. */
    default int useTicks() { return 0; }
    default double frameTimeMs() { return fps() <= 0 ? 0 : 1000.0 / fps(); }
}
