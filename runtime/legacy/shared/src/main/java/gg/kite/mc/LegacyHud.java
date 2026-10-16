package gg.kite.mc;

import gg.kite.core.GameState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;

/** The vanilla HUD's own state (boss bars, sidebar, title, tab header / footer) for the legacy adapters. */
final class LegacyHud {
    private LegacyHud() {}

    private static String strip(String s) { return s == null ? "" : s.replaceAll("§.", ""); }

    static List<GameState.BossBar> bossBars(MinecraftClient mc) {
        List<GameState.BossBar> out = new ArrayList<GameState.BossBar>();
//#if >=1.12
        if (mc.inGameHud == null) return out;
        net.minecraft.client.gui.hud.BossBarHud hud = mc.inGameHud.method_12167();
        for (net.minecraft.client.class_2840 b : ((gg.kite.client.mixin.BossBarHudAccessor) hud).kite$bars().values()) {
            out.add(new GameState.BossBar(b.getTitle().asUnformattedString(), b.getHealth(), b.getColor().name()));
        }
//#else
        if (net.minecraft.entity.boss.BossBar.name != null && net.minecraft.entity.boss.BossBar.framesToLive > 0) {
            out.add(new GameState.BossBar(strip(net.minecraft.entity.boss.BossBar.name), net.minecraft.entity.boss.BossBar.percent, "PURPLE"));
        }
//#endif
        return out;
    }

    static GameState.Sidebar sidebar(MinecraftClient mc) {
        if (mc.world == null) return null;
        Scoreboard sb = mc.world.getScoreboard();
        ScoreboardObjective obj = sb.getObjectiveForSlot(1);
        if (obj == null) return null;
        List<ScoreboardPlayerScore> entries = new ArrayList<ScoreboardPlayerScore>();
        for (Object o : sb.getAllPlayerScores(obj)) {
            ScoreboardPlayerScore e = (ScoreboardPlayerScore) o;
            if (e.getPlayerName() != null && !e.getPlayerName().startsWith("#")) entries.add(e);
        }
        Collections.sort(entries, new Comparator<ScoreboardPlayerScore>() {
            @Override public int compare(ScoreboardPlayerScore a, ScoreboardPlayerScore b) {
                int c = Integer.compare(b.getScore(), a.getScore());
                return c != 0 ? c : a.getPlayerName().compareToIgnoreCase(b.getPlayerName());
            }
        });
        List<String> names = new ArrayList<String>(), scores = new ArrayList<String>();
        for (int i = 0; i < entries.size() && i < 15; i++) {
            ScoreboardPlayerScore e = entries.get(i);
            names.add(strip(net.minecraft.scoreboard.Team.decorateName(sb.getPlayerTeam(e.getPlayerName()), e.getPlayerName())));
            scores.add(String.valueOf(e.getScore()));
        }
        return new GameState.Sidebar(strip(obj.getDisplayName()), names, scores);
    }

    static GameState.Title title(MinecraftClient mc) {
//#if >=1.8
        if (mc.inGameHud == null) return null;
        gg.kite.client.mixin.InGameHudAccessor a = (gg.kite.client.mixin.InGameHudAccessor) mc.inGameHud;
        int total = a.kite$titleTotalTicks(), in = a.kite$titleFadeIn(), remain = a.kite$titleRemain(), out = a.kite$titleFadeOut();
        String title = a.kite$title(), sub = a.kite$subtitle();
        if (total <= 0 || ((title == null || title.isEmpty()) && (sub == null || sub.isEmpty()))) return null;
        float alpha = 255f;
        if (total > out + remain && in > 0) alpha = ((in + remain + out) - total) * 255f / in;
        if (total <= out && out > 0) alpha = total * 255f / out;
        return new GameState.Title(strip(title), strip(sub), Math.max(0f, Math.min(1f, alpha / 255f)));
//#else
        return null;
//#endif
    }

    static String tabText(MinecraftClient mc, boolean header) {
//#if >=1.8
        if (mc.inGameHud == null) return "";
        gg.kite.client.mixin.PlayerListHudAccessor a = (gg.kite.client.mixin.PlayerListHudAccessor) mc.inGameHud.getPlayerListWidget();
        net.minecraft.text.Text t = header ? a.kite$header() : a.kite$footer();
        return t == null ? "" : strip(t.asFormattedString());
//#else
        return "";
//#endif
    }
}
