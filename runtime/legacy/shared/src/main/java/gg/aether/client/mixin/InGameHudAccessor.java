//#if >=1.8
package gg.aether.client.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The title / subtitle currently on screen (1.8+). */
@Mixin(InGameHud.class)
public interface InGameHudAccessor {
    @Accessor("title") String aether$title();
    @Accessor("subtitle") String aether$subtitle();
    @Accessor("titleTotalTicks") int aether$titleTotalTicks();
    @Accessor("titleFadeInTicks") int aether$titleFadeIn();
    @Accessor("titleRemainTicks") int aether$titleRemain();
    @Accessor("titleFadeOutTicks") int aether$titleFadeOut();
}
//#endif
