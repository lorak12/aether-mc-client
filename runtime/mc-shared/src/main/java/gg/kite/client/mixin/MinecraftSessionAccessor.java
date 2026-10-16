package gg.kite.client.mixin;

//#if >=1.18
import com.mojang.authlib.minecraft.UserApiService;
//#endif
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
//#if >=1.19
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
//#endif
//#if >=1.19.1
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
//#endif
//#if >=1.19.3
import net.minecraft.client.telemetry.ClientTelemetryManager;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The signed-in account's state on {@link Minecraft}: retrying the own-profile (skin) lookup and switching accounts live. */
@Mixin(Minecraft.class)
public interface MinecraftSessionAccessor {
    @Mutable @Accessor("user") void kite$setUser(User user);
//#if >=1.20.2
    @Accessor("profileFuture") CompletableFuture<?> kite$profileFuture();
    @Mutable @Accessor("profileFuture") void kite$setProfileFuture(CompletableFuture<?> f);
//#else
    @Accessor("profileProperties") com.mojang.authlib.properties.PropertyMap kite$profileProperties();
//#endif
//#if >=1.18
    @Mutable @Accessor("userApiService") void kite$setUserApiService(UserApiService s);
//#endif
//#if >=1.20.3
    @Mutable @Accessor("userPropertiesFuture") void kite$setUserPropertiesFuture(CompletableFuture<?> f);
//#endif
//#if >=1.19
    @Mutable @Accessor("profileKeyPairManager") void kite$setProfileKeyPairManager(ProfileKeyPairManager m);
//#endif
//#if >=1.19.1
    @Accessor("reportingContext") void kite$setReportingContext(ReportingContext c);
//#endif
//#if >=1.19.3
    @Accessor("telemetryManager") ClientTelemetryManager kite$telemetryManager();
    @Mutable @Accessor("telemetryManager") void kite$setTelemetryManager(ClientTelemetryManager t);
//#endif
}
