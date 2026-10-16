package gg.kite.core.ui;

import gg.kite.core.Mod;
import gg.kite.core.ModManager;
import java.util.ArrayList;
import java.util.List;

/** The mod list's tabs and filtering, kept apart from drawing so it can be tested. */
public final class ModCatalog {
    public enum Tab { ALL, NEW, HUD, SERVER, MECHANIC }

    private ModCatalog() {}

    /** The tab a mod lives in besides ALL (and NEW while unseen). Server-specific beats HUD. */
    public static Tab tabOf(Mod m) {
        if (m.serverSpecific()) return Tab.SERVER;
        if (m.category == Mod.Category.HUD) return Tab.HUD;
        return Tab.MECHANIC;
    }

    public static boolean in(ModManager mm, Mod m, Tab tab) {
        switch (tab) {
            case ALL: return true;
            case NEW: return !mm.seen(m);
            default: return tabOf(m) == tab;
        }
    }

    /** Mods in {@code tab} matching {@code query}; search order (best match first) when searching, otherwise registration order. */
    public static List<Mod> list(ModManager mm, Tab tab, String query) {
        List<Mod> out = new ArrayList<Mod>();
        for (Mod m : mm.search(query)) if (in(mm, m, tab)) out.add(m);
        return out;
    }

    public static int count(ModManager mm, Tab tab) {
        int n = 0;
        for (Mod m : mm.all()) if (in(mm, m, tab)) n++;
        return n;
    }
}
