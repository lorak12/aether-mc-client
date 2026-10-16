package gg.kite.core;

/**
 * 3D drawing in world coordinates for overlays (chunk borders, light levels, waypoints...). Implemented by the
 * version adapters; everything drawn is for the current frame only.
 */
public interface WorldRenderer {
    /** Camera (eye) position, for distance culling and label placement. */
    double cameraX();
    double cameraY();
    double cameraZ();

    void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb, float width, boolean throughWalls);

    /** Axis-aligned box; {@code strokeArgb} or {@code fillArgb} may be 0 for none. */
    void box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int strokeArgb, float width, int fillArgb, boolean throughWalls);

    /**
     * One filled face: a flat box (min == max on the axis of {@code side}) facing {@code side} ("up", "down", "north",
     * "south", "west", "east"), drawn like that face of {@link #box}. The default draws the flat box.
     */
    default void face(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, String side, int fillArgb, boolean throughWalls) {
        box(minX, minY, minZ, maxX, maxY, maxZ, 0, 1, fillArgb, throughWalls);
    }

    /** Text that always faces the camera, centred on (x, y, z). */
    void text(String text, double x, double y, double z, int argb, float scale, boolean throughWalls);
}
