package gg.kite.client.mixin;

//#if >=26.2
import net.minecraft.client.gui.Hud;
//#else
import net.minecraft.client.gui.Gui;
//#endif
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Title/subtitle state for the Titles mod and GameState.title(). */
//#if >=26.2
@Mixin(Hud.class)
//#else
@Mixin(Gui.class)
//#endif
public interface HudStateAccessor {
    @Accessor("title") Component kite$title();
    @Accessor("subtitle") Component kite$subtitle();
    @Accessor("titleTime") int kite$titleTime();
    @Accessor("titleFadeInTime") int kite$fadeIn();
    @Accessor("titleStayTime") int kite$stay();
    @Accessor("titleFadeOutTime") int kite$fadeOut();
}
