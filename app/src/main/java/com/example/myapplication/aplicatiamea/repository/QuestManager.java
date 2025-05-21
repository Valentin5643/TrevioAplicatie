package com.example.myapplication.aplicatiamea.repository;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;
import com.google.firebase.firestore.FirebaseFirestoreException;

import com.example.myapplication.aplicatiamea.RewardManager;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.DocumentSnapshot;
import com.example.myapplication.aplicatiamea.Task;
import java.util.Calendar;
import java.util.stream.Collectors;
import java.util.Collections;
import java.util.ArrayList;
import com.example.myapplication.aplicatiamea.EffectManager;
import com.google.firebase.firestore.DocumentReference;
import java.util.function.Consumer;
import com.google.firebase.firestore.WriteBatch;
import java.util.Set;
import java.util.HashSet;

public class QuestManager {
    private static final String TAG = "QuestManager";
    private static final int MAX_DAILY_QUESTS = 3;
    private static final int MAX_WEEKLY_QUESTS = 2;
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final String userId;
    private Context context;
    private final List<QuestTemplate> templates = Arrays.asList(
        new QuestTemplate("Morning Momentum Quest", "Complete 3 HIGH priority tasks before 9 AM", 3, 50, TimeUnit.HOURS.toMillis(9)),
        new QuestTemplate("Inbox Zero Quest", "Clear your Inbox project items by end of day", 1, 75, TimeUnit.DAYS.toMillis(1)),
        new QuestTemplate("Priority Cleanup Quest", "Finish 5 MEDIUM or HIGH priority tasks", 5, 80, TimeUnit.DAYS.toMillis(1)),
        new QuestTemplate("Speedy Task Quest", "Complete any 3 tasks within 2 hours", 3, 80, TimeUnit.HOURS.toMillis(2)),
        new QuestTemplate("High-Priority Sprint", "Complete 2 HIGH priority tasks within 1 hour", 2, 60, TimeUnit.HOURS.toMillis(1)),
        new QuestTemplate("Habit Streak Quest", "Mark a chosen habit as done 7 days in a row", 7, 200, TimeUnit.DAYS.toMillis(7)),
        new QuestTemplate("Healthy Habits Quest", "Complete 5 micro-habits each day for a week", 5, 100, TimeUnit.DAYS.toMillis(7)),
        new QuestTemplate("Spring Cleaning Quest", "Complete 10 small cleanup tasks over 3 days", 10, 150, TimeUnit.DAYS.toMillis(3)),
        new QuestTemplate("Weekly Review Quest", "Archive completed tasks and plan next week", 1, 120, TimeUnit.DAYS.toMillis(7)),
        new QuestTemplate("Focus Sessions Quest", "Complete two 2-hour focus sessions this week", 2, 150, TimeUnit.DAYS.toMillis(7)),
        // --- Custom Quests ---
        new QuestTemplate("Subtask Surge Quest", "Complete 10 subtasks in a day", 10, 100, TimeUnit.DAYS.toMillis(1)),
        new QuestTemplate("Plan Next Week Quest", "Plan tasks for next week", 1, 100, TimeUnit.DAYS.toMillis(7)),
        // --- Recurring Task Quest ---
        new QuestTemplate("Consistency Champion Quest", "Complete the same recurring task for 3 consecutive days", 3, 150, TimeUnit.DAYS.toMillis(3))
    );
    // Add cooldown time constant - 3 minute cooldown between task operations affecting the same quest
    private static final long QUEST_ACTION_COOLDOWN_MS = 180000; // 3 minutes
    // Add cooldown for reward claiming to prevent potential exploits
    private static final long REWARD_CLAIM_COOLDOWN_MS = 60000; // 1 minute between reward claims

    // Interface for reward claim callback
    public interface RewardClaimListener {
        void onRewardClaimed(boolean success, String message);
    }

    public QuestManager(String userId) {
        this.userId = userId;
    }
    
    // Constructor overload with context for error handling
    public QuestManager(String userId, Context context) {
        this.userId = userId;
        this.context = context;
    }

    /**
     * Ensure up to 3 daily quests each day.
     */
    public void issueDailyQuests() {
        Log.d(TAG, "[DEBUG] issueDailyQuests called");
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.d(TAG, "[DEBUG] issueDailyQuests: No user logged in");
            return;
        }
        final long now = System.currentTimeMillis();
        final long startOfDay = getStartOfDayMillis();
        
        // First ensure we have a consistent state by cleaning up any excess quests
        cleanupDailyQuests(now, startOfDay);
        
        // Check existing daily quests 
        db.collection("users").document(userId)
          .collection("questInstances")
          .whereEqualTo("frequency", "DAILY")
          .whereGreaterThanOrEqualTo("startedAt", startOfDay)
          .get()
          .addOnSuccessListener(snapshot -> {
              List<DocumentSnapshot> todayQuests = snapshot.getDocuments();
              Log.d(TAG, "[DEBUG] issueDailyQuests: Found " + todayQuests.size() + " questInstances for today");
              
              // Don't issue more if we already have the max
              if (todayQuests.size() >= MAX_DAILY_QUESTS) {
                  Log.d(TAG, "[DEBUG] Already have " + todayQuests.size() + " quests for today, no need to issue more");
                  return;
              }
              
              // Get the titles of existing quests to avoid duplicates
              Set<String> existingQuestTitles = new HashSet<>();
              for (DocumentSnapshot doc : todayQuests) {
                  String title = doc.getString("title");
                  if (title != null) {
                      existingQuestTitles.add(title);
                  }
              }
              
              // Calculate how many more quests to issue
              int toIssue = MAX_DAILY_QUESTS - todayQuests.size();
              Log.d(TAG, "[DEBUG] issueDailyQuests: Need to issue " + toIssue + " more daily quests");
              
              // Issue new quests by randomly selecting non-duplicate quest templates
              List<QuestTemplate> availableTemplates = templates.stream()
                  .filter(template -> template.getDurationMillis() <= TimeUnit.DAYS.toMillis(1)) // Daily quests only
                  .filter(template -> !existingQuestTitles.contains(template.getTitle())) // No duplicates
                  .collect(Collectors.toList());
              
              // Shuffle to randomize selection
              Collections.shuffle(availableTemplates);
              
              for (int i = 0; i < Math.min(toIssue, availableTemplates.size()); i++) {
                  QuestTemplate finalTemplate = availableTemplates.get(i);
                  issueQuestInstance(finalTemplate, "DAILY", newQuestDocRef -> Log.d(TAG, "[DEBUG] Issued new daily quest: " + finalTemplate.getTitle()));
              }
          })
          .addOnFailureListener(e -> Log.e(TAG, "Error issuing daily quests: " + e.getMessage(), e));
    }

    /**
     * Ensure up to 2 weekly quests each week.
     */
    public void issueWeeklyQuests() {
        Log.d(TAG, "[DEBUG] issueWeeklyQuests called");
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.d(TAG, "[DEBUG] issueWeeklyQuests: No user logged in");
            return;
        }
        final long now = System.currentTimeMillis();
        final long startOfWeek = getStartOfWeekMillis();
        
        // First ensure we have a consistent state by cleaning up any excess quests
        cleanupWeeklyQuests(now, startOfWeek);
        
        // Check existing weekly quests 
        db.collection("users").document(userId)
          .collection("questInstances")
          .whereEqualTo("frequency", "WEEKLY")
          .whereGreaterThanOrEqualTo("startedAt", startOfWeek)
          .get()
          .addOnSuccessListener(snapshot -> {
              List<DocumentSnapshot> thisWeekQuests = snapshot.getDocuments();
              Log.d(TAG, "[DEBUG] issueWeeklyQuests: Found " + thisWeekQuests.size() + " questInstances for this week");
              
              // Don't issue more if we already have the max
              if (thisWeekQuests.size() >= MAX_WEEKLY_QUESTS) {
                  Log.d(TAG, "[DEBUG] Already have " + thisWeekQuests.size() + " quests for this week, no need to issue more");
                  return;
              }
              
              // Get the titles of existing quests to avoid duplicates
              Set<String> existingQuestTitles = new HashSet<>();
              for (DocumentSnapshot doc : thisWeekQuests) {
                  String title = doc.getString("title");
                  if (title != null) {
                      existingQuestTitles.add(title);
                  }
              }
              
              // Calculate how many more quests to issue
              int toIssue = MAX_WEEKLY_QUESTS - thisWeekQuests.size();
              Log.d(TAG, "[DEBUG] issueWeeklyQuests: Need to issue " + toIssue + " more weekly quests");
              
              // Issue new quests by randomly selecting non-duplicate quest templates
              List<QuestTemplate> availableTemplates = templates.stream()
                  .filter(template -> template.getDurationMillis() > TimeUnit.DAYS.toMillis(1)) // Weekly quests only
                  .filter(template -> !existingQuestTitles.contains(template.getTitle())) // No duplicates
                  .collect(Collectors.toList());
              
              // Shuffle to randomize selection
              Collections.shuffle(availableTemplates);
              
              for (int i = 0; i < Math.min(toIssue, availableTemplates.size()); i++) {
                  QuestTemplate finalTemplate = availableTemplates.get(i);
                  issueQuestInstance(finalTemplate, "WEEKLY", newQuestDocRef -> {
                      Log.d(TAG, "[DEBUG] Issued new weekly quest: " + finalTemplate.getTitle());
                  });
              }
          })
          .addOnFailureListener(e -> Log.e(TAG, "Error issuing weekly quests: " + e.getMessage(), e));
    }

    /**
     * Clean up and manage daily quests to ensure exactly 3 are active
     */
    private void cleanupDailyQuests(final long now, final long startOfDay) {
        Log.d(TAG, "[DEBUG] Enforcing 3 daily quests limit");
        
        // First query all daily quests from today
        db.collection("users").document(userId)
          .collection("questInstances")
          .whereEqualTo("frequency", "DAILY")
          .whereGreaterThanOrEqualTo("startedAt", startOfDay)
          .get()
          .addOnSuccessListener(snapshot -> {
              List<DocumentSnapshot> allDailyQuests = snapshot.getDocuments();
              Log.d(TAG, "[DEBUG] issueDailyQuests: Found " + allDailyQuests.size() + " questInstances");
              
              // Filter active quests for today
              List<DocumentSnapshot> todayQuests = new ArrayList<>();
              for (DocumentSnapshot doc : allDailyQuests) {
                  Long startedAt = doc.getLong("startedAt");
                  if (startedAt != null && startedAt >= startOfDay) {
                      todayQuests.add(doc);
                  }
              }
              
              Log.d(TAG, "[DEBUG] issueDailyQuests: Found " + todayQuests.size() + " questInstances for today");
              
              if (todayQuests.size() > MAX_DAILY_QUESTS) {
                  // Sort quests by priority: completed quests first, then non-completed
                  // This ensures we prioritize keeping completed quests
                  todayQuests.sort((a, b) -> {
                      boolean aCompleted = Boolean.TRUE.equals(a.getBoolean("completed"));
                      boolean bCompleted = Boolean.TRUE.equals(b.getBoolean("completed"));
                      
                      if (aCompleted && !bCompleted) return -1;
                      if (!aCompleted && bCompleted) return 1;
                      
                      // For quests with same completion status, prioritize by startedAt (oldest first)
                      Long aStarted = a.getLong("startedAt");
                      Long bStarted = b.getLong("startedAt");
                      if (aStarted != null && bStarted != null) {
                          return Long.compare(aStarted, bStarted);
                      }
                      
                      return 0;
                  });
                  
                  // Keep the first MAX_DAILY_QUESTS quests, delete the rest
                  List<DocumentSnapshot> questsToDelete = todayQuests.subList(MAX_DAILY_QUESTS, todayQuests.size());
                  for (DocumentSnapshot questToDelete : questsToDelete) {
                      Log.d(TAG, "[DEBUG] Deleting extra daily quest: " + questToDelete.getId());
                      questToDelete.getReference().delete();
                  }
                  
                  Log.d(TAG, "[DEBUG] issueDailyQuests: Keeping " + MAX_DAILY_QUESTS + " quests after cleanup");
              }
          })
          .addOnFailureListener(e -> {
              Log.e(TAG, "Error cleaning up daily quests: " + e.getMessage(), e);
          });
    }
    
    /**
     * Clean up and manage weekly quests to ensure exactly 2 are active
     */
    private void cleanupWeeklyQuests(final long now, final long startOfWeek) {
        Log.d(TAG, "[DEBUG] Enforcing 2 weekly quests limit");
        
        // First query all weekly quests from this week
        db.collection("users").document(userId)
          .collection("questInstances")
          .whereEqualTo("frequency", "WEEKLY")
          .whereGreaterThanOrEqualTo("startedAt", startOfWeek)
          .get()
          .addOnSuccessListener(snapshot -> {
              List<DocumentSnapshot> allWeeklyQuests = snapshot.getDocuments();
              Log.d(TAG, "[DEBUG] issueWeeklyQuests: Found " + allWeeklyQuests.size() + " questInstances");
              
              // Filter active quests for this week
              List<DocumentSnapshot> thisWeekQuests = new ArrayList<>();
              for (DocumentSnapshot doc : allWeeklyQuests) {
                  Long startedAt = doc.getLong("startedAt");
                  if (startedAt != null && startedAt >= startOfWeek) {
                      thisWeekQuests.add(doc);
                  }
              }
              
              Log.d(TAG, "[DEBUG] issueWeeklyQuests: Found " + thisWeekQuests.size() + " questInstances for this week");
              
              if (thisWeekQuests.size() > MAX_WEEKLY_QUESTS) {
                  // Sort quests by priority: completed quests first, then non-completed
                  // This ensures we prioritize keeping completed quests
                  thisWeekQuests.sort((a, b) -> {
                      boolean aCompleted = Boolean.TRUE.equals(a.getBoolean("completed"));
                      boolean bCompleted = Boolean.TRUE.equals(b.getBoolean("completed"));
                      
                      if (aCompleted && !bCompleted) return -1;
                      if (!aCompleted && bCompleted) return 1;
                      
                      // For quests with same completion status, prioritize by startedAt (oldest first)
                      Long aStarted = a.getLong("startedAt");
                      Long bStarted = b.getLong("startedAt");
                      if (aStarted != null && bStarted != null) {
                          return Long.compare(aStarted, bStarted);
                      }
                      
                      return 0;
                  });
                  
                  // Keep the first MAX_WEEKLY_QUESTS quests, delete the rest
                  List<DocumentSnapshot> questsToDelete = thisWeekQuests.subList(MAX_WEEKLY_QUESTS, thisWeekQuests.size());
                  for (DocumentSnapshot questToDelete : questsToDelete) {
                      Log.d(TAG, "[DEBUG] Deleting extra weekly quest: " + questToDelete.getId());
                      questToDelete.getReference().delete();
                  }
                  
                  Log.d(TAG, "[DEBUG] issueWeeklyQuests: Keeping " + MAX_WEEKLY_QUESTS + " quests after cleanup");
              }
          })
          .addOnFailureListener(e -> {
              Log.e(TAG, "Error cleaning up weekly quests: " + e.getMessage(), e);
          });
    }
    
    /**
     * Increment progress on active quests when a task is completed, applying quest-specific filters.
     */
    public void recordTaskCompletion(String taskId, String taskTitle, String taskPriority, DocumentReference userRef, Map<String, Object> lastQuestActions) {
        long now = System.currentTimeMillis();
        
        // First check if we already have the maximum number of daily quests
        db.collection("users").document(userId)
          .collection("questInstances")
          .whereEqualTo("frequency", "DAILY")
          .whereGreaterThanOrEqualTo("startedAt", getStartOfDayMillis())
          .get()
          .addOnSuccessListener(snapshot -> {
              int currentDailyQuestCount = snapshot.size();
              
              // Only proceed with quest progress updates and new quest creation if under limits
              if (currentDailyQuestCount <= MAX_DAILY_QUESTS) {
                  Log.d(TAG, "[DEBUG] Checking for quest matches for task: " + taskTitle);
                  
                  // Check for Habit Streak Quest
                  Log.d(TAG, "[DEBUG] Checking Habit Streak Quest for task: " + taskId);
                  
                  // Check for priority-based quests
                  if (taskPriority != null) {
                      Log.d(TAG, "[DEBUG] Checking for priority-based quests with task priority: '" + taskPriority + "'");
                      
                      if ("HIGH".equals(taskPriority)) {
                          // Update High-Priority Sprint quest
                          Log.d(TAG, "[DEBUG] Triggering High-Priority Sprint progress for HIGH priority task");
                          updateMatchingQuestProgress(userRef, "High-Priority Sprint", now, lastQuestActions);
                          
                          // Update Priority Cleanup Quest for HIGH priority tasks
                          Log.d(TAG, "[DEBUG] Triggering Priority Cleanup Quest progress for HIGH priority task");
                          updateMatchingQuestProgress(userRef, "Priority Cleanup Quest", now, lastQuestActions);
                      }
                  }
                  
                  // Always trigger Speedy Task Quest progress
                  Log.d(TAG, "[DEBUG] Triggering Speedy Task Quest progress");
                  updateMatchingQuestProgress(userRef, "Speedy Task Quest", now, lastQuestActions);
              } else {
                  Log.d(TAG, "[DEBUG] Maximum daily quests (" + MAX_DAILY_QUESTS + ") already reached. Skipping quest updates for task completion.");
                  // Cleanup to ensure we have only MAX_DAILY_QUESTS quests
                  cleanupDailyQuests(now, getStartOfDayMillis());
              }
          })
          .addOnFailureListener(e -> {
              Log.e(TAG, "Error checking quest count before task completion: " + e.getMessage(), e);
          });
    }

    /**
     * Record when a task completion is undone, potentially decrementing quest progress.
     */
    public void recordTaskUndo(Task task, DocumentReference userRef) {
        long now = System.currentTimeMillis();

        // Fetch user document once to get effects map and quest action timestamps
        userRef.get().addOnSuccessListener(userSnapshot -> {
            if (!userSnapshot.exists()) {
                Log.e(TAG, "User document not found for quest undo.");
                return;
            }
            
            // Get the user's quest action timestamps for cooldown checks
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
            
            // Undo Speedy Task Quest progress
            decrementQuestProgress("Speedy Task Quest", EffectManager.EffectTiming.IMMEDIATE, userRef, finalLastQuestActions);
            
            // Undo task-specific quest progress
            if (task != null) {
                String priority = task.getPriority();
                
                // Priority Cleanup Quest (medium & high priority tasks)
                if ("MEDIUM".equalsIgnoreCase(priority) || "HIGH".equalsIgnoreCase(priority)) {
                    decrementQuestProgress("Priority Cleanup Quest", EffectManager.EffectTiming.IMMEDIATE, userRef, finalLastQuestActions);
                }
                
                // Morning Momentum Quest (high priority tasks before 9 AM)
                if ("HIGH".equalsIgnoreCase(priority)) {
                    decrementQuestProgress("Morning Momentum Quest", EffectManager.EffectTiming.IMMEDIATE, userRef, finalLastQuestActions);
                }
                
                // High-Priority Sprint (high priority tasks)
                if ("HIGH".equalsIgnoreCase(priority)) {
                    decrementQuestProgress("High-Priority Sprint", EffectManager.EffectTiming.IMMEDIATE, userRef, finalLastQuestActions);
                }
                
                // Handle subtasks
                List<Task.Subtask> steps = task.getSteps();
                if (steps != null && !steps.isEmpty()) {
                    decrementQuestProgress("Subtask Surge Quest", EffectManager.EffectTiming.IMMEDIATE, userRef, finalLastQuestActions);
                }
                
                // Handle recurring tasks
                if (task.getRecurrenceGroupId() != null && !task.getRecurrenceGroupId().isEmpty()) {
                    handleRecurringTaskUndo(task, userRef, finalLastQuestActions);
                }
            }
        });
    }
    
    /**
     * Handle undoing a recurring task completion
     * @param task The task that was uncompleted
     * @param userRef Reference to the user document
     * @param lastQuestActions The last quest actions map
     */
    private void handleRecurringTaskUndo(Task task, DocumentReference userRef, Map<String, Object> lastQuestActions) {
        // Only proceed if this is a recurring task with a group ID
        if (task.getRecurrenceGroupId() == null || task.getRecurrenceGroupId().isEmpty()) {
            return;
        }
        
        // If a recurring task is uncompleted, we need to check if this breaks a streak
        // with consecutive completions of the same task name
        db.collection("users").document(userId)
          .collection("tasks")
          .whereEqualTo("recurrenceGroupId", task.getRecurrenceGroupId())
          .get()
          .addOnSuccessListener(querySnapshot -> {
              List<Task> tasksInSeries = new ArrayList<>();
              for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                  Task seriesTask = doc.toObject(Task.class);
                  if (seriesTask != null) {
                      seriesTask.setId(doc.getId());
                      tasksInSeries.add(seriesTask);
                  }
              }
              
              // Check if undoing this specific task breaks a streak of consecutive completions
              if (breaksConsecutiveCompletionStreak(tasksInSeries, task)) {
                  Log.d(TAG, "Undoing this task breaks a consecutive completion streak for " + task.getName());
                  
                  // Check affected quests, starting with Consistency Champion Quest
                  decrementQuestProgress("Consistency Champion Quest", EffectManager.EffectTiming.IMMEDIATE, userRef, lastQuestActions);
                  
                  // Also check if Habit Streak Quest needs to be decremented
                  // We don't need to check the streak length again as breaksConsecutiveCompletionStreak already did
                  db.collection("users").document(userId)
                    .collection("questInstances")
                    .whereEqualTo("title", "Habit Streak Quest")
                    .get()
                    .addOnSuccessListener(questSnap -> {
                        for (DocumentSnapshot questDoc : questSnap.getDocuments()) {
                            String habitName = questDoc.getString("habitName");
                            
                            // Only decrement if this quest was tracking this specific habit name
                            if (habitName != null && habitName.equals(task.getName())) {
                                decrementQuestProgress("Habit Streak Quest", EffectManager.EffectTiming.IMMEDIATE, userRef, lastQuestActions);
                                break;
                            }
                        }
                    });
                  
                  // Also consider Healthy Habits Quest may need to be updated
                  decrementQuestProgress("Healthy Habits Quest", EffectManager.EffectTiming.IMMEDIATE, userRef, lastQuestActions);
              }
          })
          .addOnFailureListener(e -> {
              Log.e(TAG, "Error checking recurring task series for undo: " + e.getMessage());
              // Fallback to simple decrement if we can't check the series
              decrementQuestProgress("Consistency Champion Quest", EffectManager.EffectTiming.IMMEDIATE, userRef, lastQuestActions);
          });
    }
    
    /**
     * Checks if undoing a task completion breaks a streak of consecutive task completions
     * @param tasksInSeries All tasks in the recurrence group
     * @param undoneTask The task being undone
     * @return true if undoing this task breaks a meaningful streak
     */
    private boolean breaksConsecutiveCompletionStreak(List<Task> tasksInSeries, Task undoneTask) {
        // Group tasks by name to ensure we're only counting tasks with the exact same name
        Map<String, List<Task>> tasksByName = new HashMap<>();
        
        for (Task task : tasksInSeries) {
            if (task.getName() != null && task.getName().equals(undoneTask.getName())) {
                if (!tasksByName.containsKey(task.getName())) {
                    tasksByName.put(task.getName(), new ArrayList<>());
                }
                tasksByName.get(task.getName()).add(task);
            }
        }
        
        List<Task> sameNameTasks = tasksByName.get(undoneTask.getName());
        if (sameNameTasks == null || sameNameTasks.isEmpty()) {
            // No tasks with the same name found
            return false;
        }
        
        // Sort tasks by deadline
        Collections.sort(sameNameTasks, (t1, t2) -> 
            Long.compare(t1.getDeadlineTimestamp(), t2.getDeadlineTimestamp()));
        
        // First simulate with the undone task marked as completed
        int maxConsecutiveWithTask = countConsecutiveCompletions(sameNameTasks, null);
        
        // Then simulate with the undone task marked as not completed
        int maxConsecutiveWithoutTask = countConsecutiveCompletions(sameNameTasks, undoneTask.getId());
        
        // Check thresholds for both quests
        boolean breaksConsistencyChampion = maxConsecutiveWithTask >= 3 && maxConsecutiveWithoutTask < 3;
        boolean breaksHabitStreak = maxConsecutiveWithTask >= 7 && maxConsecutiveWithoutTask < 7;
        
        // Log what's being affected
        if (breaksConsistencyChampion) {
            Log.d(TAG, "Undoing task breaks Consistency Champion threshold: " + 
                maxConsecutiveWithTask + " -> " + maxConsecutiveWithoutTask);
        }
        
        if (breaksHabitStreak) {
            Log.d(TAG, "Undoing task breaks Habit Streak threshold: " + 
                maxConsecutiveWithTask + " -> " + maxConsecutiveWithoutTask);
        }
        
        return breaksConsistencyChampion || breaksHabitStreak;
    }
    
    /**
     * Counts consecutive completions in a list of tasks, optionally excluding one task
     * @param tasks The sorted list of tasks to check
     * @param excludeTaskId Optional task ID to consider as not completed
     * @return The maximum number of consecutive completions
     */
    private int countConsecutiveCompletions(List<Task> tasks, String excludeTaskId) {
        int maxConsecutive = 0;
        int currentConsecutive = 0;
        
        for (Task task : tasks) {
            // Check if this is the task to exclude
            boolean isCompleted = task.isCompleted();
            if (excludeTaskId != null && task.getId().equals(excludeTaskId)) {
                isCompleted = false; // Pretend this task is not completed
            }
            
            if (isCompleted) {
                currentConsecutive++;
                maxConsecutive = Math.max(maxConsecutive, currentConsecutive);
            } else {
                // Reset consecutive count on incomplete task
                currentConsecutive = 0;
            }
        }
        
        return maxConsecutive;
    }

    // Modified decrementQuestProgress to keep quests completed even if tasks are undone
    public void decrementQuestProgress(String questTitle, EffectManager.EffectTiming timing, DocumentReference userRef, Map<String, Object> lastQuestActions) {
        // Only proceed if we have a valid user
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        Log.d(TAG, "[DEBUG] Decrementing quest progress for " + questTitle + " with timing: " + timing);
        
        // Find the quest instance with this title that's active
        db.collection("users").document(userId)
          .collection("questInstances")
          .whereEqualTo("title", questTitle)
          .get() // Get all quests first, then filter by expiration
          .addOnSuccessListener(snap -> {
               if (snap.isEmpty()) {
                   Log.d(TAG, "[DEBUG] No quests found with title: " + questTitle + " for decrementing");
                   return;
               }
               
               Log.d(TAG, "[DEBUG] Found " + snap.size() + " quests with title: " + questTitle + " for decrementing");
               
               for (DocumentSnapshot doc : snap.getDocuments()) {
                   Long expiresAt = doc.getLong("expiresAt");
                   if (expiresAt == null || expiresAt <= System.currentTimeMillis()) {
                       Log.d(TAG, "[DEBUG] Quest " + doc.getId() + " is expired or has no expiration, skipping decrement");
                       continue;
                   }
                   
                   // Check if there's a quest action cooldown in effect
                   long timestamp = System.currentTimeMillis();
                   String questActionKey = "quest_action_" + doc.getId();
                   Object lastQuestActionObj = lastQuestActions.get(questActionKey);
                   long lastQuestActionTime = 0;
                   if (lastQuestActionObj instanceof Number) {
                       lastQuestActionTime = ((Number) lastQuestActionObj).longValue();
                   }
                  
                   // Check for cooldown to prevent spam
                   boolean inCooldown = (timestamp - lastQuestActionTime < QUEST_ACTION_COOLDOWN_MS);
                   if (inCooldown) {
                       Log.d(TAG, "[DEBUG] Skipping quest decrement for " + questTitle + " as it's in cooldown period.");
                       continue;
                   }
                   
                   // Create a fresh copy of the quest actions map for each document
                   final Map<String, Object> questActionsToUpdate = new HashMap<>(lastQuestActions);
                   questActionsToUpdate.put(questActionKey, timestamp);
                   
                   // --- Special logic for Speedy Task Quest ---
                   if ("Speedy Task Quest".equals(questTitle)) {
                       List<Long> completionTimestamps = new ArrayList<>();
                       if (doc.contains("completionTimestamps") && doc.get("completionTimestamps") instanceof List) {
                           try {
                               List<?> rawList = (List<?>) doc.get("completionTimestamps");
                               for (Object o : rawList) {
                                   if (o instanceof Number) {
                                       completionTimestamps.add(((Number) o).longValue());
                                   }
                               }
                           } catch (Exception e) {
                               Log.e(TAG, "Error reading completionTimestamps for Speedy Task Quest", e);
                           }
                       } else {
                           Log.d(TAG, "[DEBUG] No completionTimestamps array for Speedy Task Quest, nothing to decrement");
                       }
                       
                       Log.d(TAG, "[DEBUG] Speedy Task Quest timestamps before decrement: " + completionTimestamps);
                       
                       // Only remove the most recent timestamp if there are any
                       List<Long> updatedCompletionTimestamps = new ArrayList<>(completionTimestamps);
                       if (!updatedCompletionTimestamps.isEmpty()) {
                           // Sort timestamps to find the most recent
                           Collections.sort(updatedCompletionTimestamps);
                           updatedCompletionTimestamps.remove(updatedCompletionTimestamps.size() - 1);
                           Log.d(TAG, "[DEBUG] Speedy Task Quest timestamps after decrement: " + updatedCompletionTimestamps);
                       } else {
                           Log.d(TAG, "[DEBUG] No timestamps to remove from Speedy Task Quest");
                       }
                       
                       // Get updated progress count based on the 2-hour window
                       long twoHoursWindow = java.util.concurrent.TimeUnit.HOURS.toMillis(2);
                       long windowStart = timestamp - twoHoursWindow;
                       
                       // Filter timestamps within the 2-hour window
                       List<Long> filteredTimestamps = new ArrayList<>();
                       for (Long ts : updatedCompletionTimestamps) {
                           if (ts >= windowStart) {
                               filteredTimestamps.add(ts);
                           }
                       }
                       
                       final int progress = filteredTimestamps.size();
                       Log.d(TAG, "[DEBUG] Speedy Task Quest progress after decrement: " + progress);
                       
                       // Update the document
                       Map<String, Object> updates = new HashMap<>();
                       updates.put("completionTimestamps", updatedCompletionTimestamps);
                       updates.put("currentNode", progress);
                       
                       // If we drop below the completion threshold, reset status
                       if (progress < 3) {
                           updates.put("status", null);
                           Log.d(TAG, "[DEBUG] Speedy Task Quest status reset - progress below threshold");
                       }
                       
                       // Make a final reference to questActionsToUpdate for lambda
                       final Map<String, Object> finalQuestActionsToUpdate = questActionsToUpdate;
                       
                       db.runTransaction(transaction -> {
                           transaction.set(doc.getReference(), updates, com.google.firebase.firestore.SetOptions.merge());
                           transaction.update(userRef, "lastQuestActions", finalQuestActionsToUpdate);
                           return null;
                       }).addOnSuccessListener(v -> {
                           Log.d(TAG, "[DEBUG] Speedy Task Quest decrement transaction successful, new progress: " + progress);
                       }).addOnFailureListener(e -> {
                           Log.e(TAG, "[DEBUG] Speedy Task Quest decrement transaction FAILED: " + e.getMessage(), e);
                       });
                       continue; // Skip the rest of the loop for this quest
                   }
                   // --- End special logic for Speedy Task Quest ---
                   
                   // Regular quest progress decrement
                   Long currentProgress = doc.getLong("currentNode");
                   if (currentProgress == null) {
                       Log.d(TAG, "[DEBUG] Quest " + doc.getId() + " has no currentNode value, skipping decrement");
                       continue;
                   }
                   
                   // Avoid negative progress
                   if (currentProgress <= 0) {
                       Log.d(TAG, "[DEBUG] Quest " + doc.getId() + " already at zero progress, skipping decrement");
                       continue;
                   }
                   
                   final long newProgress = currentProgress - 1;
                   final long totalNodes = doc.getLong("totalNodes") != null ? doc.getLong("totalNodes") : 1L;
                   
                   Log.d(TAG, "[DEBUG] Decrementing quest " + questTitle + " progress: " + currentProgress + " -> " + newProgress + " (total: " + totalNodes + ")");
                   
                   Map<String, Object> updates = new HashMap<>();
                   updates.put("currentNode", newProgress);
                   
                   // Check if quest was completed and rewards were claimed
                   boolean rewardClaimed = doc.contains("rewardClaimed") && Boolean.TRUE.equals(doc.getBoolean("rewardClaimed"));
                   
                   // If quest was completed and rewards were claimed, keep it marked as completed
                   // but don't deduct points - this keeps quest progress even if tasks are undone
                   boolean deductPoints = false;
                   long rewardAmount = 0L;
                   
                   if (currentProgress >= totalNodes) {
                       if (rewardClaimed) {
                           // Keep quest completed state but don't deduct points
                           updates.put("status", "COMPLETED");
                           Log.d(TAG, "[DEBUG] Quest " + questTitle + " was completed and claimed, keeping COMPLETED status");
                       } else if (newProgress < totalNodes) {
                           // If rewards weren't claimed and new progress is below threshold, clear status
                           updates.put("status", null);
                           Log.d(TAG, "[DEBUG] Quest " + questTitle + " no longer meets completion criteria, clearing status");
                       }
                   }

                   // Capture for lambdas
                   final boolean finalDeductPoints = deductPoints;
                   final long finalRewardAmount = rewardAmount;
                   final Map<String, Object> finalQuestActionsToUpdate = questActionsToUpdate;
                   
                   // Atomically update quest and potentially deduct points
                   db.runTransaction(transaction -> {
                       transaction.set(doc.getReference(), updates, com.google.firebase.firestore.SetOptions.merge());
                       if (finalDeductPoints) {
                           transaction.update(userRef, "points", FieldValue.increment(-finalRewardAmount));
                           transaction.update(userRef, "goldCoins", FieldValue.increment(-finalRewardAmount));
                       }
                       
                       // Always update lastQuestActions
                       transaction.update(userRef, "lastQuestActions", finalQuestActionsToUpdate);
                       
                       return null; // Success
                   }).addOnSuccessListener(v -> {
                       Log.d(TAG, "[DEBUG] Quest decrement transaction successful for " + questTitle + ", new progress: " + newProgress + "/" + totalNodes);
                       if (finalDeductPoints) {
                            Log.d(TAG, "[DEBUG] Deducted " + finalRewardAmount + " points and coins for undoing " + questTitle);
                            
                            // Deduct XP for quest completion reversal with level multiplier
                            getUserLevel(level -> {
                                // Calculate the same amount that would have been awarded
                                double levelMultiplier = 1.0 + ((level - 1) * 0.1);
                                long xpAmount = Math.round(finalRewardAmount * 1.5 * levelMultiplier);
                                
                                RewardManager rewardManager = new RewardManager(userId);
                                rewardManager.deductXpForQuest(questTitle, xpAmount);
                                Log.d(TAG, "[DEBUG] Deducted " + xpAmount + " XP (level " + level + " multiplier: " + levelMultiplier + 
                                      "x) for undoing completion of " + questTitle);
                            });
                       }
                   }).addOnFailureListener(e -> {
                       Log.e(TAG, "[DEBUG] Quest decrement transaction FAILED for " + questTitle + ": " + e.getMessage(), e);
                   });
              }
          }).addOnFailureListener(e -> Log.e(TAG, "[DEBUG] Failed to query quests for decrementing " + questTitle + ": " + e.getMessage(), e));
    }

    /**
     * Exposes the full list of quest templates for UI display.
     */
    public List<QuestTemplate> getAllTemplates() {
        return new ArrayList<>(templates);
    }

    /**
     * Record one day completed for weekly quests (increment currentNode by 1).
     */
    public void recordWeeklyDayCompletion() {
        long startOfWeek = getStartOfWeekMillis();
        db.collection("users").document(userId)
          .collection("questInstances")
          .whereEqualTo("frequency", "WEEKLY")
          .whereGreaterThanOrEqualTo("startedAt", startOfWeek)
          .get()
          .addOnSuccessListener(qs -> {
              for (DocumentSnapshot doc : qs.getDocuments()) {
                  doc.getReference().update("currentNode", FieldValue.increment(1));
              }
          });
    }

    /**
     * Undo one day completion for weekly quests (decrement currentNode by 1).
     */
    public void recordWeeklyDayUndo() {
        long startOfWeek = getStartOfWeekMillis();
        db.collection("users").document(userId)
          .collection("questInstances")
          .whereEqualTo("frequency", "WEEKLY")
          .whereGreaterThanOrEqualTo("startedAt", startOfWeek)
          .get()
          .addOnSuccessListener(qs -> {
              for (DocumentSnapshot doc : qs.getDocuments()) {
                  doc.getReference().update("currentNode", FieldValue.increment(-1));
              }
          });
    }

    public void initializeCharacterSprites() {
        DocumentReference userRef = db.collection("users").document(userId);
        userRef.get().addOnSuccessListener(documentSnapshot -> {
            if (documentSnapshot.exists() && !documentSnapshot.contains("characterSprites")) {
                // Initialize with default values or an empty map
                Map<String, Object> defaultSprites = new HashMap<>();
                // Add default sprite paths or IDs here if applicable
                 defaultSprites.put("default", "path/to/default/sprite.png");
                userRef.update("characterSprites", defaultSprites)
                        .addOnSuccessListener(aVoid -> Log.d(TAG, "Character sprites initialized for user " + userId))
                        .addOnFailureListener(e -> Log.e(TAG, "Failed to initialize character sprites for user " + userId, e));
            } else if (!documentSnapshot.exists()) {
                 Log.w(TAG, "User document not found, cannot initialize sprites for user " + userId);
            } else {
                Log.d(TAG, "Character sprites field already exists for user " + userId);
            }
        }).addOnFailureListener(e -> Log.e(TAG, "Failed to check for character sprites field for user " + userId, e));
    }

    /**
     * Record progress when a subtask is completed.
     * @param subtasksCount The number of subtasks completed
     * @param userRef The reference to the user document for atomic updates
     */
    public void recordSubtaskCompletion(int subtasksCount, DocumentReference userRef) {
        long now = System.currentTimeMillis();
        Log.d(TAG, "[DEBUG] recordSubtaskCompletion called with count: " + subtasksCount);
        
        // First get the user document to retrieve lastQuestActions
        userRef.get().addOnSuccessListener(userDoc -> {
            if (!userDoc.exists()) {
                Log.e(TAG, "User document not found for subtask completion");
                return;
            }
            
            Map<String, Object> lastQuestActions = userDoc.contains("lastQuestActions") ?
                    (Map<String, Object>) userDoc.get("lastQuestActions") : new HashMap<>();
                    
            // Make a copy to prevent modification issues
            final Map<String, Object> finalLastQuestActions = lastQuestActions != null ? 
                    new HashMap<>(lastQuestActions) : new HashMap<>();
            
            // Check for Subtask Surge Quest
            // First check if there's an active daily Subtask Surge Quest
            long subtaskNow = System.currentTimeMillis();
            db.collection("users").document(userId)
              .collection("questInstances")
              .whereEqualTo("title", "Subtask Surge Quest")
              // Simplified query - remove other conditions
              .get()
              .addOnSuccessListener(querySnapshot -> {
                  // Client-side filtering for quest instances
                  DocumentSnapshot activeQuestDoc = null;
                  
                  for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                      Long expiresAt = doc.getLong("expiresAt");
                      String frequency = doc.getString("frequency");
                      Boolean completed = doc.getBoolean("completed");
                      
                      // Check if this is an active, uncompleted daily quest that hasn't expired
                      if (expiresAt != null && expiresAt > subtaskNow && 
                          "DAILY".equals(frequency) &&
                          (completed == null || !completed)) {
                          activeQuestDoc = doc;
                          break;
                      }
                  }
                  
                  if (activeQuestDoc != null) {
                      // Update progress on the active quest instance
                      long currentProgress = activeQuestDoc.getLong("currentNode") != null ? 
                          activeQuestDoc.getLong("currentNode") : 0;
                      long totalNodes = activeQuestDoc.getLong("totalNodes") != null ? 
                          activeQuestDoc.getLong("totalNodes") : 5;
                      long newProgress = Math.min(currentProgress + subtasksCount, totalNodes);
                      
                      Log.d(TAG, "[DEBUG] Subtask Surge Quest progress: " + newProgress + "/" + totalNodes);
                      Map<String, Object> updates = new HashMap<>();
                      updates.put("currentNode", newProgress);
                      
                      if (newProgress >= totalNodes) {
                          updates.put("completed", true);
                          updates.put("completedAt", now);
                          Log.d(TAG, "[DEBUG] Subtask Surge Quest completed! Progress: " + newProgress);
                      }
                      
                      activeQuestDoc.getReference().update(updates)
                         .addOnSuccessListener(v -> Log.d(TAG, "[DEBUG] Subtask Surge Quest updated: " + newProgress))
                         .addOnFailureListener(e -> Log.e(TAG, "[DEBUG] Failed to update Subtask Surge Quest: " + e.getMessage(), e));
                  } else {
                      // No active quest - issue a new one
                      Log.d(TAG, "[DEBUG] No active Subtask Surge Quest found, issuing a new one");
                      for (QuestTemplate templateLoopVar : templates) {
                          if ("Subtask Surge Quest".equals(templateLoopVar.getTitle())) {
                              final QuestTemplate finalTemplate = templateLoopVar; // Make it effectively final for lambda
                              final long taskCompletionTime = now; // Capture current 'now' for the lambda
                              final int numSubtasksCompleted = subtasksCount; // Capture current 'subtasksCount'

                              issueQuestInstance(finalTemplate, "DAILY", newQuestDocRef -> {
                                  // The new quest (newQuestDocRef) has been created.
                                  // Update it directly with the initial subtasksCount.
                                  Map<String, Object> initialProgressUpdates = new HashMap<>();
                                  long currentNodes = Math.min(numSubtasksCompleted, finalTemplate.getTotalNodes());
                                  initialProgressUpdates.put("currentNode", currentNodes);
                                  
                                  if (currentNodes >= finalTemplate.getTotalNodes()) {
                                      initialProgressUpdates.put("completed", true);
                                      initialProgressUpdates.put("completedAt", taskCompletionTime);
                                      Log.d(TAG, "[DEBUG] Subtask Surge Quest completed on issuance! Progress: " + currentNodes + "/" + finalTemplate.getTotalNodes());
                                  }
                                  
                                  newQuestDocRef.update(initialProgressUpdates)
                                      .addOnSuccessListener(v2 -> Log.d(TAG, "[DEBUG] New Subtask Surge Quest updated with initial progress: " + currentNodes + "/" + finalTemplate.getTotalNodes()))
                                      .addOnFailureListener(e -> Log.e(TAG, "[DEBUG] Failed to update new Subtask Surge Quest: " + e.getMessage(), e));
                              });
                              break;
                          }
                      }
                  }
              })
              .addOnFailureListener(e -> Log.e(TAG, "[DEBUG] Failed to query Subtask Surge Quest: " + e.getMessage(), e));
        }).addOnFailureListener(e -> Log.e(TAG, "Failed to get user document for subtask completion: " + e.getMessage(), e));
    }

    /**
     * Record progress when planning tasks for next week.
     */
    public void recordPlanNextWeek(DocumentReference userRef) {
        long now = System.currentTimeMillis();
        // Calculate start and end of next week
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        // Move to next week (set to first day of next week)
        cal.add(Calendar.WEEK_OF_YEAR, 1);
        cal.set(Calendar.DAY_OF_WEEK, cal.getFirstDayOfWeek());
        long startOfNextWeek = cal.getTimeInMillis();
        // End of next week (last millisecond of last day)
        cal.add(Calendar.DAY_OF_WEEK, 6);
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);
        long endOfNextWeek = cal.getTimeInMillis();

        // Query all tasks for the next week
        db.collection("users").document(userId)
            .collection("tasks")
            .whereGreaterThanOrEqualTo("deadlineTimestamp", startOfNextWeek)
            .whereLessThanOrEqualTo("deadlineTimestamp", endOfNextWeek)
            .get()
            .addOnSuccessListener(taskSnap -> {
                // Map from day (yyyy-MM-dd) to boolean (has task)
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                java.util.Set<String> daysWithTasks = new java.util.HashSet<>();
                for (com.google.firebase.firestore.DocumentSnapshot doc : taskSnap.getDocuments()) {
                    Long deadline = doc.getLong("deadlineTimestamp");
                    if (deadline != null) {
                        java.util.Date date = new java.util.Date(deadline);
                        String day = sdf.format(date);
                        daysWithTasks.add(day);
                    }
                }
                // Build set of all days in next week
                java.util.Set<String> allDays = new java.util.HashSet<>();
                Calendar iter = Calendar.getInstance();
                iter.setTimeInMillis(startOfNextWeek);
                for (int i = 0; i < 7; i++) {
                    allDays.add(sdf.format(iter.getTime()));
                    iter.add(Calendar.DAY_OF_MONTH, 1);
                }
                // Only progress quest if all days are covered
                if (daysWithTasks.containsAll(allDays)) {
                    // Get last quest actions to enforce cooldown
                    userRef.get().addOnSuccessListener(userDoc -> {
                        Map<String, Object> lastQuestActions = new HashMap<>();
                        if (userDoc.contains("lastQuestActions") && userDoc.get("lastQuestActions") instanceof Map) {
                            try {
                                lastQuestActions = (Map<String, Object>) userDoc.get("lastQuestActions");
                                if (lastQuestActions == null) lastQuestActions = new HashMap<>();
                            } catch (ClassCastException e) {
                                Log.e(TAG, "Firestore 'lastQuestActions' is not a Map.", e);
                            }
                        }
                        updateMatchingQuestProgress(userRef, "Plan Next Week Quest", now, lastQuestActions);
                    });
                } else {
                    Log.d(TAG, "Plan Next Week Quest: Not all days have tasks planned for next week.");
                }
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Failed to query tasks for plan next week quest", e);
            });
    }

    /**
     * Gets the current user's level and passes it to the callback
     */
    private void getUserLevel(Consumer<Long> callback) {
        db.collection("users").document(userId)
          .get()
          .addOnSuccessListener(snapshot -> {
              Long level = snapshot.getLong("level");
              if (level == null) level = 1L;
              callback.accept(level);
          })
          .addOnFailureListener(e -> {
              Log.e(TAG, "Failed to get user level", e);
              callback.accept(1L); // Default to level 1 on failure
          });
    }

    /**
     * Claim rewards for a completed quest
     * @param questId The ID of the quest to claim rewards for
     * @param listener Callback to handle the result
     */
    public void claimQuestReward(String questId, RewardClaimListener listener) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            if (listener != null) {
                listener.onRewardClaimed(false, "User not logged in");
            }
            return;
        }
        
        DocumentReference userRef = db.collection("users").document(userId);
        DocumentReference questRef = userRef.collection("questInstances").document(questId);
        
        // Get the quest document and last actions map
        userRef.get().addOnSuccessListener(userDoc -> {
            Map<String, Object> lastQuestActionsFromDb = new HashMap<>();
            if (userDoc.contains("lastQuestActions")) {
                Object lastActionsObj = userDoc.get("lastQuestActions");
                if (lastActionsObj instanceof Map) {
                    lastQuestActionsFromDb = (Map<String, Object>) lastActionsObj;
                }
            }
            
            // Create a final copy for use in lambda expressions
            final Map<String, Object> lastQuestActions = new HashMap<>(lastQuestActionsFromDb);
            
            // Now get the quest document
            questRef.get().addOnSuccessListener(questDoc -> {
                if (!questDoc.exists()) {
                    if (listener != null) {
                        listener.onRewardClaimed(false, "Quest not found");
                    }
                    return;
                }
                
                // Check if quest is completed
                Long currNode = questDoc.getLong("currentNode");
                Long totalNodes = questDoc.getLong("totalNodes");
                int curr = currNode != null ? currNode.intValue() : 0;
                int total = totalNodes != null ? totalNodes.intValue() : 0;
                
                if (curr < total) {
                    if (listener != null) {
                        listener.onRewardClaimed(false, "Quest not completed yet");
                    }
                    return;
                }
                
                // Check if reward already claimed
                boolean claimed = questDoc.contains("rewardClaimed") && Boolean.TRUE.equals(questDoc.getBoolean("rewardClaimed"));
                if (claimed) {
                    if (listener != null) {
                        listener.onRewardClaimed(false, "Reward already claimed");
                    }
                    return;
                }
                
                // Check for reward claim cooldown
                long now = System.currentTimeMillis();
                String rewardClaimKey = "reward_claim_" + questId;
                Object lastRewardClaimObj = lastQuestActions.get(rewardClaimKey);
                long lastRewardClaimTime = 0;
                if (lastRewardClaimObj instanceof Number) {
                    lastRewardClaimTime = ((Number) lastRewardClaimObj).longValue();
                }
                
                boolean inRewardClaimCooldown = (now - lastRewardClaimTime < REWARD_CLAIM_COOLDOWN_MS);
                if (inRewardClaimCooldown) {
                    Log.d(TAG, "Reward claim for " + questDoc.getString("title") + " is in cooldown period.");
                    if (listener != null) {
                        listener.onRewardClaimed(false, "Please wait a moment before claiming another reward");
                    }
                    return;
                }
                
                // Get the reward amount
                Long rewardPoints = questDoc.getLong("rewardPoints");
                long rewardAmount = rewardPoints != null ? rewardPoints : 0L;
                
                // Prepare updates
                Map<String, Object> updates = new HashMap<>();
                updates.put("status", "COMPLETED");
                updates.put("rewardClaimed", true);
                
                // Record this reward claim time
                Map<String, Object> questActionsToUpdate = new HashMap<>(lastQuestActions);
                questActionsToUpdate.put(rewardClaimKey, now);
                
                // Update quest, award points in a transaction
                db.runTransaction(transaction -> {
                    // Update quest status and rewardClaimed flag
                    transaction.update(questRef, updates);
                    
                    // Award points and gold
                    if (rewardAmount > 0) {
                        transaction.update(userRef, "points", FieldValue.increment(rewardAmount));
                        transaction.update(userRef, "goldCoins", FieldValue.increment(rewardAmount));
                    }
                    
                    // Update lastQuestActions
                    transaction.update(userRef, "lastQuestActions", questActionsToUpdate);
                    
                    return null; // Success
                }).addOnSuccessListener(v -> {
                    Log.d(TAG, "Quest reward claimed successfully for " + questDoc.getString("title"));
                    
                    // Award XP with level multiplier
                    if (rewardAmount > 0) {
                        getUserLevel(level -> {
                            // Calculate XP with level multiplier
                            double levelMultiplier = 1.0 + ((level - 1) * 0.1);
                            String freq = questDoc.getString("frequency");
                            double freqMultiplier = "WEEKLY".equalsIgnoreCase(freq) ? 2.0 : 1.0;
                            double baseMultiplier = 1.5 * levelMultiplier * freqMultiplier;
                            final long baseXpAmount = Math.round(rewardAmount * baseMultiplier);
                            
                            // Fetch user's active effects to check for Mindmeld Elixir
                            userRef.get().addOnSuccessListener(userEffectsDoc -> {
                                Map<String, Object> activeEffects = new HashMap<>();
                                if (userEffectsDoc.contains("activeEffects") && userEffectsDoc.get("activeEffects") instanceof Map) {
                                    activeEffects = (Map<String, Object>) userEffectsDoc.get("activeEffects");
                                }
                                
                                // Apply Mindmeld Elixir if active
                                long finalXpAmount = baseXpAmount;
                                if (EffectManager.isMindmeldXpBoostActive(activeEffects)) {
                                    finalXpAmount = Math.round(baseXpAmount * EffectManager.mindmeldXpBoostMultiplier);
                                    Log.d(TAG, "Applied Mindmeld Elixir boost to XP: " + finalXpAmount);
                                }
                                
                                RewardManager rewardManager = new RewardManager(userId);
                                rewardManager.awardXpForQuest(questDoc.getString("title"), finalXpAmount);
                                Log.d(TAG, "Awarded " + finalXpAmount + " XP (level " + level + " multiplier: " + levelMultiplier + 
                                      "x) for completing " + questDoc.getString("title"));
                            }).addOnFailureListener(e -> {
                                // Fallback if we can't get active effects
                                RewardManager rewardManager = new RewardManager(userId);
                                rewardManager.awardXpForQuest(questDoc.getString("title"), baseXpAmount);
                                Log.e(TAG, "Failed to check active effects for Mindmeld boost", e);
                            });
                        });
                    }
                    
                    if (listener != null) {
                        listener.onRewardClaimed(true, "Reward claimed successfully");
                    }
                }).addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to claim quest reward", e);
                    if (listener != null) {
                        listener.onRewardClaimed(false, "Failed to claim reward: " + e.getMessage());
                    }
                });
            }).addOnFailureListener(e -> {
                Log.e(TAG, "Failed to get quest document", e);
                if (listener != null) {
                    listener.onRewardClaimed(false, "Failed to retrieve quest data");
                }
            });
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to get user document", e);
            if (listener != null) {
                listener.onRewardClaimed(false, "Failed to retrieve user data");
            }
        });
    }

    // Merged handleFirestoreError from QuestManagerHelper to eliminate external helper file
    public static boolean handleFirestoreError(Exception e, Context context, boolean shouldShowToast) {
        Log.e(TAG, "Firestore error", e);
        if (e instanceof FirebaseFirestoreException) {
            FirebaseFirestoreException firestoreException = (FirebaseFirestoreException) e;
            if (firestoreException.getMessage() != null && 
                firestoreException.getMessage().contains("The query requires an index")) {
                String message = firestoreException.getMessage();
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
        }
        return false;
    }

    /**
     * Represents a quest template with static metadata.
     */
    public static class QuestTemplate {
        private String id;
        private String title;
        private String description;
        private String frequency; // "DAILY" or "WEEKLY"
        private int totalNodes;
        private int rewardPoints;
        private String badgeId;
        private long durationMillis;

        /** New ctor for title, description, nodes, reward, and duration */
        public QuestTemplate(String title, String description, int totalNodes, int rewardPoints, long durationMillis) {
            this.title = title;
            this.description = description;
            this.totalNodes = totalNodes;
            this.rewardPoints = rewardPoints;
            this.durationMillis = durationMillis;
        }

        public QuestTemplate(String id, String title, String description, String frequency,
                             int totalNodes, int rewardPoints, String badgeId) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.frequency = frequency;
            this.totalNodes = totalNodes;
            this.rewardPoints = rewardPoints;
            this.badgeId = badgeId;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public int getTotalNodes() { return totalNodes; }

        public int getRewardPoints() { return rewardPoints; }

        public long getDurationMillis() { return durationMillis; }
    }

    // Helper method to get cooldown minutes for a specific quest
    private long getQuestCooldownMinutes(String questTitle) {
        // Define quest-specific cooldowns
        switch (questTitle) {
            case "Speedy Task Quest":
                return 0; // No cooldown for this quest
            case "High-Priority Sprint":
                return 0; // No cooldown for this quest 
            case "Morning Momentum Quest":
                return 0; // No cooldown for this quest, it's time-based
            case "Habit Streak Quest":
                return 60; // 1 hour cooldown
            case "Priority Cleanup Quest":
                return 0; // No cooldown, we handle this differently
            case "Subtask Surge Quest":
                return 0; // No cooldown, we handle this differently
            default:
                return 60; // Default 1 hour cooldown
        }
    }

    /**
     * Cleanup all expired quests for the current user.
     * This is meant to be called during app startup to ensure the quest system remains clean.
     */
    public void cleanupAllExpiredQuests() {
        Log.d(TAG, "[DEBUG] Performing global expired quest cleanup");
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.d(TAG, "[DEBUG] cleanupAllExpiredQuests: No user logged in");
            return;
        }
        final long now = System.currentTimeMillis();
        final long startOfDay = getStartOfDayMillis();
        final long startOfWeek = getStartOfWeekMillis();
        
        // Query ALL quest instances
        db.collection("users").document(userId)
          .collection("questInstances")
          .get()
          .addOnSuccessListener(querySnapshot -> {
              // Only delete quests that expired before the current cycle (day/week)
              // This preserves completed quests until the cycle changes
              List<DocumentSnapshot> expiredQuests = new ArrayList<>();
              for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                  String freq = doc.getString("frequency");
                  Long startedAt = doc.getLong("startedAt");
                  Long expiresAt = doc.getLong("expiresAt");
                  
                  // For daily quests, only delete if from previous days and expired
                  if ("DAILY".equals(freq) && startedAt != null && startedAt < startOfDay && expiresAt != null && expiresAt < now) {
                      expiredQuests.add(doc);
                  }
                  // For weekly quests, only delete if from previous weeks and expired
                  else if ("WEEKLY".equals(freq) && startedAt != null && startedAt < startOfWeek && expiresAt != null && expiresAt < now) {
                      expiredQuests.add(doc);
                  }
              }
              
              // Delete expired quests in batch
              if (!expiredQuests.isEmpty()) {
                  WriteBatch batch = db.batch();
                  for (DocumentSnapshot doc : expiredQuests) {
                      batch.delete(doc.getReference());
                      Log.d(TAG, "[DEBUG] Deleting expired quest in cleanup: " + doc.getId());
                  }
                  
                  final int expiredCount = expiredQuests.size();
                  
                  batch.commit().addOnSuccessListener(unused -> 
                      Log.d(TAG, "[DEBUG] Deleted " + expiredCount + " expired quests in cleanup")
                  );
              }
              
              // Enforce the daily and weekly quest limits
              List<DocumentSnapshot> dailyQuests = new ArrayList<>();
              List<DocumentSnapshot> weeklyQuests = new ArrayList<>();
              
              for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                  String freq = doc.getString("frequency");
                  Long startedAt = doc.getLong("startedAt");
                  
                  if ("DAILY".equals(freq) && startedAt != null && startedAt >= startOfDay) {
                      dailyQuests.add(doc);
                  } else if ("WEEKLY".equals(freq) && startedAt != null && startedAt >= startOfWeek) {
                      weeklyQuests.add(doc);
                  }
              }
              
              // Sort quests by start time (newest first) to keep the most recent ones
              dailyQuests.sort((a, b) -> {
                  Long aTime = a.getLong("startedAt");
                  Long bTime = b.getLong("startedAt");
                  if (aTime == null) aTime = 0L;
                  if (bTime == null) bTime = 0L;
                  return Long.compare(bTime, aTime); // Descending order
              });
              
              weeklyQuests.sort((a, b) -> {
                  Long aTime = a.getLong("startedAt");
                  Long bTime = b.getLong("startedAt");
                  if (aTime == null) aTime = 0L;
                  if (bTime == null) bTime = 0L;
                  return Long.compare(bTime, aTime); // Descending order
              });
              
              // Delete excess quests to maintain limits
              WriteBatch batch = db.batch();
              boolean needsCommit = false;
              
              final int dailyQuestsSize = dailyQuests.size();
              final int weeklyQuestsSize = weeklyQuests.size();
              
              if (dailyQuestsSize > MAX_DAILY_QUESTS) {
                  for (int i = MAX_DAILY_QUESTS; i < dailyQuestsSize; i++) {
                      batch.delete(dailyQuests.get(i).getReference());
                      Log.d(TAG, "[DEBUG] Deleting excess daily quest: " + dailyQuests.get(i).getId());
                      needsCommit = true;
                  }
              }
              
              if (weeklyQuestsSize > MAX_WEEKLY_QUESTS) {
                  for (int i = MAX_WEEKLY_QUESTS; i < weeklyQuestsSize; i++) {
                      batch.delete(weeklyQuests.get(i).getReference());
                      Log.d(TAG, "[DEBUG] Deleting excess weekly quest: " + weeklyQuests.get(i).getId());
                      needsCommit = true;
                  }
              }
              
              if (needsCommit) {
                  batch.commit().addOnSuccessListener(unused -> {
                      Log.d(TAG, "[DEBUG] After cleanup and enforcement: " + 
                          Math.min(dailyQuestsSize, MAX_DAILY_QUESTS) + " active daily quests, " + 
                          Math.min(weeklyQuestsSize, MAX_WEEKLY_QUESTS) + " active weekly quests");
                  });
              } else {
                  Log.d(TAG, "[DEBUG] After cleanup: " + dailyQuestsSize + " active daily quests, " + 
                       weeklyQuestsSize + " active weekly quests");
              }
          })
          .addOnFailureListener(e -> {
              Log.e(TAG, "Failed to cleanup expired quests", e);
          });
    }

    // Helper: create a quest instance with frequency
    public void issueQuestInstance(QuestTemplate template, String freq, Consumer<DocumentReference> onSuccessCallback) {
        long now = System.currentTimeMillis();
        Map<String, Object> data = new HashMap<>();
        data.put("title", template.getTitle());
        data.put("description", template.getDescription());
        data.put("totalNodes", template.getTotalNodes());
        data.put("currentNode", 0);
        data.put("rewardPoints", template.getRewardPoints());
        // Determine quest start and expiration times
        long durationMillis = template.getDurationMillis();
        long startAt;
        if ("Morning Momentum Quest".equals(template.getTitle())) {
            // Start at beginning of day to enforce 9 AM deadline
            startAt = getStartOfDayMillis();
        } else {
            startAt = now;
        }
        
        // Get user's active effects to check for Efficiency Serum
        if (("High-Priority Sprint".equals(template.getTitle()) || "Speedy Task Quest".equals(template.getTitle()))) {
            db.collection("users").document(userId).get().addOnSuccessListener(userDoc -> {
                // Create a local copy of duration that we can safely use in the async callback
                final long initialDuration = durationMillis;
                
                // Check for active Efficiency Serum
                Map<String, Object> activeEffects = new HashMap<>();
                if (userDoc.contains("activeEffects") && userDoc.get("activeEffects") instanceof Map) {
                    activeEffects = (Map<String, Object>) userDoc.get("activeEffects");
                }
                
                // Apply Efficiency Serum effect if active
                double reductionPercent = EffectManager.getQuestTimeReductionPercent(activeEffects);
                long modifiedDuration = initialDuration;
                if (reductionPercent > 0) {
                    modifiedDuration = (long) (initialDuration * (1.0 - reductionPercent));
                    Log.d(TAG, "Efficiency Serum active! Reducing initial duration for " + template.getTitle() + 
                          " from " + initialDuration + "ms to " + modifiedDuration + "ms");
                }
                
                // Calculate expiry time based on updated duration
                long expiresAt = startAt + modifiedDuration;
                data.put("startedAt", startAt);
                data.put("expiresAt", expiresAt);
                data.put("frequency", freq);
                data.put("rewardClaimed", false);
                data.put("completed", false);
                
                completeQuestCreation(template, data, onSuccessCallback);
            }).addOnFailureListener(e -> {
                // Fallback if we can't get active effects
                long expiresAt = startAt + durationMillis;
                data.put("startedAt", startAt);
                data.put("expiresAt", expiresAt);
                data.put("frequency", freq);
                data.put("rewardClaimed", false);
                data.put("completed", false);
                
                Log.e(TAG, "Failed to fetch active effects for Efficiency Serum check", e);
                completeQuestCreation(template, data, onSuccessCallback);
            });
        } else {
            // For quests that don't need Efficiency Serum check, proceed directly
            long expiresAt = startAt + durationMillis;
            data.put("startedAt", startAt);
            data.put("expiresAt", expiresAt);
            data.put("frequency", freq);
            data.put("rewardClaimed", false);
            data.put("completed", false);
            
            completeQuestCreation(template, data, onSuccessCallback);
        }
    }
    
    private void completeQuestCreation(QuestTemplate template, Map<String, Object> data, Consumer<DocumentReference> onSuccessCallback) {
        // Initialize storage for Speedy Task Quest
        if ("Speedy Task Quest".equals(template.getTitle())) {
            data.put("completionTimestamps", new ArrayList<Long>());
            Log.d(TAG, "[DEBUG] Creating Speedy Task Quest with empty completionTimestamps array");
        }
        
        // If this is the Habit Streak Quest, store the chosen habit name (default to empty, to be set by UI or first completion)
        if ("Habit Streak Quest".equals(template.getTitle())) {
            data.put("habitName", ""); // To be set by UI or on first completion
        }
        
        db.collection("users").document(userId)
          .collection("questInstances")
          .add(data)
          .addOnSuccessListener(docRef -> {
              Log.d(TAG, data.get("frequency") + " quest issued: " + template.getTitle() + " with ID: " + docRef.getId());
              
              // For debug purposes, check the quest was created properly
              docRef.get().addOnSuccessListener(snapshot -> {
                  Log.d(TAG, "[DEBUG] Created quest details: title=" + snapshot.getString("title") + 
                      ", expiresAt=" + snapshot.getLong("expiresAt") + 
                      ", now=" + System.currentTimeMillis() + 
                      ", expireIn=" + (snapshot.getLong("expiresAt") - System.currentTimeMillis()) + "ms");
                  if (onSuccessCallback != null) {
                      onSuccessCallback.accept(docRef);
                  }
              });
          })
          .addOnFailureListener(e -> {
              Log.e(TAG, "Failed to issue " + data.get("frequency") + " quest: " + e.getMessage(), e);
          });
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
        // Set to first day of week (usually Sunday or Monday depending on locale)
        c.set(Calendar.DAY_OF_WEEK, c.getFirstDayOfWeek());
        // Reset time to beginning of day
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    // Modified to prevent auto-resetting quests after completion
    private void updateMatchingQuestProgress(DocumentReference userRef, String questTitle, long timestamp, Map<String, Object> lastQuestActions) {
        Log.d(TAG, "[DEBUG] updateMatchingQuestProgress called for questTitle=" + questTitle + ", timestamp=" + timestamp);
        
        // Find active quests matching the title - improve the query and add more logging
        final long currentTime = System.currentTimeMillis();
        Log.d(TAG, "[DEBUG] Current system time: " + currentTime);
        
        // Determine quest frequency and valid time window based on template duration
        QuestTemplate qt = null;
        for (QuestTemplate temp : templates) {
            if (temp.getTitle().equals(questTitle)) {
                qt = temp;
                break;
            }
        }
        String freq = (qt != null && qt.getDurationMillis() > TimeUnit.DAYS.toMillis(1)) ? "WEEKLY" : "DAILY";
        long startThreshold = freq.equals("WEEKLY") ? getStartOfWeekMillis() : getStartOfDayMillis();
        
        // Simplified query - just query by title and filter client-side
        db.collection("users").document(userId)
          .collection("questInstances")
          .whereEqualTo("title", questTitle)
          .get()
          .addOnSuccessListener(snap -> {
              if (snap.isEmpty()) {
                  Log.d(TAG, "[DEBUG] No quests found with title: " + questTitle);
                  return;
              }
              
              Log.d(TAG, "[DEBUG] Found " + snap.size() + " quests with title: " + questTitle);
              
              boolean foundActiveQuest = false;
              Map<String, Object> questActionsToUpdate = new HashMap<>(lastQuestActions);
              boolean questActionsModified = false;
              
              for (DocumentSnapshot doc : snap.getDocuments()) {
                  Long startedAt = doc.getLong("startedAt");
                  Long expiresAt = doc.getLong("expiresAt");
                  String docId = doc.getId();
                  
                  if (startedAt == null || startedAt < startThreshold) {
                      Log.d(TAG, "[DEBUG] Skipping outdated quest: " + docId + " (started at: " + startedAt + ")");
                      continue;
                  }
                  
                  if (expiresAt != null && expiresAt < currentTime) {
                      Log.d(TAG, "[DEBUG] Skipping expired quest: " + docId + " (expired at: " + expiresAt + ")");
                      continue;
                  }
                  
                  // Check if quest is already completed
                  Boolean questCompleted = doc.getBoolean("completed");
                  if (Boolean.TRUE.equals(questCompleted)) {
                      Log.d(TAG, "[DEBUG] Quest " + docId + " already completed, skipping");
                      continue;
                  }
                  
                  foundActiveQuest = true;
                  Log.d(TAG, "[DEBUG] Processing active quest: " + questTitle + ", docId=" + docId + ", expires at " + expiresAt);
                  
                  // Get quest progress data
                  long currentProgress = doc.getLong("currentNode") != null ? doc.getLong("currentNode") : 0;
                  long totalNodes = doc.getLong("totalNodes") != null ? doc.getLong("totalNodes") : 3;
                  
                  Log.d(TAG, "[DEBUG] Quest " + questTitle + " current progress: " + currentProgress + "/" + totalNodes);
                  
                  // Skip if the quest already has maximum progress
                  if (currentProgress >= totalNodes) {
                      Log.d(TAG, "[DEBUG] Quest " + questTitle + " already has max progress (" + currentProgress + "/" + totalNodes + "), marking as completed");
                      // Ensure it's properly marked as completed
                      Map<String, Object> completionUpdates = new HashMap<>();
                      completionUpdates.put("completed", true);
                      completionUpdates.put("status", "COMPLETED");
                      completionUpdates.put("completedAt", timestamp);
                      // Don't set rewardClaimed to true automatically
                      doc.getReference().update(completionUpdates);
                      continue;
                  }
                  
                  // Handle cooldown for quests that need it
                  String cooldownKey = "last_" + questTitle.replace(" ", "_").toLowerCase();
                  Object lastActionObj = lastQuestActions.get(cooldownKey);
                  long lastActionTime = 0;
                  if (lastActionObj instanceof Number) {
                      lastActionTime = ((Number) lastActionObj).longValue();
                  }
                  
                  // Prepare a map for updates
                  Map<String, Object> updates = new HashMap<>();
                  long progress = currentProgress;
                  
                  // --- Special logic for Speedy Task Quest ---
                  if ("Speedy Task Quest".equals(questTitle)) {
                      DocumentReference questRef = doc.getReference();
                      // Atomically increment progress
                      Map<String, Object> speedyUpdates = new HashMap<>();
                      speedyUpdates.put("currentNode", FieldValue.increment(1));
                      speedyUpdates.put("lastProgressAt", currentTime);
                      long newProgress = currentProgress + 1;
                      
                      // Update quest instance (do not mark as completed)
                      questRef.update(speedyUpdates)
                          .addOnSuccessListener(v -> Log.d(TAG, "[DEBUG] Speedy Task Quest progress incremented to: " + newProgress))
                          .addOnFailureListener(e -> Log.e(TAG, "[DEBUG] Speedy Task Quest update failed: " + e.getMessage(), e));
                      // Update lastQuestActions
                      questActionsToUpdate.put(cooldownKey, timestamp);
                      userRef.update("lastQuestActions", questActionsToUpdate);
                      // Skip the default update flow for Speedy Task Quest
                      continue;
                  }
                  
                  // Special logic for Subtask Surge Quest - uses subtaskCount passed through recordSubtaskCompletion
                  if ("Subtask Surge Quest".equals(questTitle)) {
                      // Check if we are being called from recordSubtaskCompletion
                      // In that case, the progress was already updated with correct subtask count
                      // and we should skip the default increment of +1
                      continue;
                  }
                  
                  // Default quest update logic for other quests
                  long cooldownMinutes = getQuestCooldownMinutes(questTitle);
                  
                  // Priority Cleanup Quest - ensure cooldown doesn't prevent completion 
                  if ("Priority Cleanup Quest".equals(questTitle)) {
                      cooldownMinutes = 0; // Disable cooldown for Priority Cleanup Quest
                      Log.d(TAG, "[DEBUG] Priority Cleanup Quest - disabling cooldown check to ensure progress");
                  }
                  
                  // Check cooldown (for quests other than the special ones handled above)
                  if (cooldownMinutes > 0 && lastActionTime > 0) {
                      long cooldownMillis = cooldownMinutes * 60 * 1000;
                      if (timestamp - lastActionTime < cooldownMillis) {
                          Log.d(TAG, "[DEBUG] Quest " + questTitle + " on cooldown. Last action: " + lastActionTime + ", current: " + timestamp);
                          continue;
                      }
                  }
                  
                  // If we passed cooldown check, increment progress
                  progress = currentProgress + 1;
                  if (progress > totalNodes) {
                      progress = totalNodes; // Cap at max progress
                  }
                  
                  updates.put("currentNode", progress);
                  updates.put("lastProgressAt", timestamp);
                  
                  // Check if quest should be marked as completed based on progress
                  if (progress >= totalNodes) {
                      updates.put("completed", true);
                      updates.put("completedAt", timestamp);
                      updates.put("status", "COMPLETED");
                      // Do not automatically set rewardClaimed to true
                      Log.d(TAG, "[DEBUG] Quest " + questTitle + " is now completed with progress " + progress + "/" + totalNodes);
                  }
                  
                  // Update questActions to enforce cooldown
                  if (cooldownMinutes > 0) {
                      questActionsToUpdate.put(cooldownKey, timestamp);
                      questActionsModified = true;
                  }
                  
                  // Use final variables for the lambda
                  final long finalProgress = progress;
                  final Map<String, Object> finalQuestActionsToUpdate = new HashMap<>(questActionsToUpdate);
                  final boolean finalQuestActionsModified = questActionsModified;
                  
                  // Update Firestore
                  doc.getReference().update(updates)
                      .addOnSuccessListener(v -> {
                          Log.d(TAG, "[DEBUG] Successfully updated quest progress for " + questTitle + ": " + finalProgress + "/" + totalNodes);
                          
                          // If lastQuestActions was modified, update user document atomically
                          if (finalQuestActionsModified) {
                              userRef.update("lastQuestActions", finalQuestActionsToUpdate)
                                    .addOnFailureListener(e -> {
                                        Log.e(TAG, "[DEBUG] Failed to update lastQuestActions: " + e.getMessage(), e);
                                    });
                          }
                      })
                      .addOnFailureListener(e -> {
                          Log.e(TAG, "[DEBUG] Failed to update quest progress for " + questTitle + ": " + e.getMessage(), e);
                      });
                  
                  // Only update the first active quest we find for this title
                  break;
              }
              
              if (!foundActiveQuest) {
                  Log.d(TAG, "[DEBUG] No active quest found for title: " + questTitle + ". Not issuing new instance here.");
              }
          })
          .addOnFailureListener(e -> {
              Log.e(TAG, "[DEBUG] Error finding quests with title " + questTitle + ": " + e.getMessage(), e);
          });
    }

    /**
     * Backward compatibility method - delegates to issueWeeklyQuests
     */
    public void issueWeeklyQuest() {
        issueWeeklyQuests();
    }

    /**
     * Backward compatibility method for recordTaskCompletion
     */
    public void recordTaskCompletion(Task task, DocumentReference userRef) {
        if (task == null) {
            Log.w(TAG, "[DEBUG] Task is null in recordTaskCompletion");
            return;
        }
        
        // Check if this is a recurring task and handle it
        if (task.getRecurrenceGroupId() != null && !task.getRecurrenceGroupId().isEmpty()) {
            checkRecurringTaskCompletion(task, userRef);
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
     * Check for recurring task completion patterns for quests
     * @param task The task that was just completed
     * @param userRef Reference to the user document
     */
    public void checkRecurringTaskCompletion(Task task, DocumentReference userRef) {
        if (task == null || task.getRecurrenceGroupId() == null || task.getRecurrenceGroupId().isEmpty()) {
            return; // Not a recurring task
        }

        Log.d(TAG, "Checking recurring task completion for task " + task.getId() + " in group " + task.getRecurrenceGroupId());
        
        // Get all tasks in this recurrence group
        db.collection("users").document(userId)
          .collection("tasks")
          .whereEqualTo("recurrenceGroupId", task.getRecurrenceGroupId())
          .get()
          .addOnSuccessListener(querySnapshot -> {
              if (querySnapshot.isEmpty()) {
                  Log.d(TAG, "No tasks found in recurrence group: " + task.getRecurrenceGroupId());
                  return;
              }
              
              List<Task> tasksInSeries = new ArrayList<>();
              for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                  Task seriesTask = doc.toObject(Task.class);
                  if (seriesTask != null) {
                      seriesTask.setId(doc.getId());
                      tasksInSeries.add(seriesTask);
                  }
              }
              
              // Check for consecutive days completion
              checkConsecutiveDaysCompletion(tasksInSeries, userRef);
          })
          .addOnFailureListener(e -> {
              Log.e(TAG, "Error checking recurring task completion: " + e.getMessage());
          });
    }
    
    /**
     * Check if tasks in a recurring series have been completed for consecutive days
     * @param tasksInSeries List of tasks in the recurring series
     * @param userRef Reference to the user document
     */
    private void checkConsecutiveDaysCompletion(List<Task> tasksInSeries, DocumentReference userRef) {
        // First, group tasks by name to ensure we're only counting tasks with the exact same name
        Map<String, List<Task>> tasksByName = new HashMap<>();
        
        for (Task task : tasksInSeries) {
            String taskName = task.getName();
            if (taskName != null) {
                if (!tasksByName.containsKey(taskName)) {
                    tasksByName.put(taskName, new ArrayList<>());
                }
                tasksByName.get(taskName).add(task);
            }
        }
        
        // Track max consecutive completions for tasks with same name
        int maxConsecutiveOverall = 0;
        String taskNameWithMaxConsecutive = "";
        
        // Process each group of same-named tasks
        for (Map.Entry<String, List<Task>> entry : tasksByName.entrySet()) {
            String taskName = entry.getKey();
            List<Task> sameNameTasks = entry.getValue();
            
            // Skip if there are fewer than 3 tasks (can't reach minimum threshold)
            if (sameNameTasks.size() < 3) {
                continue;
            }
            
            // Sort tasks by date/timestamp
            Collections.sort(sameNameTasks, (t1, t2) -> {
                long timestamp1 = t1.getDeadlineTimestamp();
                long timestamp2 = t2.getDeadlineTimestamp();
                return Long.compare(timestamp1, timestamp2);
            });
            
            // Count consecutive completed tasks
            int maxConsecutive = 0;
            int currentConsecutive = 0;
            
            for (Task currentTask : sameNameTasks) {
                if (currentTask.isCompleted()) {
                    currentConsecutive++;
                    maxConsecutive = Math.max(maxConsecutive, currentConsecutive);
                } else {
                    // Reset consecutive count on incomplete task
                    currentConsecutive = 0;
                }
            }
            
            Log.d(TAG, "Recurring tasks with name '" + taskName + "' have " + maxConsecutive + " consecutive completions");
            
            // Track the highest consecutive count across all task names
            if (maxConsecutive > maxConsecutiveOverall) {
                maxConsecutiveOverall = maxConsecutive;
                taskNameWithMaxConsecutive = taskName;
            }
        }
        
        // Create final copies for lambda access
        final int finalMaxConsecutiveOverall = maxConsecutiveOverall;
        final String finalTaskNameWithMaxConsecutive = taskNameWithMaxConsecutive;
        final boolean hasRecurringTasks = !tasksByName.isEmpty();
        
        // Get the user document to check for active quests
        userRef.get().addOnSuccessListener(userSnapshot -> {
            if (!userSnapshot.exists()) {
                Log.e(TAG, "User document not found for recurring task quest recording.");
                return;
            }
            
            // Get the user's quest action timestamps
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
            
            // Update Consistency Champion Quest if we found 3+ consecutive completions
            if (finalMaxConsecutiveOverall >= 3) {
                Log.d(TAG, "Found " + finalMaxConsecutiveOverall + " consecutive completions for task named '" + 
                      finalTaskNameWithMaxConsecutive + "'");
                // Update the Consistency Champion Quest progress
                updateMatchingQuestProgress(userRef, "Consistency Champion Quest", System.currentTimeMillis(), lastQuestActions);
            } else {
                Log.d(TAG, "No recurring tasks found with at least 3 consecutive completions of the same name");
            }
            
            // Check for Habit Streak Quest (7 days in a row threshold)
            if (finalMaxConsecutiveOverall >= 7) {
                Log.d(TAG, "Found " + finalMaxConsecutiveOverall + " consecutive completions for Habit Streak Quest: '" + 
                      finalTaskNameWithMaxConsecutive + "'");
                
                // Find active Habit Streak Quest and update with the habit name
                db.collection("users").document(userId)
                  .collection("questInstances")
                  .whereEqualTo("title", "Habit Streak Quest")
                  .get()
                  .addOnSuccessListener(questSnap -> {
                      for (DocumentSnapshot questDoc : questSnap.getDocuments()) {
                          // Skip completed quests
                          Boolean completed = questDoc.getBoolean("completed");
                          if (Boolean.TRUE.equals(completed)) {
                              continue;
                          }
                          
                          // Skip expired quests
                          Long expiresAt = questDoc.getLong("expiresAt");
                          long now = System.currentTimeMillis();
                          if (expiresAt == null || expiresAt < now) {
                              continue;
                          }
                          
                          // Found an active Habit Streak Quest
                          Map<String, Object> updates = new HashMap<>();
                          
                          // Set the habit name if not already set
                          String habitName = questDoc.getString("habitName");
                          if (habitName == null || habitName.isEmpty()) {
                              updates.put("habitName", finalTaskNameWithMaxConsecutive);
                              updates.put("info", "Recurring task: " + finalTaskNameWithMaxConsecutive);
                              Log.d(TAG, "Setting habit name for Habit Streak Quest: " + finalTaskNameWithMaxConsecutive);
                          }
                          
                          // Update progress to reflect consecutive completions
                          updates.put("currentNode", Math.min(finalMaxConsecutiveOverall, 7)); // Cap at 7 for this quest
                          
                          // Mark as completed if we've hit the threshold
                          if (finalMaxConsecutiveOverall >= 7) {
                              updates.put("completed", true);
                              updates.put("completedAt", now);
                              updates.put("status", "COMPLETED");
                              Log.d(TAG, "Marking Habit Streak Quest as completed!");
                          }
                          
                          // Update the quest
                          questDoc.getReference().update(updates)
                              .addOnSuccessListener(v -> Log.d(TAG, "Updated Habit Streak Quest successfully"))
                              .addOnFailureListener(e -> Log.e(TAG, "Failed to update Habit Streak Quest", e));
                          
                          // Only update one active quest
                          break;
                      }
                  });
            }
            
            // Check for Healthy Habits Quest progress
            // This quest is about completing different micro-habits each day,
            // so we just update it whenever we detect recurring task activity
            if (hasRecurringTasks) {
                updateMatchingQuestProgress(userRef, "Healthy Habits Quest", System.currentTimeMillis(), lastQuestActions);
            }
        });
    }
} 