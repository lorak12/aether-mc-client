package gg.aether.mc;

import gg.aether.core.EventBus;
import gg.aether.core.Events;
import gg.aether.core.ModManager;
import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;

/** Turns legacy game state changes and mixin callbacks into core {@link Events}. Client thread only. */
public final class McEvents {
    private McEvents() {}

    private static final double DAMAGE_RADIUS = 32;

    private static ModManager mods;
    private static EventBus bus() { return mods.events(); }

    private static ClientWorld lastLevel;
    private static boolean wasDead, wasOnGround = true;
    private static final Map<Integer, Float> health = new HashMap<Integer, Float>();
    private static int lastHitId = Integer.MIN_VALUE;
    private static long lastHitMs;
    private static long tpsGameTime = Long.MIN_VALUE, tpsNanos;
    private static float tps = -1;

    public static void init(ModManager mm) { mods = mm; }

    public static float tps() { return tps; }

    /** End of each client tick. {@code server} is the current server address ("" in singleplayer). */
    public static void tick(MinecraftClient mc, String server) {
        long now = System.currentTimeMillis();
        ClientWorld level = mc.world;
        ClientPlayerEntity p = mc.player;
        if (level != lastLevel) {
            if (lastLevel != null) bus().post(new Events.WorldLeave(now));
            health.clear();
            tps = -1;
            tpsGameTime = Long.MIN_VALUE;
            lastLevel = level;
            if (level != null) {
                bus().post(new Events.WorldJoin(server, now));
                if (!server.isEmpty()) LegacyPayload.joined(mc);
            }
        }
        if (level == null || p == null) return;

        boolean dead = p.getHealth() <= 0;
        if (dead && !wasDead) {
            int d = LegacyCompat.dimension(level);
            bus().post(new Events.Death(p.x, p.y, p.z, d == -1 ? "the_nether" : d == 1 ? "the_end" : "overworld", now));
        }
        wasDead = dead;

        boolean ground = p.onGround;
        if (wasOnGround && !ground && p.velocityY > 0.2 && !p.abilities.flying && !p.isTouchingWater()) bus().post(new Events.Jump(now));
        wasOnGround = ground;

        trackDamage(level, p, now);
        timeUpdate(level.getLastUpdateTime());
        tickChatFilter(mc);
    }

    private static void trackDamage(ClientWorld level, ClientPlayerEntity p, long now) {
        Map<Integer, Float> seen = new HashMap<Integer, Float>();
        for (Entity e : LegacyCompat.entitiesNear(level, p, DAMAGE_RADIUS)) {
            if (!(e instanceof LivingEntity)) continue;
            float hp = ((LivingEntity) e).getHealth();
            seen.put(e.getEntityId(), hp);
            Float before = health.get(e.getEntityId());
            if (before == null || hp >= before) continue;
            boolean byPlayer = e.getEntityId() == lastHitId && now - lastHitMs < 1000;
            boolean killed = hp <= 0;
            String type = "minecraft:" + McItems.entityId(e);
            bus().post(new Events.EntityDamaged(e.getEntityId(), type, before - hp, e.x, e.y + e.height, e.z, byPlayer, killed, now));
            if (killed && e.getEntityId() == lastHitId && now - lastHitMs < 3000) {
                bus().post(new Events.Kill(e.getName().asUnformattedString(), type, e instanceof PlayerEntity, now));
                lastHitId = Integer.MIN_VALUE;
            }
        }
        for (Iterator<Map.Entry<Integer, Float>> it = seen.entrySet().iterator(); it.hasNext();) if (it.next().getValue() <= 0) it.remove();
        health.clear();
        health.putAll(seen);
    }

    /** The player attacked an entity. */
    public static void attacked(Entity target, double reach, boolean crit) {
        lastHitId = target.getEntityId();
        lastHitMs = System.currentTimeMillis();
        bus().post(new Events.Attack(reach, crit, lastHitMs));
    }

    /** Server TPS from how fast world time advances (legacy clients get a time packet every second). */
    private static void timeUpdate(long gameTime) {
        long ns = System.nanoTime();
        if (gameTime == tpsGameTime) return;
        if (tpsGameTime != Long.MIN_VALUE && ns - tpsNanos > 900_000_000L) {
            double secs = (ns - tpsNanos) / 1e9;
            long ticks = gameTime - tpsGameTime;
            if (ticks >= 0) {
                float sample = (float) Math.min(20.0, ticks / secs);
                tps = tps < 0 ? sample : tps * 0.7f + sample * 0.3f;
            }
            tpsGameTime = gameTime;
            tpsNanos = ns;
        } else if (tpsGameTime == Long.MIN_VALUE) {
            tpsGameTime = gameTime;
            tpsNanos = ns;
        }
    }

    // ---- chat pipeline (Chat mod, streamer names, tabs) ---------------------------------------------------------

    /** What the chat mixin should add instead of the original line. */
    public static final class ChatResult {
        public final Text message;
        /** Chat line id to add it under (0 = none); a repeat removes the previous line's id first. */
        public final int id;
        /** Remove the newest stacking line first (it is the same message, now counted "(xN)"). */
        public final int removeId;

        ChatResult(Text message, int id, int removeId) {
            this.message = message;
            this.id = id;
            this.removeId = removeId;
        }
    }

    private static String lastChat;
    private static int lastChatCount, lastChatId;
    private static final int CHAT_ID_BASE = 0xAE70000;
    private static int chatIds;
    /** Rebuilt lines -> their original plain text, so chat tabs match without timestamps / counters in the way. */
    private static final Map<Text, String> ORIGINALS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Text, String>());
    private static String chatFilterName = "";

    /**
     * Posts ChatMessage and ChatReceive for a line about to be added. Returns null to drop it, otherwise what to add
     * (the same instance and id 0 when nothing changed).
     */
    public static ChatResult receive(Text msg, boolean system) {
        long now = System.currentTimeMillis();
        String text = msg.asUnformattedString();
        bus().post(new Events.ChatMessage(text, system, now));
        Events.ChatReceive e = new Events.ChatReceive(text, system, now);
        bus().post(e);
        if (e.cancel) return null;
        boolean repeat = e.stack && text.equals(lastChat);
        lastChatCount = repeat ? lastChatCount + 1 : 1;
        lastChat = text;
        gg.aether.core.Overrides fx = gg.aether.client.AetherClient.fx();
        Text body = e.replaceText != null ? new net.minecraft.text.LiteralText(e.replaceText) : msg;
        if (!fx.nameReplacements.isEmpty()) body = replaceNames(body, fx);
        int removeId = repeat ? lastChatId : 0;
        // Stacking lines carry an id so the next repeat can take them away again.
        int id = e.stack ? CHAT_ID_BASE + (++chatIds & 0xFFFF) : 0;
        lastChatId = id;
        if (body == msg && e.prefix == null && !repeat && id == 0) return new ChatResult(msg, 0, 0);
        Text out = new net.minecraft.text.LiteralText(e.prefix != null ? "\u00a77" + e.prefix + " \u00a7r" : "");
        out.append(body);
        if (repeat) out.append(new net.minecraft.text.LiteralText("\u00a77 (x" + lastChatCount + ")"));
        ORIGINALS.put(out, text);
        return new ChatResult(out, id, removeId);
    }

    /** The same text with player names replaced, keeping its colours; the original if nothing matched. */
    public static Text replaceNames(Text c, gg.aether.core.Overrides fx) {
        if (c == null || fx.nameReplacements.isEmpty()) return c;
        String formatted = c.asFormattedString();
        String replaced = replaceFormatted(formatted, fx);
        return replaced.equals(formatted) ? c : new net.minecraft.text.LiteralText(replaced);
    }

    /** {@link gg.aether.core.Overrides#replaceNames} on text with colour codes: each run between codes is replaced alone. */
    public static String replaceFormatted(String s, gg.aether.core.Overrides fx) {
        if (s == null || fx.nameReplacements.isEmpty()) return s;
        StringBuilder out = new StringBuilder();
        int run = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\u00a7' && i + 1 < s.length()) {
                out.append(fx.replaceNames(s.substring(run, i))).append(s, i, i + 2);
                i++;
                run = i + 1;
            }
        }
        return out.append(fx.replaceNames(s.substring(run))).toString();
    }

    /** Chat tabs: should this line be displayed? */
    public static boolean chatVisible(Text content) {
        gg.aether.core.Overrides.ChatFilter f = gg.aether.client.AetherClient.fx().chatFilter;
        if (f == null || content == null) return true;
        String original = ORIGINALS.get(content);
        return f.show(original != null ? original : content.asUnformattedString());
    }

    /** Chat history length for the chat queues. */
    public static int chatHistory(int vanilla) { return Math.max(vanilla, gg.aether.client.AetherClient.fx().chatHistory); }

    public static boolean hideChat() { return gg.aether.client.AetherClient.fx().hideChat; }

    /** Rebuilds the visible chat when the tab changes. */
    private static void tickChatFilter(MinecraftClient mc) {
        String name = gg.aether.client.AetherClient.fx().chatFilterName;
        if (name == null) name = "";
        if (name.equals(chatFilterName)) return;
        chatFilterName = name;
        if (mc.inGameHud != null) mc.inGameHud.getChatHud().reset();
    }

    public static void actionBar(String text) { bus().post(new Events.ActionBar(text, System.currentTimeMillis())); }

    public static void screenshot(File file) {
        if (file != null) bus().post(new Events.Screenshot(file.getAbsolutePath(), System.currentTimeMillis()));
    }
}
