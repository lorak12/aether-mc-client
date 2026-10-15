package gg.aether.mc;

/** The dropped stack's real item count, kept on item render states (vanilla keeps only how many copies to draw). */
public interface ItemStackSize {
    int aether$stackSize();
}
