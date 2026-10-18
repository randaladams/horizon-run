package com.horizonrun.game;

import android.content.SharedPreferences;

import java.util.ArrayList;

/**
 * Lifetime stats, the all-time top 10 and badges. Tracked for every player
 * (so nothing is lost when they upgrade); shown in the full version.
 * Zen-mode runs are not counted.
 */
public class Progress {

    public static final String[] BADGE_NAME = {
            "First Steps", "Kilometre Club", "Marathoner", "Full Bouquet",
            "Gold Rush", "Florist", "Bear Jumper", "Bear Necessities",
            "Wall Climber", "Slow and Steady", "Close Shave", "Globetrotter"};
    public static final String[] BADGE_DESC = {
            "Finish your first run", "Score 1,000 in one run", "Score 5,000 in one run",
            "Collect a whole trail of 5", "3 golden items in one run", "Collect 500 items in total",
            "High-jump over a bear", "High-jump 10 bears in total", "Jump over a rock wall",
            "5 snails in one run", "10 close calls in one run", "Run 100 km in total"};
    public static final int FIRST_RUN = 0, KM1 = 1, KM5 = 2, BOUQUET = 3, GOLD3 = 4, FLORIST = 5,
            BEAR1 = 6, BEARS10 = 7, WALL = 8, SNAILS5 = 9, CLOSE10 = 10, GLOBETROTTER = 11;
    public static final int BADGES = BADGE_NAME.length;

    private final SharedPreferences prefs;

    public long runs, distance, items, golden, jumps, highJumps, bears, snails, closeCalls,
            longestSecs, mostItems;
    public long badges;                                  // bit mask of unlocked badges
    public final ArrayList<long[]> top = new ArrayList<>(); // {score, time}, best first

    public Progress(SharedPreferences prefs) {
        this.prefs = prefs;
        runs = prefs.getLong("st_runs", 0);
        distance = prefs.getLong("st_dist", 0);
        items = prefs.getLong("st_items", 0);
        golden = prefs.getLong("st_gold", 0);
        jumps = prefs.getLong("st_jumps", 0);
        highJumps = prefs.getLong("st_hjumps", 0);
        bears = prefs.getLong("st_bears", 0);
        snails = prefs.getLong("st_snails", 0);
        closeCalls = prefs.getLong("st_close", 0);
        longestSecs = prefs.getLong("st_longest", 0);
        mostItems = prefs.getLong("st_most", 0);
        badges = prefs.getLong("st_badges", 0);
        String raw = prefs.getString("st_top", "");
        if (!raw.isEmpty()) {
            for (String row : raw.split(";")) {
                String[] f = row.split(",");
                if (f.length < 2) continue;
                try { top.add(new long[]{Long.parseLong(f[0]), Long.parseLong(f[1])}); }
                catch (NumberFormatException ignored) { }
            }
        }
    }

    public boolean has(int badge) { return (badges & (1L << badge)) != 0; }

    public int badgeCount() { return Long.bitCount(badges); }

    /** @return true if this badge was just unlocked. */
    public boolean unlock(int badge) {
        if (has(badge)) return false;
        badges |= 1L << badge;
        prefs.edit().putLong("st_badges", badges).apply();
        return true;
    }

    /** Adds one finished run to the lifetime totals and the top 10. */
    public void addRun(long score, long dist, long runItems, long runGold, long runJumps,
                       long runHighJumps, long runBears, long runSnails, long runClose, long secs) {
        runs++;
        distance += dist;
        items += runItems;
        golden += runGold;
        jumps += runJumps;
        highJumps += runHighJumps;
        bears += runBears;
        snails += runSnails;
        closeCalls += runClose;
        longestSecs = Math.max(longestSecs, secs);
        mostItems = Math.max(mostItems, runItems);
        if (score > 0) {
            top.add(new long[]{score, System.currentTimeMillis()});
            top.sort((a, b) -> Long.compare(b[0], a[0]));
            while (top.size() > 10) top.remove(top.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < top.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(top.get(i)[0]).append(',').append(top.get(i)[1]);
        }
        prefs.edit()
                .putLong("st_runs", runs).putLong("st_dist", distance).putLong("st_items", items)
                .putLong("st_gold", golden).putLong("st_jumps", jumps).putLong("st_hjumps", highJumps)
                .putLong("st_bears", bears).putLong("st_snails", snails).putLong("st_close", closeCalls)
                .putLong("st_longest", longestSecs).putLong("st_most", mostItems)
                .putString("st_top", sb.toString())
                .apply();
    }

    /** Wipes stats, top 10 and badges (used by Reset scores). */
    public void reset() {
        runs = distance = items = golden = jumps = highJumps = bears = snails = closeCalls = 0;
        longestSecs = mostItems = 0;
        badges = 0;
        top.clear();
        prefs.edit()
                .remove("st_runs").remove("st_dist").remove("st_items").remove("st_gold")
                .remove("st_jumps").remove("st_hjumps").remove("st_bears").remove("st_snails")
                .remove("st_close").remove("st_longest").remove("st_most").remove("st_badges")
                .remove("st_top")
                .apply();
    }
}
