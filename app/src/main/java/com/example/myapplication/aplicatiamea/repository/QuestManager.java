package com.example.myapplication.aplicatiamea.repository;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.example.myapplication.aplicatiamea.Task;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Calendar;


public class QuestManager implements QuestTemplateProvider {
    private static final String TAG = "QuestManager";
    private static final int MAX_DAILY_QUESTS = 3;
    private static final int MAX_WEEKLY_QUESTS = 2;
    
    private final String userId;
    private final Context context;
    private QuestScheduler scheduler;
    private QuestProgressTracker progressTracker;
    private TimeBoundQuestHandler timeBoundHandler;
    private QuestRewardManager rewardManager;
    private QuestCleaner cleaner;
    
    private static final long QUEST_ACTION_COOLDOWN_MS = 180000; // 3 minutes
    
    private final List<QuestTemplate> templates = Arrays.asList(
        new QuestTemplate("morning_momentum", "Morning Momentum Quest", "Complete 3 HIGH priority tasks before 9 AM", "DAILY", 3, 50, "time_bound"),
        new QuestTemplate("inbox_zero", "Inbox Zero Quest", "Clear your Inbox project items by end of day", "DAILY", 1, 75, "standard"),
        new QuestTemplate("priority_cleanup", "Priority Cleanup Quest", "Finish 5 MEDIUM or HIGH priority tasks", "DAILY", 5, 80, "standard"),
        new QuestTemplate("speedy_task", "Speedy Task Quest", "Complete any 3 tasks within 2 hours", "DAILY", 3, 80, "time_bound"),
        new QuestTemplate("high_priority_sprint", "High-Priority Sprint", "Complete 2 HIGH priority tasks within 1 hour", "DAILY", 2, 60, "time_bound"),
        new QuestTemplate("habit_streak", "Habit Streak Quest", "Mark a chosen habit as done 7 days in a row", "WEEKLY", 7, 200, "streak"),
        new QuestTemplate("healthy_habits", "Healthy Habits Quest", "Complete 5 micro-habits each day for a week", "WEEKLY", 5, 100, "standard"),
        new QuestTemplate("spring_cleaning", "Spring Cleaning Quest", "Complete 10 small cleanup tasks over 3 days", "DAILY", 10, 150, "standard"),
        new QuestTemplate("weekly_review", "Weekly Review Quest", "Archive completed tasks and plan next week", "WEEKLY", 1, 120, "standard"),
        new QuestTemplate("focus_sessions", "Focus Sessions Quest", "Complete two 2-hour focus sessions this week", "WEEKLY", 2, 150, "standard"),
        new QuestTemplate("subtask_surge", "Subtask Surge Quest", "Complete 10 subtasks in a day", "DAILY", 10, 100, "standard"),
        new QuestTemplate("plan_next_week", "Plan Next Week Quest", "Plan tasks for next week", "WEEKLY", 1, 100, "standard"),
        new QuestTemplate("consistency_champion", "Consistency Champion Quest", "Complete the same recurring task for 3 consecutive days", "DAILY", 3, 150, "streak")
    );
    
    public QuestManager(String userId) {
        this.userId = userId;
        this.context = null;
        initializeSubsystems();
    }
    
    public QuestManager(String userId, Context context) {
        this.userId = userId;
        this.context = context;
        initializeSubsystems();
    }
    
    private void initializeSubsystems() {
        Log.d(TAG, "🎯 Initializing quest subsystems for user: " + userId);
        
        this.scheduler = new QuestScheduler(userId, this);
        this.timeBoundHandler = new TimeBoundQuestHandler(userId, this);
        this.progressTracker = new QuestProgressTracker(userId, this, timeBoundHandler);
        this.rewardManager = new QuestRewardManager(userId, context);
        this.cleaner = new QuestCleaner();
        
        timeBoundHandler.cleanupExpiredTimeBoundQuests();
        
        DocumentReference userRef = FirebaseFirestore.getInstance().collection("users").document(userId);
        progressTracker.observeInboxZeroQuest(userRef);
        
        Log.d(TAG, "🎯 Quest subsystems initialized for user: " + userId);
        Log.d(TAG, "🎯 Available templates: " + templates.size());
    }
    

    public void issueDailyQuests() {
        Log.d(TAG, "🎯 Issuing daily quests for user: " + userId);
        scheduler.ensureDailyQuestsExist(MAX_DAILY_QUESTS);
        Log.d(TAG, "[DEBUG] Called ensureDailyQuestsExist for user: " + userId);
    }
    

    public void issueWeeklyQuest() {
        makeWeeklyQuests();
    }
    

    public void issueWeeklyQuests() {
        makeWeeklyQuests();
    }
    

    public void makeWeeklyQuests() {
        Log.d(TAG, "🎯 Issuing weekly quests for user: " + userId);
        scheduler.ensureWeeklyQuestsExist(MAX_WEEKLY_QUESTS);
    }
    public void cleanupAllExpiredQuests() {
        Log.d(TAG, "🎯 Cleaning up expired quests for user: " + userId);
        scheduler.cleanupExpiredQuests();
    }


    public void forceQuestRefresh(DocumentReference userRef) {
        Log.d(TAG, "🎯 Forcing quest refresh for user: " + userId);
        scheduler.refreshQuestStatus();
        
        if (progressTracker != null && userRef != null) {
            progressTracker.forceQuestUpdateChecks(userRef);
        }
    }
    

    public void recordTaskCompletion(String taskId, String taskTitle, String taskPriority,
                                  DocumentReference userRef, Map<String, Object> lastQuestActions) {
        Log.d(TAG, "🎯 Recording task completion: " + taskTitle);
        progressTracker.recordTaskCompletion(taskId, taskTitle, taskPriority, userRef, lastQuestActions);
    }
    
    public void recordTaskCompletion(Task task, DocumentReference userRef) {
        Log.d(TAG, "🎯 Recording task completion (legacy): " + task.getName());
        
        String taskId = task.getId();
        String taskTitle = task.getName();
        String taskPriority = task.getPriority();
        
        userRef.get().addOnSuccessListener(snapshot -> {
            Map<String, Object> lastQuestActions = snapshot.contains("lastQuestActions") ? 
                (Map<String, Object>) snapshot.get("lastQuestActions") : 
                new java.util.HashMap<>();
                
            recordTaskCompletion(taskId, taskTitle, taskPriority, userRef, lastQuestActions);
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to get lastQuestActions for task completion", e);
        });
    }
    

    public void undoTaskCompletion(Task task, DocumentReference userRef) {
        Log.d(TAG, "🎯 Undoing task completion: " + task.getName());
        progressTracker.recordTaskUndo(task, userRef);
    }
    

    public void recordTaskUndo(Task task, DocumentReference userRef) {
        progressTracker.recordTaskUndo(task, userRef);
    }
    

    public void cleanupExpiredTimeBoundQuests() {
        Log.d(TAG, "🎯 Cleaning up expired time-bound quests");
        timeBoundHandler.cleanupExpiredTimeBoundQuests();
    }
    

    public void recordWeeklyDayCompletion() {
        scheduler.recordWeeklyDayCompletion();
    }
    

    public void logWeeklyDayCompletion() {
        Log.d(TAG, "🎯 Logging weekly day completion");
        scheduler.recordWeeklyDayCompletion();
    }
    

    public void recordSubtaskCompletion(int subtasksCount, DocumentReference userRef) {
        Log.d("QuestDebug", "recordSubtaskCompletion called with count: " + subtasksCount);
        if (progressTracker != null) {
            progressTracker.recordSubtaskCompletion(subtasksCount, userRef);
        }
    }
    

    public void incrementXp(long amount) {
        Log.d(TAG, "🎯 Incrementing XP: " + amount);
        rewardManager.incrementXp(amount);
    }

    @Override
    public List<QuestTemplate> getAllTemplates() {
        return new ArrayList<>(templates);
    }
    

    @Override
    public void setTemplates(List<QuestTemplate> templates) {
        // Not implemented - templates are fixed
        Log.w(TAG, "setTemplates called but not implemented - templates are fixed");
    }
    

    public void claimQuestReward(String questId, RewardClaimListener listener) {
        rewardManager.claimQuestReward(questId, listener);
    }
    

    public void initializeCharacterSprites() {
        rewardManager.initializeCharacterSprites();
    }
    

    public static boolean handleFirestoreError(FirebaseFirestoreException e, Context context, boolean shouldShowToast) {
        Log.e(TAG, "Firestore error", e);
        if (e != null && e.getMessage() != null && e.getMessage().contains("The query requires an index")) {
            String message = e.getMessage();
            int urlStart = message.indexOf("https://");
            int urlEnd = message.indexOf(" ", urlStart);
            if (urlEnd < 0) urlEnd = message.length();
            String indexUrl = message.substring(urlStart, urlEnd);
            Log.e(TAG, "Missing Firestore index. Create it here: " + indexUrl);
            if (shouldShowToast && context != null) {
                Toast.makeText(context, 
                    "Database index missing. App functionality may be limited until the developer fixes this.", 
                    Toast.LENGTH_LONG).show();
            }
            return true;
        }
        return false;
    }
    

    private long getStartOfDayMillis() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }


    private long getStartOfWeekMillis() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_WEEK, c.getFirstDayOfWeek());
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }


    public void cleanupQuestObservers() {
        if (progressTracker != null) {
            progressTracker.removeInboxZeroQuestObserver();
        }
    }
} 