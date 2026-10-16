//#if >=1.8
package gg.kite.client.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The title / subtitle currently on screen (1.8+). */
@Mixin(InGameHud.class)
public interface InGameHudAccessor {
    @Accessor("title") String kite$title();
    @Accessor("subtitle") String kite$subtitle();
    @Accessor("titleTotalTicks") int kite$titleTotalTicks();
    @Accessor("titleFadeInTicks") int kite$titleFadeIn();
    @Accessor("titleRemainTicks") int kite$titleRemain();
    @Accessor("titleFadeOutTicks") int kite$titleFadeOut();
}
//#endif
