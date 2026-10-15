package gg.aether.client;

import gg.aether.mc.Gfx;
import gg.aether.core.GameState;
import gg.aether.core.Keys;
import gg.aether.core.ui.View;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.LivingEntity;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Hosts a shared {@link View} (mod menu, HUD editor, main menu) as a legacy screen. LWJGL 2 delivers input as events
 * the screen polls; keys are translated to the GLFW codes core expects.
 */
final class AetherScreen extends Screen {
    View gui() { return gui; }

    private final GameState state;
    private View gui;

    AetherScreen(GameState state) { this.state = state; }

    void setGui(View gui) { this.gui = gui; }

    @Override public void render(int mouseX, int mouseY, float tickDelta) {
        if (gui.panorama()) fillGradient(0, 0, width, height, 0xFF0E1018, 0xFF1A1E2C);
        gui.mouseMoved(mouseX, mouseY);
        gui.render(new GuiRenderer(), state);
    }

    @Override public void tick() {
        gui.tick();
        if (gui.muteMusic() && client != null) gg.aether.mc.LegacyCompat.stopMusic(client);
    }

    @Override protected void mouseClicked(int x, int y, int button) { gui.mouseDown(x, y, button); }

    @Override protected void mouseReleased(int x, int y, int button) { gui.mouseUp(x, y, button); }

    @Override protected void mouseDragged(int x, int y, int button, long held) { gui.mouseDrag(x, y); }

    /** Scroll arrives with the mouse events. */
    @Override public void handleMouse() {
        super.handleMouse();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int x = Mouse.getEventX() * width / client.width, y = height - Mouse.getEventY() * height / client.height - 1;
            gui.scroll(x, y, wheel > 0 ? 1f : -1f);
        }
    }

    /** Key releases too (vanilla screens only see presses). */
    @Override public void handleKeyboard() {
        if (Keyboard.getEventKeyState()) {
            keyPressed(Keyboard.getEventCharacter(), Keyboard.getEventKey());
        } else {
            int key = LegacyKeys.toGlfw(Keyboard.getEventKey());
            if (key >= 0) gui.keyUp(key);
        }
        client.handleKeyInput();
    }

    @Override protected void keyPressed(char c, int code) {
        int key = LegacyKeys.toGlfw(code);
        boolean used = key >= 0 && gui.keyDown(key);
        if (c >= 32 && c != 127) used |= gui.charTyped(c);
        // The main menu (like vanilla's title screen) never closes on Esc.
        if (!used && key == Keys.ESCAPE && !gui.panorama()) client.setScreen(null);
    }

    @Override public boolean shouldPauseGame() { return false; }

    @Override public void removed() {
        Keyboard.enableRepeatEvents(false);
        gui.closed();
    }

    @Override public void init() { Keyboard.enableRepeatEvents(true); }

    /** The local player standing at (x, y) (feet), {@code size} pixels per block, turned {@code yaw} degrees. */
    static void drawPlayer(int x, int y, int size, float yaw) {
        LivingEntity e = MinecraftClient.getInstance().player;
        Gfx.enableColorMaterial();
        Gfx.pushMatrix();
        Gfx.translate((float) x, (float) y, 50f);
        Gfx.scale((float) -size, (float) size, (float) size);
        Gfx.rotate(180f, 0f, 0f, 1f);
        float body = e.bodyYaw, yawWas = e.yaw, pitch = e.pitch, prevHead = e.prevHeadYaw, head = e.headYaw;
        Gfx.rotate(135f, 0f, 1f, 0f);
        DiffuseLighting.enableNormally();
        Gfx.rotate(-135f, 0f, 1f, 0f);
        Gfx.rotate(-8f, 1f, 0f, 0f);
        e.bodyYaw = yaw;
        e.yaw = yaw;
        e.pitch = 0;
        e.headYaw = yaw;
        e.prevHeadYaw = yaw;
//#if >=1.8
        EntityRenderDispatcher d = MinecraftClient.getInstance().getEntityRenderManager();
        d.setYaw(180f);
        d.setRenderShadows(false);
        gg.aether.mc.LegacyCompat.renderEntity(e);
        d.setRenderShadows(true);
//#else
        EntityRenderDispatcher d = EntityRenderDispatcher.INSTANCE;
        float dispatcherYaw = d.yaw;
        d.yaw = 180f;
        gg.aether.mc.LegacyCompat.renderEntity(e);
        d.yaw = dispatcherYaw;
//#endif
        e.bodyYaw = body;
        e.yaw = yawWas;
        e.pitch = pitch;
        e.prevHeadYaw = prevHead;
        e.headYaw = head;
        Gfx.popMatrix();
        DiffuseLighting.disable();
        Gfx.disableRescaleNormal();
        Gfx.lightmapUnitTextureOff();
    }
}
