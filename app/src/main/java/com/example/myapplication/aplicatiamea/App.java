package com.example.myapplication.aplicatiamea;

import android.app.Application;
import android.util.Log;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.OneTimeWorkRequest;
import com.example.myapplication.aplicatiamea.util.ThemeHelper;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.security.ProviderInstaller;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;
import com.example.myapplication.aplicatiamea.worker.ChallengeWorker;
import com.example.myapplication.aplicatiamea.worker.QuestWorker;
import com.example.myapplication.aplicatiamea.util.NativeLibraryHelper;
import com.google.firebase.FirebaseApp;

public class App extends Application {
    private static final String TAG = "Trevio";

    private static final boolean FORCE_QUEST_REFRESH = true;
    
    private static App instance;
    
    public static App getInstance() {
        return instance;  // should probably check for null here but eh
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        
        boolean hasPlayServices = trySetupGoogleServices();
        tryStartFirebase(); // separate method since it's gotten complex

        loadNativeLibsIfSupported();
        
        ThemeHelper.applyUserTheme(this);

        if (hasPlayServices) {
            scheduleBackgroundWork();
        } else {
            Log.w(TAG, "No Play Services");
        }
        
        if (FORCE_QUEST_REFRESH) {
            forceQuestGeneration();
        }
    }

    private void tryStartFirebase() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this);
                Log.d(TAG, "Firebase initialized successfully");
            } else {
                Log.d(TAG, "Firebase already initialized");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Firebase", e);
            try {
                Thread.sleep(1000);
                FirebaseApp.initializeApp(this);
                Log.d(TAG, "Firebase initialized successfully on retry");
            } catch (Exception retryException) {
                Log.e(TAG, "Failed to initialize Firebase on retry", retryException);
            }
        }
    }

    private boolean trySetupGoogleServices() {
        try {
            GoogleApiAvailability gApi = GoogleApiAvailability.getInstance();
            int result = gApi.isGooglePlayServicesAvailable(this);
            
            if (result == ConnectionResult.SUCCESS) {
                Log.d(TAG, "Google Play Services is available and up to date");
                tryFixSecurityProvider(); // SSL security patch while we're here
                return true;
            } else {
                Log.w(TAG, "Google Play Services issue: " + gApi.getErrorString(result));
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking Google Play Services availability", e);
            return false;
        }
    }

    private void loadNativeLibsIfSupported() {
        try {
            if (NativeLibraryHelper.loadPenguinLibrary()) {
                Log.d(TAG, "Native acceleration enabled");
            } else {
                Log.d(TAG, "Running on normal acceleration");
            }
        } catch(Throwable t) {
            Log.e(TAG, "Native library error (app will continue): " + t.getMessage());
        }
    }

    private void tryFixSecurityProvider() {
        try {
            ProviderInstaller.installIfNeeded(this);
        } catch (Exception e) {
            Log.w(TAG, "Security provider update failed: " + e.getMessage());
        }
    }

    private void scheduleBackgroundWork() {
        Log.d(TAG, "Scheduling background work");
        
        try {
            WorkManager wm = WorkManager.getInstance(this);
            long midnightDelay = getInitialDelayUntilMidnight(1);

            PeriodicWorkRequest dailyWork = new PeriodicWorkRequest.Builder(ChallengeWorker.class, 1, TimeUnit.DAYS)
                .setInitialDelay(midnightDelay, TimeUnit.MILLISECONDS)
                .setInputData(new Data.Builder().putString("frequency", "DAILY").build())
                .build();
            
            wm.enqueueUniquePeriodicWork("daily_challenges", ExistingPeriodicWorkPolicy.REPLACE, dailyWork);

            PeriodicWorkRequest weeklyWork = new PeriodicWorkRequest.Builder(ChallengeWorker.class, 7, TimeUnit.DAYS)
                .setInitialDelay(midnightDelay, TimeUnit.MILLISECONDS)
                .setInputData(new Data.Builder().putString("frequency", "WEEKLY").build())
                .build();
            
            wm.enqueueUniquePeriodicWork("weekly_challenges", ExistingPeriodicWorkPolicy.REPLACE, weeklyWork);

            PeriodicWorkRequest questWork = new PeriodicWorkRequest.Builder(QuestWorker.class, 1, TimeUnit.DAYS)
                .setInitialDelay(midnightDelay, TimeUnit.MILLISECONDS)
                .build();
            
            wm.enqueueUniquePeriodicWork("daily_quests", ExistingPeriodicWorkPolicy.REPLACE, questWork);

            OneTimeWorkRequest immediateQuestWork = new OneTimeWorkRequest.Builder(QuestWorker.class)
                .build();
            wm.enqueue(immediateQuestWork);
            
            Log.d(TAG, "Work scheduling complete");
        } catch (Exception e) {
            Log.e(TAG, "Work scheduling failed!", e);
        }
    }

    private long getInitialDelayUntilMidnight(int daysAhead) {
        Calendar now = Calendar.getInstance();
        Calendar nextTime = Calendar.getInstance();
        

        nextTime.set(Calendar.HOUR_OF_DAY, 0);
        nextTime.set(Calendar.MINUTE, 0);
        nextTime.set(Calendar.SECOND, 0);
        nextTime.set(Calendar.MILLISECOND, 0);
        

        if (nextTime.before(now)) {
            nextTime.add(Calendar.DAY_OF_YEAR, 1);
        }
        

        if (daysAhead > 1) {
            nextTime.add(Calendar.DAY_OF_YEAR, daysAhead - 1);
        }

        return Math.max(1000, nextTime.getTimeInMillis() - now.getTimeInMillis());
    }


    public void forceQuestGeneration() {
        Log.d(TAG, "Manually forcing quest generation");
        OneTimeWorkRequest immediateQuest = new OneTimeWorkRequest.Builder(QuestWorker.class).build();
        WorkManager.getInstance(this).enqueue(immediateQuest);
    }
} 