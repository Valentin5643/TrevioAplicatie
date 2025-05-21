package com.example.myapplication.aplicatiamea;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages the state of active potion effects.
 * NOTE: This class holds the runtime state. Persistence (saving/loading)
 * needs to be handled separately, likely by saving/loading these values
 * to/from Firestore when effects are activated/deactivated or the app starts.
 */
public class EffectManager {

    /**
     * Enum for effect timing used by quest-related functions
     */
    public enum EffectTiming {
        IMMEDIATE
    }

    // --- Effect States ---

    // 2. Rage Draught (Speed Boost) - Timed effect
    public static boolean isRageDraughtActive(Map<String, Object> effects) {
        long expiry = effects.getOrDefault("rageDraughtExpiryTimestamp", 0L) instanceof Number ? ((Number) effects.getOrDefault("rageDraughtExpiryTimestamp", 0L)).longValue() : 0L;
        return expiry > System.currentTimeMillis();
    }

    // 3. Dexterity Serum (Efficiency Multiplier)
    public static boolean isDexteritySerumActive(Map<String, Object> effects) { 
        int remaining = effects.getOrDefault("dexterityTasksRemaining", 0) instanceof Number ? ((Number) effects.getOrDefault("dexterityTasksRemaining", 0)).intValue() : 0;
        return remaining > 0; 
    }
    public static double getDexterityTimeReduction(Map<String, Object> effects) {
        return effects.getOrDefault("dexterityTimeReductionPercent", 0.1) instanceof Number ? ((Number) effects.getOrDefault("dexterityTimeReductionPercent", 0.1)).doubleValue() : 0.1;
    }

    // 4. Mindmeld Elixir (Daily XP Boost)
    public static double mindmeldXpBoostMultiplier = 1.2;
    public static boolean isMindmeldXpBoostActive(Map<String, Object> effects) {
        long expiry = effects.getOrDefault("mindmeldXpBoostExpiryTimestamp", 0L) instanceof Number ? ((Number) effects.getOrDefault("mindmeldXpBoostExpiryTimestamp", 0L)).longValue() : 0L;
        return expiry > System.currentTimeMillis();
    }

    // 5. Vigor Potion (Streak Recovery) - One-time use flag
    // Checked directly in map

    // 6. Giant Strength Tonic (Double Coin Reward)
    public static final long GIANT_STRENGTH_MULTIPLIER = 2;

    // 7. Clarity Elixir (Highlight Task)
    // Checked directly in map

    // 8. Midas Brew (Timed Coin Bonus)
    public static final long MIDAS_BREW_MULTIPLIER = 2;
    public static boolean isMidasBrewActive(Map<String, Object> effects) {
        long expiry = effects.getOrDefault("midasBrewExpiryTimestamp", 0L) instanceof Number ? ((Number) effects.getOrDefault("midasBrewExpiryTimestamp", 0L)).longValue() : 0L;
        return expiry > System.currentTimeMillis();
    }

    // 9. Efficiency Serum (Quest Time Reduction)
    public static double getQuestTimeReductionPercent(Map<String, Object> effects) {
        return effects.getOrDefault("questTimeReductionPercent", 0.0) instanceof Number ? ((Number) effects.getOrDefault("questTimeReductionPercent", 0.0)).doubleValue() : 0.0;
    }

    // 10. Insight Infusion (Tip/Quote)
    // Checked directly in map

    // --- Helper Methods ---

    // REMOVE loadState as it's no longer needed and references removed fields
    /*
    public static void loadState(Map<String, Object> savedState) {
        if (savedState == null) return;

        // Commented out as these fields are removed
        // ... (removed load logic) ...
    }
    */

    // XP Booster multiplier for next task
    // Deprecated fields - Keeping them temporarily to avoid breaking old applyEffect logic if not updated
    // REMOVE deprecated variables
    /*
    @Deprecated public static double nextTaskXpMultiplier = 1.0;
    @Deprecated public static long speedBoostExpiry = 0;
    @Deprecated public static boolean streakRecovered = false;
    @Deprecated public static boolean doubleCoinsNextTask = false;
    @Deprecated public static long midasStartTime = 0;
    @Deprecated public static boolean insightUsed = false;
    @Deprecated public static boolean clarityActive = false; // Also remove this one
    @Deprecated public static double questTimeReductionPercent = 0; // And this one
    */

    // Store all active effects - Deprecated, use getStateToSave
    // REMOVE deprecated getActiveEffects method
    /*
    @Deprecated
    public static Map<String, Object> getActiveEffects() {
        Map<String, Object> effects = new HashMap<>();
        // ... (references removed fields) ...
        return effects;
    }
    */

    /** Clears one-time effects when they've been used - Deprecated, handle consumption logic where effect is applied */
    // REMOVE deprecated resetOneTimeEffect method
    /*
    @Deprecated
    public static void resetOneTimeEffect(String effectKey) {
        // ... (references removed fields) ...
    }
    */

    // REMOVE deprecated apply methods
    /*
    /** Applies strength potion multiplier and resets the flag. */
    /*
    public static long applyStrengthPotion(long baseXp) {
        // strengthPotionActive = false; // Removed field
        return (long) (baseXp * strengthPotionMultiplier);
    }
    */

    /*
    /** Applies giant strength tonic multiplier and resets the flag. */
    /*
    public static long applyGiantStrengthTonic(long baseCoins) {
        // giantStrengthTonicActive = false; // Removed field
        return baseCoins * 2;
    }
    */

    /*
    /** Applies mindmeld elixir multiplier if active and resets the flag. */
    /*
    public static long applyMindmeldElixir(long baseXp) {
       // if (isMindmeldXpBoostActive()) { // Needs map
       //     mindmeldXpBoostExpiryTimestamp = 0; // Removed field
       //     return (long) (baseXp * mindmeldXpBoostMultiplier);
       // }
        return baseXp;
    }
    */

    /*
    /** Applies midas brew multiplier if active and keeps it active. */
    /*
    public static long applyMidasBrew(long baseCoins) {
       // if (isMidasBrewActive()) { // Needs map
       //     return baseCoins * 2;
       // }
        return baseCoins;
    }
    */

    // Stub fields for backward compatibility
    public static boolean strengthPotionActive = false;
    public static long rageDraughtExpiryTimestamp = 0L;
    public static int dexterityTasksRemaining = 0;
    public static long mindmeldXpBoostExpiryTimestamp = 0L;
    public static boolean vigorPotionUsedToRecoverStreak = false;
    public static boolean giantStrengthTonicActive = false;
    public static boolean clarityActive = false;
    public static long midasBrewExpiryTimestamp = 0L;
    public static double questTimeReductionPercent = 0.0;
    public static boolean insightInfusionPending = false;

    // Stub methods for backward compatibility
    public static void loadState(Map<String, Object> savedState) { }
    public static void resetVolatileEffects() { }
    public static Map<String, Object> getStateToSave() { return new HashMap<>(); }
} 