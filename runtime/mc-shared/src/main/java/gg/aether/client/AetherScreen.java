package gg.aether.client;

import gg.aether.core.GameState;
import gg.aether.core.ui.View;
//#if >=26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#elif >=1.20
import net.minecraft.client.gui.GuiGraphics;
//#else
import com.mojang.blaze3d.vertex.PoseStack;
//#endif
import net.minecraft.client.gui.screens.Screen;
//#if >=1.21.9
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//#endif
import net.minecraft.network.chat.Component;

/** Hosts a shared {@link View} (mod menu, HUD editor, world map) as a vanilla screen and forwards input to it. */
final class AetherScreen extends Screen {
    View gui() { return gui; }

    private final GameState state;
    private View gui;

    AetherScreen(GameState state) {
        super(Component.literal("Kite"));
        this.state = state;
    }

    void setGui(View gui) { this.gui = gui; }

//#if >=26.1
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
//#elif >=1.20
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
//#else
    @Override public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        LegacyGuiGraphics g = new LegacyGuiGraphics(pose);
//#endif
//#if <1.20.2
        background(g, partialTick);
//#endif
        gui.mouseMoved(mouseX, mouseY);
        gui.render(new GuiRenderer(g), state);
    }

    // The GUI dims the game itself; skip vanilla's blur so the HUD stays readable behind the menu. The main menu
    // asks for the title-screen panorama instead.
//#if >=26.1
    @Override public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (gui.panorama()) extractPanorama(g, partialTick);
//#else
//#if >=1.20.2
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) { background(g, partialTick); }
//#endif

//#if >=1.20
    private void background(GuiGraphics g, float partialTick) {
//#else
    private void background(LegacyGuiGraphics g, float partialTick) {
//#endif
//#if >=1.20.5
        if (gui.panorama()) renderPanorama(g, partialTick);
//#else
        // No Screen.renderPanorama before 1.20.5: the title screen's cube map, spun by our own renderer.
        if (gui.panorama()) {
            if (panorama == null) panorama = new net.minecraft.client.renderer.PanoramaRenderer(net.minecraft.client.gui.screens.TitleScreen.CUBE_MAP);
            panorama.render(partialTick, 1f);
        }
//#endif
//#endif
    }

//#if <1.20.5
    private static net.minecraft.client.renderer.PanoramaRenderer panorama;

//#endif
    @Override public void tick() {
//#if >=26.1
        // 26.x only sends characters while text input is on (under SDL in 26.3+; vanilla turns it
        // on for a focused EditBox). Keep it on while an Aether screen is open so its fields and type-to-search work.
//#if >=26.3
        if (minecraft != null) minecraft.textInputManager().startTextInput(this);
//#else
        if (minecraft != null) minecraft.textInputManager().startTextInput();
//#endif
//#endif
        gui.tick();
        if (gui.muteMusic() && minecraft != null) minecraft.getMusicManager().stopPlaying();
    }

    // The main menu (like vanilla's title screen) never closes on Esc.
    @Override public boolean shouldCloseOnEsc() { return !gui.panorama(); }

//#if >=1.21.9
    @Override public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
//#if >=26.3
        return gui.mouseDown((float) e.x(), (float) e.y(), SdlInput.toCoreButton(e.button()));
//#else
        return gui.mouseDown((float) e.x(), (float) e.y(), e.button());
//#endif
    }

    @Override public boolean mouseReleased(MouseButtonEvent e) {
//#if >=26.3
        return gui.mouseUp((float) e.x(), (float) e.y(), SdlInput.toCoreButton(e.button()));
//#else
        return gui.mouseUp((float) e.x(), (float) e.y(), e.button());
//#endif
    }

    @Override public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        return gui.mouseDrag((float) e.x(), (float) e.y());
    }

    @Override public void mouseMoved(double x, double y) { gui.mouseMoved((float) x, (float) y); }

    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return gui.scroll((float) x, (float) y, (float) scrollY);
    }

    @Override public boolean keyReleased(KeyEvent e) {
//#if >=26.3
        gui.keyUp(SdlInput.toGlfw(e.key()));
//#else
        gui.keyUp(e.key());
//#endif
        return super.keyReleased(e);
    }

    @Override public boolean keyPressed(KeyEvent e) {
//#if >=26.3
        return gui.keyDown(SdlInput.toGlfw(e.key())) || super.keyPressed(e);
//#else
        return gui.keyDown(e.key()) || super.keyPressed(e);
//#endif
    }

    @Override public boolean charTyped(CharacterEvent e) {
        boolean used = false;
        for (char c : e.codepointAsString().toCharArray()) used |= gui.charTyped(c);
        return used;
    }

//#else
    @Override public boolean mouseClicked(double x, double y, int button) { return gui.mouseDown((float) x, (float) y, button); }

    @Override public boolean mouseReleased(double x, double y, int button) { return gui.mouseUp((float) x, (float) y, button); }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) { return gui.mouseDrag((float) x, (float) y); }

    @Override public void mouseMoved(double x, double y) { gui.mouseMoved((float) x, (float) y); }

//#if >=1.20.2
    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return gui.scroll((float) x, (float) y, (float) scrollY);
    }
//#else
    @Override public boolean mouseScrolled(double x, double y, double scroll) { return gui.scroll((float) x, (float) y, (float) scroll); }
//#endif

    @Override public boolean keyReleased(int key, int scancode, int mods) {
        gui.keyUp(key);
        return super.keyReleased(key, scancode, mods);
    }

    @Override public boolean keyPressed(int key, int scancode, int mods) { return gui.keyDown(key) || super.keyPressed(key, scancode, mods); }

    @Override public boolean charTyped(char c, int mods) { return gui.charTyped(c); }

//#endif

    @Override public boolean isPauseScreen() { return false; }

//#if >=26.1
    @Override public void removed() {
//#if >=26.3
        if (minecraft != null) minecraft.textInputManager().stopTextInput(this);
//#else
        if (minecraft != null) minecraft.textInputManager().stopTextInput();
//#endif
        gui.closed();
    }
//#else
    @Override public void removed() { gui.closed(); }
//#endif
}
