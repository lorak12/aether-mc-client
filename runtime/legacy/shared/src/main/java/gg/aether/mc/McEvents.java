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
import net.minecraft.util.math.Box;

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
            if (level != null) bus().post(new Events.WorldJoin(server, now));
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

    /** A chat line about to be shown. Returns false to drop it. */
    public static boolean chat(String text, boolean system) {
        long now = System.currentTimeMillis();
        bus().post(new Events.ChatMessage(text, system, now));
        Events.ChatReceive e = new Events.ChatReceive(text, system, now);
        bus().post(e);
        return !e.cancel;
    }

    public static void actionBar(String text) { bus().post(new Events.ActionBar(text, System.currentTimeMillis())); }

    public static void screenshot(File file) {
        if (file != null) bus().post(new Events.Screenshot(file.getAbsolutePath(), System.currentTimeMillis()));
    }
}
