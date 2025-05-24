package com.example.myapplication.aplicatiamea.repository;

import android.content.Context;
import android.util.Log;
import com.example.myapplication.aplicatiamea.Task;
import com.google.firebase.firestore.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Main brain for quest system.
 * Controls all the various quest systems in the app.
 * 
 * TODO: Consider splitting into smaller managers for v2.0
 * HISTORY: Rewritten after v0.4.2 bugs with Firebase transaction failures
 */
public class QuestManager implements QuestTemplateProvider {
    private static final String TAG = "QuestMgr"; // shorter tag to avoid log truncation
    
    // frequency types - used in several places
    public static final String FREQ_DAILY = "DAILY";
    public static final String FREQ_WEEKLY = "WEEKLY";
    // We had MONTHLY type earlier but removed in v0.3.1
    
    private final String userId;
    private final Context context;
    private final FirebaseFirestore db;
    private final CollectionReference questsRef;
    
    // All the specialized quest handlers - this got complex over time
    private final QuestScheduler scheduler;
    private final QuestProgressTracker progressTracker;
    private final QuestRewardManager rewardManager;
    private final QuestCleaner cleaner;
    private final TimeBoundQuestHandler timeBoundQuestHandler;
    private final RecurringTaskHandler recurringTaskHandler;
    
    // cached templates to avoid re-creating them constantly
    private List<QuestTemplate> cachedTemplates = null;
    
    // Quest templates - kept in memory to avoid DB reads
    // Originally was in the database but moved here for performance
    private List<QuestTemplate> getDefaultTemplates() {
        return Arrays.asList(
            // Morning quests
            new QuestTemplate("Morning Momentum Quest", "Complete 3 HIGH priority tasks before 9 AM", 3, 50, TimeUnit.HOURS.toMillis(9)),
            
            // Daily quests
            new QuestTemplate("Inbox Zero Quest", "Clear your Inbox project items by end of day", 1, 75, TimeUnit.DAYS.toMillis(1)),
            new QuestTemplate("Priority Cleanup Quest", "Finish 5 MEDIUM or HIGH priority tasks", 5, 80, TimeUnit.DAYS.toMillis(1)),
            new QuestTemplate("Speedy Task Quest", "Complete any 3 tasks within 2 hours", 3, 80, TimeUnit.HOURS.toMillis(2)),
            new QuestTemplate("High-Priority Sprint", "Complete 2 HIGH priority tasks within 1 hour", 2, 60, TimeUnit.HOURS.toMillis(1)),
            
            // Weekly quests - more XP but harder
            new QuestTemplate("Habit Streak Quest", "Mark a chosen habit as done 7 days in a row", 7, 200, TimeUnit.DAYS.toMillis(7)),
            new QuestTemplate("Healthy Habits Quest", "Complete at least 5 micro-habits each day for 7 days", 7, 100, TimeUnit.DAYS.toMillis(7)),
            new QuestTemplate("Spring Cleaning Quest", "Complete 10 small cleanup tasks over 3 days", 10, 150, TimeUnit.DAYS.toMillis(3)), // added for Spring 2023
            new QuestTemplate("Weekly Review Quest", "Archive completed tasks and plan next week", 1, 120, TimeUnit.DAYS.toMillis(7)),
            new QuestTemplate("Streak Builder Quest", "Complete all daily tasks for 3 consecutive days", 3, 150, TimeUnit.DAYS.toMillis(7)),
            
            // Custom quests - these are newer
            new QuestTemplate("Subtask Surge Quest", "Complete 10 subtasks in a day", 10, 100, TimeUnit.DAYS.toMillis(1)),
            new QuestTemplate("Plan Next Week Quest", "Plan tasks for next week", 1, 100, TimeUnit.DAYS.toMillis(7)),
            
            // Recurring task quest - seems popular so keeping it
            new QuestTemplate("Consistency Champion Quest", "Complete the same recurring task for 3 consecutive days", 3, 150, TimeUnit.DAYS.toMillis(3))
            
            // Commented out until we add level-based unlocks
            // new QuestTemplate("Master Planner Quest", "Plan tasks with estimates that match actual time", 5, 200, TimeUnit.DAYS.toMillis(7))
        );
    }
    
    // Constructor with just userId - mostly for testing
    public QuestManager(String userId) {
        this.userId = userId;
        this.context = null;
        this.db = FirebaseFirestore.getInstance();
        this.questsRef = db.collection("users").document(userId).collection("questInstances");
        
        // Initialize all the components
        this.timeBoundQuestHandler = new TimeBoundQuestHandler(userId, this);
        this.scheduler = new QuestScheduler(userId, this);
        this.progressTracker = new QuestProgressTracker(userId, this, timeBoundQuestHandler);
        this.rewardManager = new QuestRewardManager(userId);
        this.cleaner = new QuestCleaner(userId, timeBoundQuestHandler);
        this.recurringTaskHandler = new RecurringTaskHandler(userId, progressTracker);
    }
    
    // Main constructor used by the app
    public QuestManager(String userId, Context context) {
        this.userId = userId;
        this.context = context;
        this.db = FirebaseFirestore.getInstance();
        this.questsRef = db.collection("users").document(userId).collection("questInstances");
        
        // Initialize all the components
        this.timeBoundQuestHandler = new TimeBoundQuestHandler(userId, this);
        this.scheduler = new QuestScheduler(userId, this);
        this.progressTracker = new QuestProgressTracker(userId, this, timeBoundQuestHandler);
        this.rewardManager = new QuestRewardManager(userId, context);
        this.cleaner = new QuestCleaner(userId, timeBoundQuestHandler);
        this.recurringTaskHandler = new RecurringTaskHandler(userId, progressTracker);
        
        // Do a cleanup at start - this was added after we had too many expired quests
        tryCleanupExpiredQuests();
    }

    @Override
    public List<QuestTemplate> getAllTemplates() {
        if (cachedTemplates == null) {
            cachedTemplates = getDefaultTemplates();
        }
        return new ArrayList<>(cachedTemplates);
    }
    
    /**
     * For testing only - allow mocking templates
     */
    void setTemplates(List<QuestTemplate> templates) {
        this.cachedTemplates = templates;
    }
    
    //
    // Methods forwarded to the right components
    //

    /**
     * Make sure user has 3 daily quests to work on.
     */
    public void issueDailyQuests() {
        scheduler.issueDailyQuests();
    }
    
    /**
     * Weekly quest stuff
     */
    public void makeWeeklyQuests() {
        scheduler.issueWeeklyQuests();
    }
    
    /**
     * @deprecated Use makeWeeklyQuests() instead, kept for compat
     */
    @Deprecated
    public void issueWeeklyQuest() {
        // FIXME: Remove this after all callsites updated to makeWeeklyQuests
        scheduler.issueWeeklyQuests();
    }
    
    /**
     * Call when task completed - triggers quest updates
     */
    public void recordTaskCompletion(String taskId, String taskTitle, String taskPriority, DocumentReference userRef, Map<String, Object> lastQuestActions) {
        // Actually important for quest timing!
        progressTracker.recordTaskCompletion(taskId, taskTitle, taskPriority, userRef, lastQuestActions);
    }
    
    // Backwards compat version - not ideal, but works
    // Duplicated because too many places call this version
    public void recordTaskCompletion(Task task, DocumentReference userRef) {
        if (task == null) {
            Log.w(TAG, "[DEBUG] Task is null in recordTaskCompletion");
            return;
        }
        
        // Check if this is a recurring task and handle it
        if (task.getRecurrenceGroupId() != null && !task.getRecurrenceGroupId().isEmpty()) {
            recurringTaskHandler.checkRecurringTaskCompletion(task, userRef);
        }
        
        // Get the user document to retrieve lastQuestActions
        userRef.get().addOnSuccessListener(userSnapshot -> {
            if (!userSnapshot.exists()) {
                Log.e(TAG, "User document not found for quest progress recording.");
                return;
            }
            
            // Get the user's quest action timestamps for cooldown checks
            Map<String, Object> lastQuestActionsFromDb = new HashMap<>();
            if (userSnapshot.contains("lastQuestActions") && userSnapshot.get("lastQuestActions") instanceof Map) {
                try {
                    lastQuestActionsFromDb = (Map<String, Object>) userSnapshot.get("lastQuestActions");
                    if (lastQuestActionsFromDb == null) lastQuestActionsFromDb = new HashMap<>();
                } catch (ClassCastException e) {
                    Log.e(TAG, "Firestore 'lastQuestActions' is not a Map.", e);
                    lastQuestActionsFromDb = new HashMap<>();
                }
            }
            
            // Now call the new method with the extracted information
            String taskId = task.getId();
            String taskTitle = task.getName();
            String taskPriority = task.getPriority();
            
            recordTaskCompletion(taskId, taskTitle, taskPriority, userRef, lastQuestActionsFromDb);
        });
    }
    
    /**
     * Record when a task completion is undone
     */
    public void undoTaskCompletion(Task task, DocumentReference userRef) {
        // Delegate to progress tracker
        progressTracker.recordTaskUndo(task, userRef);
        
        // Handle recurring tasks
        if (task != null && task.getRecurrenceGroupId() != null && !task.getRecurrenceGroupId().isEmpty()) {
            // Get quest actions first
            userRef.get().addOnSuccessListener(userSnapshot -> {
                Map<String, Object> lastQuestActions = new HashMap<>();
                if (userSnapshot.contains("lastQuestActions") && userSnapshot.get("lastQuestActions") instanceof Map) {
                    try {
                        lastQuestActions = (Map<String, Object>) userSnapshot.get("lastQuestActions");
                        if (lastQuestActions == null) lastQuestActions = new HashMap<>();
                    } catch (ClassCastException e) {
                        Log.e(TAG, "Firestore 'lastQuestActions' is not a Map.", e);
                        lastQuestActions = new HashMap<>();
                    }
                }
                final Map<String, Object> finalLastQuestActions = lastQuestActions;
                
                // Now handle the recurring task undo with the actions map
                recurringTaskHandler.handleRecurringTaskUndo(task, userRef, finalLastQuestActions);
            });
        }
    }
    
    /**
     * Alias for undoTaskCompletion to maintain compatibility
     * @deprecated Use undoTaskCompletion instead
     */
    @Deprecated
    public void recordTaskUndo(Task task, DocumentReference userRef) {
        undoTaskCompletion(task, userRef);
    }
    
    // Added this in a rush for Subtask feature - not the cleanest but it works
    public void trackSubtaskFinish(int subtasksCount, DocumentReference userRef) {
        progressTracker.recordSubtaskCompletion(subtasksCount, userRef);
    }
    
    /**
     * Record progress when planning tasks for next week
     */
    public void recordPlanNextWeek(DocumentReference userRef) {
        progressTracker.recordPlanNextWeek(userRef);
    }
    
    /**
     * Get reward for quest
     * @return points based on quest difficulty and level
     */
    public void claimQuestReward(String questId, RewardClaimListener listener) {
        rewardManager.claimQuestReward(questId, listener);
    }
    
    /**
     * Record one day completed for weekly quests
     */
    public void logWeeklyDayCompletion() {
        scheduler.recordWeeklyDayCompletion();
    }
    
    /**
     * Undo one day completion for weekly quests
     */
    public void undoWeeklyDayCompletionRecord() {
        scheduler.recordWeeklyDayUndo();
    }
    
    // Same thing as above but with a different name
    // Keep both to avoid breaking changes
    public void recordWeeklyDayUndo() {
        undoWeeklyDayCompletionRecord();
    }
    
    /**
     * Increments the user's XP by the specified amount
     * @param amount how much XP to add
     */
    public void incrementXp(long amount) {
        rewardManager.incrementXp(amount);
    }
    
    /**
     * Initialize character sprites for a new user
     * Not fully implemented yet - more for v2.0
     */
    public void initializeCharacterSprites() {
        rewardManager.initializeCharacterSprites();
    }
    
    /**
     * Cleanup all expired quests for the current user
     * FIXME: This sometimes causes transaction errors - need to split into smaller batches
     */
    public void cleanupAllExpiredQuests() {
        cleaner.cleanupAllExpiredQuests();
    }
    
    // Added this as more targeted solution when the big cleanup 
    // kept timing out - let's try this first
    private void tryCleanupExpiredQuests() {
        try {
            cleanupExpiredTimeBoundQuests();
        } catch (Exception e) {
            Log.e(TAG, "Quest cleanup failed - will try again later", e);
        }
    }
    
    /**
     * Check for and clean up expired time-bound quests
     */
    public void cleanupExpiredTimeBoundQuests() {
        timeBoundQuestHandler.cleanupExpiredTimeBoundQuests();
    }
    
    /**
     * Utility method for handling Firestore errors
     * This was extracted from QuestCleaner to make it more broadly available
     * @return true if handled, false if unknown error
     */
    public static boolean handleFirestoreError(Exception e, Context context, boolean shouldShowToast) {
        return QuestCleaner.handleFirestoreError(e, context, shouldShowToast);
    }
} 