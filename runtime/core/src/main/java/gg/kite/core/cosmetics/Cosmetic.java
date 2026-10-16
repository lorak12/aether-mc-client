package gg.kite.core.cosmetics;

import java.io.IOException;

/**
 * One wearable item: a cape (texture only, drawn by the game's cape layer, any resolution with the 64x32 layout) or a
 * model. Textures may be animated as a vertical strip of equally sized frames.
 */
public final class Cosmetic {
    public enum Rarity {
        COMMON(0xFF9AA4B8), RARE(0xFF4FA3FF), EPIC(0xFFA78BFA), LEGENDARY(0xFFFFB443);

        public final int color;

        Rarity(int color) { this.color = color; }

        static Rarity byId(String id) {
            for (Rarity r : values()) if (r.name().equalsIgnoreCase(id)) return r;
            return COMMON;
        }
    }

    /** Where the texture PNG comes from (jar resource or file); read on demand by the adapter. */
    public interface TextureSource {
        byte[] read() throws IOException;
    }

    public final String id, name, description;
    public final Slot slot;
    public final Rarity rarity;
    /** Shipped with the client: other players have it too, so it is synced. Local cosmetics only show for you. */
    public final boolean builtin;
    /** Null for capes. */
    public final CosmeticModel model;
    /** Full PNG size (all frames). */
    public final int textureWidth, textureHeight;
    public final int frames, frameTimeMs;
    /** Models: blend semi-transparent pixels (fairy wings) instead of the default cutout. */
    public final boolean translucent;
    /** Thumbnail region of one frame, 0..1: u0, v0, u1, v1 (used when there is no icon image). */
    public final float[] icon;
    private final TextureSource texture;
    /** A pre-rendered thumbnail PNG, or null. */
    private final TextureSource iconImage;

    Cosmetic(String id, String name, String description, Slot slot, Rarity rarity, boolean builtin, CosmeticModel model,
             int textureWidth, int textureHeight, int frames, int frameTimeMs, boolean translucent, float[] icon, TextureSource texture,
             TextureSource iconImage) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.slot = slot;
        this.rarity = rarity;
        this.builtin = builtin;
        this.model = model;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.frames = Math.max(1, frames);
        this.frameTimeMs = Math.max(10, frameTimeMs);
        this.translucent = translucent;
        this.icon = icon;
        this.texture = texture;
        this.iconImage = iconImage;
    }

    public boolean animated() { return frames > 1; }

    /** Height of one frame in pixels. */
    public int frameHeight() { return textureHeight / frames; }

    /** Frame shown at {@code millis} (any clock; frames loop). */
    public int frame(long millis) { return frames <= 1 ? 0 : (int) ((millis / frameTimeMs) % frames); }

    public byte[] readTexture() throws IOException { return texture.read(); }

    public boolean hasIconImage() { return iconImage != null; }

    /** The thumbnail PNG; only when {@link #hasIconImage()}. */
    public byte[] readIconImage() throws IOException {
        if (iconImage == null) throw new IOException("no icon image");
        return iconImage.read();
    }

    /** Stable key for the adapter's texture cache (local and built-in ids never collide). */
    public String textureKey() { return (builtin ? "builtin/" : "") + id; }

    @Override public String toString() { return id; }
}
