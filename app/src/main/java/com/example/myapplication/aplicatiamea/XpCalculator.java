package com.example.myapplication.aplicatiamea;

/**
 * Utility class to calculate XP rewards in a fun and fair way.
 */
public class XpCalculator {
    private static final long BASE_DAILY_XP = 50;
    private static final long MAX_DAILY_XP = 200;
    private static final long BASE_LEVEL_XP = 100;

    // Maximum user level
    public static final int MAX_LEVEL = 10;

    /**
     * Calculates XP for daily login based on consecutive days.
     * xp = BASE_DAILY_XP + (days - 1) * 10, capped at MAX_DAILY_XP.
     * Treats days <= 1 as BASE_DAILY_XP.
     */
    public static long calculateDailyLoginXp(int consecutiveDays) {
        if (consecutiveDays <= 1) {
            return BASE_DAILY_XP;
        }
        long xp = BASE_DAILY_XP + (long)(consecutiveDays - 1) * 10;
        return Math.min(xp, MAX_DAILY_XP);
    }

    public static long xpThresholdForLevel(int level) {
        if (level <= 1) {
            return 0;
        }
        // Increased difficulty: quadratic progression (BASE_LEVEL_XP * (level-1)^2)
        long n = level - 1;
        return BASE_LEVEL_XP * n * n;
    }

    /**
     * Calculates XP for task completion based on difficulty string mapping to TaskDifficulty.
     */

    // Use a less strict curve with higher max level (10+), so level 5 is easier and level 10 is a late-game goal
    public static long getXpRequiredForLevel(long level) {
        if (level <= 1) return 0;  // Level 1 is starting level
        // New curve: easier early, slower after 5, max level 10+ for unlocks
        if (level <= 5) {
            // Early levels: easier, e.g. base 80, exponent 1.4
            return Math.round(80 * Math.pow(level, 1.4));
        } else {
            // After level 5, curve slows but still grows, e.g. base 120, exponent 1.7
            return Math.round(120 * Math.pow(level, 1.7));
        }
    }
    
    // Calculate current level based on total XP, capped at MAX_LEVEL
    public static long calculateLevelFromXp(long totalXp) {
        long level = 1;
        
        // Handle max level specifically
        long maxLevelXp = getXpRequiredForLevel(MAX_LEVEL);
        if (totalXp >= maxLevelXp) {
            return MAX_LEVEL;
        }
        
        // Calculate level for XP below max level
        while (level < MAX_LEVEL && getXpRequiredForLevel(level + 1) <= totalXp) {
            level++;
        }
        return level;
    }
} 