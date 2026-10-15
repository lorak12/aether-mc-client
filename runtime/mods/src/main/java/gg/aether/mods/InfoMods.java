package gg.aether.mods;

import gg.aether.core.GameState;
import gg.aether.core.HudElement;
import gg.aether.core.HudElement.Anchor;
import gg.aether.core.Mod;
import gg.aether.core.Setting;
import gg.aether.core.UiRenderer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only information widgets about the player, inventory and surroundings. */
public final class InfoMods {
    private InfoMods() {}

    public static final class ItemCounter extends HudTextMod {
        private final Setting.Bool wholeInventory = add(new Setting.Bool("total", "Count whole inventory", true));
        private final Setting.Bool hotbarOnly = add(new Setting.Bool("hotbarOnly", "Count the hotbar only", false), wholeInventory);
        private final Setting.Int low = add(new Setting.Int("low", "Low-count warning (0 = off)", 8, 0, 64));
        private final Setting.Bool name = add(new Setting.Bool("name", "Item name", true));
        private final Setting.Bool stacks = add(new Setting.Bool("stacks", "Also show stacks (2x64 + 5)", false));

        public ItemCounter() { super("itemcounter", "Item Counter", "How many of the held item you have", Category.INVENTORY, Anchor.BOTTOM_CENTER, 0, -80); }

        int count(GameState s) {
            GameState.ItemStack h = s.heldItem();
            if (h == null) return 0;
            if (!wholeInventory.on()) return h.count;
            int n = 0;
            List<GameState.ItemStack> inv = s.inventory();
            int limit = hotbarOnly.on() ? Math.min(9, inv.size()) : inv.size();
            for (int i = 0; i < limit; i++) {
                GameState.ItemStack st = inv.get(i);
                if (st != null && st.itemId.equals(h.itemId)) n += st.count;
            }
            GameState.ItemStack off = s.offhandItem();
            if (!hotbarOnly.on() && off != null && off.itemId.equals(h.itemId)) n += off.count;
            return Math.max(n, h.count);
        }

        @Override protected List<String> lines(GameState s) {
            GameState.ItemStack h = s.heldItem();
            if (h == null) return Collections.emptyList();
            int n = count(s);
            String warn = low.val() > 0 && n <= low.val() ? "(!) " : "";
            String text = name.on() ? (h.name != null ? h.name : prettyId(h.itemId)) + ": " + n : String.valueOf(n);
            if (stacks.on() && n >= 64) text += " (" + n / 64 + "x64" + (n % 64 > 0 ? " + " + n % 64 : "") + ")";
            return Collections.singletonList(warn + text);
        }

        @Override protected int lineColor(int i, GameState s) {
            return low.val() > 0 && count(s) <= low.val() ? 0xFFFFAA33 : textColor.argb();
        }
    }

    public static final class ServerIp extends HudTextMod {
        private final Setting.Bool showDimension = add(new Setting.Bool("dimension", "Show dimension", false));

        public ServerIp() { super("serverip", "Server IP", "Current server address", Category.UTILITY, Anchor.BOTTOM_RIGHT, -4, -4); sensitive = true; }

        @Override protected List<String> lines(GameState s) {
            String a = s.serverAddress();
            List<String> l = new ArrayList<String>();
            l.add(a == null || a.isEmpty() ? "Singleplayer" : a);
            if (showDimension.on()) l.add(s.dimension());
            return l;
        }
    }

    /** WAILA: "What am I looking at?" for blocks and entities, with tool, hardness, health and the owning mod. */
    public static final class TargetInfo extends HudTextMod {
        private final Setting.Bool icon = add(new Setting.Bool("icon", "Block icon", true));
        private final Setting.Bool showId = add(new Setting.Bool("id", "Registry id", false));
        private final Setting.Bool mod = add(new Setting.Bool("mod", "Mod name", true));
        private final Setting.Bool tool = add(new Setting.Bool("tool", "Tool and harvestability", true));
        private final Setting.Bool hardness = add(new Setting.Bool("hardness", "Hardness", false));
        private final Setting.Bool health = add(new Setting.Bool("health", "Entity health", true));
        private final Setting.Bool fluid = add(new Setting.Bool("fluid", "Fluid", false));
        private final Setting.Bool position = add(new Setting.Bool("position", "Position", false));
        private final Setting.Bool showDistance = add(new Setting.Bool("distance", "Distance", false));
        private final Setting.Bool light = add(new Setting.Bool("light", "Light at the face", false));
        private GameState.Target shown = GameState.Target.NONE;
        private int toolLine = -1, modLine = -1;

        public TargetInfo() { super("targetinfo", "WAILA / Target Info", "What you are looking at: block or entity, tool, hardness, health, mod", Category.WORLD, Anchor.TOP_CENTER, 0, 28); }

        @Override protected List<String> lines(GameState s) {
            GameState.Target t = s.target();
            if (t.kind == GameState.Target.Kind.NONE && previewing()) {
                t = new GameState.Target(GameState.Target.Kind.BLOCK, "Stone", "minecraft:stone", 3.2);
                t.tool = "pickaxe";
                t.hardness = 1.5f;
            }
            shown = t;
            toolLine = modLine = -1;
            if (t.kind == GameState.Target.Kind.NONE) return Collections.emptyList();
            List<String> l = new ArrayList<String>();
            l.add(t.name);
            if (showId.on()) l.add(t.id);
            if (t.kind == GameState.Target.Kind.BLOCK) {
                if (tool.on()) {
                    toolLine = l.size();
                    l.add((t.tool.isEmpty() ? "Any tool" : "Tool: " + t.tool) + (t.harvestable ? "" : " (no drops)"));
                }
                if (hardness.on()) l.add(t.hardness < 0 ? "Unbreakable" : String.format(Locale.ROOT, "Hardness %.1f", t.hardness));
                if (fluid.on() && !t.fluid.isEmpty()) l.add("Fluid: " + prettyId(t.fluid));
                if (light.on()) l.add("Light " + Math.max(t.blockLight, t.skyLight) + " (block " + t.blockLight + ")");
            } else if (health.on() && t.maxHealth > 0) {
                l.add(String.format(Locale.ROOT, "❤ %.1f / %.1f", t.health, t.maxHealth));
            }
            if (position.on()) l.add(t.x + " " + t.y + " " + t.z);
            if (showDistance.on()) l.add(String.format(Locale.ROOT, "%.1f m", t.distance));
            if (mod.on()) {
                modLine = l.size();
                l.add(modName(t.namespace()));
            }
            return l;
        }

        static String modName(String ns) { return ns.equals("minecraft") ? "Minecraft" : prettyId(ns); }

        @Override protected int lineColor(int i, GameState s) {
            if (i == toolLine && !shown.harvestable) return bad();
            if (i == modLine) return 0xFF5577FF;
            return textColor.argb();
        }

        @Override public void renderHud(UiRenderer r, GameState s) {
            if (!icon.on()) {
                super.renderHud(r, s);
                return;
            }
            List<String> lines = lines(s);
            if (lines.isEmpty()) return;
            float textW = 0;
            for (String l : lines) textW = Math.max(textW, r.textWidth(l));
            float h = Math.max(20, lines.size() * (r.fontHeight() + 1) - 1 + PAD * 2), w = textW + PAD * 3 + 18;
            hud.natural(w, h);
            w = Math.max(w, hud.boxWidth());
            h = Math.max(h, hud.boxHeight());
            float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), w, h);
            r.push(p[0], p[1], hud.scale());
            if (background.on()) r.rect(0, 0, w, h, withOpacity(bgColor.argb()), 3f);
            if (border.on()) outline(r, 0, 0, w, h, withOpacity(borderColor.argb()));
            if (shown.kind == GameState.Target.Kind.BLOCK) r.item(shown.id, PAD, (h - 16) / 2f);
            float y = (h - (lines.size() * (r.fontHeight() + 1) - 1)) / 2f;
            for (int i = 0; i < lines.size(); i++) {
                r.text(lines.get(i), PAD * 2 + 18 + alignOffset(w - PAD * 3 - 18, r.textWidth(lines.get(i))), y, withOpacity(lineColor(i, s)), shadow.on());
                y += r.fontHeight() + 1;
            }
            r.pop();
        }
    }

    /** Hotbar (and optionally the whole inventory) as an always-visible grid. */
    public static final class InventoryHud extends Mod {
        private final Setting.Bool background = add(new Setting.Bool("background", "Background", true));
        private final Setting.Bool grid = add(new Setting.Bool("grid", "Slot grid", true));
        private final Setting.Int slot = add(new Setting.Int("slot", "Slot size", 18, 12, 32));
        private final Setting.Bool full = add(new Setting.Bool("full", "Show full inventory", false));
        private final Setting.Bool hotbar = add(new Setting.Bool("hotbar", "Show hotbar row", true));
        private final Setting.Bool armor = add(new Setting.Bool("armor", "Show armor and offhand", false));
        private final Setting.Bool counts = add(new Setting.Bool("counts", "Show counts", true));
        private final Setting.Bool durability = add(new Setting.Bool("durability", "Durability bars", true));

        public InventoryHud() {
            super("inventoryhud", "Inventory HUD", "Your items without opening the inventory", Category.INVENTORY, true);
            hud = new HudElement(Anchor.MIDDLE_RIGHT, -6, 160);
        }

        @Override public void renderHud(UiRenderer r, GameState s) {
            List<GameState.ItemStack> inv = s.inventory();
            if (inv.isEmpty()) return;
            // Rows: optional armor/offhand row, the main inventory (slots 9-35), then the hotbar (0-8).
            List<GameState.ItemStack[]> rows = new ArrayList<GameState.ItemStack[]>();
            if (armor.on()) {
                GameState.ItemStack[] row = new GameState.ItemStack[9];
                List<GameState.ArmorPiece> a = s.armor();
                for (int i = 0; i < a.size() && i < 4; i++) {
                    GameState.ArmorPiece ap = a.get(i);
                    if (ap != null) row[i] = new GameState.ItemStack(ap.itemId, 1, ap.durability, ap.maxDurability);
                }
                row[5] = s.offhandItem();
                rows.add(row);
            }
            if (full.on()) {
                for (int r0 = 1; r0 <= 3; r0++) {
                    GameState.ItemStack[] row = new GameState.ItemStack[9];
                    for (int c = 0; c < 9; c++) row[c] = r0 * 9 + c < inv.size() ? inv.get(r0 * 9 + c) : null;
                    rows.add(row);
                }
            }
            if (hotbar.on()) {
                GameState.ItemStack[] row = new GameState.ItemStack[9];
                for (int c = 0; c < 9 && c < inv.size(); c++) row[c] = inv.get(c);
                rows.add(row);
            }
            if (rows.isEmpty()) return;
            int sz = slot.val(), pad = 3;
            float w = 9 * sz + pad * 2, h = rows.size() * sz + pad * 2;
            float[] p = hud.resolve(r.screenWidth(), r.screenHeight(), w, h);
            r.push(p[0], p[1], hud.scale());
            if (background.on()) r.rect(0, 0, w, h, fade(0x66000000), 3f);
            for (int row = 0; row < rows.size(); row++) {
                for (int col = 0; col < 9; col++) {
                    float x = pad + col * sz, y = pad + row * sz;
                    if (grid.on()) r.rect(x, y, sz - 1, sz - 1, fade(0x33FFFFFF), 1f);
                    GameState.ItemStack st = rows.get(row)[col];
                    if (st == null) continue;
                    r.item(st.itemId, x + (sz - 17) / 2f, y + (sz - 17) / 2f);
                    if (durability.on() && st.maxDurability > 0 && st.durability < st.maxDurability) {
                        float f = st.durability / (float) st.maxDurability;
                        r.rect(x + 2, y + sz - 4, sz - 5, 2, fade(0xFF000000), 0);
                        r.rect(x + 2, y + sz - 4, (sz - 5) * f, 1, fade(HudTextMod.gradient(f, fx.colorblind)), 0);
                    }
                    if (counts.on() && st.count > 1) {
                        String c = String.valueOf(st.count);
                        r.text(c, x + sz - 1 - r.textWidth(c), y + sz - 1 - r.fontHeight(), fade(0xFFFFFFFF), true);
                    }
                }
            }
            r.pop();
        }

        private int fade(int argb) {
            int a = Math.round(((argb >>> 24) & 0xFF) * hud.opacity());
            return (a << 24) | (argb & 0xFFFFFF);
        }
    }
}
