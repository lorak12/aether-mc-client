package gg.aether.client.mixin;

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
    @Accessor("title") Component aether$title();
    @Accessor("subtitle") Component aether$subtitle();
    @Accessor("titleTime") int aether$titleTime();
    @Accessor("titleFadeInTime") int aether$fadeIn();
    @Accessor("titleStayTime") int aether$stay();
    @Accessor("titleFadeOutTime") int aether$fadeOut();
}
