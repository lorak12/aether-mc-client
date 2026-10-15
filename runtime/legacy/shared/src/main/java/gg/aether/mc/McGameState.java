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
//#if >=1.8
import net.minecraft.client.network.PlayerListEntry;
//#endif
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
//#if >=1.8
    @Override public int fps() { return MinecraftClient.getCurrentFps(); }
//#else
    @Override public int fps() {
        String s = mc.fpsDebugString; // "61 fps, 0 chunk updates"
        if (s == null) return 0;
        int n = 0, i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) n = n * 10 + (s.charAt(i++) - '0');
        return n;
    }
//#endif

    @Override public int pingMs() {
        ClientPlayerEntity p = p();
        if (p == null || mc.getNetworkHandler() == null || mc.isInSingleplayer()) return -1;
//#if >=1.8
        PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(p.getUuid());
        return e == null ? -1 : e.getLatency();
//#else
        for (Object o : mc.getNetworkHandler().field_7911) {
            net.minecraft.client.class_482 e = (net.minecraft.client.class_482) o;
            if (e.field_1679.equals(p.getGameProfile().getName())) return e.field_1680;
        }
        return -1;
//#endif
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
        for (Object o : p.getStatusEffectInstances()) {
            StatusEffectInstance e = (StatusEffectInstance) o;
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
        for (Entity e : LegacyCompat.entitiesNear(level(), p, radius)) {
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
//#if >=1.8
        if (hit == null || p == null || lv == null || hit.type == BlockHitResult.Type.MISS || hit.pos == null) return Target.NONE;
        double dist = p.getCameraPosVec(1f).distanceTo(hit.pos);
        if (hit.type == BlockHitResult.Type.ENTITY && hit.entity != null) {
//#else
        if (hit == null || p == null || lv == null || hit.hitResult == net.minecraft.util.hit.HitResultType.MISS || hit.pos == null) return Target.NONE;
        double dist = hit.pos.distanceTo(net.minecraft.util.math.Vec3d.of(p.x, p.y + p.getEyeHeight(), p.z));
        if (hit.hitResult == net.minecraft.util.hit.HitResultType.ENTITY && hit.entity != null) {
//#endif
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
//#if >=1.8
        BlockPos pos = hit.getBlockPos();
        if (pos == null) return Target.NONE;
        int bx = pos.getX(), by = pos.getY(), bz = pos.getZ();
        String face = hit.direction == null ? "" : hit.direction.getName();
        BlockPos front = hit.direction == null ? pos : pos.offset(hit.direction);
        int fx = front.getX(), fy = front.getY(), fz = front.getZ();
//#else
        int bx = hit.x, by = hit.y, bz = hit.z;
        String[] faces = { "down", "up", "north", "south", "west", "east" };
        String face = hit.side >= 0 && hit.side < faces.length ? faces[hit.side] : "";
        int[][] step = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };
        int s = hit.side >= 0 && hit.side < step.length ? hit.side : 1;
        int fx = bx + step[s][0], fy = by + step[s][1], fz = bz + step[s][2];
//#endif
        net.minecraft.block.Block b = LegacyCompat.block(lv, bx, by, bz);
        Target t = new Target(Target.Kind.BLOCK, b.getTranslatedName(), McItems.blockId(b), dist);
        t.x = bx;
        t.y = by;
        t.z = bz;
        t.face = face;
        t.hardness = LegacyCompat.hardness(lv, bx, by, bz);
        t.blockLight = LegacyCompat.light(lv, false, fx, fy, fz);
        t.skyLight = LegacyCompat.light(lv, true, fx, fy, fz);
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

    @Override public int blockLight(int x, int y, int z) { return level() == null ? 0 : LegacyCompat.light(level(), false, x, y, z); }
    @Override public int skyLight(int x, int y, int z) { return level() == null ? 15 : LegacyCompat.light(level(), true, x, y, z); }

    @Override public String blockId(int x, int y, int z) {
        ClientWorld lv = level();
        if (lv == null) return "minecraft:air";
        return McItems.blockId(LegacyCompat.block(lv, x, y, z));
    }

    @Override public int topY(int x, int z) {
        ClientWorld lv = level();
        if (lv == null) return Integer.MIN_VALUE;
        return LegacyCompat.topY(lv, x, z);
    }

    @Override public int mapColor(int x, int z) {
        ClientWorld lv = level();
        if (lv == null) return -1;
        int topY = LegacyCompat.topY(lv, x, z) - 1;
        if (topY < 0) return 0;
        return LegacyCompat.mapColor(lv, x, topY, z);
    }

    // ---- vanilla HUD state ------------------------------------------------------------------------------------------

    @Override public List<TabPlayer> tabPlayers() {
        if (mc.getNetworkHandler() == null) return Collections.emptyList();
        List<TabPlayer> out = new ArrayList<TabPlayer>();
//#if >=1.8
        for (PlayerListEntry i : mc.getNetworkHandler().getPlayerList()) {
            String name = i.getProfile().getName();
            String display = i.getDisplayName() == null ? name : i.getDisplayName().asUnformattedString();
            out.add(new TabPlayer(name, display, i.getLatency(), LegacyCompat.gameMode(i),
                    i.getScoreboardTeam() == null ? "" : i.getScoreboardTeam().getName()));
        }
//#else
        for (Object o : mc.getNetworkHandler().field_7911) {
            net.minecraft.client.class_482 i = (net.minecraft.client.class_482) o;
            out.add(new TabPlayer(i.field_1679, i.field_1679, i.field_1680, "", ""));
        }
//#endif
        return out;
    }

    // ---- more player / world state ---------------------------------------------------------------------------------

    @Override public gg.aether.core.GameState.ItemStack offhandItem() { return p() == null ? null : McItems.stack(LegacyCompat.offhand(p())); }

    @Override public float[] heldFood() { return p() == null ? null : LegacyCompat.food(LegacyCompat.mainHand(p())); }

    @Override public float hotbarDestroySpeed(int slot) {
        ClientPlayerEntity p = p();
        BlockHitResult hit = mc.result;
        ClientWorld lv = level();
        if (p == null || lv == null || hit == null || slot < 0 || slot > 8) return 0;
//#if >=1.8
        if (hit.type != BlockHitResult.Type.BLOCK || hit.getBlockPos() == null) return 0;
        net.minecraft.util.math.BlockPos pos = hit.getBlockPos();
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
//#else
        if (hit.hitResult != net.minecraft.util.hit.HitResultType.BLOCK) return 0;
        int x = hit.x, y = hit.y, z = hit.z;
//#endif
        return LegacyCompat.destroySpeed(LegacyCompat.inventory(p, slot), lv, x, y, z);
    }

    @Override public float hotbarAttackDamage(int slot) {
        ClientPlayerEntity p = p();
        if (p == null || slot < 0 || slot > 8) return 1;
        return LegacyCompat.attackDamage(LegacyCompat.inventory(p, slot));
    }

    @Override public String serverMotd() { return mc.getCurrentServerEntry() == null || mc.getCurrentServerEntry().label == null ? "" : strip(mc.getCurrentServerEntry().label); }
    @Override public String serverVersion() { return mc.getCurrentServerEntry() == null || mc.getCurrentServerEntry().version == null ? "" : strip(mc.getCurrentServerEntry().version); }
    @Override public String serverPlayers() {
        return mc.getCurrentServerEntry() == null || mc.getCurrentServerEntry().playerCountLabel == null ? "" : strip(mc.getCurrentServerEntry().playerCountLabel);
    }

    @Override public String gpuName() { return org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER); }

    @Override public int monitorRefreshRate() {
        int hz = org.lwjgl.opengl.Display.getDisplayMode().getFrequency();
        return hz > 0 ? hz : 60;
    }

    /** "C: 123/456 (s) D: 12, pC: 0, pU: 0, aB: 11" -> {rendered, total}. */
    private int[] chunks() {
        if (mc.worldRenderer == null) return new int[2];
        String d = mc.worldRenderer.getChunksDebugString();
        try {
            int c = d.indexOf("C: ") + 3, slash = d.indexOf('/', c), end = d.indexOf(' ', slash);
            return new int[] { Integer.parseInt(d.substring(c, slash).trim()), Integer.parseInt(d.substring(slash + 1, end).trim()) };
        } catch (RuntimeException e) {
            return new int[2];
        }
    }

    @Override public int renderedChunks() { return chunks()[0]; }
    @Override public int totalChunks() { return chunks()[1]; }

    @Override public int renderedEntities() {
        if (mc.worldRenderer == null) return 0;
        String d = mc.worldRenderer.getEntitiesDebugString(); // "E: 12/34, B: 0, I: 5, ..."
        try {
            int c = d.indexOf("E: ") + 3;
            return Integer.parseInt(d.substring(c, d.indexOf('/', c)).trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override public Boolean slimeChunk(int cx, int cz) {
        net.minecraft.server.integrated.IntegratedServer sv = mc.getServer();
        if (sv == null) return null;
        long seed = sv.worlds[0].getSeed();
        java.util.Random r = new java.util.Random(seed + (long) (cx * cx * 4987142) + (long) (cx * 5947611) + (long) (cz * cz) * 4392871L + (long) (cz * 389711) ^ 987234911L);
        return r.nextInt(10) == 0;
    }

    @Override public int caveColor(int x, int z, int fromY) {
        ClientWorld lv = level();
        if (lv == null) return -1;
        boolean air = false;
        for (int y = fromY; y > Math.max(0, fromY - 64); y--) {
            net.minecraft.block.Block b = LegacyCompat.block(lv, x, y, z);
            if (b == net.minecraft.block.Blocks.AIR) air = true;
            else if (air) return LegacyCompat.mapColor(lv, x, y, z);
        }
        return -1;
    }

    private static String strip(String s) { return s.replaceAll("\u00a7.", ""); }

    // ---- vanilla HUD state: boss bars, sidebar, title, tab header / footer ----------------------------------------------

    @Override public List<BossBar> bossBars() { return LegacyHud.bossBars(mc); }

    @Override public Sidebar sidebar() { return LegacyHud.sidebar(mc); }

    @Override public Title title() { return LegacyHud.title(mc); }

    @Override public String tabHeader() { return LegacyHud.tabText(mc, true); }

    @Override public String tabFooter() { return LegacyHud.tabText(mc, false); }

    // ---- entities ---------------------------------------------------------------------------------------------------

    @Override public List<EntityInfo> entities(double radius) {
        ClientPlayerEntity p = p();
        ClientWorld lv = level();
        if (p == null || lv == null) return Collections.emptyList();
        long now = lv.getLastUpdateTime();
        if (now == infoTick && radius == infoRadius) return infos;
        List<EntityInfo> out = new ArrayList<EntityInfo>();
        double r2 = radius * radius;
        for (Entity e : LegacyCompat.entitiesNear(lv, p, radius)) {
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
