package gg.aether.client.mixin;

import gg.aether.client.AetherClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Tooltips mod: the tooltip frame in the mod's colours (vanilla: translucent purple-black with a purple border). */
@Mixin(Screen.class)
abstract class ScreenMixin {
    private static final String TOOLTIP = "renderTooltip(Ljava/util/List;II)V";

    @ModifyConstant(method = TOOLTIP, constant = @Constant(intValue = -267386864))
    private int aether$tooltipBackground(int vanilla) {
        Integer c = AetherClient.fx().tooltipBackground;
        return c == null ? vanilla : c;
    }

    @ModifyConstant(method = TOOLTIP, constant = @Constant(intValue = 1347420415))
    private int aether$tooltipBorder(int vanilla) {
        Integer c = AetherClient.fx().tooltipBorder;
        return c == null ? vanilla : c;
    }
}
