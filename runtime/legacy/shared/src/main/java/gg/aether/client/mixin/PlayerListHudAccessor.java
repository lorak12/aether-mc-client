//#if >=1.8
package gg.aether.client.mixin;

import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The tab list's header and footer text (1.8+). */
@Mixin(PlayerListHud.class)
public interface PlayerListHudAccessor {
    @Accessor("header") Text aether$header();
    @Accessor("footer") Text aether$footer();
}
//#endif
