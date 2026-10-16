package gg.kite.core;

/** Events adapters post onto the {@link EventBus}. */
public final class Events {
    private Events() {}

    /** Mouse button press. button: 0 = left, 1 = right, 2 = middle. */
    public static final class Click {
        public final int button;
        public final long timeMs;
        public Click(int button, long timeMs) { this.button = button; this.timeMs = timeMs; }
    }

    /** The player hit an entity. {@code distance} is eye-to-target in blocks. */
    public static final class Attack {
        public final double distance;
        public final boolean critical;
        public final long timeMs;
        public Attack(double distance, boolean critical, long timeMs) { this.distance = distance; this.critical = critical; this.timeMs = timeMs; }
    }

    /** The player took damage. */
    public static final class Hurt {
        public final float amount;
        public final long timeMs;
        public Hurt(float amount, long timeMs) { this.amount = amount; this.timeMs = timeMs; }
    }

    /** Raw mouse movement in pixels, posted while a mod may want it (freelook). */
    public static final class MouseMove {
        public final float dx, dy;
        public MouseMove(float dx, float dy) { this.dx = dx; this.dy = dy; }
    }

    /** Any key press/release while no screen is open (GLFW code), e.g. for AFK detection. */
    public static final class KeyInput {
        public final int code;
        public final boolean down;
        public KeyInput(int code, boolean down) { this.code = code; this.down = down; }
    }

    /** Scroll wheel: positive = up. */
    public static final class Scroll {
        public final float delta;
        public Scroll(float delta) { this.delta = delta; }
    }

    /** Vanilla action-bar message (the text above the hotbar). */
    public static final class ActionBar {
        public final String text;
        public final long timeMs;
        public ActionBar(String text, long timeMs) { this.text = text; this.timeMs = timeMs; }
    }

    /** A line added to the chat (plain text, formatting stripped). {@code system} for server/game messages. */
    public static final class ChatMessage {
        public final String text;
        public final boolean system;
        public final long timeMs;
        public ChatMessage(String text, boolean system, long timeMs) { this.text = text; this.system = system; this.timeMs = timeMs; }
    }

    /**
     * A chat line is about to be added. Handlers may cancel it or change how it is shown; the adapter applies the
     * result. {@code text} is the plain text (formatting stripped).
     */
    public static final class ChatReceive {
        public final String text;
        public final boolean system;
        public final long timeMs;
        public boolean cancel;
        /** Shown before the message (e.g. a timestamp), grey. */
        public String prefix;
        /** ARGB bar/colour marking the line (mentions), null for none. */
        public Integer highlight;
        /** Replace the whole message with this plain text (loses formatting); null to keep it. */
        public String replaceText;
        /** Merge with the previous identical line as "(x2)". */
        public boolean stack;
        public ChatReceive(String text, boolean system, long timeMs) { this.text = text; this.system = system; this.timeMs = timeMs; }
    }

    /** An item tooltip is being built; handlers append lines (and their colours). */
    public static final class Tooltip {
        public final String itemId, name, rarity;
        public final int count, maxStack, durability, maxDurability;
        public final java.util.List<String> tags, components, enchantments;
        /** {nutrition, saturation} or null. */
        public final float[] food;
        public final boolean advanced;
        public final java.util.List<String> add = new java.util.ArrayList<String>();
        public final java.util.List<Integer> colors = new java.util.ArrayList<Integer>();
        public Tooltip(String itemId, String name, String rarity, int count, int maxStack, int durability, int maxDurability,
                java.util.List<String> tags, java.util.List<String> components, java.util.List<String> enchantments, float[] food, boolean advanced) {
            this.itemId = itemId;
            this.name = name;
            this.rarity = rarity;
            this.count = count;
            this.maxStack = maxStack;
            this.durability = durability;
            this.maxDurability = maxDurability;
            this.tags = tags;
            this.components = components;
            this.enchantments = enchantments;
            this.food = food;
            this.advanced = advanced;
        }
        public void line(String text, int argb) {
            add.add(text);
            colors.add(argb);
        }
    }

    /** A screenshot was written. {@code path} is the absolute file path. */
    public static final class Screenshot {
        public final String path;
        public final long timeMs;
        public Screenshot(String path, long timeMs) { this.path = path; this.timeMs = timeMs; }
    }

    /** The player broke ({@code placed = false}) or placed a block. */
    public static final class Block {
        public final String blockId;
        public final int x, y, z;
        public final boolean placed;
        public final long timeMs;
        public Block(String blockId, int x, int y, int z, boolean placed, long timeMs) {
            this.blockId = blockId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.placed = placed;
            this.timeMs = timeMs;
        }
    }

    /** A living entity near the player lost health. {@code byPlayer}: the player hit it within the last second. */
    public static final class EntityDamaged {
        public final int entityId;
        public final String type;
        public final float amount;
        public final double x, y, z;
        public final boolean byPlayer, killed;
        public final long timeMs;
        public EntityDamaged(int entityId, String type, float amount, double x, double y, double z, boolean byPlayer, boolean killed, long timeMs) {
            this.entityId = entityId;
            this.type = type;
            this.amount = amount;
            this.x = x;
            this.y = y;
            this.z = z;
            this.byPlayer = byPlayer;
            this.killed = killed;
            this.timeMs = timeMs;
        }
    }

    /** An entity the player hit recently died. */
    public static final class Kill {
        public final String name, type;
        public final boolean player;
        public final long timeMs;
        public Kill(String name, String type, boolean player, long timeMs) { this.name = name; this.type = type; this.player = player; this.timeMs = timeMs; }
    }

    /** The player died. */
    public static final class Death {
        public final double x, y, z;
        public final String dimension;
        public final long timeMs;
        public Death(double x, double y, double z, String dimension, long timeMs) { this.x = x; this.y = y; this.z = z; this.dimension = dimension; this.timeMs = timeMs; }
    }

    /** The player picked up items. */
    public static final class Pickup {
        public final String itemId;
        public final int count;
        public final long timeMs;
        public Pickup(String itemId, int count, long timeMs) { this.itemId = itemId; this.count = count; this.timeMs = timeMs; }
    }

    /** An advancement toast was shown. */
    public static final class Advancement {
        public final String title;
        public final long timeMs;
        public Advancement(String title, long timeMs) { this.title = title; this.timeMs = timeMs; }
    }

    public static final class Jump {
        public final long timeMs;
        public Jump(long timeMs) { this.timeMs = timeMs; }
    }

    /** A message from the server on the {@code kite:client} plugin channel (UTF-8 JSON). */
    public static final class ServerPayload {
        public final String channel, json;
        public final long timeMs;
        public ServerPayload(String channel, String json, long timeMs) { this.channel = channel; this.json = json; this.timeMs = timeMs; }
    }

    /** Joined a world or server ({@code address} "" for singleplayer). */
    public static final class WorldJoin {
        public final String address;
        public final long timeMs;
        public WorldJoin(String address, long timeMs) { this.address = address; this.timeMs = timeMs; }
    }

    public static final class WorldLeave {
        public final long timeMs;
        public WorldLeave(long timeMs) { this.timeMs = timeMs; }
    }
}
