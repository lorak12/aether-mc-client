//#if >=1.12
package gg.aether.client.mixin;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.class_2840;
import net.minecraft.client.gui.hud.BossBarHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The boss bars on screen (1.9+ keeps one per boss; before, a single static bar). */
@Mixin(BossBarHud.class)
public interface BossBarHudAccessor {
    @Accessor("field_13306") Map<UUID, class_2840> aether$bars();
}
//#endif
