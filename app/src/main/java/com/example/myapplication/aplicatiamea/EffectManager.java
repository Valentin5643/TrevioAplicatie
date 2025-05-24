package com.example.myapplication.aplicatiamea;

import java.util.HashMap;
import java.util.Map;
import android.util.Log;

/**
 * All the magic potion stuff lives here
 * Used to be a complete disaster 😅
 * Still not perfect but way better than the mess we had before
 * 
 * TODO: Add combo effects, was planning to implement but ran out of time
 */
public class EffectManager {
    private static final String TAG = "EffectMgr"; // shorter tag for logs
    
    // debug flag - turn on for testing
    private static final boolean DEBUG = false;

    // Not really used but keeping it for future stuff
    public enum EffectTiming {
        IMMEDIATE,
        DELAYED,     // might use this later
        OVER_TIME    // for DOT effects
    }

    // --- Potion Effects ---
    // constants to avoid magic strings
    private static final String RAGE_DRAUGHT_EXPIRY = "rageDraughtExpiryTimestamp";
    private static final String DEXTERITY_TASKS = "dexterityTasksRemaining";
    private static final String DEXTERITY_PERCENT = "dexterityTimeReductionPercent";
    
    // my attempt at better potion names - we should use these in the UI too
    // private static final String[] POTION_NAMES = {
    //    "Rage Draught", "Dexterity Serum", "Mindmeld Elixir", "Midas Brew"
    // };

    // Checks if the rage boost is active
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

    // Dexterity potion - makes the next few tasks faster
    public static boolean isDexteritySerumActive(Map<String, Object> effects) { 
        if (effects == null) return false;
        
        int tasksLeft = (int) getNumericValue(effects, DEXTERITY_TASKS, 0);
        
        // hacky fix for negative values that somehow got into prod
        if (tasksLeft < 0) tasksLeft = 0;
        
        return tasksLeft > 0;
    }
    
    // Default 10% time reduction
    public static double getDexterityTimeReduction(Map<String, Object> effects) {
        if (effects == null) return 0.1; // fallback to 10%
        
        // clamp between 5-30% to avoid exploits
        double reduction = getNumericValue(effects, DEXTERITY_PERCENT, 0.1);
        return Math.min(0.3, Math.max(0.05, reduction)); 
    }

    // XP boost - 20% more XP until midnight
    public static double mindmeldXpBoostMultiplier = 1.2; // 1.2x
    public static boolean isMindmeldXpBoostActive(Map<String, Object> effects) {
        if (effects == null) return false;
        
        long expiry = getNumericValue(effects, "mindmeldXpBoostExpiryTimestamp", 0L);
        boolean active = expiry > System.currentTimeMillis();
        
        // for debugging performance issues
        if (DEBUG && active) {
            Log.d(TAG, "XP boost active: " + mindmeldXpBoostMultiplier + "x");
        }
        
        return active;
    }

    // Strength potion doubles coin rewards
    public static final long GIANT_STRENGTH_MULTIPLIER = 2;  // too OP? maybe reduce to 1.5x

    // Midas potion - double coins for 30 min
    public static final long MIDAS_BREW_MULTIPLIER = 2;
    public static boolean isMidasBrewActive(Map<String, Object> effects) {
        if (effects == null) return false;
        
        long expiry = getNumericValue(effects, "midasBrewExpiryTimestamp", 0L);
        return expiry > System.currentTimeMillis();
    }

    // Speed up quests with efficiency potion
    public static double getQuestTimeReductionPercent(Map<String, Object> effects) {
        if (effects == null) return 0.0;
        
        // Default to none if missing
        double reduction = getNumericValue(effects, "questTimeReductionPercent", 0.0);
        
        // tried increasing this but it made quests too easy
        return reduction; 
    }
    
    // calculate total coin multiplier from all effects
    public static double getTotalCoinMultiplier(Map<String, Object> effects) {
        double multiplier = 1.0;
        
        if (effects == null) return multiplier;
        
        // apply Midas brew if active
        if (isMidasBrewActive(effects)) {
            multiplier *= MIDAS_BREW_MULTIPLIER;
        }
        
        // other multipliers go here
        
        return multiplier;
    }

    // Helper to extract numbers safely - Firebase types are a mess
    private static <T extends Number> T getNumericValue(Map<String, Object> map, String key, T defaultVal) {
        if (map == null || !map.containsKey(key)) return defaultVal;
        
        Object val = map.get(key);
        if (!(val instanceof Number)) return defaultVal;
        
        // This is gross but Firebase gives us weird number types sometimes
        Number num = (Number) val;
        
        // Hacky Java type matching
        if (defaultVal instanceof Integer) return (T) Integer.valueOf(num.intValue());
        if (defaultVal instanceof Long) return (T) Long.valueOf(num.longValue());
        if (defaultVal instanceof Double) return (T) Double.valueOf(num.doubleValue());
        if (defaultVal instanceof Float) return (T) Float.valueOf(num.floatValue());
        
        // Shouldn't happen
        return defaultVal;
    }
} 