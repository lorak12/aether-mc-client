package gg.aether.mc;

import gg.aether.core.GameState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.SurvivalInventoryScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Box;

/**
 * {@link GameState} for legacy Minecraft (1.7.10 - 1.12.2). Covers what HUD and PvP mods read; the rarer queries keep
 * GameState's defaults. Everything tolerates being called outside a world.
 */
public final class McGameState implements GameState {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    private long entitiesTick = Long.MIN_VALUE, infoTick = Long.MIN_VALUE;
    private int entitiesRadius;
    private double infoRadius;
    private Map<String, Integer> entities = Collections.emptyMap();
    private List<EntityInfo> infos = Collections.emptyList();

    private ClientPlayerEntity p() { return mc.player; }
    private ClientWorld level() { return mc.world; }

    @Override public boolean inWorld() { return mc.player != null && mc.world != null; }
    @Override public int fps() { return MinecraftClient.getCurrentFps(); }

    @Override public int pingMs() {
        ClientPlayerEntity p = p();
        if (p == null || mc.getNetworkHandler() == null || mc.isInSingleplayer()) return -1;
        PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(p.getUuid());
        return e == null ? -1 : e.getLatency();
    }

    @Override public double x() { return p() == null ? 0 : p().x; }
    @Override public double y() { return p() == null ? 0 : p().y; }
    @Override public double z() { return p() == null ? 0 : p().z; }
    @Override public float yaw() { return p() == null ? 0 : p().yaw; }
    @Override public float pitch() { return p() == null ? 0 : p().pitch; }

    @Override public String biome() {
        if (p() == null || level() == null) return "";
        return LegacyCompat.biome(level(), p());
    }

    @Override public String serverAddress() {
        return mc.getCurrentServerEntry() == null || mc.isInSingleplayer() ? "" : mc.getCurrentServerEntry().address;
    }

    @Override public boolean isDown(Key k) {
        if (mc.options == null) return false;
        KeyBinding m;
        switch (k) {
            case FORWARD: m = mc.options.forwardKey; break;
            case BACK: m = mc.options.backKey; break;
            case LEFT: m = mc.options.leftKey; break;
            case RIGHT: m = mc.options.rightKey; break;
            case JUMP: m = mc.options.jumpKey; break;
            case SNEAK: m = mc.options.sneakKey; break;
            case SPRINT: m = mc.options.sprintKey; break;
            case ATTACK: m = mc.options.attackKey; break;
            case PLAYER_LIST: m = mc.options.playerListKey; break;
            default: m = mc.options.useKey; break;
        }
        return m.isPressed();
    }

    @Override public List<ArmorPiece> armor() {
        ClientPlayerEntity p = p();
        if (p == null) return Collections.emptyList();
        List<ArmorPiece> out = new ArrayList<ArmorPiece>(4);
        for (int i = 3; i >= 0; i--) {
            net.minecraft.item.ItemStack st = LegacyCompat.armor(p, i);
            out.add(st == null ? null : new ArmorPiece(McItems.id(st), st.getMaxDamage() - st.getDamage(), st.getMaxDamage()));
        }
        return out;
    }

    @Override public List<Effect> effects() {
        ClientPlayerEntity p = p();
        if (p == null) return Collections.emptyList();
        List<Effect> out = new ArrayList<Effect>();
        for (StatusEffectInstance e : p.getStatusEffectInstances()) {
            StatusEffect type = LegacyCompat.effect(e);
            String name = type == null ? "?" : net.minecraft.client.resource.language.I18n.translate(type.getTranslationKey());
            boolean good = type == null || !type.isNegative();
            out.add(new Effect(name, e.getAmplifier(), e.getDuration(), good, ""));
        }
        return out;
    }

    @Override public long usedMemoryBytes() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
    @Override public long maxMemoryBytes() { return Runtime.getRuntime().maxMemory(); }
    @Override public long nowMillis() { return System.currentTimeMillis(); }

    @Override public boolean screenOpen() { return mc.currentScreen != null; }
    @Override public boolean chatOpen() { return mc.currentScreen instanceof ChatScreen; }
    @Override public boolean debugOpen() { return mc.options != null && mc.options.debugEnabled; }
    @Override public boolean isDead() { return p() != null && p().getHealth() <= 0; }

    @Override public String dimension() {
        if (level() == null) return "overworld";
        int d = LegacyCompat.dimension(level());
        return d == -1 ? "the_nether" : d == 1 ? "the_end" : "overworld";
    }

    @Override public ItemStack heldItem() { return p() == null ? null : McItems.stack(LegacyCompat.mainHand(p())); }
    @Override public int selectedSlot() { return p() == null ? 0 : p().inventory.selectedSlot; }

    @Override public List<ItemStack> inventory() {
        ClientPlayerEntity p = p();
        if (p == null) return Collections.emptyList();
        List<ItemStack> out = new ArrayList<ItemStack>(36);
        for (int i = 0; i < 36; i++) out.add(McItems.stack(LegacyCompat.inventory(p, i)));
        return out;
    }

    @Override public Map<String, Integer> nearbyEntities(int radius) {
        ClientPlayerEntity p = p();
        if (p == null || level() == null) return Collections.emptyMap();
        long now = level().getLastUpdateTime();
        if (now == entitiesTick && radius == entitiesRadius) return entities;
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        double r2 = (double) radius * radius;
        for (Entity e : level().getEntitiesIn(p, p.getBoundingBox().expand(radius, radius, radius))) {
            if (!(e instanceof LivingEntity) || e.squaredDistanceTo(p) > r2) continue;
            String name = e.getName().asUnformattedString();
            Integer n = out.get(name);
            out.put(name, n == null ? 1 : n + 1);
        }
        entitiesTick = now;
        entitiesRadius = radius;
        return entities = out;
    }

    @Override public Target target() {
        BlockHitResult hit = mc.result;
        ClientPlayerEntity p = p();
        ClientWorld lv = level();
        if (hit == null || p == null || lv == null || hit.type == BlockHitResult.Type.MISS || hit.pos == null) return Target.NONE;
        double dist = p.getCameraPosVec(1f).distanceTo(hit.pos);
        if (hit.type == BlockHitResult.Type.ENTITY && hit.entity != null) {
            Entity e = hit.entity;
            Target t = new Target(Target.Kind.ENTITY, e.getName().asUnformattedString(), "minecraft:" + McItems.entityId(e), dist);
            t.x = (int) Math.floor(e.x);
            t.y = (int) Math.floor(e.y);
            t.z = (int) Math.floor(e.z);
            if (e instanceof LivingEntity) {
                t.health = ((LivingEntity) e).getHealth();
                t.maxHealth = ((LivingEntity) e).getMaxHealth();
            }
            return t;
        }
        BlockPos pos = hit.getBlockPos();
        if (pos == null) return Target.NONE;
        net.minecraft.block.Block b = lv.getBlockState(pos).getBlock();
        Target t = new Target(Target.Kind.BLOCK, b.getTranslatedName(), McItems.blockId(b), dist);
        t.x = pos.getX();
        t.y = pos.getY();
        t.z = pos.getZ();
        t.face = hit.direction == null ? "" : hit.direction.getName();
        t.hardness = LegacyCompat.hardness(lv, pos);
        BlockPos front = hit.direction == null ? pos : pos.offset(hit.direction);
        t.blockLight = lv.getLightAtPos(LightType.BLOCK, front);
        t.skyLight = lv.getLightAtPos(LightType.SKY, front);
        return t;
    }

    // ---- player -----------------------------------------------------------------------------------------------------

    @Override public float health() { return p() == null ? 20 : p().getHealth(); }
    @Override public float maxHealth() { return p() == null ? 20 : p().getMaxHealth(); }
    @Override public float absorption() { return p() == null ? 0 : p().getAbsorption(); }
    @Override public int food() { return p() == null ? 20 : p().getHungerManager().getFoodLevel(); }
    @Override public float saturation() { return p() == null ? 5 : p().getHungerManager().getSaturationLevel(); }
    @Override public int air() { return p() == null ? 300 : p().getAir(); }
    @Override public int xpLevel() { return p() == null ? 0 : p().experienceLevel; }
    @Override public float xpProgress() { return p() == null ? 0 : p().experienceProgress; }
    @Override public int armorValue() { return p() == null ? 0 : p().getArmorProtectionValue(); }

    @Override public double speed() {
        if (p() == null) return 0;
        double dx = p().x - p().prevX, dz = p().z - p().prevZ;
        return Math.sqrt(dx * dx + dz * dz) * 20.0;
    }

    @Override public double velocityY() { return p() == null ? 0 : (p().y - p().prevY) * 20.0; }
    @Override public boolean onGround() { return p() == null || p().onGround; }
    @Override public boolean sprinting() { return p() != null && p().isSprinting(); }
    @Override public boolean sneaking() { return p() != null && p().isSneaking(); }
    @Override public boolean flying() { return p() != null && p().abilities.flying; }
    @Override public boolean inWater() { return p() != null && p().isTouchingWater(); }

    @Override public String gameMode() {
        if (mc.interactionManager == null) return "survival";
        return LegacyCompat.gameMode(mc);
    }

    @Override public boolean hardcore() { return level() != null && level().getLevelProperties().isHardcore(); }
    @Override public String playerName() { return p() == null ? "" : p().getGameProfile().getName(); }
    @Override public boolean thirdPerson() { return mc.options != null && mc.options.perspective != 0; }
    @Override public int useTicks() { return p() == null ? 0 : LegacyCompat.useTicks(p()); }
    @Override public boolean heldIsBlock() { return p() != null && LegacyCompat.mainHand(p()) != null && LegacyCompat.mainHand(p()).getItem() instanceof net.minecraft.item.BlockItem; }

    // ---- world ------------------------------------------------------------------------------------------------------

    @Override public long dayTime() { return level() == null ? 6000 : level().getTimeOfDay(); }
    @Override public long gameTime() { return level() == null ? 0 : level().getLastUpdateTime(); }
    @Override public int minBuildY() { return 0; }
    @Override public int maxBuildY() { return 255; }
    @Override public boolean raining() { return level() != null && level().isRaining(); }
    @Override public boolean thundering() { return level() != null && level().isThundering(); }

    @Override public String worldName() {
        if (mc.getServer() != null) return mc.getServer().getLevelName();
        return serverAddress();
    }

    @Override public float serverTps() { return McEvents.tps(); }
    @Override public int renderDistance() { return mc.options == null ? 12 : mc.options.viewDistance; }

    @Override public int blockLight(int x, int y, int z) { return level() == null ? 0 : level().getLightAtPos(LightType.BLOCK, new BlockPos(x, y, z)); }
    @Override public int skyLight(int x, int y, int z) { return level() == null ? 15 : level().getLightAtPos(LightType.SKY, new BlockPos(x, y, z)); }

    @Override public String blockId(int x, int y, int z) {
        ClientWorld lv = level();
        if (lv == null) return "minecraft:air";
        return McItems.blockId(lv.getBlockState(new BlockPos(x, y, z)).getBlock());
    }

    @Override public int topY(int x, int z) {
        ClientWorld lv = level();
        if (lv == null) return Integer.MIN_VALUE;
        return lv.getHighestBlock(new BlockPos(x, 0, z)).getY() - 1;
    }

    @Override public int mapColor(int x, int z) {
        ClientWorld lv = level();
        if (lv == null) return -1;
        BlockPos top = lv.getHighestBlock(new BlockPos(x, 0, z)).down();
        if (top.getY() < 0) return 0;
        return LegacyCompat.mapColor(lv, top);
    }

    // ---- vanilla HUD state ------------------------------------------------------------------------------------------

    @Override public List<TabPlayer> tabPlayers() {
        if (mc.getNetworkHandler() == null) return Collections.emptyList();
        List<TabPlayer> out = new ArrayList<TabPlayer>();
        for (PlayerListEntry i : mc.getNetworkHandler().getPlayerList()) {
            String name = i.getProfile().getName();
            String display = i.getDisplayName() == null ? name : i.getDisplayName().asUnformattedString();
            out.add(new TabPlayer(name, display, i.getLatency(), LegacyCompat.gameMode(i),
                    i.getScoreboardTeam() == null ? "" : i.getScoreboardTeam().getName()));
        }
        return out;
    }

    // ---- entities ---------------------------------------------------------------------------------------------------

    @Override public List<EntityInfo> entities(double radius) {
        ClientPlayerEntity p = p();
        ClientWorld lv = level();
        if (p == null || lv == null) return Collections.emptyList();
        long now = lv.getLastUpdateTime();
        if (now == infoTick && radius == infoRadius) return infos;
        List<EntityInfo> out = new ArrayList<EntityInfo>();
        double r2 = radius * radius;
        for (Entity e : lv.getEntitiesIn(p, new Box(p.x - radius, p.y - radius, p.z - radius, p.x + radius, p.y + radius, p.z + radius))) {
            double d2 = e.squaredDistanceTo(p);
            if (d2 > r2) continue;
            boolean living = e instanceof LivingEntity;
            float hp = living ? ((LivingEntity) e).getHealth() : 0, max = living ? ((LivingEntity) e).getMaxHealth() : 0;
            EntityInfo info = new EntityInfo(e.getEntityId(), "minecraft:" + McItems.entityId(e), e.getName().asUnformattedString(), e.x, e.y, e.z, Math.sqrt(d2),
                    hp, max, e.height, living, e instanceof Monster, e instanceof PlayerEntity, e instanceof ItemEntity, false, e.isInvisible(), -1);
            info.width = e.width;
            out.add(info);
        }
        Collections.sort(out, new Comparator<EntityInfo>() {
            @Override public int compare(EntityInfo a, EntityInfo b) { return Double.compare(a.distance, b.distance); }
        });
        infoTick = now;
        infoRadius = radius;
        return infos = out;
    }

    // ---- screens ----------------------------------------------------------------------------------------------------

    @Override public String screenName() {
        Screen s = mc.currentScreen;
        if (s == null) return "";
        if (s instanceof ChatScreen) return "chat";
        if (s instanceof SurvivalInventoryScreen || s instanceof CreativeInventoryScreen) return "inventory";
        if (s instanceof HandledScreen) return "container";
        if (s instanceof GameMenuScreen) return "pause";
        return "other";
    }

    @Override public boolean containerOpen() { return mc.currentScreen instanceof HandledScreen; }
    @Override public boolean windowFocused() { return mc.focused; }
    @Override public int totalEntities() { return level() == null ? 0 : level().loadedEntities.size(); }

    /** "dark_forest" -> "Dark Forest". */
    static String pretty(String path) {
        StringBuilder b = new StringBuilder();
        for (String w : path.split("_")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
        }
        return b.toString();
    }
}
