package gg.aether.mc;

import gg.aether.core.Events;
import gg.aether.core.EventBus;
import gg.aether.core.Overrides;
import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Turns game state changes and mixin callbacks into core {@link Events}. Shared by every adapter; all methods run on
 * the client (render) thread.
 */
public final class McEvents {
    private McEvents() {}

    private static final double DAMAGE_RADIUS = 32;

    private static EventBus bus() { return Compat.mods().events(); }

    // Tick-derived state.
    private static ClientLevel lastLevel;
    private static boolean wasDead, wasOnGround = true;
    private static final Map<Integer, Float> health = new HashMap<Integer, Float>();
    private static int lastHitId = Integer.MIN_VALUE;
    private static long lastHitMs;

    // Server TPS from time packets: game-time advanced vs wall time between updates (servers send one per second).
    private static long tpsGameTime = Long.MIN_VALUE, tpsNanos;
    private static float tps = -1;

    public static float tps() { return tps; }

    /** End of each client tick. */
    public static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        tickChatFilter(mc);
        ClientLevel level = mc.level;
        LocalPlayer p = mc.player;
        if (level != lastLevel) {
            if (lastLevel != null) bus().post(new Events.WorldLeave(now));
            health.clear();
            tps = -1;
            tpsGameTime = Long.MIN_VALUE;
            lastLevel = level;
            if (level != null) {
                bus().post(new Events.WorldJoin(Compat.state().serverAddress(), now));
                McPayload.joined(mc);
            }
        }
        if (level == null || p == null) return;

        boolean dead = p.isDeadOrDying();
        if (dead && !wasDead) bus().post(new Events.Death(p.getX(), p.getY(), p.getZ(), level.dimension().identifier().getPath(), now));
        wasDead = dead;

//#if >=1.20
        boolean ground = p.onGround();
//#else
        boolean ground = p.isOnGround();
//#endif
        if (wasOnGround && !ground && p.getDeltaMovement().y > 0.2 && !p.getAbilities().flying && !p.isInWater()) bus().post(new Events.Jump(now));
        wasOnGround = ground;

        trackDamage(level, p, now);
    }

    /** Health changes of living entities near the player become EntityDamaged (and Kill) events. */
    private static void trackDamage(ClientLevel level, LocalPlayer p, long now) {
        Map<Integer, Float> seen = new HashMap<Integer, Float>();
        AABB box = p.getBoundingBox().inflate(DAMAGE_RADIUS);
        for (Entity e : level.getEntities(p, box)) {
            if (!(e instanceof LivingEntity)) continue;
            LivingEntity le = (LivingEntity) e;
            float hp = le.getHealth();
            seen.put(e.getId(), hp);
            Float before = health.get(e.getId());
            if (before == null || hp >= before) continue;
            boolean byPlayer = e.getId() == lastHitId && now - lastHitMs < 1000;
            boolean killed = hp <= 0;
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            bus().post(new Events.EntityDamaged(e.getId(), type, before - hp, e.getX(), e.getY() + e.getBbHeight(), e.getZ(), byPlayer, killed, now));
            if (killed && e.getId() == lastHitId && now - lastHitMs < 3000) {
                bus().post(new Events.Kill(e.getName().getString(), type, e instanceof Player, now));
                lastHitId = Integer.MIN_VALUE;
            }
        }
        // Keep entities that just died (health 0) out of the next comparison; forget ones that left.
        for (Iterator<Map.Entry<Integer, Float>> it = seen.entrySet().iterator(); it.hasNext();) if (it.next().getValue() <= 0) it.remove();
        health.clear();
        health.putAll(seen);
    }

    // ---- mixin callbacks --------------------------------------------------------------------------------------------

    /** The player attacked an entity (MultiPlayerGameMode.attack). */
    public static void attacked(Entity target) {
        lastHitId = target.getId();
        lastHitMs = System.currentTimeMillis();
    }

    public static void timeUpdate(long gameTime) {
        long ns = System.nanoTime();
        if (tpsGameTime != Long.MIN_VALUE) {
            double secs = (ns - tpsNanos) / 1e9;
            long ticks = gameTime - tpsGameTime;
            if (secs > 0.5 && ticks >= 0) {
                float sample = (float) Math.min(20.0, ticks / secs);
                tps = tps < 0 ? sample : tps * 0.7f + sample * 0.3f;
            }
        }
        tpsGameTime = gameTime;
        tpsNanos = ns;
    }

    public static void chat(Component message, boolean system) {
        if (message == null) return;
        bus().post(new Events.ChatMessage(message.getString(), system, System.currentTimeMillis()));
    }

    // ---- chat pipeline (Chat mod, streamer names, tabs) ---------------------------------------------------------

    /** What the chat mixin should add instead of the original line. */
    public static final class ChatResult {
        public final Component message;
        /** Indicator bar colour (ARGB), null to keep the original tag. */
        public final Integer highlight;
        /** Remove the newest existing line first (it is the same message, now counted "(xN)"). */
        public final boolean replacePrevious;
        ChatResult(Component message, Integer highlight, boolean replacePrevious) {
            this.message = message;
            this.highlight = highlight;
            this.replacePrevious = replacePrevious;
        }
    }

    private static String lastChat;
    private static int lastChatCount;
    /** Rebuilt lines -> their original plain text, so chat tabs match without timestamps / counters in the way. */
    private static final Map<Component, String> originals = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Component, String>());
    private static String chatFilterName = "";

    /**
     * Posts ChatMessage and ChatReceive for a line about to be added. Returns null to drop it, otherwise the line to
     * add (the same instance when nothing changed).
     */
    public static ChatResult receive(Component msg, boolean system) {
        if (msg == null) return new ChatResult(null, null, false);
        String text = msg.getString();
        long now = System.currentTimeMillis();
        bus().post(new Events.ChatMessage(text, system, now));
        Events.ChatReceive e = new Events.ChatReceive(text, system, now);
        bus().post(e);
        if (e.cancel) return null;
        boolean repeat = e.stack && text.equals(lastChat);
        lastChatCount = repeat ? lastChatCount + 1 : 1;
        lastChat = text;
        Overrides fx = Compat.mods().overrides();
        Component body = e.replaceText != null ? Component.literal(e.replaceText) : msg;
        if (!fx.nameReplacements.isEmpty()) body = replaceNames(body, fx);
        if (body == msg && e.prefix == null && !repeat) return new ChatResult(msg, e.highlight, false);
        MutableComponent out = Component.empty();
        if (e.prefix != null) out.append(Component.literal(e.prefix + " ").withStyle(ChatFormatting.GRAY));
        out.append(body);
        if (repeat) out.append(Component.literal(" (x" + lastChatCount + ")").withStyle(ChatFormatting.GRAY));
        originals.put(out, text);
        return new ChatResult(out, e.highlight, repeat);
    }

    /** The same component with player names replaced, keeping every style; the original if nothing matched. */
    public static Component replaceNames(Component c, Overrides fx) {
        if (c == null || fx.nameReplacements.isEmpty()) return c;
        String plain = c.getString();
        if (fx.replaceNames(plain).equals(plain)) return c;
        final MutableComponent out = Component.empty();
        c.visit((style, part) -> {
            out.append(Component.literal(fx.replaceNames(part)).withStyle(style));
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    /** Chat tabs: should this line be displayed? */
    public static boolean chatVisible(Component content) {
        Overrides.ChatFilter f = Compat.mods().overrides().chatFilter;
        if (f == null || content == null) return true;
        String original = originals.get(content);
        return f.show(original != null ? original : content.getString());
    }

    /** Chat history length for the chat queues. */
    public static int chatHistory(int vanilla) { return Math.max(vanilla, Compat.mods().overrides().chatHistory); }

    public static boolean hideChat() { return Compat.mods().overrides().hideChat; }

    /** Rebuilds the visible chat when the tab changes. */
    private static void tickChatFilter(Minecraft mc) {
        String name = Compat.mods().overrides().chatFilterName;
        if (name == null) name = "";
        if (name.equals(chatFilterName)) return;
        chatFilterName = name;
        net.minecraft.client.gui.components.ChatComponent chat = Compat.chat(mc);
        if (chat != null) chat.rescaleChat();
    }

    public static void screenshot(File file) {
        if (file != null) bus().post(new Events.Screenshot(file.getAbsolutePath(), System.currentTimeMillis()));
    }

    public static void blockBroken(String blockId, BlockPos pos) {
        bus().post(new Events.Block(blockId, pos.getX(), pos.getY(), pos.getZ(), false, System.currentTimeMillis()));
    }

    /** {@code held} is the stack before use; only block items count as placements. */
    public static void blockPlaced(ItemStack held, BlockPos pos) {
        if (held == null || !(held.getItem() instanceof BlockItem)) return;
        String id = BuiltInRegistries.BLOCK.getKey(((BlockItem) held.getItem()).getBlock()).toString();
        bus().post(new Events.Block(id, pos.getX(), pos.getY(), pos.getZ(), true, System.currentTimeMillis()));
    }

    /** ClientboundTakeItemEntityPacket for the local player (called before the item entity is removed). */
    public static void pickup(Minecraft mc, int itemEntityId, int collectorId, int amount) {
        if (mc.level == null || mc.player == null || collectorId != mc.player.getId()) return;
        Entity e = mc.level.getEntity(itemEntityId);
        if (!(e instanceof ItemEntity)) return;
        String id = BuiltInRegistries.ITEM.getKey(((ItemEntity) e).getItem().getItem()).toString();
        bus().post(new Events.Pickup(id, amount, System.currentTimeMillis()));
    }

    public static void advancement(Component title) {
        if (title != null) bus().post(new Events.Advancement(title.getString(), System.currentTimeMillis()));
    }
}
