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
