package gg.aether.core;

/**
 * Backend-agnostic 2D drawing surface. Implemented per rendering backend (OpenGL on legacy versions,
 * Blaze3D pipelines on 26.x) so mods and UI never touch GL/Vulkan directly.
 */
public interface UiRenderer {
    /** Typefaces for {@link #font}: Minecraft's own, the UI face (Inter) and the display face (Sora). */
    enum Font { VANILLA, UI, DISPLAY }

    int screenWidth();
    int screenHeight();

    void rect(float x, float y, float w, float h, int argb, float cornerRadius);
    void text(String s, float x, float y, int argb, boolean shadow);
    int textWidth(String s);
    int fontHeight();

    /** Draw an item icon (adapter resolves the id in its own registry). */
    void item(String itemId, float x, float y);

    /**
     * Font used by later {@link #text}/{@link #textWidth} calls on this renderer. Adapters without bundled fonts ignore
     * it (vanilla font). Glyphs missing from a face fall back to Minecraft's.
     */
    default void font(Font f) {}

    /**
     * Draw a whole texture from the client's resources ("aether:textures/gui/logo.png") into (x, y, w, h).
     * False if unsupported or missing.
     */
    default boolean texture(String id, float x, float y, float w, float h) { return false; }

    /** Start a new layer: everything drawn after it (popups) covers everything before, text included. */
    default void layer() {}

    void push(float translateX, float translateY, float scale);
    void pop();

    /** Only draw inside this rectangle (in the current {@link #push} transform, like drawing) until {@link #unclip}. Not nestable. */
    default void clip(float x, float y, float w, float h) {}
    default void unclip() {}

    /** Draw a player's face (skin head + hat layer) from the tab list; false if unknown. */
    default boolean head(String playerName, float x, float y, int size) { return false; }

    /**
     * Draw an account's face (skin head + hat layer) by uuid, also outside a world and for accounts that aren't signed
     * in; false while it is unknown (looked up online on first use).
     */
    default boolean head(String playerName, String uuid, float x, float y, float size) { return head(playerName, x, y, Math.round(size)); }

    /**
     * Draw an ARGB image of {@code w x h} pixels into (x, y, dw, dh). {@code key} names a texture the adapter keeps;
     * pixels are only re-uploaded when {@code version} changes. Returns false if images are not supported.
     */
    default boolean image(String key, int w, int h, int[] argb, long version, float x, float y, float dw, float dh) { return false; }

    /** {@link #image} multiplied by {@code tint} (ARGB; its alpha fades the image). Untinted when unsupported. */
    default boolean image(String key, int w, int h, int[] argb, long version, float x, float y, float dw, float dh, int tint) {
        return image(key, w, h, argb, version, x, y, dw, dh);
    }

    /** Real screen pixels per unit at the current transform (GUI scale times any {@link #push} scale). */
    default float pixelScale() { return 1f; }

    /**
     * Draw the local player (with cosmetics) standing in the box (x, y, w, h), turned {@code yaw} degrees (0 = facing
     * the viewer, 180 = showing the back). Outside a world it is the signed-in account's skin. False if unsupported.
     */
    default boolean player(float x, float y, float w, float h, float yaw) { return false; }

    /**
     * {@link #player(float, float, float, float, float)} seen from {@code pitch} degrees above (negative: from below,
     * -90..90). Renderers without tilt support ignore it.
     */
    default boolean player(float x, float y, float w, float h, float yaw, float pitch) { return player(x, y, w, h, yaw); }

    /**
     * Draw a cosmetic's thumbnail (its icon image, or its texture's icon region in the current animation frame) fitted
     * and centred in (x, y, w, h). False while it is still loading or when unsupported.
     */
    default boolean cosmeticIcon(gg.aether.core.cosmetics.Cosmetic c, float x, float y, float w, float h) { return false; }
}
