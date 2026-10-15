//#if >=1.21.11
package gg.aether.client.mixin;

import gg.aether.mc.McBadge;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.network.chat.FontDescription;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The client badge's font ("aether:badge", see McBadge): vanilla builds fonts only from resource packs. */
@Mixin(targets = "net.minecraft.client.gui.font.FontManager$CachedFontProvider")
abstract class FontProviderMixin {
    @Inject(method = "getGlyphSource", at = @At("HEAD"), cancellable = true)
    private void aether$badge(FontDescription description, CallbackInfoReturnable<GlyphSource> cir) {
        if (McBadge.FONT.equals(description)) cir.setReturnValue(McBadge.SOURCE);
    }
}
//#endif
