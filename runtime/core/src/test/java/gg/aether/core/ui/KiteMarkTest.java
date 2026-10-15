package gg.aether.core.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class KiteMarkTest {
    @Test
    void markIsColouredOnATransparentBackground() {
        int px = 64;
        int[] p = KiteMark.pixels(px);
        assertEquals(px * px, p.length);
        assertEquals(0, p[0] >>> 24, "transparent corner");
        assertEquals(0, p[px - 1] >>> 24);
        // Upper right face (#A78BFA) and lower left face (#7C3AED), well inside.
        assertEquals(0xFFA78BFA, p[(px / 4) * px + px * 5 / 8]);
        assertEquals(0xFF7C3AED, p[(px * 5 / 8) * px + px * 3 / 8]);
        // No see-through seam where the faces meet (the vertical centre line).
        for (int y = px / 6; y < px * 5 / 6; y++) assertEquals(0xFF, p[y * px + px / 2] >>> 24, "row " + y);
        assertSame(p, KiteMark.pixels(px), "cached");
    }
}
