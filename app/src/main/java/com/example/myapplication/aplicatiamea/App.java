package com.example.myapplication.aplicatiamea;

import android.app.Application;
import android.util.Log;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.OneTimeWorkRequest;
import com.google.android.gms.security.ProviderInstaller;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;
import com.example.myapplication.aplicatiamea.worker.ChallengeWorker;
import com.example.myapplication.aplicatiamea.worker.QuestWorker;

public class App extends Application {
    private static final String TAG = "App";

    @Override
    public void onCreate() {
        super.onCreate();
        
        // Update Android security provider to avoid SSL errors
        updateAndroidSecurityProvider();
        
        scheduleChallenges();
        // Issue today's quest immediately on startup (for testing)
        OneTimeWorkRequest immediateQuest = new OneTimeWorkRequest.Builder(QuestWorker.class).build();
        WorkManager.getInstance(this).enqueue(immediateQuest);
    }

    /**
     * Update Android security provider to handle potential SSL/TLS errors 
     * caused by older or missing Google Play Services
     */
    private void updateAndroidSecurityProvider() {
        try {
            ProviderInstaller.installIfNeeded(this);
        } catch (Exception e) {
            // Google Play Services is not available or outdated
            Log.e(TAG, "Error installing security provider", e);
            // Continue app execution - non-fatal error
        }
    }

    private void scheduleChallenges() {
        // Schedule daily challenges at next midnight, repeating every 24 hours
        long dailyDelay = computeInitialDelayMillis(1);
        PeriodicWorkRequest dailyWork = new PeriodicWorkRequest.Builder(ChallengeWorker.class, 1, TimeUnit.DAYS)
            .setInitialDelay(dailyDelay, TimeUnit.MILLISECONDS)
            .setInputData(new Data.Builder().putString("frequency", "DAILY").build())
            .build();
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("daily_challenges", ExistingPeriodicWorkPolicy.REPLACE, dailyWork);

        // Schedule weekly challenges at next midnight, repeating every 7 days
        long weeklyDelay = computeInitialDelayMillis(1);
        PeriodicWorkRequest weeklyWork = new PeriodicWorkRequest.Builder(ChallengeWorker.class, 7, TimeUnit.DAYS)
            .setInitialDelay(weeklyDelay, TimeUnit.MILLISECONDS)
            .setInputData(new Data.Builder().putString("frequency", "WEEKLY").build())
            .build();
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("weekly_challenges", ExistingPeriodicWorkPolicy.REPLACE, weeklyWork);

        // Schedule daily quests at midnight
        PeriodicWorkRequest questWork = new PeriodicWorkRequest.Builder(QuestWorker.class, 1, TimeUnit.DAYS)
            .setInitialDelay(computeInitialDelayMillis(1), TimeUnit.MILLISECONDS)
            .build();
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("daily_quests", ExistingPeriodicWorkPolicy.KEEP, questWork);
    }

    private long computeInitialDelayMillis(int daysAhead) {
        Calendar now = Calendar.getInstance();
        Calendar nextMidnight = (Calendar) now.clone();
        nextMidnight.set(Calendar.HOUR_OF_DAY, 0);
        nextMidnight.set(Calendar.MINUTE, 0);
        nextMidnight.set(Calendar.SECOND, 0);
        nextMidnight.set(Calendar.MILLISECOND, 0);
        nextMidnight.add(Calendar.DAY_OF_YEAR, daysAhead);
        return nextMidnight.getTimeInMillis() - now.getTimeInMillis();
    }
} 