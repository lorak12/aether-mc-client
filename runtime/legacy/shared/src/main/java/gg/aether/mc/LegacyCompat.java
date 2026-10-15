package gg.aether.mc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * The calls that differ between the legacy versions (1.7.10, 1.8.9, 1.12.2), for the shared legacy adapter code.
 * Legacy Yarn leaves some 1.12.2 members unnamed (method_xxxx / field_xxxx); those are used as they are.
 */
public final class LegacyCompat {
    private LegacyCompat() {}

    /** Armor slot 0 = boots ... 3 = helmet; null when empty. */
    public static ItemStack armor(PlayerEntity p, int slot) {
//#if >=1.12
        ItemStack st = p.inventory.field_15083.get(slot);
        return st.isEmpty() ? null : st;
//#else
        return p.inventory.armor[slot];
//#endif
    }

    /** Main inventory slot 0-35 (0-8 hotbar); null when empty. */
    public static ItemStack inventory(PlayerEntity p, int slot) {
//#if >=1.12
        ItemStack st = p.inventory.field_15082.get(slot);
        return st.isEmpty() ? null : st;
//#else
        return p.inventory.main[slot];
//#endif
    }

    public static ItemStack mainHand(PlayerEntity p) {
//#if >=1.12
        ItemStack st = p.getMainHandStack();
        return st.isEmpty() ? null : st;
//#else
        return p.getMainHandStack();
//#endif
    }

    public static int count(ItemStack st) {
//#if >=1.12
        return st.getCount();
//#else
        return st.count;
//#endif
    }

    public static String biome(World w, Entity e) {
//#if >=1.12
        return w.getBiome(new net.minecraft.util.math.BlockPos(e)).getName();
//#elif >=1.8
        return w.getBiome(new net.minecraft.util.math.BlockPos(e)).name;
//#else
        return w.getBiome((int) Math.floor(e.x), (int) Math.floor(e.z)).name;
//#endif
    }

    /** -1 nether, 0 overworld, 1 end. */
    public static int dimension(World w) {
//#if >=1.12
        return w.dimension.getDimensionType().getId();
//#elif >=1.8
        return w.dimension.getType();
//#else
        return w.dimension.dimensionType;
//#endif
    }

    public static StatusEffect effect(StatusEffectInstance e) {
//#if >=1.12
        return e.getStatusEffect();
//#else
        return StatusEffect.STATUS_EFFECTS[e.getEffectId()];
//#endif
    }

    public static String gameMode(MinecraftClient mc) {
//#if >=1.12
        return mc.interactionManager.method_9667().getGameModeName();
//#elif >=1.8
        return mc.interactionManager.getCurrentGameMode().getName();
//#else
        net.minecraft.entity.player.PlayerAbilities a = mc.field_3805.abilities;
        return a.creativeMode ? "creative" : a.allowModifyWorld ? "survival" : "adventure";
//#endif
    }

//#if >=1.8
    /** A player list entry's game mode name, "" when unknown. */
    public static String gameMode(net.minecraft.client.network.PlayerListEntry e) {
        if (e.getGameMode() == null) return "";
//#if >=1.12
        return e.getGameMode().getGameModeName();
//#else
        return e.getGameMode().getName();
//#endif
    }
//#endif

    /** Ticks the player has been using (drawing, eating) the held item, 0 when not using. */
    public static int useTicks(PlayerEntity p) {
//#if >=1.12
        return p.method_13066();
//#else
        return p.isUsingItem() ? p.getItemUseTicks() : 0;
//#endif
    }

    public static float hardness(World w, int x, int y, int z) {
//#if >=1.12
        net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
        return w.getBlockState(pos).getHardness(w, pos);
//#elif >=1.8
        net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
        return w.getBlockState(pos).getBlock().getStrength(w, pos);
//#else
        return w.getBlock(x, y, z).getHardness(w, x, y, z);
//#endif
    }

    public static int mapColor(World w, int x, int y, int z) {
//#if >=1.12
        net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
        return w.getBlockState(pos).getMaterialColor(w, pos).color;
//#elif >=1.8
        net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
        return w.getBlockState(pos).getBlock().getMaterialColor(w.getBlockState(pos)).color;
//#else
        return w.getBlock(x, y, z).getMapColor(w.getBlockData(x, y, z)).color;
//#endif
    }

    /** Registry id of the block at (x, y, z). */
    public static net.minecraft.block.Block block(World w, int x, int y, int z) {
//#if >=1.8
        return w.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z)).getBlock();
//#else
        return w.getBlock(x, y, z);
//#endif
    }

    /** Block light / sky light at (x, y, z). */
    public static int light(World w, boolean sky, int x, int y, int z) {
//#if >=1.8
        return w.getLightAtPos(sky ? net.minecraft.world.LightType.SKY : net.minecraft.world.LightType.BLOCK, new net.minecraft.util.math.BlockPos(x, y, z));
//#else
        return w.method_3642(sky ? net.minecraft.world.LightType.SKY : net.minecraft.world.LightType.BLOCK, x, y, z);
//#endif
    }

    /** Y of the highest solid block in the column. */
    public static int topY(World w, int x, int z) {
//#if >=1.8
        return w.getHighestBlock(new net.minecraft.util.math.BlockPos(x, 0, z)).getY() - 1;
//#else
        return w.getHighestBlockY(x, z) - 1;
//#endif
    }

    /** Entities other than {@code self} within {@code radius} blocks (box test, like vanilla's lookups). */
    public static java.util.List<Entity> entitiesNear(World w, Entity self, double radius) {
        java.util.List<Entity> out = new java.util.ArrayList<Entity>();
//#if >=1.8
        net.minecraft.util.math.Box box = self.getBoundingBox().expand(radius, radius, radius);
//#else
        net.minecraft.util.math.Box box = self.boundingBox.expand(radius, radius, radius);
//#endif
        for (Object o : w.getEntitiesIn(self, box)) out.add((Entity) o);
        return out;
    }

    public static boolean riding(Entity e) {
//#if >=1.12
        return e.getVehicle() != null;
//#else
        return e.vehicle != null;
//#endif
    }

    public static void stopMusic(MinecraftClient mc) {
//#if >=1.8 && <1.12
        mc.getMusicTracker().stop();
//#endif
    }

    /** An item icon at (x, y), GUI lighting set up by the caller. */
    public static void renderGuiItem(MinecraftClient mc, ItemStack st, int x, int y) {
//#if >=1.12
        mc.getItemRenderer().method_12461(st, x, y);
//#elif >=1.8
        mc.getItemRenderer().renderInGuiWithOverrides(st, x, y);
//#else
        GUI_ITEMS.method_6920(mc.textRenderer, mc.getTextureManager(), st, x, y);
//#endif
    }

//#if <1.8
    private static final net.minecraft.client.render.item.ItemRenderer GUI_ITEMS = new net.minecraft.client.render.item.ItemRenderer();
//#endif

    public static void renderEntity(LivingEntity e) {
//#if >=1.12
        MinecraftClient.getInstance().getEntityRenderManager().method_12446(e, 0.0, 0.0, 0.0, 0f, 1f, false);
//#elif >=1.8
        MinecraftClient.getInstance().getEntityRenderManager().render(e, 0.0, 0.0, 0.0, 0f, 1f);
//#else
        net.minecraft.client.render.entity.EntityRenderDispatcher.INSTANCE.render(e, 0.0, 0.0, 0.0, 0f, 1f);
//#endif
    }

    /** The dispatcher's view yaw / shadows around a GUI entity render. */
    public static float dispatcherYaw() {
//#if >=1.8
        return 0f;
//#else
        return net.minecraft.client.render.entity.EntityRenderDispatcher.INSTANCE.yaw;
//#endif
    }

    public static java.awt.image.BufferedImage readImage(java.io.InputStream in) throws java.io.IOException {
//#if >=1.8
        return net.minecraft.client.texture.TextureUtil.create(in);
//#else
        return javax.imageio.ImageIO.read(in);
//#endif
    }

    /** An elytra in the chest slot (1.9+ only). */
    public static boolean wearsElytra(PlayerEntity p) {
//#if >=1.12
        return p.getStack(net.minecraft.entity.EquipmentSlot.CHEST).getItem() == net.minecraft.item.Items.ELYTRA;
//#else
        return false;
//#endif
    }

    /** Vanilla's cape layer conditions: the player has a cape texture and shows the cape part. */
    public static boolean capeVisible(net.minecraft.client.network.AbstractClientPlayerEntity p) {
//#if >=1.8
        return p.canRenderCapeTexture() && p.isPartVisible(net.minecraft.client.render.entity.PlayerModelPart.CAPE) && p.getSkinId() != null;
//#else
        return p.canRenderCapeTexture() && !p.method_4577();
//#endif
    }

    /** The cape texture (Legacy Yarn names it getSkinId; it is the one vanilla's cape layer binds). */
    public static net.minecraft.util.Identifier capeTexture(net.minecraft.client.network.AbstractClientPlayerEntity p) { return p.getSkinId(); }

    /** The entity's packed light level for the next vertices. */
    public static int lightmap(Entity e, float tickDelta) {
//#if >=1.12
        return e.getLightmapCoordinates();
//#else
        return e.getLightmapCoordinates(tickDelta);
//#endif
    }

//#if <1.8
    /** Limb swing amount (1.7.10 leaves it unnamed). */
    public static float limbAmount(LivingEntity e, float tickDelta) { return e.field_6748 + (e.field_6749 - e.field_6748) * tickDelta; }
//#endif

    /** The hitbox debug render (F3+B). */
    public static boolean hitboxes(MinecraftClient mc) {
//#if >=1.8
        return mc.getEntityRenderManager().getRenderHitboxes();
//#else
        return net.minecraft.client.render.entity.EntityRenderDispatcher.renderHitboxes;
//#endif
    }

    public static void setHitboxes(MinecraftClient mc, boolean on) {
//#if >=1.8
        mc.getEntityRenderManager().setRenderHitboxes(on);
//#else
        net.minecraft.client.render.entity.EntityRenderDispatcher.renderHitboxes = on;
//#endif
    }

    /** The chunk border debug render (F3+G, 1.9+). */
    public static boolean chunkBorders(MinecraftClient mc) {
//#if >=1.12
        return mc.debugRenderer.isEnabled();
//#else
        return false;
//#endif
    }

    public static void toggleChunkBorders(MinecraftClient mc) {
//#if >=1.12
        mc.debugRenderer.toggleChunkBorders();
//#endif
    }

    /** The off-hand item (1.9+), null when empty or before 1.9. */
    public static ItemStack offhand(PlayerEntity p) {
//#if >=1.12
        ItemStack st = p.getStack(net.minecraft.entity.EquipmentSlot.OFFHAND);
        return st.isEmpty() ? null : st;
//#else
        return null;
//#endif
    }

    /** {hunger, saturation} a food item restores, else null. */
    public static float[] food(ItemStack st) {
        if (st == null || !(st.getItem() instanceof net.minecraft.item.FoodItem)) return null;
        net.minecraft.item.FoodItem f = (net.minecraft.item.FoodItem) st.getItem();
        return new float[] { f.getHungerPoints(st), f.getHungerPoints(st) * f.getSaturation(st) * 2f };
    }

    /** Mining speed of {@code st} against the block at (x, y, z). */
    public static float destroySpeed(ItemStack st, World w, int x, int y, int z) {
        if (st == null) return 1f;
//#if >=1.12
        return st.getBlockBreakingSpeed(w.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z)));
//#else
        return st.getMiningSpeedMultiplier(block(w, x, y, z));
//#endif
    }

    /** Attack damage of {@code st}: a fist's 1 plus the item's attack damage modifier. */
    public static float attackDamage(ItemStack st) {
        if (st == null) return 1f;
        double dmg = 1.0;
//#if >=1.12
        java.util.Collection<?> mods = st.getAttributes(net.minecraft.entity.EquipmentSlot.MAINHAND).get("generic.attackDamage");
//#else
        java.util.Collection<?> mods = st.getAttributes().get("generic.attackDamage");
//#endif
        for (Object o : mods) {
            net.minecraft.entity.attribute.AttributeModifier m = (net.minecraft.entity.attribute.AttributeModifier) o;
            if (m.getOperation() == 0) dmg += m.getAmount();
        }
        return (float) dmg;
    }

    /** Whether the server's player list has {@code p} (a real player, not an NPC). */
    public static boolean inTabList(PlayerEntity p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return true;
//#if >=1.8
        return mc.getNetworkHandler().getPlayerListEntry(p.getUuid()) != null;
//#else
        for (Object o : mc.getNetworkHandler().field_7911) if (((net.minecraft.client.class_482) o).field_1679.equals(p.getGameProfile().getName())) return true;
        return false;
//#endif
    }

    /** A plugin message's data. */
    public static byte[] payload(net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket packet) {
//#if >=1.8
        net.minecraft.util.PacketByteBuf buf = packet.getPayload();
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
//#else
        return packet.getPayload();
//#endif
    }

    /** A plugin message to the server. */
    public static net.minecraft.network.Packet customPayload(String channel, byte[] data) {
//#if >=1.8
        return new net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket(channel,
                new net.minecraft.util.PacketByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data)));
//#else
        return new net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket(channel, data);
//#endif
    }

    /** Draw a string (1.7.10 takes whole pixels). */
    public static void drawText(net.minecraft.client.font.TextRenderer font, String s, float x, float y, int argb, boolean shadow) {
//#if >=1.8
        font.draw(s, x, y, argb, shadow);
//#else
        font.draw(s, Math.round(x), Math.round(y), argb, shadow);
//#endif
    }

    /** A short UI sound by its legacy name ("random.click") / 1.12 sound event id ("ui.button.click"). */
    public static void playSound(String legacy, String modern, float pitch) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getSoundManager() == null) return;
//#if >=1.12
        net.minecraft.sound.Sound s = net.minecraft.sound.Sound.REGISTRY.get(new net.minecraft.util.Identifier(modern));
        if (s != null) mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.method_12521(s, pitch));
//#else
        mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(new net.minecraft.util.Identifier(legacy), pitch));
//#endif
    }
}
