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

public class StreakHelper {
    public interface StreakUpdateCallback {
        void onSuccess(int newStreak);
        void onFailure();
    }

    private final Context context;
    private final FirebaseUser user;
    private final SimpleDateFormat sdf;
    private final TimeZone tz;

    public StreakHelper(Context context, TimeZone timeZone) {
        this.context = context.getApplicationContext();
        this.tz = timeZone;
        this.user = FirebaseAuth.getInstance().getCurrentUser();
        this.sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        this.sdf.setTimeZone(tz);
    }

    /**
     * Calculates the consecutive-day streak ending on the given date (yyyy-MM-dd).
     * If the end date is not completed, streak resets to 0.
     */
    public void updateStreak(DocumentReference userRef, List<String> completedDates, String endDateStr, StreakUpdateCallback callback) {
        if (user == null || userRef == null) {
            Log.e("StreakHelper", "User or userRef is null, cannot update streak.");
            if (callback != null) callback.onFailure();
            return;
        }

        userRef.get().addOnSuccessListener(userDoc -> {
            // Build a sorted set of unique completed days (desc)
            TreeSet<Calendar> days = new TreeSet<>((a, b) -> b.compareTo(a));
            
            // Parse end date at midnight
            Calendar endDate = Calendar.getInstance(tz);
            try {
                endDate.setTime(sdf.parse(endDateStr));
            } catch (ParseException e) {
                // Fallback to today if parsing fails
                endDate = Calendar.getInstance(tz);
            }
            endDate.set(Calendar.HOUR_OF_DAY, 0);
            endDate.set(Calendar.MINUTE, 0);
            endDate.set(Calendar.SECOND, 0);
            endDate.set(Calendar.MILLISECOND, 0);
    
            // Populate from passed completedDates list
            for (String ds : completedDates) {
                try {
                    Calendar c = Calendar.getInstance(tz);
                    c.setTime(sdf.parse(ds));
                    c.set(Calendar.HOUR_OF_DAY, 0);
                    c.set(Calendar.MINUTE, 0);
                    c.set(Calendar.SECOND, 0);
                    c.set(Calendar.MILLISECOND, 0);
                    days.add(c);
                } catch (ParseException ignored) {}
            }
    
            // --- Vigor Potion Check --- 
            boolean vigorUsed = false;
            Map<String, Object> activeEffects = new HashMap<>();
            Map<String, Object> effectsToSave = new HashMap<>();
            
            // Get the active effects map from the user document
            if (userDoc.contains("activeEffects") && userDoc.get("activeEffects") instanceof Map) {
                activeEffects = (Map<String, Object>) userDoc.get("activeEffects");
                // Create a mutable copy for potential updates
                effectsToSave = new HashMap<>(activeEffects);
            }
            
            // Create final variables for lambda use
            final boolean[] vigorUsedRef = { false };
            final Map<String, Object> finalEffectsToSave = new HashMap<>(effectsToSave);
            
            if (!days.contains(endDate)) {
                // Check if Vigor Potion is active (directly from Firestore map)
                boolean hasVigorPotion = activeEffects.getOrDefault("vigorPotionUsedToRecoverStreak", false) instanceof Boolean && 
                                        (Boolean) activeEffects.getOrDefault("vigorPotionUsedToRecoverStreak", false);
                
                if (hasVigorPotion) {
                    Log.d("StreakHelper", "Streak broken, but Vigor Potion is active. Attempting recovery.");
                    days.add((Calendar) endDate.clone()); // Simulate today being completed
                    finalEffectsToSave.put("vigorPotionUsedToRecoverStreak", false); // Consume the effect in the map
                    vigorUsedRef[0] = true; // Mark that we need to save updated effect state
                    // Show toast notification
                    Toast.makeText(context, "Vigor Potion saved your streak!", Toast.LENGTH_LONG).show();
                }
            }
            // --- End Vigor Potion Check ---
    
            // Count consecutive streak ending on endDate
            int streak = 0;
            Calendar expect = (Calendar) endDate.clone();
            // Only count if endDate is completed (or was simulated by Vigor Potion)
            if (days.contains(expect)) {
                // count current day and backwards
                do {
                    streak++;
                    expect.add(Calendar.DAY_OF_YEAR, -1);
                } while (days.contains(expect));
            }
    
            // Check if today's date is still in the completedDates list
            String todayStr = sdf.format(Calendar.getInstance(tz).getTime());
            boolean isTodayInCompletedDates = completedDates.contains(todayStr);
            
            // If this is today's streak calculation and the UI is showing an inconsistent value
            // ensure we reset to 0 if today's tasks aren't all done
            if (endDateStr.equals(todayStr) && !isTodayInCompletedDates) {
                streak = 0; // Force streak to 0 if today isn't in completed dates
                Log.d("StreakHelper", "Today not in completed dates, resetting streak to 0");
            }
    
            // Log the streak calculation for debugging
            Log.d("StreakHelper", "Calculated streak: " + streak);
            Log.d("StreakHelper", "Completed dates: " + completedDates);
            Log.d("StreakHelper", "End date: " + endDateStr + ", Today: " + todayStr + ", Today completed: " + isTodayInCompletedDates);
    
            final int streakFinal = streak;
    
            // Persist both completedDates and streak
            FirebaseFirestore.getInstance()
                    .runTransaction(tx -> {
                        tx.update(userRef, "completedDates", completedDates);
                        tx.update(userRef, "streak", streakFinal);
                        // Save updated effects if vigor was used
                        if (vigorUsedRef[0]) {
                            Log.d("StreakHelper", "Saving updated effect state in transaction after Vigor use.");
                            tx.update(userRef, "activeEffects", finalEffectsToSave);
                        }
                        return null;
                    })
                    .addOnSuccessListener(aVoid -> {
                        Log.d("StreakHelper", "Streak updated to " + streakFinal);
                        if (callback != null) callback.onSuccess(streakFinal);
                    })
                    .addOnFailureListener(e -> {
                        Log.e("StreakHelper", "Streak update failed", e);
                        if (callback != null) callback.onFailure();
                    });
        }).addOnFailureListener(e -> {
            Log.e("StreakHelper", "Failed to get user document for streak update", e);
            if (callback != null) callback.onFailure();
        });
    }
}