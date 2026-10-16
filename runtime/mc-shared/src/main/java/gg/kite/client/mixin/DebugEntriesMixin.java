//#if >=1.21.9
package gg.kite.client.mixin;

import gg.kite.core.Overrides;
import gg.kite.client.KiteClient;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hitboxes and Chunk Borders mods switch the vanilla debug renderers on without changing the player's F3 setup. */
@Mixin(DebugScreenEntryList.class)
abstract class DebugEntriesMixin {
    @Inject(method = "isCurrentlyEnabled", at = @At("RETURN"), cancellable = true)
    private void kite$force(Identifier id, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) return;
        Overrides fx = KiteClient.fx();
        if ((fx.showHitboxes && DebugScreenEntries.ENTITY_HITBOXES.equals(id)) || (fx.showChunkBorders && DebugScreenEntries.CHUNK_BORDERS.equals(id))) cir.setReturnValue(true);
    }
}
//#endif
