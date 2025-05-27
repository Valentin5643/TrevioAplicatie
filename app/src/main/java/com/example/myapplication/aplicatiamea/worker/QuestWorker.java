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
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


public class QuestWorker extends Worker {
    private static final String TAG = "QuestGen";
    
    private static final int MAX_RETRY_COUNT = 3;
    private int retryCount = 0;

    private static boolean ranToday = false;
    

    private static long lastGenTime = 0;
    

    private static final long MIN_GEN_INTERVAL_MS = 4 * 60 * 60 * 1000;

    public QuestWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String currentTime = sdf.format(new Date());
        Log.d(TAG, "🎯🎯🎯 QuestWorker started at " + currentTime + " 🎯🎯🎯");
        
        long now = System.currentTimeMillis();
        if (now - lastGenTime < MIN_GEN_INTERVAL_MS) {
            Log.d(TAG, "Skipping quest gen, last gen was " + ((now - lastGenTime) / 1000 / 60) + " mins ago");
            return Result.success(); // Pretend success to avoid retries
        }
        
        if (noNetwork()) {
            Log.w(TAG, "No network connection, will retry later");
            return Result.retry();
        }
        
        String uid = getCurrentUserId();
                
        if (uid == null) {
            Log.w(TAG, "No user logged in, skipping quest gen");
            return Result.retry(); // Maybe next time
        }
        
        try {
            Log.d(TAG, "🎯 Starting quest generation cycle for " +
                  uid.substring(0, Math.min(5, uid.length())) + "...");
                  
            QuestManager mgr = new QuestManager(uid, getApplicationContext());
            
            Log.d(TAG, "🎯 Step 1: Cleaning up expired quests");
            cleanupQuests(mgr);
            
            Log.d(TAG, "🎯 Step 2: Generating daily quests");
            createDailyQuests(mgr);
            
            Log.d(TAG, "🎯 Step 3: Generating weekly quests");
            makeWeeklyQuests(mgr);
            
            Log.d(TAG, "🎯 Step 4: Refreshing quest status");
            triggerQuestStatusRefresh(uid);
            
            Log.d(TAG, "🎯 Quests successfully generated 🎯");
            lastGenTime = now;
            ranToday = true;
            return Result.success();
        } catch (Exception e) {
            return handleError(e);
        }
    }
    
    private boolean noNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (cm == null) {
            return true;
        }
        
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork == null || !activeNetwork.isConnectedOrConnecting();
    }
    

    private String getCurrentUserId() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            return null;
        }
        return FirebaseAuth.getInstance().getCurrentUser().getUid();
    }
    
    private void cleanupQuests(QuestManager mgr) {
        try {
            mgr.cleanupAllExpiredQuests();
            Log.d(TAG, "🎯 Successfully cleaned up expired quests");
        } catch (Exception e) {
            Log.e(TAG, "Quest cleanup failed but continuing", e);
        }
    }
    
    private void createDailyQuests(QuestManager mgr) {
        try {
            mgr.issueDailyQuests();
            Log.d(TAG, "🎯 Successfully issued daily quests");
        } catch (Exception e) {
            Log.e(TAG, "Failed to issue daily quests", e);
        }
    }
    
    private void makeWeeklyQuests(QuestManager mgr) {
        try {
            mgr.makeWeeklyQuests();
            Log.d(TAG, "🎯 Successfully issued weekly quests");
        } catch (Exception e) {
            Log.e(TAG, "Failed to issue weekly quests", e);
        }
    }
    
    private void triggerQuestStatusRefresh(String uid) {
        try {
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            DocumentReference userRef = db.collection("users").document(uid);
            
            QuestManager mgr = new QuestManager(uid, getApplicationContext());
            mgr.forceQuestRefresh(userRef);
            
            Map<String, Object> refreshMarker = new HashMap<>();
            refreshMarker.put("lastQuestRefresh", System.currentTimeMillis());
            refreshMarker.put("refreshSource", "QuestWorker");
            
            userRef.update("questState", refreshMarker)
                .addOnSuccessListener(v -> Log.d(TAG, "Updated quest refresh marker"))
                .addOnFailureListener(e -> Log.w(TAG, "Failed to update quest refresh marker", e));
                
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing quest status", e);
        }
    }
    
    private Result handleError(Exception e) {
        Log.e(TAG, "💥 Quest generation failed: " + e.getMessage(), e);
        
        if (++retryCount < MAX_RETRY_COUNT) {
            Log.w(TAG, "Attempting retry " + retryCount + "/" + MAX_RETRY_COUNT);
            
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ie) {}
            
            return doWork();
        }
        
        return Result.failure();
    }
} 