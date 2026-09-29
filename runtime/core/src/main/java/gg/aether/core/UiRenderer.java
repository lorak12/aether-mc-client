package gg.aether.core;

/**
 * Backend-agnostic 2D drawing surface. Implemented per rendering backend (OpenGL on legacy versions,
 * Blaze3D pipelines on 26.x) so mods and UI never touch GL/Vulkan directly.
 */
public interface UiRenderer {
    int screenWidth();
    int screenHeight();

    void rect(float x, float y, float w, float h, int argb, float cornerRadius);
    void text(String s, float x, float y, int argb, boolean shadow);
    int textWidth(String s);
    int fontHeight();

    /** Draw an item icon (adapter resolves the id in its own registry). */
    void item(String itemId, float x, float y);

    void push(float translateX, float translateY, float scale);
    void pop();
}
