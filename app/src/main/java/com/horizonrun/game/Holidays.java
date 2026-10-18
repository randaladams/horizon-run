package com.horizonrun.game;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * US holiday themes for the collectible flowers. Each theme runs from 10 days
 * before the holiday to 3 days after. When two windows overlap, a holiday that
 * has already passed hands over to the one still coming up.
 */
public final class Holidays {

    public static final int NONE = 0, NEW_YEAR = 1, VALENTINE = 2, ST_PATRICK = 3, EASTER = 4,
            JULY_4 = 5, HALLOWEEN = 6, THANKSGIVING = 7, CHRISTMAS = 8;
    public static final int COUNT = 9;

    static final int DAYS_BEFORE = 10, DAYS_AFTER = 3;

    /** Shown on the start screen. */
    public static final String[] BANNER = {
            "", "Happy New Year!", "Happy Valentine's Day!", "Happy St. Patrick's Day!", "Happy Easter!",
            "Happy 4th of July!", "Happy Halloween!", "Happy Thanksgiving!", "Merry Christmas!"};
    /** Short name, used by the theme preview switch. */
    public static final String[] NAME = {
            "Normal", "New Year's", "Valentine's Day", "St. Patrick's Day", "Easter",
            "Independence Day", "Halloween", "Thanksgiving", "Christmas"};
    /** What the collectibles are called (plural, capitalised). */
    public static final String[] ITEMS = {
            "Flowers", "Poppers", "Hearts", "Shamrocks", "Eggs",
            "Stars", "Pumpkins", "Leaves", "Presents"};
    /** Pop-up for collecting a whole trail of five. */
    public static final String[] TRAIL = {
            "BOUQUET!", "PARTY TIME!", "SWEETHEART!", "LUCKY STREAK!", "EGG HUNT!",
            "STAR SPANGLED!", "PUMPKIN PATCH!", "BUMPER HARVEST!", "HO HO HO!"};
    /** Pop-up for the rare golden item. */
    public static final String[] GOLD = {
            "GOLDEN FLOWER", "CHAMPAGNE!", "GOLDEN HEART", "POT OF GOLD", "GOLDEN EGG",
            "FIREWORK!", "GLOWING SKULL", "GOLDEN TURKEY", "CHRISTMAS TREE"};
    /** Banner text colour. */
    public static final int[] COLOR = {
            0xFFFFFFFF, 0xFFFFE07A, 0xFFFF7FB0, 0xFF7CE07C, 0xFFD9B8FF,
            0xFF9CC8FF, 0xFFFF9A3C, 0xFFFFB45A, 0xFFFF6A5A};
    /** Sky tint for light / dark mode (0 = none). */
    public static final int[] TINT_LIGHT = {
            0, 0, 0xFFFFB6CF, 0xFFB8E8B0, 0xFFE2D4FF,
            0, 0xFFF08A3A, 0xFFE8A85A, 0xFFDDEBFF};
    public static final int[] TINT_DARK = {
            0, 0xFF2A1F4A, 0xFF4A1A34, 0xFF103A22, 0xFF2E2448,
            0xFF101A40, 0xFF3A1A50, 0xFF3A2414, 0xFF14284A};
    /** Falling particles: 0 none, 1 snow, 2 leaves, 3 confetti. */
    public static final int[] WEATHER = {0, 3, 0, 0, 0, 3, 0, 2, 1};

    private Holidays() { }

    /** The theme for a given day. */
    public static int forDate(LocalDate today) {
        int bestId = NONE;
        long bestUntil = Long.MIN_VALUE;
        boolean bestUpcoming = false;
        for (int id = 1; id < COUNT; id++) {
            for (int y = today.getYear() - 1; y <= today.getYear() + 1; y++) {
                LocalDate d = date(id, y);
                long until = ChronoUnit.DAYS.between(today, d);   // >0 coming up, <0 passed
                if (until > DAYS_BEFORE || until < -DAYS_AFTER) continue;
                boolean upcoming = until >= 0;
                // prefer one still coming up (the nearest); otherwise the most recent
                boolean better;
                if (bestId == NONE) better = true;
                else if (upcoming != bestUpcoming) better = upcoming;
                else if (upcoming) better = until < bestUntil;
                else better = until > bestUntil;
                if (better) {
                    bestId = id;
                    bestUntil = until;
                    bestUpcoming = upcoming;
                }
            }
        }
        return bestId;
    }

    static LocalDate date(int id, int year) {
        switch (id) {
            case NEW_YEAR:     return LocalDate.of(year, 1, 1);
            case VALENTINE:    return LocalDate.of(year, 2, 14);
            case ST_PATRICK:   return LocalDate.of(year, 3, 17);
            case EASTER:       return easter(year);
            case JULY_4:       return LocalDate.of(year, 7, 4);
            case HALLOWEEN:    return LocalDate.of(year, 10, 31);
            case THANKSGIVING: return LocalDate.of(year, 11, 1)
                    .with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY));
            default:           return LocalDate.of(year, 12, 25);
        }
    }

    /** Western (Gregorian) Easter Sunday. */
    public static LocalDate easter(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4;
        int f = (b + 8) / 25, g = (b - f + 1) / 3, h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(y, month, day);
    }
}
