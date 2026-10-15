//#if >=1.8
package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import gg.aether.mc.LegacyCosmetics;
import gg.aether.mc.McEvents;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The tab list: names with the client badge and streamer replacements, hidden when a mod redraws it. */
@Mixin(PlayerListHud.class)
abstract class PlayerListHudMixin {
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void aether$name(PlayerListEntry entry, CallbackInfoReturnable<String> cir) {
        String name = McEvents.replaceFormatted(cir.getReturnValue(), AetherClient.fx());
        if (entry != null && entry.getProfile() != null) name = LegacyCosmetics.badge(entry.getProfile().getId(), name);
        if (!name.equals(cir.getReturnValue())) cir.setReturnValue(name);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void aether$hide(int width, Scoreboard scoreboard, ScoreboardObjective objective, CallbackInfo ci) {
        if (AetherClient.fx().hideVanillaTabList) ci.cancel();
    }
}
//#endif
