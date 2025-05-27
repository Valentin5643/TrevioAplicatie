package com.example.myapplication.aplicatiamea;

import java.util.HashMap;
import java.util.Map;
import android.util.Log;


public class EffectManager {
    private static final String TAG = "EfctMgr";

    private static final boolean DEBUG = false;


    public enum EffectTiming {
        IMMEDIATE,
        DELAYED,
        OVER_TIME
    }


    private static final String RAGE_DRAUGHT_EXPIRY = "rageDraughtExpiryTimestamp";
    private static final String DEXTERITY_TASKS = "dexterityTasksRemaining";
    private static final String DEXTERITY_PERCENT = "dexterityTimeReductionPercent";


    public static boolean isRageDraughtActive(Map<String, Object> effects) {
        if (effects == null) return false;

        long expiry = getNumericValue(effects, RAGE_DRAUGHT_EXPIRY, 0L);
        boolean active = expiry > System.currentTimeMillis();

        if (DEBUG && active) {
            Log.d(TAG, "Rage potion active, expires in: " +
                  ((expiry - System.currentTimeMillis()) / 1000) + " seconds");
        }

        return active;
    }


    public static boolean isDexteritySerumActive(Map<String, Object> effects) {
        if (effects == null) return false;

        int tasksLeft = (int) getNumericValue(effects, DEXTERITY_TASKS, 0);

        // hacky fix for negative values that somehow got into prod
        if (tasksLeft < 0) tasksLeft = 0;

        return tasksLeft > 0;
    }

    public static double getDexterityTimeReduction(Map<String, Object> effects) {
        if (effects == null) return 0.1;

        double reduction = getNumericValue(effects, DEXTERITY_PERCENT, 0.1);
        return Math.min(0.3, Math.max(0.05, reduction));
    }

    public static double mindmeldXpBoostMultiplier = 1.2; // 1.2x
    public static boolean isMindmeldXpBoostActive(Map<String, Object> effects) {
        if (effects == null) return false;
        
        long expiry = getNumericValue(effects, "mindmeldXpBoostExpiryTimestamp", 0L);
        boolean active = expiry > System.currentTimeMillis();
        
        if (DEBUG && active) {
            Log.d(TAG, "XP boost active: " + mindmeldXpBoostMultiplier + "x");
        }
        
        return active;
    }

    public static final long GIANT_STRENGTH_MULTIPLIER = 2;  // too OP? maybe reduce to 1.5x

    public static final long MIDAS_BREW_MULTIPLIER = 2;
    public static boolean isMidasBrewActive(Map<String, Object> effects) {
        if (effects == null) return false;

        long expiry = getNumericValue(effects, "midasBrewExpiryTimestamp", 0L);
        return expiry > System.currentTimeMillis();
    }

    public static double getQuestTimeReductionPercent(Map<String, Object> effects) {
        if (effects == null) return 0.0;

        double reduction = getNumericValue(effects, "questTimeReductionPercent", 0.0);

        return reduction;
    }

    public static double getTotalCoinMultiplier(Map<String, Object> effects) {
        double multiplier = 1.0;

        if (effects == null) return multiplier;

        if (isMidasBrewActive(effects)) {
            multiplier *= MIDAS_BREW_MULTIPLIER;
        }

        return multiplier;
    }

    private static <T extends Number> T getNumericValue(Map<String, Object> map, String key, T defaultVal) {
        if (map == null || !map.containsKey(key)) return defaultVal;
        
        Object val = map.get(key);
        if (!(val instanceof Number)) return defaultVal;

        Number num = (Number) val;
        

        if (defaultVal instanceof Integer) return (T) Integer.valueOf(num.intValue());
        if (defaultVal instanceof Long) return (T) Long.valueOf(num.longValue());
        if (defaultVal instanceof Double) return (T) Double.valueOf(num.doubleValue());
        if (defaultVal instanceof Float) return (T) Float.valueOf(num.floatValue());
        

        return defaultVal;
    }
} 