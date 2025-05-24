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
    private static final String TAG = "TrevioApp";
    
    // lol let's not turn this on by accident again
    private static final boolean FORCE_QUEST_REFRESH = false;
    
    // Singleton pattern stuff
    private static App instance;  // mihai likes lowercase for static vars
    
    public static App getInstance() {
        return instance;  // should probably check for null here but meh
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        
        // Firebase & Google Services - do these early since everything depends on them
        boolean hasPlayServices = trySetupGoogleServices();
        tryStartFirebase(); // separate method since it's gotten complex
        
        // Hardware acceleration stuff - only load if device supports it
        loadNativeLibsIfSupported();
        
        // Dark theme preference from last session
        ThemeHelper.applyUserTheme(this);
        
        // Background tasks - but only if Google Play is working
        if (hasPlayServices) {
            scheduleBackgroundRefreshes();
        } else {
            Log.w(TAG, "No Play Services - running in degraded mode");
        }
        
        // Debug quest forcing for testing new quest algorithms
        if (FORCE_QUEST_REFRESH) {
            Log.d(TAG, "DEBUG MODE: Generating quests immediately");
            OneTimeWorkRequest immediateQuest = new OneTimeWorkRequest.Builder(QuestWorker.class).build();
            WorkManager.getInstance(this).enqueue(immediateQuest);
        }
    }

    // Firebase initialization has gotten messy with all the edge cases we've hit
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
            // One retry after delay - sometimes first attempt fails on slow devices
            try {
                Thread.sleep(1000);
                FirebaseApp.initializeApp(this);
                Log.d(TAG, "Firebase initialized successfully on retry");
            } catch (Exception retryException) {
                Log.e(TAG, "Failed to initialize Firebase on retry", retryException);
            }
        }
    }

    // Google Play Services check - returns true if we can use WorkManager etc.
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

    // Optional native library loading - penguin lib gives us hardware acceleration
    // but app works fine without it on most devices
    private void loadNativeLibsIfSupported() {
        try {
            if (NativeLibraryHelper.loadPenguinLibrary()) {
                Log.d(TAG, "Native acceleration enabled");
            } else {
                Log.d(TAG, "Running without native acceleration (normal for most devices)");
            }
        } catch(Throwable t) {
            // Catch everything to prevent startup crashes - this lib is optional
            Log.e(TAG, "Native library initialization error (app will continue): " + t.getMessage());
        }
    }

    // Android security patch for some Samsung/Huawei devices with broken TLS.
    // Without this, Firebase connections can fail on some Android 4.4-6.0 devices.
    private void tryFixSecurityProvider() {
        // this sometimes crashes on Xiaomi devices for stupid reason
        // but apparently we need it for old samsungs or something idk
        try {
            ProviderInstaller.installIfNeeded(this);
        } catch (Exception e) {
            // Not a big deal, firebase usually works anyway
            Log.w(TAG, "Security provider update failed: " + e.getMessage());
        }
    }

    // Daily/weekly challenge and quest scheduling
    private void scheduleBackgroundRefreshes() {
        try {
            WorkManager wm = WorkManager.getInstance(this);
            long midnightDelay = getInitialDelayUntilMidnight(1);
            
            // Daily challenge refresh at midnight
            PeriodicWorkRequest dailyWork = new PeriodicWorkRequest.Builder(ChallengeWorker.class, 1, TimeUnit.DAYS)
                .setInitialDelay(midnightDelay, TimeUnit.MILLISECONDS)
                .setInputData(new Data.Builder().putString("frequency", "DAILY").build())
                .build();
            
            wm.enqueueUniquePeriodicWork("daily_challenges", ExistingPeriodicWorkPolicy.REPLACE, dailyWork);
    
            // Weekly challenges - same timing but every 7 days
            PeriodicWorkRequest weeklyWork = new PeriodicWorkRequest.Builder(ChallengeWorker.class, 7, TimeUnit.DAYS)
                .setInitialDelay(midnightDelay, TimeUnit.MILLISECONDS)
                .setInputData(new Data.Builder().putString("frequency", "WEEKLY").build())
                .build();
            
            wm.enqueueUniquePeriodicWork("weekly_challenges", ExistingPeriodicWorkPolicy.REPLACE, weeklyWork);
    
            // Daily quests - KEEP policy prevents duplicate quests if app restarts
            PeriodicWorkRequest questWork = new PeriodicWorkRequest.Builder(QuestWorker.class, 1, TimeUnit.DAYS)
                .setInitialDelay(midnightDelay, TimeUnit.MILLISECONDS)
                .build();
            
            wm.enqueueUniquePeriodicWork("daily_quests", ExistingPeriodicWorkPolicy.KEEP, questWork);
            
            Log.d(TAG, "Work scheduling complete");
        } catch (Exception e) {
            // After countless crashes on weird OEM devices, I'm not taking chances - Mihai
            Log.e(TAG, "Work scheduling failed!", e);
        }
    }

    // Calculate milliseconds until next midnight + N days
    private long getInitialDelayUntilMidnight(int daysAhead) {
        Calendar now = Calendar.getInstance();
        Calendar nextTime = Calendar.getInstance();
        
        // Reset to midnight
        nextTime.set(Calendar.HOUR_OF_DAY, 0);
        nextTime.set(Calendar.MINUTE, 0);
        nextTime.set(Calendar.SECOND, 0);
        nextTime.set(Calendar.MILLISECOND, 0);
        
        // Make sure we're looking at NEXT midnight, not past
        if (nextTime.before(now)) {
            nextTime.add(Calendar.DAY_OF_YEAR, 1);
        }
        
        // Add extra days if needed
        if (daysAhead > 1) {
            nextTime.add(Calendar.DAY_OF_YEAR, daysAhead - 1);
        }
        
        // always make sure we delay at least a little bit
        // weird bug on samsung devices otherwise
        return Math.max(1000, nextTime.getTimeInMillis() - now.getTimeInMillis());
    }
} 