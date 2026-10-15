package gg.aether.mc;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import gg.aether.core.cosmetics.Cosmetic;
import gg.aether.core.cosmetics.CosmeticModel;
import gg.aether.core.cosmetics.Cosmetics;
import gg.aether.core.cosmetics.Mesh;
import gg.aether.core.cosmetics.Slot;
import gg.aether.core.cosmetics.WavyCape;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//#if >=1.21.2
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
//#endif
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
//#if >=1.21.9
import net.minecraft.core.ClientAsset;
//#endif
//#if >=1.21.2
import net.minecraft.core.component.DataComponents;
//#endif
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
//#if >=1.20.2
import net.minecraft.world.entity.player.PlayerSkin;
//#endif
import net.minecraft.world.item.Items;
//#if >=1.21.2
import net.minecraft.world.item.equipment.Equippable;
//#endif

/**
 * Draws cosmetics on players. Cosmetic capes replace the skin's cape texture, so vanilla's cape layer uses them (not
 * under an elytra: that keeps its own texture); with Wavy capes on, every cape is drawn as cloth ({@link WavyCape})
 * by {@link CosmeticLayer} instead, one simulation per player. Models
 * are emitted by {@link CosmeticLayer} on the posed player model. Textures are decoded off-thread, split into one
 * texture per animation frame and uploaded on first use.
 */
public final class McCosmetics {
    private McCosmetics() {}

    private static final int FULL_BRIGHT = 0xF000F0;

    // ---- textures ---------------------------------------------------------------------------------------------------

    /** One cosmetic's texture (all animation frames) or its menu icon. */
    private static final class Tex {
        final Cosmetic owner;
        final boolean icon;
        volatile NativeImage[] decoded;
        volatile boolean failed, dropped;
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
    /** Textures unused this long are released (other players' capes come and go). */
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

    /** {@code c}'s icon image, or the icon region of its current texture frame; null while loading. */
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

    /** The uploaded texture, starting the decode on first use; null until then. */
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
                Compat.mods().notifications().info(now, "Cosmetic " + c.name + ": " + (icon ? "icon" : "texture") + " failed to load");
            }
            return null;
        }
        if (t.ids == null) {
            NativeImage[] frames = t.decoded;
            if (frames == null) return null;
            upload(t, frames);
        }
        return t;
    }

    private static void decode(Tex t) {
        Cosmetic c = t.owner;
        try {
            byte[] encoded = t.icon ? c.readIconImage() : c.readTexture();
//#if <1.20
            // Older NativeImage.read(byte[]) allocates the entire encoded image on LWJGL's tiny thread-local stack.
            java.nio.ByteBuffer bytes = org.lwjgl.system.MemoryUtil.memAlloc(encoded.length);
            NativeImage full;
            try {
                bytes.put(encoded).flip();
                full = NativeImage.read(bytes);
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(bytes);
            }
//#else
            NativeImage full = NativeImage.read(encoded);
//#endif
            int frames = t.icon ? 1 : c.frames, w = full.getWidth(), fh = full.getHeight() / frames;
            NativeImage[] out = new NativeImage[frames];
            if (frames == 1) {
                out[0] = full;
            } else {
                for (int f = 0; f < frames; f++) {
                    NativeImage img = new NativeImage(w, fh, false);
                    for (int y = 0; y < fh; y++) for (int x = 0; x < w; x++) img.setPixel(x, y, full.getPixel(x, f * fh + y));
                    out[f] = img;
                }
                full.close();
            }
            t.decoded = out;
            if (t.dropped) close(t); // released while decoding
        } catch (Exception | LinkageError e) {
            t.failed = true;
        }
    }

    private static void upload(Tex t, NativeImage[] frames) {
        Minecraft mc = Minecraft.getInstance();
        String base = "cosmetics/" + McTextures.idPath(t.key()).substring("dynamic/".length());
        t.width = frames[0].getWidth();
        t.height = frames[0].getHeight();
        Identifier[] ids = new Identifier[frames.length];
        for (int i = 0; i < frames.length; i++) {
            final String label = "aether cosmetic " + t.key() + " #" + i;
            ids[i] = Identifier.fromNamespaceAndPath("aether", base + "/" + i);
            mc.getTextureManager().register(ids[i], Compat.dynamicTexture(label, frames[i]));
        }
        t.ids = ids;
        t.decoded = null;
    }

    /** Free a texture's GPU copies and any decoded images not uploaded yet. */
    private static void release(Tex t) {
        t.dropped = true;
        if (t.ids != null) for (Identifier id : t.ids) Minecraft.getInstance().getTextureManager().release(id);
        close(t);
    }

    /** Decoder and render thread may both get here for the same texture. */
    private static void close(Tex t) {
        NativeImage[] pending;
        synchronized (t) {
            pending = t.decoded;
            t.decoded = null;
        }
        if (pending != null) for (NativeImage img : pending) img.close();
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

    // ---- players ----------------------------------------------------------------------------------------------------

    /** What a player wears this frame, remembered per render state for the layer. */
    private static final class Worn {
        final Cosmetics.Equipped items;
        final boolean flying, helmet, footwear, elytra;

        Worn(Cosmetics.Equipped items, boolean flying, boolean helmet, boolean footwear, boolean elytra) {
            this.items = items;
            this.flying = flying;
            this.helmet = helmet;
            this.footwear = footwear;
            this.elytra = elytra;
        }
    }

    private static final Map<AvatarRenderState, Worn> WORN = new WeakHashMap<AvatarRenderState, Worn>();

    /** AvatarRenderer.extractRenderState tail: look up the player's cosmetics and put their cape on the skin. */
    public static void extract(Object avatar, AvatarRenderState state) {
        trackCape(avatar, state);
        if (!(avatar instanceof AbstractClientPlayer)) {
            WORN.remove(state);
            return;
        }
        AbstractClientPlayer p = (AbstractClientPlayer) avatar;
        dress(state, Compat.mods().cosmetics().forPlayer(p.getUUID().toString()), state.isFallFlying || p.getAbilities().flying);
    }

    /** Remember {@code eq} for the layer and put its cape on the state's skin. */
    private static void dress(AvatarRenderState state, Cosmetics.Equipped eq, boolean flying) {
        if (eq.isEmpty()) {
            WORN.remove(state);
            return;
        }
        boolean armor = Compat.mods().cosmetics().hideUnderArmor();
        WORN.put(state, new Worn(eq, flying, armor && !state.headEquipment.isEmpty(),
                armor && !state.feetEquipment.isEmpty(), wearsElytra(state)));
        Cosmetic cape = eq.get(Slot.CAPE);
        // Under an elytra the cosmetic cape is left off entirely: vanilla hides the cape, and the elytra keeps its own
        // texture (or a Mojang cape's, as in vanilla) instead of being painted with the cosmetic.
//#if >=1.21.2
        Identifier tex = cape == null || wearsElytra(state) ? null : texture(cape);
        if (tex != null && state.skin != null) {
            PlayerSkin s = state.skin;
//#if >=1.21.9
            ClientAsset.Texture t = new ClientAsset.ResourceTexture(tex, tex);
            state.skin = new PlayerSkin(s.body(), t, s.elytra(), s.model(), s.secure());
//#else
            state.skin = new PlayerSkin(s.texture(), s.textureUrl(), tex, s.elytraTexture(), s.model(), s.secure());
//#endif
            state.showCape = true;
        }
//#endif
    }

//#if <1.21.2
    /**
     * Before 1.21.2 vanilla's cape (and elytra) layers read the cape from the player's skin, so the cosmetic cape goes in
     * there (AbstractClientPlayerMixin). Null: no cosmetic cape, or an elytra is worn (it keeps its own texture).
     */
    public static Identifier capeTexture(AbstractClientPlayer p) {
        Cosmetic cape = Compat.mods().cosmetics().forPlayer(p.getUUID().toString()).get(Slot.CAPE);
        if (cape == null || p.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) return null;
        return texture(cape);
    }
//#endif

    private static boolean hidden(Slot slot, Worn w) {
        switch (slot) {
            case HAT: case EARS: return w.helmet;
            case BOOTS: return w.footwear;
            case WINGS: return w.elytra;
            default: return false;
        }
    }

    /** {@link CosmeticLayer}: emit every worn model on its bones. */
    static void submit(PoseStack pose, SubmitNodeCollector nodes, final int light, AvatarRenderState state, PlayerModel model) {
        submitCape(pose, nodes, light, state, model);
        Worn w = WORN.get(state);
        if (w == null || state.isInvisible) return;
//#if >=1.21.2
        final int overlay = LivingEntityRenderer.getOverlayCoords(state, 0f);
//#else
        final int overlay = LivingEntityRenderer.getOverlayCoords(state.entity, 0f);
//#endif
        CosmeticModel.Motion motion = new CosmeticModel.Motion(state.ageInTicks, state.walkAnimationSpeed, w.flying);
        for (Cosmetic c : w.items.all()) {
            if (c.model == null || hidden(c.slot, w)) continue;
            Identifier tex = texture(c);
            if (tex == null) continue;
            Mesh mesh = c.model.mesh(motion);
            for (CosmeticModel.Bone b : CosmeticModel.Bone.values()) {
                final Mesh.Layer base = mesh.layer(b, false), glow = mesh.layer(b, true);
                if (base == null && glow == null) continue;
                pose.pushPose();
                bone(model, b).translateAndRotate(pose);
                if (base != null) {
                    geometry(nodes, pose, c.translucent ? RenderTypes.entityTranslucent(tex) : Compat.cutoutNoCull(tex), (p, vc) -> emit(p, vc, base, light, overlay));
                }
//#if >=1.19
                if (glow != null) geometry(nodes, pose, RenderTypes.entityTranslucentEmissive(tex), (p, vc) -> emit(p, vc, glow, FULL_BRIGHT, overlay));
//#else
                if (glow != null) geometry(nodes, pose, RenderTypes.eyes(tex), (p, vc) -> emit(p, vc, glow, FULL_BRIGHT, overlay));
//#endif
                pose.popPose();
            }
        }
    }

    // ---- wavy capes ------------------------------------------------------------------------------------------------

    /**
     * The cape texture to draw as cloth instead of vanilla's cape, or null to leave the cape to vanilla. Same checks as
     * vanilla's CapeLayer; {@code CapeLayerMixin} skips vanilla's cape whenever this is non-null.
     */
    public static Identifier wavyCape(AvatarRenderState state) {
//#if >=1.21.9
        if (state.isInvisible || !state.showCape || state.skin == null || state.skin.cape() == null) return null;
//#elif >=1.20.2
        if (state.isInvisible || !state.showCape || state.skin == null || state.skin.capeTexture() == null) return null;
//#else
        if (state.isInvisible || !state.showCape || !state.entity.isCapeLoaded() || state.entity.getCloakTextureLocation() == null) return null;
//#endif
        if (!Compat.mods().cosmetics().wavyCapes()) return null;
        if (wearsElytra(state)) return null; // vanilla's cape layer hides the cape under an elytra
//#if >=1.21.9
        return state.skin.cape().texturePath();
//#elif >=1.20.2
        return state.skin.capeTexture();
//#else
        return state.entity.getCloakTextureLocation();
//#endif
    }

    /** An elytra (or any glider) in the chest slot: it replaces the cape, so no cape and no cosmetic cape texture. */
    private static boolean wearsElytra(AvatarRenderState state) {
//#if >=1.21.2
        return state.chestEquipment.getItem() == Items.ELYTRA || state.chestEquipment.get(DataComponents.GLIDER) != null;
//#else
        return state.chestEquipment.getItem() == Items.ELYTRA;
//#endif
    }

    /** One player's cloth this frame: its simulation and the body yaw at extraction (previews turn the state after). */
    private static final class CapeInput {
        final WavyCape.Sim sim;
        final float bodyYaw;

        CapeInput(WavyCape.Sim sim, float bodyYaw) {
            this.sim = sim;
            this.bodyYaw = bodyYaw;
        }
    }

    /** Each player's cloth, kept across frames; dropped a minute after it was last drawn. */
    private static final Map<UUID, WavyCape.Sim> CAPE_SIMS = new HashMap<UUID, WavyCape.Sim>();
    private static final Map<AvatarRenderState, CapeInput> CAPES = new WeakHashMap<AvatarRenderState, CapeInput>();
    private static final long CLOCK_START = System.nanoTime();
    private static double lastCapeSweep;

    /** A steady clock for the cloth, in ticks (50 ms) of real time. */
    private static double capeClock() { return (System.nanoTime() - CLOCK_START) / 5e7; }

    /** At extraction: tie the render state to the entity's cloth simulation. */
    private static void trackCape(Object avatar, AvatarRenderState state) {
        if (!(avatar instanceof Entity) || !Compat.mods().cosmetics().wavyCapes()) {
            CAPES.remove(state);
            return;
        }
        double now = capeClock();
        if (now - lastCapeSweep > 600) {
            lastCapeSweep = now;
            CAPE_SIMS.values().removeIf(sim -> !(now - sim.time() < 1200));
        }
        WavyCape.Sim sim = CAPE_SIMS.computeIfAbsent(((Entity) avatar).getUUID(), id -> new WavyCape.Sim());
        CAPES.put(state, new CapeInput(sim, state.bodyRot));
    }

    private static void submitCape(PoseStack pose, SubmitNodeCollector nodes, final int light, AvatarRenderState state, PlayerModel model) {
        Identifier tex = wavyCape(state);
        if (tex == null) return;
        CapeInput in = CAPES.get(state);
        WavyCape.Motion motion = new WavyCape.Motion(state.ageInTicks, state.capeFlap, state.capeLean, state.capeLean2,
                in == null ? state.bodyRot : in.bodyYaw);
        Mesh mesh = in == null ? WavyCape.mesh(motion) : in.sim.mesh(capeClock(), motion);
        final Mesh.Layer cloth = mesh.layer(CosmeticModel.Bone.BODY, false);
        pose.pushPose();
        // Vanilla moves the cape out over a chestplate.
//#if >=1.21.2
        Equippable chest = state.chestEquipment.get(DataComponents.EQUIPPABLE);
        if (chest != null && chest.slot() == EquipmentSlot.CHEST && chest.assetId().isPresent()) pose.translate(0f, -0.053125f, 0.06875f);
//#else
        if (!state.chestEquipment.isEmpty()) pose.translate(0f, -0.053125f, 0.06875f);
//#endif
        model.body.translateAndRotate(pose);
        geometry(nodes, pose, Compat.cutoutNoCull(tex), (p, vc) -> emit(p, vc, cloth, light, OverlayTexture.NO_OVERLAY));
        pose.popPose();
    }

    private interface Geometry { void emit(PoseStack.Pose pose, VertexConsumer vc); }

    /** Custom geometry in the current pose: queued as a submit node (1.21.9+), drawn into the buffers before. */
    private static void geometry(SubmitNodeCollector nodes, PoseStack pose, net.minecraft.client.renderer.rendertype.RenderType type, Geometry g) {
//#if >=1.21.9
        nodes.submitCustomGeometry(pose, type, g::emit);
//#else
        g.emit(pose.last(), nodes.getBuffer(type));
//#endif
    }

    private static ModelPart bone(PlayerModel m, CosmeticModel.Bone b) {
        switch (b) {
            case HEAD: return m.head;
            case RIGHT_ARM: return m.rightArm;
            case LEFT_ARM: return m.leftArm;
            case RIGHT_LEG: return m.rightLeg;
            case LEFT_LEG: return m.leftLeg;
            default: return m.body;
        }
    }

    private static void emit(PoseStack.Pose pose, VertexConsumer vc, Mesh.Layer l, int light, int overlay) {
        float[] d = l.data();
        for (int q = 0; q < l.quads(); q++) {
            int o = q * Mesh.STRIDE;
            float nx = d[o], ny = d[o + 1], nz = d[o + 2];
            for (int k = 0; k < 4; k++) {
                int v = o + 3 + k * 5;
//#if >=1.21
                vc.addVertex(pose, d[v] / 16f, d[v + 1] / 16f, d[v + 2] / 16f).setColor(-1).setUv(d[v + 3], d[v + 4])
                        .setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
//#elif >=1.20.5
                vc.vertex(pose, d[v] / 16f, d[v + 1] / 16f, d[v + 2] / 16f).color(-1).uv(d[v + 3], d[v + 4])
                        .overlayCoords(overlay).uv2(light).normal(pose, nx, ny, nz).endVertex();
//#else
                Compat.argb(vc.vertex(pose.pose(), d[v] / 16f, d[v + 1] / 16f, d[v + 2] / 16f), -1).uv(d[v + 3], d[v + 4])
                        .overlayCoords(overlay).uv2(light).normal(pose.normal(), nx, ny, nz).endVertex();
//#endif
            }
        }
    }

    // ---- badge ------------------------------------------------------------------------------------------------------

    /** {@code name} with the client badge in front when {@code uuid} runs the client. */
    public static Component badge(UUID uuid, Component name) {
        if (name == null || uuid == null || !Compat.mods().cosmetics().badge(uuid.toString())) return name;
//#if >=1.21.11
        // The Kite mark as a glyph (McBadge).
        return McBadge.badge().append(name);
//#else
        return Component.empty().append(Compat.color(Component.literal(Cosmetics.BADGE + " "), Cosmetics.BADGE_COLOR & 0xFFFFFF)).append(name);
//#endif
    }

    // ---- menu preview -----------------------------------------------------------------------------------------------

//#if >=1.21.2
    /** Render state for drawing {@code e} in a GUI, turned {@code yaw} degrees (0 faces the viewer), like the inventory. */
    public static EntityRenderState previewState(LivingEntity e, float yaw) {
        EntityRenderState s = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(e).createRenderState(e, 1f);
//#if >=1.21.9
        s.lightCoords = FULL_BRIGHT;
        s.shadowPieces.clear();
        s.outlineColor = 0;
//#endif
        if (s instanceof LivingEntityRenderState) {
            LivingEntityRenderState l = (LivingEntityRenderState) s;
            l.bodyRot = 180f + yaw;
            l.yRot = 0;
            if (l.pose != Pose.FALL_FLYING) l.xRot = 0;
            l.boundingBoxWidth /= l.scale;
            l.boundingBoxHeight /= l.scale;
            l.scale = 1f;
        }
        return s;
    }
//#endif

//#if >=1.21.9
    /**
     * A player standing in a GUI without a world (the shop opened from the main menu): the signed-in account's skin
     * and the local cosmetics with try-ons, turned {@code yaw} degrees like {@link #previewState}.
     */
    public static AvatarRenderState menuPreviewState(float yaw) {
        AvatarRenderState s = new AvatarRenderState();
        s.skin = McSkins.localSkin();
        // Animated cosmetics (flapping wings, spinning hats) keep moving.
        s.ageInTicks = (System.currentTimeMillis() % 3_600_000L) / 50f;
        s.boundingBoxWidth = 0.6f;
        s.boundingBoxHeight = 1.8f;
        s.eyeHeight = 1.62f;
        s.lightCoords = FULL_BRIGHT;
        s.bodyRot = 180f + yaw;
        dress(s, Compat.mods().cosmetics().own(), false);
        return s;
    }
//#endif
}
