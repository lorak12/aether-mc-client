package gg.aether.client.mixin;

import gg.aether.mc.McEvents;
import java.io.File;
import net.minecraft.client.util.ScreenshotUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Screenshot events with the file they are written to (the file appears a moment later, written off-thread). */
@Mixin(ScreenshotUtils.class)
abstract class ScreenshotUtilsMixin {
    /** Automatic names (F2). */
    @Inject(method = "getScreenshotFile", at = @At("RETURN"))
    private static void aether$auto(File dir, CallbackInfoReturnable<File> cir) { McEvents.screenshot(cir.getReturnValue()); }

    /** Explicit names. */
//#if >=1.12
    @Inject(method = "method_12154(Ljava/io/File;Ljava/lang/String;IILnet/minecraft/client/gl/Framebuffer;)Lnet/minecraft/text/Text;", at = @At("HEAD"))
//#else
    @Inject(method = "saveScreenshot(Ljava/io/File;Ljava/lang/String;IILnet/minecraft/client/gl/Framebuffer;)Lnet/minecraft/text/Text;", at = @At("HEAD"))
//#endif
    private static void aether$named(File gameDir, String name, int width, int height, net.minecraft.client.gl.Framebuffer fb,
            CallbackInfoReturnable<net.minecraft.text.Text> cir) {
        if (name != null) McEvents.screenshot(new File(new File(gameDir, "screenshots"), name));
    }
}
