//#if <26.3
package gg.kite.client.mixin;

import java.util.Map;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.multiplayer.ClientAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientAdvancements.class)
public interface ClientAdvancementsAccessor {
    @Accessor("progress") Map<AdvancementHolder, AdvancementProgress> kite$progress();
}
//#endif
