package gg.aether.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import gg.aether.mc.McEvents;
import java.io.File;
import java.util.function.Consumer;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Screenshot events with the file they are written to (the file appears a moment later, written off-thread). */
@Mixin(Screenshot.class)
abstract class ScreenshotMixin {
    /** Automatic names (F2). */
    @Inject(method = "getFile", at = @At("RETURN"))
    private static void aether$auto(File dir, CallbackInfoReturnable<File> cir) { McEvents.screenshot(cir.getReturnValue()); }

    /** Explicit names. */
//#if >=1.21.6
    @Inject(method = "grab(Ljava/io/File;Ljava/lang/String;Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V", at = @At("HEAD"))
    private static void aether$named(File gameDir, String name, RenderTarget target, int downscale, Consumer<Component> callback, CallbackInfo ci) {
//#elif >=1.17
    @Inject(method = "grab(Ljava/io/File;Ljava/lang/String;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V", at = @At("HEAD"))
    private static void aether$named(File gameDir, String name, RenderTarget target, Consumer<Component> callback, CallbackInfo ci) {
//#else
    @Inject(method = "grab(Ljava/io/File;Ljava/lang/String;IILcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V", at = @At("HEAD"))
    private static void aether$named(File gameDir, String name, int width, int height, RenderTarget target, Consumer<Component> callback, CallbackInfo ci) {
//#endif
//#if >=1.19.3
        if (name != null) McEvents.screenshot(new File(new File(gameDir, Screenshot.SCREENSHOT_DIR), name));
//#else
        if (name != null) McEvents.screenshot(new File(new File(gameDir, "screenshots"), name));
//#endif
    }
}
