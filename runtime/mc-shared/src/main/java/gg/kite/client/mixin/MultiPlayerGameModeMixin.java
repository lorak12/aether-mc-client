package gg.kite.client.mixin;

import gg.kite.core.Events;
import gg.kite.mc.McEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import gg.kite.client.KiteClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hits (combo, reach, kills) and block break/place events. */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void kite$attack(Player player, Entity target, CallbackInfo ci) {
        McEvents.attacked(target);
        gg.kite.core.Overrides fx = KiteClient.fx();
        for (int i = 0; i < fx.extraCrits; i++) Minecraft.getInstance().particleEngine.createTrackingEmitter(target, net.minecraft.core.particles.ParticleTypes.CRIT);
        if (fx.alwaysMagicCrits) Minecraft.getInstance().particleEngine.createTrackingEmitter(target, net.minecraft.core.particles.ParticleTypes.ENCHANTED_HIT);
        // Reach = eye to the nearest point of the target's hitbox, as servers measure it.
        Vec3 eye = player.getEyePosition();
        AABB bb = target.getBoundingBox();
        double dx = eye.x - Math.max(bb.minX, Math.min(bb.maxX, eye.x));
        double dy = eye.y - Math.max(bb.minY, Math.min(bb.maxY, eye.y));
        double dz = eye.z - Math.max(bb.minZ, Math.min(bb.maxZ, eye.z));
//#if >=1.20
        boolean crit = player.fallDistance > 0 && !player.onGround() && !player.onClimbable() && !player.isInWater() && !player.isPassenger();
//#else
        boolean crit = player.fallDistance > 0 && !player.isOnGround() && !player.onClimbable() && !player.isInWater() && !player.isPassenger();
//#endif
        KiteClient.mods().events().post(new Events.Attack(Math.sqrt(dx * dx + dy * dy + dz * dz), crit, System.currentTimeMillis()));
    }

    @Unique private String kite$breaking;
    @Unique private ItemStack kite$using;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void kite$breakStart(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        kite$breaking = mc.level == null ? null : BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString();
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void kite$broken(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && kite$breaking != null) McEvents.blockBroken(kite$breaking, pos);
        kite$breaking = null;
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
//#if >=1.19
    private void kite$useStart(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
//#else
    private void kite$useStart(LocalPlayer player, net.minecraft.client.multiplayer.ClientLevel level, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
//#endif
        kite$using = player.getItemInHand(hand).copy();
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
//#if >=1.19
    private void kite$used(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
//#else
    private void kite$used(LocalPlayer player, net.minecraft.client.multiplayer.ClientLevel level, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
//#endif
        if (cir.getReturnValue().consumesAction() && kite$using != null) {
            BlockPos target = hit.getBlockPos();
            Minecraft mc = Minecraft.getInstance();
//#if >=1.19.3
            BlockPos placed = mc.level != null && mc.level.getBlockState(target).canBeReplaced() ? target : target.relative(hit.getDirection());
//#else
            net.minecraft.world.item.context.BlockPlaceContext place = new net.minecraft.world.item.context.BlockPlaceContext(player, hand, kite$using, hit);
            BlockPos placed = mc.level != null && mc.level.getBlockState(target).canBeReplaced(place) ? target : target.relative(hit.getDirection());
//#endif
            McEvents.blockPlaced(kite$using, placed);
        }
        kite$using = null;
    }
}
