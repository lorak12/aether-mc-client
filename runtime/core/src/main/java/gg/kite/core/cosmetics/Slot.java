package gg.kite.core.cosmetics;

import java.util.Locale;

/** Where a cosmetic is worn; a player wears at most one per slot. Ids match the backend's loadout slots. */
public enum Slot {
    CAPE("Capes"), WINGS("Wings"), HAT("Hats"), EARS("Ears"), FACE("Face"), BOOTS("Boots"), BACK("Back");

    public final String label;

    Slot(String label) { this.label = label; }

    public String id() { return name().toLowerCase(Locale.ROOT); }

    /** Null for unknown ids. */
    public static Slot byId(String id) {
        if (id == null) return null;
        for (Slot s : values()) if (s.id().equalsIgnoreCase(id.trim())) return s;
        return null;
    }
}
