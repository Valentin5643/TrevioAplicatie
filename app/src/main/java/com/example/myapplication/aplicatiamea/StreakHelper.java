package com.example.myapplication.aplicatiamea;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.Map;
import java.util.HashMap;

/**
 * Handles streak calculation, potion effects, and Firebase updates.
 * 
 * IMPORTANT: This is the most bug-prone part of the app!
 * Remember users get REALLY mad if they lose their streaks.
 */
public class StreakHelper {
    private static final String TAG = "StreakEngine"; // old name but kept for consistency
    
    // Max number of days to consider in calculation (prevents runaway loops)
    private static final int MAX_DAYS_TO_CHECK = 60; // 2 months should be enough for anyone
    
    // How often to save full calculations (to save bandwidth)
    private static final long RECALC_INTERVAL_MS = 1000 * 60 * 60 * 6; // 6 hours  
    
    // Last time we did full calculation to avoid excessive Firestore writes
    private static long lastRecalculationTime = 0;
    
    // Let's hope no one notices we're testing this secretly
    private static final boolean ENABLE_EXPERIMENTAL = false;
    
    // Might change this to 1 day of grace period later
    private static final int GRACE_PERIOD_DAYS = 0;
    
    // Callback so we can tell the UI when we're done (or failed)
    public interface StreakUpdateCallback {
        void onSuccess(int newStreak);
        void onFailure();
    }
    
    // Simple callback interface for TaskAdapter
    public interface StreakCallback {
        void onSuccess(int streak);
        void onFailure();
    }

    // needed stuff
    private final Context ctx;
    private final FirebaseUser user;
    private final SimpleDateFormat sdf;
    private final TimeZone tz;
    
    // debug flag - set to true when testing
    private boolean debugMode = false;

    public StreakHelper(Context context, TimeZone timeZone) {
        this.ctx = context.getApplicationContext();
        this.tz = timeZone;
        this.user = FirebaseAuth.getInstance().getCurrentUser();
        this.sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        this.sdf.setTimeZone(tz);
        
        // just for testing, remove in prod
        //testStreakCalculation();
    }

    /**
     * Calculates user's streak based on completed days.
     * 
     * This madness is critical to retention so DON'T TOUCH unless
     * you're ready to test extensively. Can easily break streaks.
     * 
     * Trust me, I spent an entire weekend fixing this once.
     */
    public void updateStreak(DocumentReference userRef, List<String> completedDates, 
                            String endDateStr, StreakUpdateCallback callback) {
        
        if (user == null) {
            Log.e(TAG, "No user - abort streak calc");
            if (callback != null) callback.onFailure();
            return;
        }
        
        if (userRef == null) {
            Log.e(TAG, "Missing userRef");
            if (callback != null) callback.onFailure();
            return;
        }
        
        // Quick validation - too few or too many dates
        if (completedDates == null || completedDates.size() > 500) {
            Log.e(TAG, "Bad completedDates list: " + (completedDates == null ? "null" : completedDates.size()));
            if (callback != null) callback.onFailure();
            return;
        }

        // Get user document to check effects
        userRef.get().addOnSuccessListener(userDoc -> {
            // Use TreeSet for super-fast date comparisons
            TreeSet<Calendar> days = new TreeSet<>((a, b) -> b.compareTo(a));
            
            // Parse end date (what day are we checking)
            Calendar endDate = Calendar.getInstance(tz);
            try {
                endDate.setTime(sdf.parse(endDateStr));
            } catch (ParseException e) {
                Log.w(TAG, "Failed to parse endDate: " + endDateStr);
            }
            
            // Zero out time portion
            zeroOutTime(endDate);
    
            // Parse all completion dates into Calendar objects
            for (String dateStr : completedDates) {
                try {
                    Calendar cal = Calendar.getInstance(tz);
                    cal.setTime(sdf.parse(dateStr));
                    zeroOutTime(cal);
                    days.add(cal);
                } catch (ParseException e) {
                    // Skip bad dates - might mean data corruption
                    Log.w(TAG, "Bad date in streak calc: " + dateStr);
                }
            }
    
            // --- Vigor Potion Check --- 
            boolean vigorUsed = false;
            
            // Extract active effects
            Map<String, Object> effects = new HashMap<>();
            if (userDoc.contains("activeEffects")) {
                Object effectsObj = userDoc.get("activeEffects");
                if (effectsObj instanceof Map) {
                    effects = (Map<String, Object>) effectsObj;
                }
            }
            
            Map<String, Object> updatedEffects = new HashMap<>(effects);
            
            // Activate Vigor potion if needed to save streak
            if (!days.contains(endDate)) {
                boolean hasVigor = effects.containsKey("vigorPotionUsedToRecoverStreak") && 
                               Boolean.TRUE.equals(effects.get("vigorPotionUsedToRecoverStreak"));
                
                if (hasVigor) {
                    Log.d(TAG, "🧪 VIGOR POTION ACTIVATED 🧪");
                    days.add((Calendar) endDate.clone());  
                    updatedEffects.put("vigorPotionUsedToRecoverStreak", false);
                    vigorUsed = true;
                    
                    // UI feedback
                    Toast.makeText(ctx, "✨ Vigor Potion saved your streak! ✨", Toast.LENGTH_LONG).show();
                }
            }
    
            // Actual streak calculation (work backwards counting days)
            int streakCount = 0;  // renamed from streak to be clearer
            Calendar checkDate = (Calendar) endDate.clone();
            
            if (days.contains(checkDate)) {
                int daysChecked = 0;
                do {
                    streakCount++;
                    checkDate.add(Calendar.DAY_OF_YEAR, -1);
                    daysChecked++;
                    // Safety valve to prevent infinite loops or excessive calculations
                    if (daysChecked > MAX_DAYS_TO_CHECK) {
                        Log.w(TAG, "Hit max days in streak calc - stopping at " + streakCount);
                        break;
                    }
                } while (days.contains(checkDate));
            }
            
            // Experimental: Grace period for 1 missed day
            // TODO: Test this more and probably remove if it causes problems
            if (ENABLE_EXPERIMENTAL && GRACE_PERIOD_DAYS > 0 && streakCount > 0) {
                Calendar graceDate = (Calendar) checkDate.clone();
                graceDate.add(Calendar.DAY_OF_YEAR, -GRACE_PERIOD_DAYS);
                
                if (days.contains(graceDate)) {
                    // We found another streak after a gap, so add the missed day + new streak
                    Calendar nextCheck = (Calendar) graceDate.clone();
                    int extraDays = 1; // Count the grace day
                    
                    // And keep counting additional streak days
                    do {
                        extraDays++;
                        nextCheck.add(Calendar.DAY_OF_YEAR, -1);
                    } while (days.contains(nextCheck) && extraDays < MAX_DAYS_TO_CHECK);
                    
                    // Add the grace day + extra streak days
                    streakCount += extraDays;
                    Log.d(TAG, "Applied grace period! Added " + extraDays + " days to streak");
                }
            }
    
            // Double-check today isn't an edge case
            String todayStr = sdf.format(Calendar.getInstance(tz).getTime());
            boolean isTodayCompleted = completedDates.contains(todayStr);
            
            // Today isn't done, but we're calculating for today = no streak  
            if (endDateStr.equals(todayStr) && !isTodayCompleted && !vigorUsed) {
                streakCount = 0;
                Log.d(TAG, "Today not complete, streak broken");
            }
    
            // For debug
            Log.d(TAG, String.format("Streak: %d, dates: %d, end: %s", 
                    streakCount, completedDates.size(), endDateStr));
                    
            // Optimization: only save the full list of dates periodically
            // to avoid writing the whole array every time
            boolean shouldUpdateCompletedDates = 
                vigorUsed || 
                System.currentTimeMillis() - lastRecalculationTime > RECALC_INTERVAL_MS;
    
            // Final refs for lambda
            final int newStreak = streakCount;
            final boolean finalVigorUsed = vigorUsed;
            final boolean shouldSaveDates = shouldUpdateCompletedDates;
    
            // Write changes to Firestore
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            db.runTransaction(tx -> {
                    tx.update(userRef, "streak", newStreak);
                    
                    // Only save the dates list if we need to (saves bandwidth)
                    if (shouldSaveDates) {
                        tx.update(userRef, "completedDates", completedDates);
                        lastRecalculationTime = System.currentTimeMillis();
                    }
                    
                    if (finalVigorUsed) {
                        tx.update(userRef, "activeEffects", updatedEffects);
                    }
                    return null;
                })
                .addOnSuccessListener(aVoid -> {
                    if (callback != null) callback.onSuccess(newStreak);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Transaction failed: " + e.getMessage());
                    if (callback != null) callback.onFailure();
                });
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Couldn't fetch user doc: " + e.getMessage());
            if (callback != null) callback.onFailure();
        });
    }
    
    // renamed from resetTimeToMidnight for clarity
    private void zeroOutTime(Calendar cal) {
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
    }

    /**
     * Calculates current streak and calls back with the result
     * Just a simple getter from Firestore
     */
    public void calculateStreak(StreakCallback callback) {
        if (user == null) {
            Log.e(TAG, "No user - abort streak calc");
            if (callback != null) callback.onFailure();
            return;
        }
        
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        DocumentReference userRef = db.collection("users").document(user.getUid());
        
        userRef.get().addOnSuccessListener(doc -> {
            if (doc.exists()) {
                Integer streak = doc.getLong("streak") != null ? doc.getLong("streak").intValue() : 0;
                if (callback != null) {
                    callback.onSuccess(streak);
                }
            } else {
                Log.e(TAG, "User document doesn't exist");
                if (callback != null) callback.onFailure();
            }
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to get streak data: " + e.getMessage());
            if (callback != null) callback.onFailure();
        });
    }
    
    // left this here in case we need to test again
    /*
    private void testStreakCalculation() {
        // Create some test dates
        List<String> testDates = new ArrayList<>();
        testDates.add("2023-05-01");
        testDates.add("2023-05-02");
        testDates.add("2023-05-03");
        // Missing 2023-05-04
        testDates.add("2023-05-05");
        testDates.add("2023-05-06");
        
        Log.d(TAG, "TEST STREAK CALCULATION");
        // Test with no grace period
        GRACE_PERIOD_DAYS = 0;
        // Calculate streak as of May 6 (should be 2)
        int streak = calculateOfflineStreak(testDates, "2023-05-06");
        Log.d(TAG, "No grace period: " + streak);
        
        // Test with grace period of 1
        GRACE_PERIOD_DAYS = 1; 
        // Calculate streak as of May 6 (should be 6)
        streak = calculateOfflineStreak(testDates, "2023-05-06");
        Log.d(TAG, "With grace period: " + streak);
    }
    
    private int calculateOfflineStreak(List<String> dates, String endDateStr) {
        // This is a copy of the main calculation for testing
        // Removed to save space
        return 0;
    }
    */
}