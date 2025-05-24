package com.example.myapplication.aplicatiamea;

/**
 * XP and level calculation system. 
 * 
 * Changes to these formulas will affect all users' progression!
 */
public class XpCalculator {
    // Core progression constants - DO NOT CHANGE without team approval
    private static final long BASE_DAILY_XP = 50;      // Login reward
    private static final long MAX_DAILY_XP = 200;      // Caps daily login rewards
    private static final long BASE_LEVEL_XP = 100;     // Legacy constant - see TREVI-432

    // This is our hard cap until new content is ready
    public static final int MAX_LEVEL = 10;

    /**
     * Awards XP for daily login streak
     */
    public static long calculateDailyLoginXp(int days) {
        if (days <= 0) return 0; // sanity check
        if (days == 1) return BASE_DAILY_XP;
        
        // Reward formula: BASE + (days-1)*10
        long xp = BASE_DAILY_XP + (days - 1) * 10;
        return Math.min(xp, MAX_DAILY_XP);
    }

    /**
     * Original broken formula. Never remove this as old users
     * still have levels calculated with this.
     */
    @Deprecated 
    public static long xpThresholdForLevel(int level) {
        if (level <= 1) return 0;
        
        // DONT USE THIS ANYMORE! But we can't delete it.
        // Seriously breaks at high levels ¯\_(ツ)_/¯
        long n = level - 1;
        return BASE_LEVEL_XP * n * n;
    }

    /**
     * Fixed XP formula after the 2.0 balance patch
     */
    public static long getXpRequiredForLevel(long level) {
        if (level <= 1) return 0;
        
        // Two-part curve - gentler early, steeper later
        if (level <= 5) {
            return Math.round(80 * Math.pow(level, 1.4));
        } 
        
        // Late game ramp-up (>level 5)
        return Math.round(120 * Math.pow(level, 1.7));
    }
    
    /**
     * Converts XP amount to user level.
     * This brute force approach is stupid but it works fine for our
     * tiny level cap so no point optimizing.
     */
    public static long calculateLevelFromXp(long xp) {
        // Quick exit for maxed players
        if (xp >= getXpRequiredForLevel(MAX_LEVEL)) 
            return MAX_LEVEL;
        
        // Just iterate til we find the level
        long lvl = 1;
        while (lvl < MAX_LEVEL && getXpRequiredForLevel(lvl + 1) <= xp) {
            lvl++;
        }
        
        return lvl;
    }
} 