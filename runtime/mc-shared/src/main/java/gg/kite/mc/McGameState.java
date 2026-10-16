package gg.kite.mc;

import gg.kite.core.GameState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
//#if >=1.19.3
import net.minecraft.core.registries.Registries;
//#endif
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
//#if >=1.19
import net.minecraft.world.level.levelgen.structure.Structure;
//#endif
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
//#if >=1.20.3
import net.minecraft.world.scores.PlayerScoreEntry;
//#endif
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * {@link GameState} read straight from the client. Shared source: compiled by every Fabric adapter against its own
 * Minecraft version, with the few API differences behind {@link Compat}. Everything tolerates being called outside a world.
 */
public final class McGameState implements GameState {
    private static final EquipmentSlot[] ARMOR = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
    private static final Pattern FRACTION = Pattern.compile("(\\d+)/(\\d+)");

    private final Minecraft mc = Minecraft.getInstance();

    // Entity scans are the expensive queries: cache per game tick + radius.
    private long entitiesTick = Long.MIN_VALUE, infoTick = Long.MIN_VALUE, structTick = Long.MIN_VALUE;
    private int entitiesRadius;
    private double infoRadius;
    private Map<String, Integer> entities = Collections.emptyMap();
    private List<EntityInfo> infos = Collections.emptyList();
    private List<String> structs = Collections.emptyList();

    private LocalPlayer p() { return mc.player; }
    private ClientLevel level() { return mc.level; }

    @Override public boolean inWorld() { return mc.player != null && mc.level != null; }
//#if >=1.19.3
    @Override public int fps() { return mc.getFps(); }
//#else
    @Override public int fps() {
        try { return Integer.parseInt(mc.fpsString.split(" ")[0]); }
        catch (RuntimeException e) { return 0; }
    }
//#endif

    @Override public int pingMs() {
        LocalPlayer p = p();
        ClientPacketListener c = mc.getConnection();
        if (p == null || c == null || mc.isLocalServer()) return -1;
        PlayerInfo info = c.getPlayerInfo(p.getUUID());
        return info == null ? -1 : info.getLatency();
    }

    @Override public double x() { return p() == null ? 0 : p().getX(); }
    @Override public double y() { return p() == null ? 0 : p().getY(); }
    @Override public double z() { return p() == null ? 0 : p().getZ(); }
    @Override public float yaw() { return p() == null ? 0 : p().getYRot(); }
    @Override public float pitch() { return p() == null ? 0 : p().getXRot(); }

    @Override public String biome() {
        if (p() == null || level() == null) return "";
//#if >=1.18
        return level().getBiome(p().blockPosition()).unwrapKey().map(k -> pretty(k.identifier().getPath())).orElse("");
//#else
        net.minecraft.world.level.biome.Biome b = level().getBiome(p().blockPosition());
        net.minecraft.resources.Identifier key = mc.getConnection() == null ? null : mc.getConnection().registryAccess().registryOrThrow(net.minecraft.core.Registry.BIOME_REGISTRY).getKey(b);
        return key == null ? "" : pretty(key.getPath());
//#endif
    }

    @Override public String serverAddress() {
        ServerData sd = mc.getCurrentServer();
        return sd == null || mc.isLocalServer() ? "" : sd.ip;
    }

    @Override public boolean isDown(Key k) {
        if (mc.options == null) return false;
        KeyMapping m;
        switch (k) {
            case FORWARD: m = mc.options.keyUp; break;
            case BACK: m = mc.options.keyDown; break;
            case LEFT: m = mc.options.keyLeft; break;
            case RIGHT: m = mc.options.keyRight; break;
            case JUMP: m = mc.options.keyJump; break;
            case SNEAK: m = mc.options.keyShift; break;
            case SPRINT: m = mc.options.keySprint; break;
            case ATTACK: m = mc.options.keyAttack; break;
            default: m = mc.options.keyUse; break;
        }
        return m.isDown();
    }

    @Override public List<ArmorPiece> armor() {
        LocalPlayer p = p();
        if (p == null) return Collections.emptyList();
        List<ArmorPiece> out = new ArrayList<ArmorPiece>(4);
        for (EquipmentSlot s : ARMOR) {
            net.minecraft.world.item.ItemStack st = p.getItemBySlot(s);
            out.add(st.isEmpty() ? null : new ArmorPiece(id(st), st.getMaxDamage() - st.getDamageValue(), st.getMaxDamage()));
        }
        return out;
    }

    @Override public List<Effect> effects() {
        LocalPlayer p = p();
        if (p == null) return Collections.emptyList();
        List<Effect> out = new ArrayList<Effect>();
        for (MobEffectInstance e : p.getActiveEffects()) {
//#if >=1.19.4
            int ticks = e.isInfiniteDuration() ? 20 * 5999 : e.getDuration();
//#else
            int ticks = e.getDuration();
//#endif
//#if >=1.20.5
            String id = e.getEffect().unwrapKey().map(k -> k.identifier().toString()).orElse("");
            out.add(new Effect(e.getEffect().value().getDisplayName().getString(), e.getAmplifier(), ticks, e.getEffect().value().isBeneficial(), id));
//#else
            net.minecraft.resources.Identifier key = BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect());
            out.add(new Effect(e.getEffect().getDisplayName().getString(), e.getAmplifier(), ticks, e.getEffect().isBeneficial(), key == null ? "" : key.toString()));
//#endif
        }
        return out;
    }

    @Override public long usedMemoryBytes() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
    @Override public long maxMemoryBytes() { return Runtime.getRuntime().maxMemory(); }
    @Override public long nowMillis() { return System.currentTimeMillis(); }

    @Override public boolean screenOpen() { return Compat.screen(mc) != null; }
    @Override public boolean chatOpen() { return Compat.screen(mc) instanceof ChatScreen; }
    @Override public boolean debugOpen() { return mc.getDebugOverlay().showDebugScreen(); }
    @Override public boolean isDead() { return p() != null && p().isDeadOrDying(); }

    @Override public String dimension() {
        return level() == null ? "overworld" : level().dimension().identifier().getPath();
    }

    @Override public ItemStack heldItem() { return p() == null ? null : stack(p().getMainHandItem()); }
    @Override public ItemStack offhandItem() { return p() == null ? null : stack(p().getOffhandItem()); }
    @Override public int selectedSlot() { return p() == null ? 0 : Compat.selectedSlot(p()); }

    @Override public List<ItemStack> inventory() {
        LocalPlayer p = p();
        if (p == null) return Collections.emptyList();
        List<ItemStack> out = new ArrayList<ItemStack>(36);
        for (int i = 0; i < 36; i++) out.add(stack(p.getInventory().getItem(i)));
        return out;
    }

    @Override public Map<String, Integer> nearbyEntities(int radius) {
        LocalPlayer p = p();
        if (p == null || level() == null) return Collections.emptyMap();
        long now = level().getGameTime();
        if (now == entitiesTick && radius == entitiesRadius) return entities;
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        double r2 = (double) radius * radius;
        for (Entity e : level().getEntities(p, p.getBoundingBox().inflate(radius))) {
            if (!(e instanceof LivingEntity) || e.distanceToSqr(p) > r2) continue;
            String name = e.getType().getDescription().getString();
            Integer n = out.get(name);
            out.put(name, n == null ? 1 : n + 1);
        }
        entitiesTick = now;
        entitiesRadius = radius;
        return entities = out;
    }

    @Override public Target target() {
        HitResult hit = mc.hitResult;
        LocalPlayer p = p();
        ClientLevel lv = level();
        if (hit == null || p == null || lv == null || hit.getType() == HitResult.Type.MISS) return Target.NONE;
        Vec3 eye = p.getEyePosition();
        double dist = eye.distanceTo(hit.getLocation());
        if (hit instanceof EntityHitResult) {
            Entity e = ((EntityHitResult) hit).getEntity();
            Target t = new Target(Target.Kind.ENTITY, e.getName().getString(), BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), dist);
            BlockPos bp = e.blockPosition();
            t.x = bp.getX();
            t.y = bp.getY();
            t.z = bp.getZ();
            if (e instanceof LivingEntity) {
                t.health = ((LivingEntity) e).getHealth();
                t.maxHealth = ((LivingEntity) e).getMaxHealth();
            }
            return t;
        }
        if (hit instanceof BlockHitResult) {
            BlockHitResult bh = (BlockHitResult) hit;
            BlockPos pos = bh.getBlockPos();
            BlockState st = lv.getBlockState(pos);
            Target t = new Target(Target.Kind.BLOCK, st.getBlock().getName().getString(), BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString(), dist);
            t.x = pos.getX();
            t.y = pos.getY();
            t.z = pos.getZ();
            t.face = bh.getDirection().getSerializedName();
            t.hardness = st.getDestroySpeed(lv, pos);
            t.tool = tool(st);
            t.harvestable = !st.requiresCorrectToolForDrops() || p.getMainHandItem().isCorrectToolForDrops(st);
            FluidState fs = st.getFluidState();
            if (!fs.isEmpty()) t.fluid = BuiltInRegistries.FLUID.getKey(fs.getType()).getPath().replace("flowing_", "");
            BlockPos front = pos.relative(bh.getDirection());
            t.blockLight = lv.getBrightness(LightLayer.BLOCK, front);
            t.skyLight = lv.getBrightness(LightLayer.SKY, front);
            Vec3 at = hit.getLocation();
            t.hitX = at.x;
            t.hitY = at.y;
            t.hitZ = at.z;
            outline(t, st, lv, pos);
            return t;
        }
        return Target.NONE;
    }

    /** The block's outline shape and edges, from the same shape and context vanilla's block outline uses. */
    private void outline(Target t, BlockState st, ClientLevel lv, BlockPos pos) {
        Entity cam = mc.getCameraEntity();
        VoxelShape shape = st.getShape(lv, pos, cam == null ? CollisionContext.empty() : CollisionContext.of(cam));
        if (shape.isEmpty()) return;
        final double ox = pos.getX(), oy = pos.getY(), oz = pos.getZ();
        List<double[]> boxes = new ArrayList<double[]>();
        for (AABB b : shape.toAabbs()) boxes.add(new double[] { ox + b.minX, oy + b.minY, oz + b.minZ, ox + b.maxX, oy + b.maxY, oz + b.maxZ });
        final List<double[]> edges = new ArrayList<double[]>();
        shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> edges.add(new double[] { ox + x1, oy + y1, oz + z1, ox + x2, oy + y2, oz + z2 }));
        t.shape = boxes;
        t.edges = edges;
    }

    private static String tool(BlockState st) {
//#if >=1.17
        if (st.is(BlockTags.MINEABLE_WITH_PICKAXE)) return "pickaxe";
        if (st.is(BlockTags.MINEABLE_WITH_AXE)) return "axe";
        if (st.is(BlockTags.MINEABLE_WITH_SHOVEL)) return "shovel";
        if (st.is(BlockTags.MINEABLE_WITH_HOE)) return "hoe";
//#else
        // No mineable tags before 1.17: go by the block's material.
        net.minecraft.world.level.material.Material m = st.getMaterial();
        if (m == net.minecraft.world.level.material.Material.STONE || m == net.minecraft.world.level.material.Material.METAL
                || m == net.minecraft.world.level.material.Material.HEAVY_METAL || m == net.minecraft.world.level.material.Material.PISTON
                || m == net.minecraft.world.level.material.Material.SHULKER_SHELL) return "pickaxe";
        if (m == net.minecraft.world.level.material.Material.WOOD || m == net.minecraft.world.level.material.Material.NETHER_WOOD
                || m == net.minecraft.world.level.material.Material.BAMBOO) return "axe";
        if (m == net.minecraft.world.level.material.Material.DIRT || m == net.minecraft.world.level.material.Material.CLAY
                || m == net.minecraft.world.level.material.Material.SAND || m == net.minecraft.world.level.material.Material.SNOW
                || m == net.minecraft.world.level.material.Material.TOP_SNOW) return "shovel";
        if (m == net.minecraft.world.level.material.Material.LEAVES || m == net.minecraft.world.level.material.Material.SPONGE) return "hoe";
//#endif
//#if >=1.20
        if (st.is(BlockTags.SWORD_EFFICIENT)) return "sword";
//#endif
        return "";
    }

    // ---- player -----------------------------------------------------------------------------------------------------

    @Override public float health() { return p() == null ? 20 : p().getHealth(); }
    @Override public float maxHealth() { return p() == null ? 20 : p().getMaxHealth(); }
    @Override public float absorption() { return p() == null ? 0 : p().getAbsorptionAmount(); }
    @Override public int food() { return p() == null ? 20 : p().getFoodData().getFoodLevel(); }
    @Override public float saturation() { return p() == null ? 5 : p().getFoodData().getSaturationLevel(); }
    @Override public float exhaustion() { return p() == null ? 0 : Compat.exhaustion(p().getFoodData()); }
    @Override public int air() { return p() == null ? 300 : p().getAirSupply(); }
    @Override public int maxAir() { return p() == null ? 300 : p().getMaxAirSupply(); }
    @Override public int xpLevel() { return p() == null ? 0 : p().experienceLevel; }
    @Override public float xpProgress() { return p() == null ? 0 : p().experienceProgress; }
    @Override public int armorValue() { return p() == null ? 0 : p().getArmorValue(); }

    @Override public double speed() {
        if (p() == null) return 0;
        double dx = p().getX() - p().xo, dz = p().getZ() - p().zo;
        return Math.sqrt(dx * dx + dz * dz) * 20.0;
    }

    @Override public double velocityY() { return p() == null ? 0 : (p().getY() - p().yo) * 20.0; }
//#if >=1.20
    @Override public boolean onGround() { return p() == null || p().onGround(); }
//#else
    @Override public boolean onGround() { return p() == null || p().isOnGround(); }
//#endif
    @Override public boolean sprinting() { return p() != null && p().isSprinting(); }
    @Override public boolean sneaking() { return p() != null && p().isShiftKeyDown(); }
    @Override public boolean flying() { return p() != null && p().getAbilities().flying; }
    @Override public boolean inWater() { return p() != null && p().isInWater(); }

    @Override public String gameMode() {
        return mc.gameMode == null ? "survival" : mc.gameMode.getPlayerMode().getName();
    }

    @Override public float[] heldFood() {
        if (p() == null) return null;
        return ItemCompat.food(p().getMainHandItem());
    }

    @Override public float hotbarDestroySpeed(int slot) {
        LocalPlayer p = p();
        ClientLevel lv = level();
        if (p == null || lv == null || slot < 0 || slot > 8 || !(mc.hitResult instanceof BlockHitResult) || mc.hitResult.getType() == HitResult.Type.MISS) return 0;
        BlockState st = lv.getBlockState(((BlockHitResult) mc.hitResult).getBlockPos());
        net.minecraft.world.item.ItemStack it = p.getInventory().getItem(slot);
        float speed = it.getDestroySpeed(st);
        // A tool that cannot harvest the block (stone pickaxe on diamond ore) loses the drop: treat it as slow.
        if (st.requiresCorrectToolForDrops() && !it.isCorrectToolForDrops(st)) speed = Math.min(speed, 0.5f);
        return speed;
    }

    @Override public float hotbarAttackDamage(int slot) {
        LocalPlayer p = p();
        if (p == null || slot < 0 || slot > 8) return 1;
        return ItemCompat.attackDamage(p.getInventory().getItem(slot));
    }

    @Override public int[] advancements() {
        Map<net.minecraft.advancements.AdvancementHolder, net.minecraft.advancements.AdvancementProgress> prog = Compat.advancementProgress(mc);
        if (prog == null || prog.isEmpty()) return null;
        int done = 0, total = 0;
        for (Map.Entry<net.minecraft.advancements.AdvancementHolder, net.minecraft.advancements.AdvancementProgress> e : prog.entrySet()) {
            if (!e.getKey().value().display().isPresent()) continue; // recipes and hidden technical ones
            total++;
            if (e.getValue().isDone()) done++;
        }
        return new int[] { done, total };
    }

    @Override public String serverMotd() {
        ServerData sd = mc.getCurrentServer();
        return sd == null || sd.motd == null ? "" : sd.motd.getString();
    }

    @Override public String serverVersion() {
        ServerData sd = mc.getCurrentServer();
        return sd == null || sd.version == null ? "" : sd.version.getString();
    }

    @Override public String serverPlayers() {
        ServerData sd = mc.getCurrentServer();
//#if >=1.19.4
        if (sd == null || sd.players == null) return "";
        return sd.players.online() + "/" + sd.players.max();
//#else
        return sd == null || sd.status == null ? "" : sd.status.getString();
//#endif
    }

    @Override public boolean heldIsBlock() { return p() != null && p().getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem; }

    @Override public int monitorRefreshRate() { return Compat.refreshRate(mc); }
    @Override public int useTicks() { return p() == null || !p().isUsingItem() ? 0 : p().getTicksUsingItem(); }

    @Override public boolean hardcore() { return level() != null && level().getLevelData().isHardcore(); }
    @Override public String playerName() { return p() == null ? "" : p().getName().getString(); }
    @Override public boolean thirdPerson() { return mc.options != null && !mc.options.getCameraType().isFirstPerson(); }

    // ---- world ------------------------------------------------------------------------------------------------------

    @Override public long dayTime() { return level() == null ? 6000 : Compat.dayTime(level()); }
    @Override public long gameTime() { return level() == null ? 0 : level().getGameTime(); }
    @Override public int minBuildY() { return level() == null ? -64 : Compat.minY(level()); }
    @Override public int maxBuildY() { return level() == null ? 319 : level().getMaxY(); }
    @Override public boolean raining() { return level() != null && level().isRaining(); }
    @Override public boolean thundering() { return level() != null && level().isThundering(); }

    @Override public String worldName() {
        MinecraftServer sv = mc.getSingleplayerServer();
        if (sv != null) return sv.getWorldData().getLevelName();
        return serverAddress();
    }

    @Override public String serverBrand() {
        ClientPacketListener c = mc.getConnection();
        String b = c == null ? null : c.serverBrand();
        return b == null ? "" : b;
    }

    @Override public float serverTps() { return McEvents.tps(); }
//#if >=1.18
    @Override public int renderDistance() { return mc.options == null ? 12 : mc.options.getEffectiveRenderDistance(); }
//#else
    @Override public int renderDistance() { return mc.options == null ? 12 : mc.options.renderDistance; }
//#endif

    @Override public int blockLight(int x, int y, int z) { return level() == null ? 0 : level().getBrightness(LightLayer.BLOCK, new BlockPos(x, y, z)); }
    @Override public int skyLight(int x, int y, int z) { return level() == null ? 15 : level().getBrightness(LightLayer.SKY, new BlockPos(x, y, z)); }

    @Override public String blockId(int x, int y, int z) {
        ClientLevel lv = level();
        if (lv == null || !lv.hasChunk(x >> 4, z >> 4)) return "minecraft:air";
        return BuiltInRegistries.BLOCK.getKey(lv.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
    }

    @Override public boolean spawnable(int x, int y, int z) {
        ClientLevel lv = level();
        if (lv == null || !lv.hasChunk(x >> 4, z >> 4)) return false;
        BlockPos pos = new BlockPos(x, y, z), below = pos.below();
        BlockState at = lv.getBlockState(pos), floor = lv.getBlockState(below);
        if (!at.getCollisionShape(lv, pos).isEmpty() || !at.getFluidState().isEmpty() || at.isSignalSource()) return false;
        return floor.isValidSpawn(lv, below, Compat.spawnProbe());
    }

    @Override public int mapColor(int x, int z) {
        ClientLevel lv = level();
        if (lv == null || !lv.hasChunk(x >> 4, z >> 4)) return -1;
        int y = lv.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (y < Compat.minY(lv)) return 0;
        BlockPos pos = new BlockPos(x, y, z);
        return lv.getBlockState(pos).getMapColor(lv, pos).col;
    }

    @Override public int topY(int x, int z) {
        ClientLevel lv = level();
        if (lv == null || !lv.hasChunk(x >> 4, z >> 4)) return Integer.MIN_VALUE;
        return lv.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
    }

    @Override public int caveColor(int x, int z, int fromY) {
        ClientLevel lv = level();
        if (lv == null || !lv.hasChunk(x >> 4, z >> 4)) return -1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, fromY, z);
        boolean air = false;
        for (int y = fromY; y > Math.max(Compat.minY(lv), fromY - 64); y--) {
            pos.setY(y);
            BlockState st = lv.getBlockState(pos);
            if (st.isAir()) air = true;
            else if (air) return st.getMapColor(lv, pos).col;
        }
        return -1;
    }

    @Override public Boolean slimeChunk(int chunkX, int chunkZ) {
        MinecraftServer sv = mc.getSingleplayerServer();
        if (sv == null) return null;
        long seed = sv.overworld().getSeed();
        return WorldgenRandom.seedSlimeChunk(chunkX, chunkZ, seed, 987234911L).nextInt(10) == 0;
    }

    @Override public List<String> structures() {
        MinecraftServer sv = mc.getSingleplayerServer();
        LocalPlayer p = p();
        if (sv == null || p == null || level() == null) return Collections.emptyList();
        long now = level().getGameTime();
        if (now - structTick < 20) return structs; // the server level is another thread's: ask once a second
        structTick = now;
        List<String> out = new ArrayList<String>();
        try {
            ServerLevel sl = sv.getLevel(level().dimension());
            if (sl != null) {
//#if >=1.19
                for (Structure s : sl.structureManager().getAllStructuresAt(p.blockPosition()).keySet()) {
//#elif >=1.18
                for (net.minecraft.world.level.levelgen.feature.ConfiguredStructureFeature<?, ?> s : sl.structureFeatureManager().getAllStructuresAt(p.blockPosition()).keySet()) {
//#else
                for (net.minecraft.world.level.levelgen.feature.StructureFeature<?> s : net.minecraft.core.Registry.STRUCTURE_FEATURE) {
                    if (!sl.structureFeatureManager().getStructureAt(p.blockPosition(), true, s).isValid()) continue;
//#endif
//#if >=1.19.3
                    net.minecraft.resources.Identifier key = sl.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(s);
//#elif >=1.19
                    net.minecraft.resources.Identifier key = sl.registryAccess().registryOrThrow(net.minecraft.core.Registry.STRUCTURE_REGISTRY).getKey(s);
//#elif >=1.18
                    net.minecraft.resources.Identifier key = sl.registryAccess().registryOrThrow(net.minecraft.core.Registry.CONFIGURED_STRUCTURE_FEATURE_REGISTRY).getKey(s);
//#else
                    net.minecraft.resources.Identifier key = net.minecraft.core.Registry.STRUCTURE_FEATURE.getKey(s);
//#endif
                    if (key != null) out.add(pretty(key.getPath()));
                }
            }
        } catch (RuntimeException ignored) {
            // concurrent chunk changes on the server thread; keep the last answer
            return structs;
        }
        return structs = out;
    }

    // ---- vanilla HUD state ------------------------------------------------------------------------------------------

    @Override public List<BossBar> bossBars() {
        Collection<LerpingBossEvent> events = Compat.bossEvents(mc);
        if (events == null || events.isEmpty()) return Collections.emptyList();
        List<BossBar> out = new ArrayList<BossBar>();
        for (LerpingBossEvent e : events) out.add(new BossBar(e.getName().getString(), e.getProgress(), e.getColor().name()));
        return out;
    }

    @Override public Sidebar sidebar() {
        ClientLevel lv = level();
        LocalPlayer p = p();
        if (lv == null || p == null) return null;
        Scoreboard sb = lv.getScoreboard();
        Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (obj == null) return null;
        List<String> names = new ArrayList<String>(), scores = new ArrayList<String>();
//#if >=1.20.3
        List<PlayerScoreEntry> entries = new ArrayList<PlayerScoreEntry>();
        for (PlayerScoreEntry e : sb.listPlayerScores(obj)) if (!e.isHidden()) entries.add(e);
        Collections.sort(entries, new Comparator<PlayerScoreEntry>() {
            @Override public int compare(PlayerScoreEntry a, PlayerScoreEntry b) {
                int c = Integer.compare(b.value(), a.value());
                return c != 0 ? c : a.owner().compareToIgnoreCase(b.owner());
            }
        });
        for (int i = 0; i < entries.size() && i < 15; i++) {
            PlayerScoreEntry e = entries.get(i);
            names.add(PlayerTeam.formatNameForTeam(sb.getPlayersTeam(e.owner()), e.ownerName()).getString());
            scores.add(e.formatValue(obj.numberFormatOrDefault(net.minecraft.network.chat.numbers.StyledFormat.SIDEBAR_DEFAULT)).getString());
        }
//#else
        // Before 1.20.3: Score objects; owners starting with '#' are hidden, as in vanilla's sidebar.
        List<net.minecraft.world.scores.Score> entries = new ArrayList<net.minecraft.world.scores.Score>();
        for (net.minecraft.world.scores.Score e : sb.getPlayerScores(obj)) if (e.getOwner() != null && !e.getOwner().startsWith("#")) entries.add(e);
        Collections.sort(entries, new Comparator<net.minecraft.world.scores.Score>() {
            @Override public int compare(net.minecraft.world.scores.Score a, net.minecraft.world.scores.Score b) {
                int c = Integer.compare(b.getScore(), a.getScore());
                return c != 0 ? c : a.getOwner().compareToIgnoreCase(b.getOwner());
            }
        });
        for (int i = 0; i < entries.size() && i < 15; i++) {
            net.minecraft.world.scores.Score e = entries.get(i);
            names.add(PlayerTeam.formatNameForTeam(sb.getPlayersTeam(e.getOwner()), net.minecraft.network.chat.Component.literal(e.getOwner())).getString());
            scores.add(String.valueOf(e.getScore()));
        }
//#endif
        return new Sidebar(obj.getDisplayName().getString(), names, scores);
    }

    @Override public Title title() { return Compat.title(mc); }

    @Override public List<TabPlayer> tabPlayers() {
        ClientPacketListener c = mc.getConnection();
        if (c == null) return Collections.emptyList();
        List<TabPlayer> out = new ArrayList<TabPlayer>();
//#if >=1.19.3
        for (PlayerInfo i : c.getListedOnlinePlayers()) {
//#else
        for (PlayerInfo i : c.getOnlinePlayers()) {
//#endif
            String name = i.getProfile().name();
            String display = i.getTabListDisplayName() == null ? name : i.getTabListDisplayName().getString();
            PlayerTeam t = i.getTeam();
            out.add(new TabPlayer(name, display, i.getLatency(), i.getGameMode() == null ? "" : i.getGameMode().getName(), t == null ? "" : t.getName()));
        }
        return out;
    }

    @Override public String tabHeader() { return Compat.tabHeader(mc); }
    @Override public String tabFooter() { return Compat.tabFooter(mc); }

    // ---- entities ---------------------------------------------------------------------------------------------------

    @Override public List<EntityInfo> entities(double radius) {
        LocalPlayer p = p();
        ClientLevel lv = level();
        if (p == null || lv == null) return Collections.emptyList();
        long now = lv.getGameTime();
        if (now == infoTick && radius == infoRadius) return infos;
        List<EntityInfo> out = new ArrayList<EntityInfo>();
        double r2 = radius * radius;
        for (Entity e : lv.getEntities(p, new AABB(p.getX() - radius, p.getY() - radius, p.getZ() - radius, p.getX() + radius, p.getY() + radius, p.getZ() + radius))) {
            double d2 = e.distanceToSqr(p);
            if (d2 > r2) continue;
            out.add(info(e, Math.sqrt(d2)));
        }
        Collections.sort(out, new Comparator<EntityInfo>() {
            @Override public int compare(EntityInfo a, EntityInfo b) { return Double.compare(a.distance, b.distance); }
        });
        infoTick = now;
        infoRadius = radius;
        return infos = out;
    }

    static EntityInfo info(Entity e, double dist) {
        boolean living = e instanceof LivingEntity;
        float hp = living ? ((LivingEntity) e).getHealth() : 0, max = living ? ((LivingEntity) e).getMaxHealth() : 0;
        int fuse = -1;
        if (e instanceof PrimedTnt) fuse = ((PrimedTnt) e).getFuse();
        else if (e instanceof Creeper) {
            float swell = ((Creeper) e).getSwelling(0f);
            if (swell > 0) fuse = Math.max(0, Math.round((1f - swell) * 30));
        }
        String name = e instanceof ItemEntity ? ((ItemEntity) e).getItem().getHoverName().getString() : e.getName().getString();
        EntityInfo info = new EntityInfo(e.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), name, e.getX(), e.getY(), e.getZ(), dist, hp, max,
                e.getBbHeight(), living, e instanceof Enemy, e instanceof Player, e instanceof ItemEntity, e instanceof Projectile, e.isInvisible(), fuse);
        info.width = e.getBbWidth();
        return info;
    }

    // ---- screens, window, diagnostics -------------------------------------------------------------------------------

    @Override public String screenName() {
        Screen s = Compat.screen(mc);
        if (s == null) return "";
        if (s instanceof ChatScreen) return "chat";
        if (s instanceof InventoryScreen || s instanceof CreativeModeInventoryScreen) return "inventory";
        if (s instanceof AbstractContainerScreen) return "container";
        if (s instanceof PauseScreen) return "pause";
        return "other";
    }

    @Override public boolean containerOpen() { return Compat.screen(mc) instanceof AbstractContainerScreen; }
    @Override public boolean windowFocused() { return mc.isWindowActive(); }

    @Override public int renderedChunks() { return fraction(Compat.sectionStats(mc), 0); }
    @Override public int totalChunks() { return fraction(Compat.sectionStats(mc), 1); }
    @Override public int renderedEntities() { return fraction(Compat.entityStats(mc), 0); }
    @Override public int totalEntities() { return level() == null ? 0 : level().getEntityCount(); }
    @Override public String gpuName() { return Compat.gpuName(); }

    @Override public float packetsIn() {
        ClientPacketListener c = mc.getConnection();
        return c == null ? -1 : c.getConnection().getAverageReceivedPackets();
    }

    @Override public float packetsOut() {
        ClientPacketListener c = mc.getConnection();
        return c == null ? -1 : c.getConnection().getAverageSentPackets();
    }

    /** First "a/b" in a vanilla statistics string ("C: 120/441 ..."); part 0 = a, 1 = b. */
    static int fraction(String stats, int part) {
        if (stats == null) return 0;
        Matcher m = FRACTION.matcher(stats);
        if (!m.find()) return 0;
        try {
            return Integer.parseInt(m.group(part + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    static ItemStack stack(net.minecraft.world.item.ItemStack st) {
        if (st == null || st.isEmpty()) return null;
        List<String> ench = ItemCompat.enchantments(st);
        return new ItemStack(id(st), st.getCount(), st.getMaxDamage() - st.getDamageValue(), st.getMaxDamage(), st.getHoverName().getString(), ench);
    }

    static String id(net.minecraft.world.item.ItemStack st) {
        return BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
    }

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
