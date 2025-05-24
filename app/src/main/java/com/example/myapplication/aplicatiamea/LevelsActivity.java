package com.example.myapplication.aplicatiamea;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.myapplication.aplicatiamea.util.ThemeHelper;
import com.example.myapplication.aplicatiamea.dialogs.LevelUpDialog;

/**
 * Player progression display - the "stats screen" that gamers love to obsess over.
 * Shows current level, XP progress, unlocked abilities, and reward history.
 * Marketing insisted on making this feel like an RPG character sheet, and honestly,
 * it works pretty well for keeping users engaged with their productivity journey.
 */
public class LevelsActivity extends Activity {
    private static final String LOG_TAG = "PlayerProgression";
    
    // Core progression UI - the numbers users care about most
    private TextView currentLevelDisplay;
    private TextView experiencePointsText;
    private TextView nextLevelRequirement;
    private ProgressBar levelProgressIndicator;
    private Button returnToMainBtn;
    
    // Achievement and milestone displays
    private TextView totalTasksCompleted;
    private TextView currentStreakCount;
    private TextView bestStreakRecord;
    private TextView coinsEarnedTotal;
    private ImageView playerLevelBadge;
    
    // Rewards and unlocks system
    private RecyclerView unlockedFeaturesDisplay;
    private RecyclerView rewardHistoryList;
    private UnlockAdapter featuresAdapter;
    private RewardHistoryAdapter historyAdapter;
    
    // Backend connections for real-time data
    private FirebaseFirestore gameDatabase;
    private FirebaseAuth playerAuth;
    private DocumentReference playerProgressDoc;
    private ListenerRegistration progressUpdateListener;
    
    // Player data management
    private Map<String, Object> currentPlayerStats = new HashMap<>();
    private boolean isLoadingProgressData = false;
    private boolean hasShownLevelUpCelebration = false;
    
    // Level system configuration - tuned based on user engagement metrics
    private static final int BASE_XP_REQUIREMENT = 100;
    private static final double XP_SCALING_FACTOR = 1.5; // Each level requires 50% more XP
    private static final int MAX_PLAYER_LEVEL = 50; // Cap to prevent infinite grinding

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply player's chosen theme first
        ThemeHelper.applyUserTheme(this);
        
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_levels);
        
        // Initialize all UI components and their handlers
        setupUserInterface();
        
        // Connect to Firebase and establish data flow
        initializeBackendSystems();
        
        // Load player progression data and start real-time updates
        loadPlayerProgressionData();
        
        Log.d(LOG_TAG, "Player progression screen initialized");
    }
    
    /**
     * Wire up all UI components - separated for better organization
     * This screen has a lot of different stat displays and grew organically
     * 
     * Note: Some views might not exist in all layout versions, so we handle missing views gracefully
     */
    private void setupUserInterface() {
        // Core level information display - handle missing views gracefully
        currentLevelDisplay = findViewById(R.id.tvCurrentLevel);
        experiencePointsText = findViewById(R.id.tvCurrentXP);
        nextLevelRequirement = findViewById(R.id.tvXPNeeded);
        levelProgressIndicator = findViewById(R.id.progressBarLevel);
        returnToMainBtn = findViewById(R.id.btnBack);
        
        // Achievement and milestone tracking - may not exist in all layouts
        totalTasksCompleted = findViewById(R.id.tvTotalTasks);
        currentStreakCount = findViewById(R.id.tvCurrentStreak);
        bestStreakRecord = findViewById(R.id.tvBestStreak);
        coinsEarnedTotal = findViewById(R.id.tvTotalCoins);
        playerLevelBadge = findViewById(R.id.ivLevelBadge);
        
        // Handle missing views gracefully - create placeholders or hide functionality
        if (currentLevelDisplay == null) {
            Log.w(LOG_TAG, "Level display view missing - creating text fallback");
            // Could create a simple TextView programmatically here if needed
        }
        
        if (returnToMainBtn == null) {
            // Try alternative button IDs that might exist
            returnToMainBtn = findViewById(R.id.btn_back);
            if (returnToMainBtn == null) {
                returnToMainBtn = findViewById(android.R.id.button1);
            }
        }
        
        // Set up navigation if we found a back button
        if (returnToMainBtn != null) {
            returnToMainBtn.setOnClickListener(v -> {
                Log.d(LOG_TAG, "Player returning to main dashboard");
                finish();
            });
        } else {
            Log.w(LOG_TAG, "No back button found - user will need to use system back");
        }
        
        // Initialize achievement displays with null checks
        setupProgressDisplays();
        
        // Configure unlocks and rewards lists
        configureRewardsDisplay();
    }
    
    /**
     * Set up Firebase connections with proper authentication checks
     * Player progression is sensitive data that needs secure access
     */
    private void initializeBackendSystems() {
        try {
            gameDatabase = FirebaseFirestore.getInstance();
            playerAuth = FirebaseAuth.getInstance();
            
            FirebaseUser authenticatedPlayer = playerAuth.getCurrentUser();
            if (authenticatedPlayer == null) {
                Log.e(LOG_TAG, "Player not authenticated - cannot display progression");
                Toast.makeText(this, "Please sign in to view your progress", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            
            // Set up reference to player's progression document
            playerProgressDoc = gameDatabase.collection("users").document(authenticatedPlayer.getUid());
            
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to initialize backend systems", e);
            Toast.makeText(this, "Connection error - please try again", Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * Configure the progress displays with proper default states
     * Users should see meaningful information even while data loads
     * Handles missing views gracefully to prevent crashes
     */
    private void setupProgressDisplays() {
        // Set loading states for all numeric displays - with null checks
        if (currentLevelDisplay != null) {
            currentLevelDisplay.setText("--");
        }
        if (experiencePointsText != null) {
            experiencePointsText.setText("Loading...");
        }
        if (nextLevelRequirement != null) {
            nextLevelRequirement.setText("--");
        }
        if (totalTasksCompleted != null) {
            totalTasksCompleted.setText("--");
        }
        if (currentStreakCount != null) {
            currentStreakCount.setText("--");
        }
        if (bestStreakRecord != null) {
            bestStreakRecord.setText("--");
        }
        if (coinsEarnedTotal != null) {
            coinsEarnedTotal.setText("--");
        }
        
        // Initialize progress bar if it exists
        if (levelProgressIndicator != null) {
            levelProgressIndicator.setProgress(0);
            levelProgressIndicator.setMax(100);
        }
        
        // Set default level badge if view exists
        if (playerLevelBadge != null) {
            try {
                playerLevelBadge.setImageResource(R.drawable.badge_level_1_beginner);
            } catch (Exception e) {
                Log.w(LOG_TAG, "Failed to set level badge - drawable may not exist", e);
                // Continue without crashing
            }
        }
    }
    
    /**
     * Set up the rewards and unlocks display lists
     * These show what the player has earned and what's coming next
     * Handles missing views gracefully to prevent crashes
     */
    private void configureRewardsDisplay() {
        unlockedFeaturesDisplay = findViewById(R.id.rvUnlockedFeatures);
        rewardHistoryList = findViewById(R.id.rvRewardHistory);
        
        // Initialize with empty data - will populate once player data loads
        List<UnlockItem> emptyUnlocks = new ArrayList<>();
        List<RewardHistoryItem> emptyHistory = new ArrayList<>();
        
        // Only set up RecyclerViews if they exist in the layout
        if (unlockedFeaturesDisplay != null) {
            featuresAdapter = new UnlockAdapter(emptyUnlocks);
            unlockedFeaturesDisplay.setLayoutManager(new LinearLayoutManager(this));
            unlockedFeaturesDisplay.setAdapter(featuresAdapter);
            Log.d(LOG_TAG, "Unlocked features display configured");
        } else {
            Log.w(LOG_TAG, "Unlocked features RecyclerView not found in layout");
        }
        
        if (rewardHistoryList != null) {
            historyAdapter = new RewardHistoryAdapter(emptyHistory);
            rewardHistoryList.setLayoutManager(new LinearLayoutManager(this));
            rewardHistoryList.setAdapter(historyAdapter);
            Log.d(LOG_TAG, "Reward history display configured");
        } else {
            Log.w(LOG_TAG, "Reward history RecyclerView not found in layout");
        }
        
        Log.d(LOG_TAG, "Rewards display configuration completed");
    }
    
    /**
     * Load player progression data and establish real-time updates
     * This is where we get all the juicy stats that players love to see
     */
    private void loadPlayerProgressionData() {
        if (playerProgressDoc == null) {
            Log.e(LOG_TAG, "Cannot load progression - player document reference is null");
            return;
        }
        
        if (isLoadingProgressData) {
            Log.d(LOG_TAG, "Progress data already loading - skipping duplicate request");
            return;
        }
        
        isLoadingProgressData = true;
        Log.d(LOG_TAG, "Starting player progression data load");
        
        // Set up real-time listener for live progress updates
        progressUpdateListener = playerProgressDoc.addSnapshotListener((documentSnapshot, error) -> {
            isLoadingProgressData = false;
            
            if (error != null) {
                Log.e(LOG_TAG, "Progress data listener failed", error);
                handleProgressDataError(error);
                return;
            }
            
            if (documentSnapshot == null || !documentSnapshot.exists()) {
                Log.w(LOG_TAG, "Player progression document not found");
                initializeNewPlayerProgression();
                return;
            }
            
            // Process the loaded progression data
            processPlayerProgressionUpdate(documentSnapshot);
        });
    }
    
    /**
     * Process updated player progression data and refresh UI
     * This is called whenever the player's stats change in real-time
     */
    private void processPlayerProgressionUpdate(DocumentSnapshot playerData) {
        try {
            // Cache the current stats for comparison
            Map<String, Object> previousStats = new HashMap<>(currentPlayerStats);
            currentPlayerStats = playerData.getData() != null ? playerData.getData() : new HashMap<>();
            
            Log.d(LOG_TAG, "Processing progression update for player");
            
            // Extract core progression metrics
            PlayerProgressMetrics metrics = extractProgressMetrics(currentPlayerStats);
            
            // Update all UI displays with fresh data
            updateLevelDisplay(metrics);
            updateAchievementStats(metrics);
            updateUnlockedFeatures(metrics);
            updateRewardHistory(metrics);
            
            // Check for level-up celebrations
            checkForLevelUpCelebration(previousStats, metrics);
            
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error processing progression update", e);
            Toast.makeText(this, "Error updating progress display", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Extract and validate progression metrics from raw Firestore data
     * Handles missing fields and data type conversions gracefully
     */
    private PlayerProgressMetrics extractProgressMetrics(Map<String, Object> rawData) {
        PlayerProgressMetrics metrics = new PlayerProgressMetrics();
        
        // Current level and XP - core progression indicators
        metrics.currentLevel = extractNumericValue(rawData, "level", 1);
        metrics.currentXP = extractNumericValue(rawData, "xp", 0);
        metrics.totalXPEarned = extractNumericValue(rawData, "totalXP", metrics.currentXP);
        
        // Task completion statistics
        metrics.tasksCompleted = extractNumericValue(rawData, "tasksCompleted", 0);
        metrics.subtasksCompleted = extractNumericValue(rawData, "subtasksCompleted", 0);
        
        // Streak tracking - important for engagement
        metrics.currentStreak = extractNumericValue(rawData, "currentStreak", 0);
        metrics.bestStreak = extractNumericValue(rawData, "bestStreak", 0);
        
        // Economic metrics
        metrics.totalCoinsEarned = extractNumericValue(rawData, "totalCoinsEarned", 0);
        metrics.currentCoins = extractNumericValue(rawData, "goldCoins", 0);
        
        // Calculate derived metrics
        metrics.xpForCurrentLevel = calculateXPRequiredForLevel(metrics.currentLevel);
        metrics.xpForNextLevel = calculateXPRequiredForLevel(metrics.currentLevel + 1);
        metrics.progressToNextLevel = calculateLevelProgress(metrics.currentXP, metrics.currentLevel);
        
        return metrics;
    }
    
    /**
     * Helper method to safely extract numeric values from Firestore data
     * Firestore can return different numeric types, so we need to handle conversions
     */
    private long extractNumericValue(Map<String, Object> data, String field, long defaultValue) {
        try {
            Object value = data.get(field);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to extract numeric value for field: " + field, e);
        }
        return defaultValue;
    }
    
    /**
     * Update the main level display with current progression
     * This is the centerpiece that shows the player's current status
     * Handles missing UI elements gracefully
     */
    private void updateLevelDisplay(PlayerProgressMetrics metrics) {
        // Update level number with visual flair - if view exists
        if (currentLevelDisplay != null) {
            currentLevelDisplay.setText("Level " + metrics.currentLevel);
        }
        
        // Show current XP and requirement for next level
        if (experiencePointsText != null) {
            experiencePointsText.setText("XP: " + metrics.currentXP);
        }
        
        if (nextLevelRequirement != null) {
            long xpNeeded = metrics.xpForNextLevel - metrics.currentXP;
            if (xpNeeded <= 0) {
                nextLevelRequirement.setText("Ready to level up!");
            } else {
                nextLevelRequirement.setText("Next: " + xpNeeded + " XP");
            }
        }
        
        // Update progress bar with smooth animation - if view exists
        if (levelProgressIndicator != null) {
            int progressPercentage = (int) metrics.progressToNextLevel;
            levelProgressIndicator.setProgress(progressPercentage);
        }
        
        // Update level badge based on current level
        updateLevelBadge(metrics.currentLevel);
        
        Log.d(LOG_TAG, "Level display updated - Level " + metrics.currentLevel + 
              ", " + (int) metrics.progressToNextLevel + "% to next");
    }
    
    /**
     * Update achievement statistics display
     * These numbers give players a sense of their overall accomplishment
     * Handles missing UI elements gracefully
     */
    private void updateAchievementStats(PlayerProgressMetrics metrics) {
        // Task completion metrics - only update if view exists
        if (totalTasksCompleted != null) {
            totalTasksCompleted.setText(String.valueOf(metrics.tasksCompleted));
        }
        
        // Streak information - crucial for habit formation
        if (currentStreakCount != null) {
            currentStreakCount.setText(String.valueOf(metrics.currentStreak));
        }
        if (bestStreakRecord != null) {
            bestStreakRecord.setText(String.valueOf(metrics.bestStreak));
        }
        
        // Economic achievements
        if (coinsEarnedTotal != null) {
            coinsEarnedTotal.setText(String.valueOf(metrics.totalCoinsEarned));
        }
        
        Log.d(LOG_TAG, "Achievement stats updated - " + metrics.tasksCompleted + 
              " tasks, " + metrics.currentStreak + " day streak");
    }
    
    /**
     * Update the unlocked features display
     * Shows what capabilities the player has earned through progression
     */
    private void updateUnlockedFeatures(PlayerProgressMetrics metrics) {
        List<UnlockItem> unlockedFeatures = generateUnlocksList(metrics.currentLevel);
        featuresAdapter.updateUnlocks(unlockedFeatures);
        
        Log.d(LOG_TAG, "Unlocked features updated - " + unlockedFeatures.size() + " features available");
    }
    
    /**
     * Update the reward history display
     * Shows recent XP gains and level-ups for player satisfaction
     */
    private void updateRewardHistory(PlayerProgressMetrics metrics) {
        // For now, generate a simple recent activity list
        // In a full implementation, this would come from a detailed activity log
        List<RewardHistoryItem> recentActivity = generateRecentActivityList(metrics);
        historyAdapter.updateHistory(recentActivity);
        
        Log.d(LOG_TAG, "Reward history updated with recent activity");
    }
    
    /**
     * Generate list of unlocked features based on player level
     * Each level unlocks new capabilities to keep progression rewarding
     */
    private List<UnlockItem> generateUnlocksList(long currentLevel) {
        List<UnlockItem> unlocks = new ArrayList<>();
        
        // Basic features unlocked at level 1
        unlocks.add(new UnlockItem("Task Creation", "Create and manage your daily tasks", 1, true));
        unlocks.add(new UnlockItem("Basic Rewards", "Earn XP and coins for completed tasks", 1, true));
        
        // Progressive unlocks based on level
        if (currentLevel >= 3) {
            unlocks.add(new UnlockItem("Subtasks", "Break down tasks into smaller steps", 3, true));
        }
        
        if (currentLevel >= 5) {
            unlocks.add(new UnlockItem("Daily Streaks", "Track consecutive days of productivity", 5, true));
        }
        
        if (currentLevel >= 8) {
            unlocks.add(new UnlockItem("Shop Access", "Purchase cosmetic items and power-ups", 8, true));
        }
        
        if (currentLevel >= 12) {
            unlocks.add(new UnlockItem("Advanced Stats", "Detailed analytics and insights", 12, true));
        }
        
        if (currentLevel >= 15) {
            unlocks.add(new UnlockItem("Challenge Modes", "Special productivity challenges", 15, true));
        }
        
        // Show upcoming unlocks as motivation
        if (currentLevel < 20) {
            unlocks.add(new UnlockItem("Team Features", "Collaborate with friends and colleagues", 20, false));
        }
        
        if (currentLevel < 25) {
            unlocks.add(new UnlockItem("Custom Themes", "Personalize your app experience", 25, false));
        }
        
        return unlocks;
    }
    
    /**
     * Generate recent activity list for reward history
     * Simulates recent XP gains and achievements for display
     */
    private List<RewardHistoryItem> generateRecentActivityList(PlayerProgressMetrics metrics) {
        List<RewardHistoryItem> activity = new ArrayList<>();
        
        // Add some sample recent activity
        // In a real implementation, this would be pulled from an activity log
        activity.add(new RewardHistoryItem("Task Completed", "+50 XP", "2 hours ago"));
        activity.add(new RewardHistoryItem("Daily Streak", "+25 XP", "1 day ago"));
        activity.add(new RewardHistoryItem("Level Up!", "Reached Level " + metrics.currentLevel, "2 days ago"));
        activity.add(new RewardHistoryItem("Subtask Mastery", "+15 XP", "3 days ago"));
        
        return activity;
    }
    
    /**
     * Calculate XP required to reach a specific level
     * Uses exponential scaling to make higher levels more challenging
     */
    private long calculateXPRequiredForLevel(long level) {
        if (level <= 1) return 0;
        
        long totalXP = 0;
        for (int i = 2; i <= level; i++) {
            totalXP += (long) (BASE_XP_REQUIREMENT * Math.pow(XP_SCALING_FACTOR, i - 2));
        }
        return totalXP;
    }
    
    /**
     * Calculate progress percentage toward next level
     * Returns value between 0-100 for progress bar display
     */
    private double calculateLevelProgress(long currentXP, long currentLevel) {
        long xpForCurrentLevel = calculateXPRequiredForLevel(currentLevel);
        long xpForNextLevel = calculateXPRequiredForLevel(currentLevel + 1);
        
        if (xpForNextLevel <= xpForCurrentLevel) return 100.0; // Max level reached
        
        long xpInCurrentLevel = currentXP - xpForCurrentLevel;
        long xpNeededForLevel = xpForNextLevel - xpForCurrentLevel;
        
        if (xpNeededForLevel <= 0) return 100.0;
        
        double progress = (double) xpInCurrentLevel / xpNeededForLevel * 100.0;
        return Math.max(0.0, Math.min(100.0, progress));
    }
    
    /**
     * Update the level badge icon based on current level
     * Visual progression feedback is important for player satisfaction
     */
    private void updateLevelBadge(long level) {
        int badgeResource;
        
        if (level >= 50) {
            badgeResource = R.drawable.badge_level_50_legendary;
        } else if (level >= 40) {
            badgeResource = R.drawable.badge_level_40_master;
        } else if (level >= 30) {
            badgeResource = R.drawable.badge_level_30_expert;
        } else if (level >= 20) {
            badgeResource = R.drawable.badge_level_20_advanced;
        } else if (level >= 10) {
            badgeResource = R.drawable.badge_level_10_proficient;
        } else if (level >= 5) {
            badgeResource = R.drawable.badge_level_5_novice;
        } else {
            badgeResource = R.drawable.badge_level_1_beginner;
        }
        
        playerLevelBadge.setImageResource(badgeResource);
    }
    
    /**
     * Check if player leveled up and show celebration if needed
     * Level-ups are major milestones that deserve special recognition
     */
    private void checkForLevelUpCelebration(Map<String, Object> previousStats, PlayerProgressMetrics currentMetrics) {
        if (hasShownLevelUpCelebration) return; // Prevent duplicate celebrations
        
        long previousLevel = extractNumericValue(previousStats, "level", 1);
        
        if (currentMetrics.currentLevel > previousLevel) {
            Log.d(LOG_TAG, "Player leveled up from " + previousLevel + " to " + currentMetrics.currentLevel);
            showLevelUpCelebration(currentMetrics.currentLevel);
            hasShownLevelUpCelebration = true;
            
            // Reset flag after a delay to allow future celebrations
            new android.os.Handler().postDelayed(() -> hasShownLevelUpCelebration = false, 5000);
        }
    }
    
    /**
     * Show level-up celebration dialog
     * This is a key moment for player engagement and should feel rewarding
     */
    private void showLevelUpCelebration(long newLevel) {
        try {
            LevelUpDialog celebrationDialog = new LevelUpDialog();
            Bundle dialogArgs = new Bundle();
            dialogArgs.putLong("newLevel", newLevel);
            celebrationDialog.setArguments(dialogArgs);
            
            // Show the celebration - users love this feedback
            celebrationDialog.show(getFragmentManager(), "levelUp");
            
            Log.d(LOG_TAG, "Level-up celebration displayed for level " + newLevel);
            
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to show level-up celebration", e);
            // Fallback to simple toast if dialog fails
            Toast.makeText(this, "Congratulations! You reached Level " + newLevel + "!", 
                         Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * Handle progression data loading errors
     * Provide helpful feedback and recovery options
     */
    private void handleProgressDataError(Exception error) {
        Log.e(LOG_TAG, "Error loading progression data", error);
        
        String errorMessage = "Unable to load your progress";
        if (error.getMessage() != null) {
            if (error.getMessage().contains("permission")) {
                errorMessage = "Access denied - please sign in again";
            } else if (error.getMessage().contains("network")) {
                errorMessage = "Network error - check your connection";
            }
        }
        
        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
    }
    
    /**
     * Initialize progression for new players
     * Sets up default stats when no progression document exists
     */
    private void initializeNewPlayerProgression() {
        Log.d(LOG_TAG, "Initializing progression for new player");
        
        Map<String, Object> defaultProgression = new HashMap<>();
        defaultProgression.put("level", 1);
        defaultProgression.put("xp", 0);
        defaultProgression.put("totalXP", 0);
        defaultProgression.put("tasksCompleted", 0);
        defaultProgression.put("subtasksCompleted", 0);
        defaultProgression.put("currentStreak", 0);
        defaultProgression.put("bestStreak", 0);
        defaultProgression.put("totalCoinsEarned", 0);
        defaultProgression.put("goldCoins", 100); // Starting bonus
        
        // Save initial progression to Firestore
        playerProgressDoc.set(defaultProgression)
            .addOnSuccessListener(aVoid -> {
                Log.d(LOG_TAG, "New player progression initialized successfully");
                currentPlayerStats = defaultProgression;
                
                // Process the initial data
                PlayerProgressMetrics metrics = extractProgressMetrics(currentPlayerStats);
                updateLevelDisplay(metrics);
                updateAchievementStats(metrics);
                updateUnlockedFeatures(metrics);
                updateRewardHistory(metrics);
            })
            .addOnFailureListener(e -> {
                Log.e(LOG_TAG, "Failed to initialize new player progression", e);
                Toast.makeText(this, "Failed to set up your progress tracking", Toast.LENGTH_LONG).show();
            });
    }
    
    @Override
    protected void onDestroy() {
        // Clean up real-time listeners to prevent memory leaks
        if (progressUpdateListener != null) {
            progressUpdateListener.remove();
            Log.d(LOG_TAG, "Progress update listener removed");
        }
        super.onDestroy();
    }
    
    /**
     * Data class to hold player progression metrics
     * Keeps all the stats organized and easy to work with
     */
    private static class PlayerProgressMetrics {
        long currentLevel;
        long currentXP;
        long totalXPEarned;
        long tasksCompleted;
        long subtasksCompleted;
        long currentStreak;
        long bestStreak;
        long totalCoinsEarned;
        long currentCoins;
        
        // Derived metrics
        long xpForCurrentLevel;
        long xpForNextLevel;
        double progressToNextLevel;
    }
    
    /**
     * Data class for unlocked features
     */
    private static class UnlockItem {
        String featureName;
        String description;
        int unlockLevel;
        boolean isUnlocked;
        
        UnlockItem(String name, String desc, int level, boolean unlocked) {
            this.featureName = name;
            this.description = desc;
            this.unlockLevel = level;
            this.isUnlocked = unlocked;
        }
    }
    
    /**
     * Data class for reward history items
     */
    private static class RewardHistoryItem {
        String activityName;
        String rewardAmount;
        String timeAgo;
        
        RewardHistoryItem(String activity, String reward, String time) {
            this.activityName = activity;
            this.rewardAmount = reward;
            this.timeAgo = time;
        }
    }
    
    /**
     * Simple adapter for unlocked features list
     * In a full implementation, this would be in its own file
     */
    private static class UnlockAdapter extends RecyclerView.Adapter<UnlockAdapter.UnlockViewHolder> {
        private List<UnlockItem> unlocks;
        
        UnlockAdapter(List<UnlockItem> unlocks) {
            this.unlocks = unlocks;
        }
        
        void updateUnlocks(List<UnlockItem> newUnlocks) {
            this.unlocks = newUnlocks;
            notifyDataSetChanged();
        }
        
        @NonNull
        @Override
        public UnlockViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            // This would inflate a proper layout in a real implementation
            TextView textView = new TextView(parent.getContext());
            return new UnlockViewHolder(textView);
        }
        
        @Override
        public void onBindViewHolder(@NonNull UnlockViewHolder holder, int position) {
            UnlockItem item = unlocks.get(position);
            String displayText = item.featureName + " (Level " + item.unlockLevel + ")";
            if (!item.isUnlocked) {
                displayText = "🔒 " + displayText;
            }
            holder.textView.setText(displayText);
        }
        
        @Override
        public int getItemCount() {
            return unlocks.size();
        }
        
        static class UnlockViewHolder extends RecyclerView.ViewHolder {
            TextView textView;
            
            UnlockViewHolder(View itemView) {
                super(itemView);
                textView = (TextView) itemView;
            }
        }
    }
    
    /**
     * Simple adapter for reward history list
     * Shows recent XP gains and achievements
     */
    private static class RewardHistoryAdapter extends RecyclerView.Adapter<RewardHistoryAdapter.HistoryViewHolder> {
        private List<RewardHistoryItem> history;
        
        RewardHistoryAdapter(List<RewardHistoryItem> history) {
            this.history = history;
        }
        
        void updateHistory(List<RewardHistoryItem> newHistory) {
            this.history = newHistory;
            notifyDataSetChanged();
        }
        
        @NonNull
        @Override
        public HistoryViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            // This would inflate a proper layout in a real implementation
            TextView textView = new TextView(parent.getContext());
            return new HistoryViewHolder(textView);
        }
        
        @Override
        public void onBindViewHolder(@NonNull HistoryViewHolder holder, int position) {
            RewardHistoryItem item = history.get(position);
            String displayText = item.activityName + " " + item.rewardAmount + " (" + item.timeAgo + ")";
            holder.textView.setText(displayText);
        }
        
        @Override
        public int getItemCount() {
            return history.size();
        }
        
        static class HistoryViewHolder extends RecyclerView.ViewHolder {
            TextView textView;
            
            HistoryViewHolder(View itemView) {
                super(itemView);
                textView = (TextView) itemView;
            }
        }
    }
} 