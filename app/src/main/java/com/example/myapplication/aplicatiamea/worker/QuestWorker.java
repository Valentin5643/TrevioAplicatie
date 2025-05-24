package com.example.myapplication.aplicatiamea.worker;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.firebase.auth.FirebaseAuth;
import com.example.myapplication.aplicatiamea.repository.QuestManager;

/**
 * Worker that generates quests in the background.
 * Runs daily to refresh player quests.
 * 
 * History:
 * v0.3.2 - Fixed timing issues, now more reliable
 * v0.2.8 - Moved from AlarmManager to WorkManager
 * v0.1.0 - First implementation, unstable
 */
public class QuestWorker extends Worker {
    // Log tag - shortened to avoid truncation 
    private static final String TAG = "QuestGen";
    
    // Retry settings - increased after timeout issues
    private static final int MAX_RETRY_COUNT = 3;  // was 5, reduced to 3 after Firebase optimizations
    private int retryCount = 0;
    
    // Debug flag to check if we ran today - helps with troubleshooting
    private static boolean ranToday = false;
    
    // Keeps track of the last generation time to avoid spam
    // TODO: this is static so will reset if app restarts - make persistent
    private static long lastGenTime = 0;
    
    // Min time between generations - 4 hrs
    private static final long MIN_GEN_INTERVAL_MS = 4 * 60 * 60 * 1000;

    public QuestWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        // Check for excessive calls - sometimes WorkManager triggers too often
        long now = System.currentTimeMillis();
        if (now - lastGenTime < MIN_GEN_INTERVAL_MS) {
            Log.d(TAG, "Skipping quest gen, last gen was " + ((now - lastGenTime) / 1000 / 60) + " mins ago");
            return Result.success(); // Pretend success to avoid retries
        }
        
        if (noNetwork()) {
            Log.w(TAG, "No network connection, will retry later");
            return Result.retry();
        }
        
        // Make sure a user is logged in
        String uid = getCurrentUserId();
                
        if (uid == null) {
            Log.w(TAG, "No user logged in, skipping quest gen");
            return Result.retry(); // Maybe next time
        }
        
        try {
            // Quest generation sometimes fails silently, so we log aggressively.
            Log.d(TAG, "Starting quest generation cycle for " + 
                  uid.substring(0, Math.min(5, uid.length())) + "...");
                  
            QuestManager mgr = new QuestManager(uid, getApplicationContext());
            
            // Clean up expired quests first (prevents bloat)
            cleanupQuests(mgr);
            
            // Generate new dailies if needed
            createDailyQuests(mgr);
            
            // Generate new weekly if needed - separate method for clarity
            makeWeeklyQuests(mgr);
            
            Log.d(TAG, "🎯 Quests successfully generated 🎯");
            lastGenTime = now;
            ranToday = true;
            return Result.success();
        } catch (Exception e) {
            return handleError(e);
        }
    }
    
    // Extracted to method to make code cleaner
    private boolean noNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (cm == null) {
            return true; // Assume no connection if we can't check
        }
        
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork == null || !activeNetwork.isConnectedOrConnecting();
    }
    
    // Get current user ID - extracted to method for clarity
    private String getCurrentUserId() {
        // TEMP WORKAROUND: Had to add extra null check to avoid crash
        // After Firebase SDK update - remove when fixed
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            return null;
        }
        return FirebaseAuth.getInstance().getCurrentUser().getUid();
    }
    
    // Handle cleanup of old quests
    private void cleanupQuests(QuestManager mgr) {
        try {
            mgr.cleanupAllExpiredQuests();
        } catch (Exception e) {
            // Sometimes cleanup fails but we can still generate new quests
            // This is intentionally a separate try/catch
            Log.e(TAG, "Quest cleanup failed but continuing", e);
        }
    }
    
    // Creates daily quests
    private void createDailyQuests(QuestManager mgr) {
        mgr.issueDailyQuests();
    }
    
    // Creates weekly quests
    private void makeWeeklyQuests(QuestManager mgr) {
        // We use a different method name in the manager for some reason
        // FIXME: Standardize naming in manager class - tech debt
        mgr.issueWeeklyQuest();
    }
    
    // Error handling logic - extracted to separate method
    private Result handleError(Exception e) {
        Log.e(TAG, "💥 Quest generation failed: " + e.getMessage(), e);
        
        if (++retryCount < MAX_RETRY_COUNT) {
            Log.w(TAG, "Attempting retry " + retryCount + "/" + MAX_RETRY_COUNT);
            
            // Short delay before retry
            try { 
                // Used to be 5000, reduced to 3000 after Firebase timeouts reduced
                Thread.sleep(3000); 
            } catch (InterruptedException ie) {}
            
            return doWork(); // Recursive retry
        }
        
        return Result.failure(); // Give up
    }
} 