package gg.aether.core;

import java.util.List;

/** Read-only view of the running game, implemented by each version adapter. Mods only see this. */
public interface GameState {
    enum Key { FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, SPRINT, ATTACK, USE }

    final class ArmorPiece {
        public final String itemId;
        public final int durability, maxDurability;
        public ArmorPiece(String itemId, int durability, int maxDurability) {
            this.itemId = itemId;
            this.durability = durability;
            this.maxDurability = maxDurability;
        }
    }

    final class Effect {
        public final String name;
        public final int amplifier, ticksLeft;
        public Effect(String name, int amplifier, int ticksLeft) {
            this.name = name;
            this.amplifier = amplifier;
            this.ticksLeft = ticksLeft;
        }
    }

    boolean inWorld();
    int fps();
    /** -1 when unknown (singleplayer / not yet measured). */
    int pingMs();
    double x();
    double y();
    double z();
    float yaw();
    String biome();
    String serverAddress();
    boolean isDown(Key k);
    /** Helmet, chest, legs, boots (null entries for empty slots). */
    List<ArmorPiece> armor();
    List<Effect> effects();
    long usedMemoryBytes();
    long maxMemoryBytes();
    long nowMillis();
}
