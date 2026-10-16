//#if >=1.16 && <1.20
package gg.kite.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Immediate-mode GUI operations used by Minecraft before GuiGraphics existed. */
public final class LegacyGuiGraphics {
    private final PoseStack pose;

    public LegacyGuiGraphics(PoseStack pose) { this.pose = pose; }
    public PoseStack pose() { return pose; }
    public int guiWidth() { return Minecraft.getInstance().getWindow().getGuiScaledWidth(); }
    public int guiHeight() { return Minecraft.getInstance().getWindow().getGuiScaledHeight(); }

    public void fill(int x0, int y0, int x1, int y1, int color) {
        GuiComponent.fill(pose, x0, y0, x1, y1, color);
    }

    public void drawString(Font font, String text, int x, int y, int color, boolean shadow) {
        if (shadow) font.drawShadow(pose, text, x, y, color);
        else font.draw(pose, text, x, y, color);
    }

    public void renderFakeItem(ItemStack stack, int x, int y) {
//#if >=1.19.4
        Minecraft.getInstance().getItemRenderer().renderAndDecorateFakeItem(pose, stack, x, y);
//#else
        Minecraft.getInstance().getItemRenderer().renderAndDecorateFakeItem(stack, x, y);
//#endif
    }

//#if <1.17
    // 1.16: raw GL scissor in framebuffer pixels, y from the bottom.
    public void enableScissor(int x0, int y0, int x1, int y1) {
        com.mojang.blaze3d.platform.Window w = Minecraft.getInstance().getWindow();
        double s = w.getGuiScale();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glScissor((int) (x0 * s), (int) (w.getHeight() - y1 * s), Math.max(0, (int) ((x1 - x0) * s)), Math.max(0, (int) ((y1 - y0) * s)));
    }
    public void disableScissor() { org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST); }
//#elif >=1.19.1
    public void enableScissor(int x0, int y0, int x1, int y1) { GuiComponent.enableScissor(x0, y0, x1, y1); }
    public void disableScissor() { GuiComponent.disableScissor(); }
//#else
    // 1.19: RenderSystem scissor in framebuffer pixels, y from the bottom.
    public void enableScissor(int x0, int y0, int x1, int y1) {
        com.mojang.blaze3d.platform.Window w = Minecraft.getInstance().getWindow();
        double s = w.getGuiScale();
        RenderSystem.enableScissor((int) (x0 * s), (int) (w.getHeight() - y1 * s), Math.max(0, (int) ((x1 - x0) * s)), Math.max(0, (int) ((y1 - y0) * s)));
    }
    public void disableScissor() { RenderSystem.disableScissor(); }
//#endif

    public void blit(Identifier texture, int x, int y, int width, int height, float u, float v,
            int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
//#if >=1.17
        RenderSystem.setShaderTexture(0, texture);
//#else
        Minecraft.getInstance().getTextureManager().bind(texture);
//#endif
        GuiComponent.blit(pose, x, y, width, height, u, v, regionWidth, regionHeight, textureWidth, textureHeight);
    }

    public void flush() { /* GuiComponent draws immediately. */ }
}
//#endif
