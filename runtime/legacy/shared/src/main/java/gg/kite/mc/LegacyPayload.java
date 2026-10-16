package gg.kite.mc;

import gg.kite.client.KiteClient;
import gg.kite.core.Events;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;

/**
 * The server integration channel {@code kite:client} (UTF-8 JSON from server plugins: HUD lines, timers, waypoints,
 * notifications, mod restrictions) on 1.7.10 - 1.12.2. Servers only send to clients that registered the channel, which
 * those versions do with a {@code REGISTER} message on join.
 */
public final class LegacyPayload {
    private LegacyPayload() {}

    public static final String CHANNEL = "kite:client";
    private static final int MAX = 1 << 20;

    /** True when the packet was ours (consumed); the JSON goes to the mods. */
    public static boolean handle(CustomPayloadS2CPacket packet) {
        if (!CHANNEL.equals(packet.getChannel())) return false;
        byte[] data = LegacyCompat.payload(packet);
        if (data.length > MAX) return true;
        KiteClient.mods().events().post(new Events.ServerPayload(CHANNEL, new String(data, StandardCharsets.UTF_8), System.currentTimeMillis()));
        return true;
    }

    /** On joining a server (not singleplayer): register the channel and say hello. */
    public static void joined(MinecraftClient mc) {
        if (mc.getNetworkHandler() == null || mc.isInSingleplayer()) return;
        send(mc, "REGISTER", CHANNEL.getBytes(StandardCharsets.UTF_8));
        String hello = "{\"type\":\"hello\",\"client\":\"Kite\",\"version\":\"" + KiteClient.mods().host().versionInfo().replace("\"", "'") + "\"}";
        send(mc, CHANNEL, hello.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(MinecraftClient mc, String channel, byte[] data) {
        try {
            mc.getNetworkHandler().sendPacket(LegacyCompat.customPayload(channel, data));
        } catch (RuntimeException e) {
            Log.warn("Could not send " + channel, e);
        }
    }
}
