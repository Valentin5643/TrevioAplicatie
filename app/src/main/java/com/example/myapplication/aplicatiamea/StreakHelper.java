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
    private static final String TAG = "StreakEngine";

    private static final int MAX_DAYS_TO_CHECK = 60;

    private static final long RECALC_INTERVAL_MS = 1000 * 60 * 60 * 6;

    private static long lastRecalculationTime = 0;

    private static final boolean ENABLE_EXPERIMENTAL = false;

    private static final int GRACE_PERIOD_DAYS = 0;

    public interface StreakUpdateCallback {
        void onSuccess(int newStreak);
        void onFailure();
    }

    public interface StreakCallback {
        void onSuccess(int streak);
        void onFailure();
    }

    private final Context ctx;
    private final FirebaseUser user;
    private final SimpleDateFormat sdf;
    private final TimeZone tz;

    private boolean debugMode = false;

    public StreakHelper(Context context, TimeZone timeZone) {
        this.ctx = context.getApplicationContext();
        this.tz = timeZone;
        this.user = FirebaseAuth.getInstance().getCurrentUser();
        this.sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        this.sdf.setTimeZone(tz);

    }

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
        

        if (completedDates == null || completedDates.size() > 500) {
            Log.e(TAG, "Bad completedDates list: " + (completedDates == null ? "null" : completedDates.size()));
            if (callback != null) callback.onFailure();
            return;
        }


        userRef.get().addOnSuccessListener(userDoc -> {

            TreeSet<Calendar> days = new TreeSet<>((a, b) -> b.compareTo(a));

            Calendar endDate = Calendar.getInstance(tz);
            try {
                endDate.setTime(sdf.parse(endDateStr));
            } catch (ParseException e) {
                Log.w(TAG, "Failed to parse endDate: " + endDateStr);
            }

            zeroOutTime(endDate);
            for (String dateStr : completedDates) {
                try {
                    Calendar cal = Calendar.getInstance(tz);
                    cal.setTime(sdf.parse(dateStr));
                    zeroOutTime(cal);
                    days.add(cal);
                } catch (ParseException e) {
                    Log.w(TAG, "Bad date in streak calc: " + dateStr);
                }
            }

            boolean vigorUsed = false;

            Map<String, Object> effects = new HashMap<>();
            if (userDoc.contains("activeEffects")) {
                Object effectsObj = userDoc.get("activeEffects");
                if (effectsObj instanceof Map) {
                    effects = (Map<String, Object>) effectsObj;
                }
            }
            
            Map<String, Object> updatedEffects = new HashMap<>(effects);

            if (!days.contains(endDate)) {
                boolean hasVigor = effects.containsKey("vigorPotionUsedToRecoverStreak") && 
                               Boolean.TRUE.equals(effects.get("vigorPotionUsedToRecoverStreak"));
                
                if (hasVigor) {
                    Log.d(TAG, "🧪 VIGOR POTION ACTIVATED 🧪");
                    days.add((Calendar) endDate.clone());  
                    updatedEffects.put("vigorPotionUsedToRecoverStreak", false);
                    vigorUsed = true;

                    Toast.makeText(ctx, "✨ Vigor Potion saved your streak! ✨", Toast.LENGTH_LONG).show();
                }
            }

            int streakCount = 0;
            Calendar checkDate = (Calendar) endDate.clone();
            
            if (days.contains(checkDate)) {
                int daysChecked = 0;
                do {
                    streakCount++;
                    checkDate.add(Calendar.DAY_OF_YEAR, -1);
                    daysChecked++;
                    if (daysChecked > MAX_DAYS_TO_CHECK) {
                        Log.w(TAG, "Hit max days in streak calc - stopping at " + streakCount);
                        break;
                    }
                } while (days.contains(checkDate));
            }

            if (ENABLE_EXPERIMENTAL && GRACE_PERIOD_DAYS > 0 && streakCount > 0) {
                Calendar graceDate = (Calendar) checkDate.clone();
                graceDate.add(Calendar.DAY_OF_YEAR, -GRACE_PERIOD_DAYS);
                
                if (days.contains(graceDate)) {
                    Calendar nextCheck = (Calendar) graceDate.clone();
                    int extraDays = 1;

                    do {
                        extraDays++;
                        nextCheck.add(Calendar.DAY_OF_YEAR, -1);
                    } while (days.contains(nextCheck) && extraDays < MAX_DAYS_TO_CHECK);

                    streakCount += extraDays;
                    Log.d(TAG, "Applied grace period! Added " + extraDays + " days to streak");
                }
            }

            String todayStr = sdf.format(Calendar.getInstance(tz).getTime());
            boolean isTodayCompleted = completedDates.contains(todayStr);

            if (endDateStr.equals(todayStr) && !isTodayCompleted && !vigorUsed) {
                streakCount = 0;
                Log.d(TAG, "Today not complete, streak broken");
            }
    
            // debug
            Log.d(TAG, String.format("Streak: %d, dates: %d, end: %s", 
                    streakCount, completedDates.size(), endDateStr));

            boolean shouldUpdateCompletedDates = 
                vigorUsed || 
                System.currentTimeMillis() - lastRecalculationTime > RECALC_INTERVAL_MS;

            final int newStreak = streakCount;
            final boolean finalVigorUsed = vigorUsed;
            final boolean shouldSaveDates = shouldUpdateCompletedDates;
    

            FirebaseFirestore db = FirebaseFirestore.getInstance();
            db.runTransaction(tx -> {
                    tx.update(userRef, "streak", newStreak);

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

    private void zeroOutTime(Calendar cal) {
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
    }


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
}