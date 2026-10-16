//#if <1.21.2
package gg.kite.mc;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;

/**
 * Before 1.21.2 players render straight from the entity; this stands in for the player render state the cosmetics code
 * reads (same field names), filled from the entity once per draw. One per player, so per-state maps keep their entries.
 */
public final class PlayerState {
    public AbstractClientPlayer entity;
//#if >=1.20.2
    public net.minecraft.client.resources.PlayerSkin skin;
//#endif
    public boolean showCape, isInvisible, isFallFlying, isCrouching;
    public ItemStack headItem = ItemStack.EMPTY, chestItem = ItemStack.EMPTY, feetItem = ItemStack.EMPTY;
    public float ageInTicks, walkAnimationSpeed, bodyRot, capeFlap, capeLean, capeLean2;

    private static final Map<AbstractClientPlayer, PlayerState> STATES = new WeakHashMap<AbstractClientPlayer, PlayerState>();

    /** The player's state for this draw, refreshed from the entity (and its cosmetics looked up). */
    public static PlayerState of(AbstractClientPlayer p, float partialTick) {
        PlayerState s = STATES.get(p);
        if (s == null) {
            s = new PlayerState();
            STATES.put(p, s);
        }
        s.fill(p, partialTick);
        McCosmetics.extract(p, s);
        return s;
    }

    private void fill(AbstractClientPlayer p, float pt) {
        entity = p;
//#if >=1.20.2
        skin = p.getSkin();
//#endif
        showCape = p.isModelPartShown(PlayerModelPart.CAPE);
        isInvisible = p.isInvisible();
        isFallFlying = p.isFallFlying();
        isCrouching = p.isCrouching();
        headItem = p.getItemBySlot(EquipmentSlot.HEAD);
        chestItem = p.getItemBySlot(EquipmentSlot.CHEST);
        feetItem = p.getItemBySlot(EquipmentSlot.FEET);
        ageInTicks = p.tickCount + pt;
//#if >=1.19.4
        walkAnimationSpeed = p.walkAnimation.speed(pt);
//#else
        walkAnimationSpeed = Mth.lerp(pt, p.animationSpeedOld, p.animationSpeed);
//#endif
        bodyRot = Mth.rotLerp(pt, p.yBodyRotO, p.yBodyRot);
        // Same as vanilla's cape: flap from vertical speed and walking bob, lean from horizontal speed.
        double dx = Mth.lerp(pt, p.xCloakO, p.xCloak) - Mth.lerp(pt, p.xo, p.getX());
        double dy = Mth.lerp(pt, p.yCloakO, p.yCloak) - Mth.lerp(pt, p.yo, p.getY());
        double dz = Mth.lerp(pt, p.zCloakO, p.zCloak) - Mth.lerp(pt, p.zo, p.getZ());
        double sin = Mth.sin(bodyRot * ((float) Math.PI / 180f)), cos = -Mth.cos(bodyRot * ((float) Math.PI / 180f));
        capeFlap = Mth.clamp((float) dy * 10f, -6f, 32f);
        capeLean = Mth.clamp((float) (dx * sin + dz * cos) * 100f, 0f, 150f);
        capeLean2 = Mth.clamp((float) (dx * cos - dz * sin) * 100f, -20f, 20f);
        float bob = Mth.lerp(pt, p.oBob, p.bob);
        capeFlap += Mth.sin(Mth.lerp(pt, p.walkDistO, p.walkDist) * 6f) * 32f * bob;
    }
}
//#endif
