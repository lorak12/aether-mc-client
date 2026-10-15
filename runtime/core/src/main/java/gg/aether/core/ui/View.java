package gg.aether.core.ui;

import gg.aether.core.GameState;
import gg.aether.core.UiRenderer;

/**
 * A full-screen interactive page (the mod menu, the world map...). Adapters host it in their own screen class and
 * forward GUI-scaled input and GLFW key codes.
 */
public interface View {
    void render(UiRenderer r, GameState s);
    void mouseMoved(float x, float y);
    /** button: 0 left, 1 right, 2 middle. Returns true if consumed. */
    boolean mouseDown(float x, float y, int button);
    boolean mouseDrag(float x, float y);
    boolean mouseUp(float x, float y, int button);
    /** Positive = wheel up. */
    boolean scroll(float x, float y, float delta);
    boolean keyDown(int code);
    default void keyUp(int code) {}
    boolean charTyped(char c);
    /** The hosting screen went away (Esc, another screen opened...). */
    void closed();
    /** The view asks its host to close; set by the host. */
    default void setCloser(Runnable close) {}
    /** Draw the game world behind the view (false: the host fills the background). */
    default boolean transparent() { return true; }
    /** Draw vanilla's rotating title-screen panorama behind the view (main menu). */
    default boolean panorama() { return false; }
    /** Keep the menu music silent while this view is open. */
    default boolean muteMusic() { return false; }
    /** Called once per client tick while open. */
    default void tick() {}
}
