package gg.aether.core.cosmetics;

/** Reads a PNG's size from its header, so the registry can validate textures without decoding them. */
final class Png {
    private Png() {}

    private static final byte[] SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

    /** {width, height}, or null if {@code head} (at least 24 bytes) is not a PNG. */
    static int[] size(byte[] head) {
        if (head == null || head.length < 24) return null;
        for (int i = 0; i < SIGNATURE.length; i++) if (head[i] != SIGNATURE[i]) return null;
        if (head[12] != 'I' || head[13] != 'H' || head[14] != 'D' || head[15] != 'R') return null;
        int w = readInt(head, 16), h = readInt(head, 20);
        return w > 0 && h > 0 ? new int[] { w, h } : null;
    }

    private static int readInt(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }
}
