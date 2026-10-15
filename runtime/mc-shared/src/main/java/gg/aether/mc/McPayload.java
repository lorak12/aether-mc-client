package gg.aether.mc;

import gg.aether.core.Events;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
//#if >=1.20.5
import net.minecraft.network.codec.StreamCodec;
//#endif
//#if >=1.20.2
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//#else
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
//#endif

/**
 * The server integration channel {@code aether:client}: UTF-8 JSON messages from server plugins (HUD lines, timers,
 * waypoints, notifications, mod restrictions). Plugin servers only send to clients that registered the channel, so
 * the client announces it with {@code minecraft:register} on join.
 * <p>
 * With Fabric API installed its networking owns custom payloads, so the channel is registered through it
 * (reflection; Aether does not depend on Fabric API). Without it, the payload is read here: 1.20.5+ through the
 * DiscardedPayload.codec hook (unknown channels normally drop their bytes), 1.20.2-1.20.4 through
 * ClientboundCustomPayloadPacket.readPayload, older versions straight from the packet's buffer.
 */
public final class McPayload {
    private McPayload() {}

    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("aether", "client");
    static final Identifier REGISTER = Identifier.fromNamespaceAndPath("minecraft", "register");
    private static final int MAX = 1 << 20;
    private static boolean viaFabric;

//#if >=1.20.5
    public static final CustomPacketPayload.Type<Raw> TYPE = new CustomPacketPayload.Type<Raw>(CHANNEL);

    /** Any payload kept as raw bytes. */
    public static final class Raw implements CustomPacketPayload {
        final CustomPacketPayload.Type<Raw> type;
        final byte[] data;
        Raw(Identifier id, byte[] data) {
            this.type = id.equals(CHANNEL) ? TYPE : new CustomPacketPayload.Type<Raw>(id);
            this.data = data;
        }
        Identifier id() { return type.id(); }
        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return type; }
    }

    static <B extends FriendlyByteBuf> StreamCodec<B, Raw> codec(final Identifier id) {
        return StreamCodec.of((buf, p) -> buf.writeBytes(p.data), buf -> new Raw(id, bytes(buf)));
    }

    /** Called once at start-up. */
    public static void init() {
        if (!FabricLoader.getInstance().isModLoaded("fabric-networking-api-v1")) return;
        try {
            ClassLoader cl = McPayload.class.getClassLoader();
            Class<?> reg = Class.forName("net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry", true, cl);
            StreamCodec<FriendlyByteBuf, Raw> c = codec(CHANNEL);
            // Fabric API renamed playS2C / playC2S to clientboundPlay / serverboundPlay (26.x).
            for (String[] side : new String[][] { { "clientboundPlay", "playS2C" }, { "serverboundPlay", "playC2S" } }) {
                Object r = registry(reg, side).invoke(null);
                reg.getMethod("register", CustomPacketPayload.Type.class, StreamCodec.class).invoke(r, TYPE, c);
            }
            Class<?> net = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking", true, cl);
            Class<?> handlerType = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$PlayPayloadHandler", true, cl);
            Object handler = proxy(cl, handlerType, args -> handle((CustomPacketPayload) args[0]));
            net.getMethod("registerGlobalReceiver", CustomPacketPayload.Type.class, handlerType).invoke(null, TYPE, handler);
            viaFabric = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warn(e);
        }
    }

    private static java.lang.reflect.Method registry(Class<?> reg, String[] names) throws NoSuchMethodException {
        for (String n : names) {
            try { return reg.getMethod(n); } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException("PayloadTypeRegistry." + String.join("/", names));
    }

    /** DiscardedPayload.codec hook: our codec for the two channels we read / write, else null. */
    public static StreamCodec<FriendlyByteBuf, Raw> codecFor(Identifier id) {
        return ours(id) ? codec(id) : null;
    }
//#else
    /** Called once at start-up. Fabric API before 1.20.5 has per-channel byte handlers. */
    public static void init() {
        if (!FabricLoader.getInstance().isModLoaded("fabric-networking-api-v1")) return;
        try {
            ClassLoader cl = McPayload.class.getClassLoader();
            Class<?> net = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking", true, cl);
            Class<?> handlerType = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$PlayChannelHandler", true, cl);
            // receive(Minecraft client, ClientPacketListener handler, FriendlyByteBuf buf, PacketSender sender)
            Object handler = proxy(cl, handlerType, args -> received(bytes((FriendlyByteBuf) args[2])));
            net.getMethod("registerGlobalReceiver", Identifier.class, handlerType).invoke(null, CHANNEL, handler);
            viaFabric = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warn(e);
        }
    }
//#endif

//#if >=1.20.2 && <1.20.5
    /** Any payload kept as raw bytes. */
    public static final class Raw implements CustomPacketPayload {
        final Identifier id;
        final byte[] data;
        Raw(Identifier id, byte[] data) {
            this.id = id;
            this.data = data;
        }
        @Override public void write(FriendlyByteBuf buf) { buf.writeBytes(data); }
        @Override public Identifier id() { return id; }
    }

    /** ClientboundCustomPayloadPacket.readPayload hook: our channel as raw bytes, else null (vanilla reads it). */
    public static CustomPacketPayload read(Identifier id, FriendlyByteBuf buf) {
        return ours(id) ? new Raw(id, bytes(buf)) : null;
    }
//#endif

//#if >=1.20.2
    /** Custom payload handler hook. True if the payload was ours (consumed). */
    public static boolean handle(CustomPacketPayload p) {
        if (!(p instanceof Raw)) return false;
        Raw r = (Raw) p;
        if (r.id().equals(CHANNEL)) received(r.data); // else the server's own minecraft:register
        return true;
    }
//#else
    /** ClientPacketListener.handleCustomPayload hook (network thread): true if the packet was ours (consumed). */
    public static boolean handle(Identifier id, FriendlyByteBuf buf) {
        if (viaFabric || !CHANNEL.equals(id)) return false;
        received(bytes(new FriendlyByteBuf(buf.copy())));
        return true;
    }
//#endif

    /** True for the channels we read / write ourselves (not when Fabric API's networking owns them). */
    static boolean ours(Identifier id) {
        return !viaFabric && id != null && (id.equals(CHANNEL) || id.equals(REGISTER));
    }

    private static byte[] bytes(FriendlyByteBuf buf) {
        int n = buf.readableBytes();
        if (n > MAX) throw new IllegalArgumentException("Aether payload too large: " + n);
        byte[] b = new byte[n];
        buf.readBytes(b);
        return b;
    }

    /** A message arrived on aether:client: hand it to the mods on the client thread. */
    private static void received(byte[] data) {
        final String json = new String(data, StandardCharsets.UTF_8);
        Minecraft mc = Minecraft.getInstance();
        Runnable post = () -> Compat.mods().events().post(new Events.ServerPayload(CHANNEL.toString(), json, System.currentTimeMillis()));
        if (mc.isSameThread()) post.run(); else mc.execute(post);
    }

    private interface Receiver { void receive(Object[] args); }

    /** A Fabric API handler interface implemented by {@code r} (only its single abstract method does anything). */
    private static Object proxy(ClassLoader cl, Class<?> type, final Receiver r) {
        InvocationHandler h = (proxy, m, args) -> {
            switch (m.getName()) {
                case "receive": r.receive(args); return null;
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "AetherPayloadHandler";
                default: return null;
            }
        };
        return Proxy.newProxyInstance(cl, new Class<?>[] { type }, h);
    }

    private static void warn(Throwable e) {
        org.apache.logging.log4j.LogManager.getLogger("aether").warn("Could not register aether:client with Fabric API networking", e);
    }

    /** On joining a server: announce the channel (unless Fabric API does) and say hello. */
    public static void joined(Minecraft mc) {
        ClientPacketListener c = mc.getConnection();
        if (c == null || mc.hasSingleplayerServer()) return;
        // Fabric networking owns custom payloads; if registering with it failed, Raw cannot be encoded there.
        if (!viaFabric && FabricLoader.getInstance().isModLoaded("fabric-networking-api-v1")) return;
        if (!viaFabric) send(c, REGISTER, CHANNEL.toString().getBytes(StandardCharsets.UTF_8));
        String hello = "{\"type\":\"hello\",\"client\":\"Aether\",\"version\":\"" + Compat.mods().host().versionInfo().replace("\"", "'") + "\"}";
        send(c, CHANNEL, hello.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(ClientPacketListener c, Identifier id, byte[] data) {
//#if >=1.20.2
        c.send(new ServerboundCustomPayloadPacket(new Raw(id, data)));
//#else
        c.send(new ServerboundCustomPayloadPacket(id, new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data))));
//#endif
    }
}
