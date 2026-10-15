package gg.aether.client.mixin;

import gg.aether.core.Events;
import gg.aether.mc.McEvents;
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
import gg.aether.client.AetherClient;
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
    private void aether$attack(Player player, Entity target, CallbackInfo ci) {
        McEvents.attacked(target);
        gg.aether.core.Overrides fx = AetherClient.fx();
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
        AetherClient.mods().events().post(new Events.Attack(Math.sqrt(dx * dx + dy * dy + dz * dz), crit, System.currentTimeMillis()));
    }

    @Unique private String aether$breaking;
    @Unique private ItemStack aether$using;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void aether$breakStart(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        aether$breaking = mc.level == null ? null : BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString();
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void aether$broken(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && aether$breaking != null) McEvents.blockBroken(aether$breaking, pos);
        aether$breaking = null;
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
//#if >=1.19
    private void aether$useStart(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
//#else
    private void aether$useStart(LocalPlayer player, net.minecraft.client.multiplayer.ClientLevel level, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
//#endif
        aether$using = player.getItemInHand(hand).copy();
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
//#if >=1.19
    private void aether$used(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
//#else
    private void aether$used(LocalPlayer player, net.minecraft.client.multiplayer.ClientLevel level, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
//#endif
        if (cir.getReturnValue().consumesAction() && aether$using != null) {
            BlockPos target = hit.getBlockPos();
            Minecraft mc = Minecraft.getInstance();
//#if >=1.19.3
            BlockPos placed = mc.level != null && mc.level.getBlockState(target).canBeReplaced() ? target : target.relative(hit.getDirection());
//#else
            net.minecraft.world.item.context.BlockPlaceContext place = new net.minecraft.world.item.context.BlockPlaceContext(player, hand, aether$using, hit);
            BlockPos placed = mc.level != null && mc.level.getBlockState(target).canBeReplaced(place) ? target : target.relative(hit.getDirection());
//#endif
            McEvents.blockPlaced(aether$using, placed);
        }
        aether$using = null;
    }
}
