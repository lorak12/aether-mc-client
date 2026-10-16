package gg.kite.core.cosmetics;

/**
 * Quads of a cosmetic model for one frame, grouped by the player bone they follow. Positions are in model pixels
 * (1/16 block) relative to the bone's pivot, Y pointing down like vanilla models; UVs are 0..1. The adapter poses
 * each bone like the player model, scales by 1/16 and emits the vertices.
 */
public final class Mesh {
    /** Floats per quad: normal (x, y, z), then four vertices of (x, y, z, u, v). */
    public static final int STRIDE = 3 + 4 * 5;

    /** One bone's quads for one render layer. */
    public static final class Layer {
        private float[] data = new float[STRIDE * 8];
        private int quads;

        public int quads() { return quads; }
        /** Read-only view; valid for {@link #quads()} * {@link #STRIDE} floats. */
        public float[] data() { return data; }

        float[] next() {
            if ((quads + 1) * STRIDE > data.length) {
                float[] grown = new float[data.length * 2];
                System.arraycopy(data, 0, grown, 0, quads * STRIDE);
                data = grown;
            }
            quads++;
            return data;
        }
    }

    private final Layer[] normal = new Layer[CosmeticModel.Bone.values().length];
    private final Layer[] glow = new Layer[CosmeticModel.Bone.values().length];

    /** Quads following {@code bone}; {@code glowing} ones are drawn full-bright. Null if there are none. */
    public Layer layer(CosmeticModel.Bone bone, boolean glowing) { return (glowing ? glow : normal)[bone.ordinal()]; }

    public boolean isEmpty() {
        for (int i = 0; i < normal.length; i++) if (normal[i] != null || glow[i] != null) return false;
        return true;
    }

    Layer layerFor(CosmeticModel.Bone bone, boolean glowing) {
        Layer[] arr = glowing ? glow : normal;
        Layer l = arr[bone.ordinal()];
        if (l == null) arr[bone.ordinal()] = l = new Layer();
        return l;
    }
}
