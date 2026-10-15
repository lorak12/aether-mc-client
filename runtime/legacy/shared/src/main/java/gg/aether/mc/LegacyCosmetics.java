package gg.aether.mc;

import gg.aether.client.AetherClient;
import gg.aether.core.cosmetics.Cosmetic;
import gg.aether.core.cosmetics.CosmeticModel;
import gg.aether.core.cosmetics.Cosmetics;
import gg.aether.core.cosmetics.Mesh;
import gg.aether.core.cosmetics.Slot;
import gg.aether.core.cosmetics.WavyCape;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.model.BiPedModel;
import net.minecraft.client.render.model.ModelPart;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

/**
 * Cosmetics on legacy OpenGL (1.7.10 - 1.12.2): the same models, capes (cosmetic and wavy) and badge as the modern
 * adapters, drawn as immediate-mode quads on the posed player model. Textures are decoded off-thread (one image per
 * animation frame) and registered as dynamic textures on first use.
 */
public final class LegacyCosmetics {
    private LegacyCosmetics() {}

    private static final int FULL_BRIGHT = 0xF000F0;

    // ---- textures ---------------------------------------------------------------------------------------------------

    /** One cosmetic's texture (all animation frames) or its menu icon. */
    private static final class Tex {
        final Cosmetic owner;
        final boolean icon;
        volatile int[][] decoded;
        volatile boolean failed;
        boolean reported;
        Identifier[] ids;
        int width, height;
        long lastUsed;

        Tex(Cosmetic owner, boolean icon) {
            this.owner = owner;
            this.icon = icon;
        }

        String key() { return owner.textureKey() + (icon ? "#icon" : ""); }
    }

    private static final Map<String, Tex> TEXTURES = new HashMap<String, Tex>();
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aether-cosmetic-textures");
        t.setDaemon(true);
        return t;
    });
    private static final long IDLE_MS = 10 * 60_000;
    private static long lastSweep;

    /** Texture of {@code c}'s current animation frame; null while it loads or if it failed. Render thread. */
    public static Identifier texture(Cosmetic c) {
        Tex t = ready(c, false);
        return t == null ? null : t.ids[Math.min(t.ids.length - 1, c.frame(System.currentTimeMillis()))];
    }

    /** A menu thumbnail: {@code (u, v, w, h)} of texture {@code id} ({@code texW x texH}). */
    public static final class Icon {
        public final Identifier id;
        public final int u, v, w, h, texW, texH;

        Icon(Identifier id, int u, int v, int w, int h, int texW, int texH) {
            this.id = id;
            this.u = u;
            this.v = v;
            this.w = w;
            this.h = h;
            this.texW = texW;
            this.texH = texH;
        }
    }

    public static Icon icon(Cosmetic c) {
        if (c.hasIconImage()) {
            Tex t = ready(c, true);
            return t == null ? null : new Icon(t.ids[0], 0, 0, t.width, t.height, t.width, t.height);
        }
        Identifier id = texture(c);
        if (id == null) return null;
        int tw = c.textureWidth, th = c.frameHeight();
        float[] r = c.icon;
        return new Icon(id, Math.round(r[0] * tw), Math.round(r[1] * th), Math.round((r[2] - r[0]) * tw), Math.round((r[3] - r[1]) * th), tw, th);
    }

    private static Tex ready(Cosmetic c, boolean icon) {
        long now = System.currentTimeMillis();
        if (now - lastSweep > 30_000) sweep(now);
        String key = c.textureKey() + (icon ? "#icon" : "");
        Tex t = TEXTURES.get(key);
        if (t != null && t.owner != c) { // the catalog was reloaded
            release(t);
            TEXTURES.remove(key);
            t = null;
        }
        if (t == null) {
            final Tex fresh = new Tex(c, icon);
            TEXTURES.put(key, fresh);
            fresh.lastUsed = now;
            DECODER.execute(() -> decode(fresh));
            return null;
        }
        t.lastUsed = now;
        if (t.failed) {
            if (!t.reported) {
                t.reported = true;
                Log.warn("Cosmetic " + c.name + ": " + (icon ? "icon" : "texture") + " failed to load");
            }
            return null;
        }
        if (t.ids == null) {
            int[][] frames = t.decoded;
            if (frames == null) return null;
            upload(t, frames);
        }
        return t;
    }

    private static void decode(Tex t) {
        Cosmetic c = t.owner;
        try {
            byte[] encoded = t.icon ? c.readIconImage() : c.readTexture();
            BufferedImage full = ImageIO.read(new ByteArrayInputStream(encoded));
            if (full == null) throw new java.io.IOException("not an image");
            int frames = t.icon ? 1 : c.frames, w = full.getWidth(), fh = full.getHeight() / frames;
            int[][] out = new int[frames][];
            for (int f = 0; f < frames; f++) {
                int[] px = new int[w * fh];
                full.getRGB(0, f * fh, w, fh, px, 0, w);
                out[f] = px;
            }
            t.width = w;
            t.height = fh;
            t.decoded = out;
        } catch (Exception | LinkageError e) {
            t.failed = true;
        }
    }

    private static void upload(Tex t, int[][] frames) {
        Identifier[] ids = new Identifier[frames.length];
        for (int i = 0; i < frames.length; i++) ids[i] = McTextures.prepare("cosmetic/" + t.key() + "#" + i, t.width, t.height, frames[i], 1L);
        t.ids = ids;
        t.decoded = null;
    }

    private static void release(Tex t) {
        t.failed = true;
        t.decoded = null;
    }

    private static void sweep(long now) {
        lastSweep = now;
        for (Iterator<Tex> it = TEXTURES.values().iterator(); it.hasNext(); ) {
            Tex t = it.next();
            if (now - t.lastUsed < IDLE_MS) continue;
            it.remove();
            release(t);
        }
    }

    // ---- what a player wears ----------------------------------------------------------------------------------------

    private static Cosmetics.Equipped equipped(AbstractClientPlayerEntity p) {
        return AetherClient.mods().cosmetics().forPlayer(p.getUuid().toString());
    }

    private static boolean hidden(Slot slot, boolean helmet, boolean footwear, boolean elytra) {
        switch (slot) {
            case HAT: case EARS: return helmet;
            case BOOTS: return footwear;
            case WINGS: return elytra;
            default: return false;
        }
    }

    /** The cosmetic cape's texture for {@code p}, or {@code vanilla} when they wear none (or an elytra). */
    public static Identifier capeTexture(AbstractClientPlayerEntity p, Identifier vanilla) {
        Cosmetic cape = equipped(p).get(Slot.CAPE);
        if (cape == null || wearsElytra(p)) return vanilla;
        Identifier tex = texture(cape);
        return tex == null ? vanilla : tex;
    }

    /** True when {@code p} wears a cosmetic cape (the player then has a cape to draw even without a Mojang one). */
    public static boolean hasCosmeticCape(AbstractClientPlayerEntity p) {
        return equipped(p).get(Slot.CAPE) != null && !wearsElytra(p);
    }

    private static boolean wearsElytra(AbstractClientPlayerEntity p) { return LegacyCompat.wearsElytra(p); }

    // ---- drawing ---------------------------------------------------------------------------------------------------

    private static ModelPart bone(BiPedModel m, CosmeticModel.Bone b) {
        switch (b) {
            case HEAD: return m.head;
            case RIGHT_ARM: return m.rightArm;
            case LEFT_ARM: return m.leftArm;
            case RIGHT_LEG: return m.rightLeg;
            case LEFT_LEG: return m.leftLeg;
            default: return m.body;
        }
    }

    /**
     * The worn models on the posed model of {@code p}. {@code limb} is the limb swing amount (0 standing .. 1 running),
     * {@code age} the entity's age in ticks.
     */
    public static void draw(AbstractClientPlayerEntity p, BiPedModel model, float limb, float tickDelta, float age) {
        Cosmetics.Equipped eq = equipped(p);
        if (eq.isEmpty() || p.isInvisible()) return;
        boolean armor = AetherClient.mods().cosmetics().hideUnderArmor();
        boolean helmet = armor && LegacyCompat.armor(p, 3) != null, footwear = armor && LegacyCompat.armor(p, 0) != null, elytra = wearsElytra(p);
        CosmeticModel.Motion motion = new CosmeticModel.Motion(age, limb, p.abilities.flying);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        Gfx.disableCull();
        Gfx.color(1f, 1f, 1f, 1f);
        for (Cosmetic c : eq.all()) {
            if (c.model == null || hidden(c.slot, helmet, footwear, elytra)) continue;
            Identifier tex = texture(c);
            if (tex == null || !McTextures.bind(tex)) continue;
            Mesh mesh = c.model.mesh(motion);
            for (CosmeticModel.Bone b : CosmeticModel.Bone.values()) {
                Mesh.Layer base = mesh.layer(b, false), glow = mesh.layer(b, true);
                if (base == null && glow == null) continue;
                Gfx.pushMatrix();
                bone(model, b).preRender(0.0625f);
                if (base != null) {
                    if (c.translucent) {
                        Gfx.enableBlend();
                        Gfx.blendFunc(770, 771);
                    }
                    emit(base);
                    if (c.translucent) Gfx.disableBlend();
                }
                if (glow != null) {
                    // Emissive: full light, no shading; the entity's own light back afterwards.
                    Gfx.disableLighting();
                    Gfx.lightmap(FULL_BRIGHT);
                    Gfx.enableBlend();
                    Gfx.blendFunc(770, 771);
                    emit(glow);
                    Gfx.disableBlend();
                    Gfx.lightmap(LegacyCompat.lightmap(p, tickDelta));
                    Gfx.enableLighting();
                }
                Gfx.popMatrix();
            }
        }
        if (cull) Gfx.enableCull();
    }

    private static void emit(Mesh.Layer l) {
        float[] d = l.data();
        Gfx.beginModel();
        for (int q = 0; q < l.quads(); q++) {
            int o = q * Mesh.STRIDE;
            float nx = d[o], ny = d[o + 1], nz = d[o + 2];
            for (int k = 0; k < 4; k++) {
                int v = o + 3 + k * 5;
                Gfx.modelVertex(d[v] / 16f, d[v + 1] / 16f, d[v + 2] / 16f, d[v + 3], d[v + 4], nx, ny, nz);
            }
        }
        Gfx.draw();
    }

    // ---- wavy capes ------------------------------------------------------------------------------------------------

    /** Each player's cloth, kept across frames; dropped a minute after it was last drawn. */
    private static final Map<UUID, WavyCape.Sim> CAPE_SIMS = new HashMap<UUID, WavyCape.Sim>();
    private static final long CLOCK_START = System.nanoTime();
    private static double lastCapeSweep;

    private static double capeClock() { return (System.nanoTime() - CLOCK_START) / 5e7; }

    /** The cape texture to draw as cloth instead of vanilla's cape, or null to leave the cape to vanilla. */
    public static Identifier wavyCape(AbstractClientPlayerEntity p) {
        if (!AetherClient.mods().cosmetics().wavyCapes() || p.isInvisible() || !LegacyCompat.capeVisible(p) || wearsElytra(p)) return null;
        return LegacyCompat.capeTexture(p);
    }

    /** Draw {@code p}'s cape as cloth, if it should be; true when it was drawn (vanilla's cape is skipped then). */
    public static boolean drawWavyCape(AbstractClientPlayerEntity p, BiPedModel model, float tickDelta, float age) {
        Identifier tex = wavyCape(p);
        if (tex == null || !McTextures.bind(tex)) return false;
        // The inputs vanilla's cape layer turns into its rotations.
        double d0 = p.capeX + (p.prevCapeX - p.capeX) * tickDelta - (p.prevX + (p.x - p.prevX) * tickDelta);
        double d1 = p.capeY + (p.prevCapeY - p.capeY) * tickDelta - (p.prevY + (p.y - p.prevY) * tickDelta);
        double d2 = p.capeZ + (p.prevCapeZ - p.capeZ) * tickDelta - (p.prevZ + (p.z - p.prevZ) * tickDelta);
        float bodyYaw = p.prevBodyYaw + (p.bodyYaw - p.prevBodyYaw) * tickDelta;
        double sin = Math.sin(bodyYaw * Math.PI / 180.0), cos = -Math.cos(bodyYaw * Math.PI / 180.0);
        float flap = Math.max(-6f, Math.min(32f, (float) d1 * 10f));
        float lean = (float) (d0 * sin + d2 * cos) * 100f;
        float lean2 = (float) (d0 * cos - d2 * sin) * 100f;
        if (lean < 0f) lean = 0f;
        flap += Math.sin((p.prevHorizontalSpeed + (p.horizontalSpeed - p.prevHorizontalSpeed) * tickDelta) * 6f)
                * 32f * (p.prevStrideDistance + (p.strideDistance - p.prevStrideDistance) * tickDelta);
        if (p.isSneaking()) flap += 25f;

        double now = capeClock();
        if (now - lastCapeSweep > 600) {
            lastCapeSweep = now;
            for (Iterator<WavyCape.Sim> it = CAPE_SIMS.values().iterator(); it.hasNext(); ) if (!(now - it.next().time() < 1200)) it.remove();
        }
        WavyCape.Sim sim = CAPE_SIMS.get(p.getUuid());
        if (sim == null) CAPE_SIMS.put(p.getUuid(), sim = new WavyCape.Sim());
        Mesh mesh = sim.mesh(now, new WavyCape.Motion(age, flap, lean, lean2, bodyYaw));
        Mesh.Layer cloth = mesh.layer(CosmeticModel.Bone.BODY, false);
        if (cloth == null) return true;
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        Gfx.disableCull();
        Gfx.color(1f, 1f, 1f, 1f);
        Gfx.pushMatrix();
        model.body.preRender(0.0625f);
        emit(cloth);
        Gfx.popMatrix();
        if (cull) Gfx.enableCull();
        return true;
    }

    // ---- badge ------------------------------------------------------------------------------------------------------

    /** {@code name} with the client badge in front when {@code uuid} runs the client. */
    public static String badge(UUID uuid, String name) {
        if (name == null || uuid == null || !AetherClient.mods().cosmetics().badge(uuid.toString())) return name;
        return "§9" + Cosmetics.BADGE + " §r" + name;
    }
}
