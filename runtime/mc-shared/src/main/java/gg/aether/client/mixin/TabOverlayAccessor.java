package gg.aether.client.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerTabOverlay.class)
public interface TabOverlayAccessor {
    @Accessor("header") Component aether$header();
    @Accessor("footer") Component aether$footer();
}
