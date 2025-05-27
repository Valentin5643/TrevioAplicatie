package com.example.myapplication.aplicatiamea;

/**
 * XP and level calculation system. 
 * 
 * Changes to these formulas will affect all users' progression!
 */
public class XpCalculator {
    // Core progression constants
    private static final long BASE_DAILY_XP = 50;
    private static final long BASE_LEVEL_XP = 100;

    public static final int MAX_LEVEL = 10;

    @Deprecated 
    public static long xpThresholdForLevel(int level) {
        if (level <= 1) return 0;
        long n = level - 1;
        return BASE_LEVEL_XP * n * n;
    }

    public static long getXpRequiredForLevel(long level) {
        if (level <= 1) return 0;

        if (level == 2) return 100;

        if (level <= 5) {
            return 100 + Math.round(75 * Math.pow(level - 1, 1.5));
        } 

        return 100 + Math.round(100 * Math.pow(level - 1, 1.8));
    }

    public static long calculateLevelFromXp(long xp) {

        if (xp >= getXpRequiredForLevel(MAX_LEVEL)) 
            return MAX_LEVEL;

        long lvl = 1;
        while (lvl < MAX_LEVEL && getXpRequiredForLevel(lvl + 1) <= xp) {
            lvl++;
        }
        
        return lvl;
    }
} 