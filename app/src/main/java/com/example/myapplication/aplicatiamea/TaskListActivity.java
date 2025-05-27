package com.example.myapplication.aplicatiamea;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.airbnb.lottie.LottieAnimationView;
import com.example.myapplication.aplicatiamea.repository.QuestManager;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.WriteBatch;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import android.content.Intent;

/**
 * Main task list/calendar screen - shows tasks for each day and streak info.
 */
public class TaskListActivity extends AppCompatActivity {
    private final ArrayList<Task> tasks = new ArrayList<>();
    private TaskListAdapter adapter;
    private RecyclerView rvTasks;
    private Calendar currentDate;
    private TextView dateHeader;
    private TextView pointsCounter;
    private ListenerRegistration taskListener;
    private LottieAnimationView flameAnimation;
    private TextView streakCounter;
    private StreakHelper streakHelper;
    // Hardcoded for now - should pull from user settings eventually
    private final TimeZone userTimeZone = TimeZone.getTimeZone("Europe/Bucharest");
    private LottieAnimationView confettiView;
    private SharedPreferences prefs;
    private FirebaseFirestore db;
    private String actualTodayDateString;

    // Anti-spam protection to prevent task toggling too fast
    private final ConcurrentHashMap<String, Boolean> tasksInProgress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> subtasksInProgress = new ConcurrentHashMap<>();
    private static final long ANTI_SPAM_COOLDOWN = 2000; // 2 sec cooldown

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task_list);
        db = FirebaseFirestore.getInstance();

        // Make sure user has daily and weekly quests
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null) {
            QuestManager qm = new QuestManager(currentUser.getUid(), this);
            qm.issueDailyQuests();
            qm.issueWeeklyQuest();
        }

        // Figure out what today actually is (for UI highlighting)
        Calendar realToday = Calendar.getInstance(userTimeZone);
        realToday.set(Calendar.HOUR_OF_DAY, 0);
        realToday.set(Calendar.MINUTE, 0);
        realToday.set(Calendar.SECOND, 0);
        realToday.set(Calendar.MILLISECOND, 0);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sdf.setTimeZone(userTimeZone);
        actualTodayDateString = sdf.format(realToday.getTime());
        Log.d("TaskListActivity", "Today is: " + actualTodayDateString);
        
        // Set up all the stuff
        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        currentDate = Calendar.getInstance(userTimeZone);
        currentDate.set(Calendar.HOUR_OF_DAY, 0);
        currentDate.set(Calendar.MINUTE, 0);
        currentDate.set(Calendar.SECOND, 0);
        currentDate.set(Calendar.MILLISECOND, 0);
        
        initializeViews();
        setupFirebase();
        setupStreak();
        setupDateNavigation();
        loadInitialStreak();
        
        // Update streak including yesterday's tasks in case they 
        // completed stuff after midnight
        recalcStreakOnStartup();
        
        // Set up the task list
        rvTasks = findViewById(R.id.taskList);
        rvTasks.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TaskListAdapter(tasks, this, db, userTimeZone, actualTodayDateString, 
                tasksInProgress, subtasksInProgress, ANTI_SPAM_COOLDOWN, streakHelper);
        rvTasks.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d("TaskListActivity", "onResume: Refreshing activity state");
        
        // Refresh everything when user comes back to this screen
        refreshTasks();
        loadPoints();
        updateStreakDisplay();
        
        // Fix for quest data not being updated correctly
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null) {
            String uid = currentUser.getUid();
            QuestManager questManager = new QuestManager(uid, this);
            DocumentReference userRef = db.collection("users").document(uid);
            
            // Force check any quests that might have been completed
            questManager.forceQuestRefresh(userRef);
            
            // Also check if any time-bound quests have expired
            questManager.cleanupExpiredTimeBoundQuests();
        }
    }

    private void initializeViews() {
        flameAnimation = findViewById(R.id.streakIcon);
        streakCounter = findViewById(R.id.streakCounter);
        dateHeader = findViewById(R.id.dateHeader);
        pointsCounter = findViewById(R.id.pointsValue);
        
        // Confetti animation for level ups - hidden initially
        confettiView = findViewById(R.id.confettiView);
        
        // Add null checks before using the animation views
        if (confettiView != null) {
            confettiView.setVisibility(View.GONE);
        }
        
        if (flameAnimation != null) {
            flameAnimation.setVisibility(View.GONE);
        }
    }

    private void setupFirebase() {
        streakHelper = new StreakHelper(this, userTimeZone);
        updateDateDisplay();
        loadTasksForDate(); // Load initial tasks

        // Create/fix user document if needed
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null) {
            DocumentReference uref = db.collection("users").document(currentUser.getUid());
            uref.get().addOnSuccessListener(doc -> {
                if (!doc.exists()) {
                    // Brand new user, set up default fields
                    Map<String, Object> defaults = new HashMap<>();
                    defaults.put("points", 0L);
                    defaults.put("streak", 0L);
                    defaults.put("xp", 0L);
                    defaults.put("level", 1L);
                    defaults.put("completedDates", new ArrayList<String>());
                    defaults.put("dailyBonusAwardedDates", new ArrayList<String>());
                    defaults.put("activeEffects", new HashMap<String, Object>());
                    uref.set(defaults);
                } else {
                    // Fix any issues with existing data
                    // Username handled during signup, not here
                    
                    // Make sure XP and level can't be negative/missing
                    Long xpVal = doc.getLong("xp");
                    if (xpVal == null || xpVal < 0) {
                        uref.update("xp", 0L);
                    }
                    
                    Long lvlVal = doc.getLong("level");
                    if (lvlVal == null || lvlVal < 1) {
                        uref.update("level", 1L);
                    }
                    
                    // Add newer fields if missing
                    if (!doc.contains("activeEffects")) {
                        uref.update("activeEffects", new HashMap<String, Object>());
                    }
                    
                    if (!doc.contains("goldCoins")) {
                        uref.update("goldCoins", 0L);
                    }
                }
                
                // Now load points once user doc is ready
                loadPoints();
            }).addOnFailureListener(e -> Log.e("TaskList", "Failed to setup user: " + e.getMessage()));
        }

        // Handle quest generation with guards
        // We track this in prefs so we don't spam the server
        prefs = getSharedPreferences("quest_prefs", MODE_PRIVATE);
        if (currentUser != null) {
            // First clean up expired quests
            QuestManager questManager = new QuestManager(currentUser.getUid(), this);
            questManager.cleanupAllExpiredQuests();
            
            // Generate daily quests if we haven't done it today
            int today = Calendar.getInstance(userTimeZone).get(Calendar.DAY_OF_YEAR);
            int lastDaily = prefs.getInt("lastDailyIssueDay", -1);
            if (lastDaily != today) {
                questManager.issueDailyQuests();
                prefs.edit().putInt("lastDailyIssueDay", today).apply();
            }
            
            // Same for weekly quests
            int thisWeek = Calendar.getInstance(userTimeZone).get(Calendar.WEEK_OF_YEAR);
            int lastWeekly = prefs.getInt("lastWeeklyIssueWeek", -1);
            if (lastWeekly != thisWeek) {
                questManager.issueWeeklyQuest();
                prefs.edit().putInt("lastWeeklyIssueWeek", thisWeek).apply();
            }
        }
    }

    private void setupStreak() {
        streakCounter.setOnClickListener(v -> showStreakInfoDialog());
    }

    private void setupDateNavigation() {
        findViewById(R.id.btnPrevDay).setOnClickListener(v -> navigateDay(-1));
        findViewById(R.id.btnNextDay).setOnClickListener(v -> navigateDay(1));
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
    }

    private void navigateDay(int days) {
        currentDate.add(Calendar.DAY_OF_YEAR, days);
        updateDateDisplay();
        refreshTasks();
    }

    private void refreshTasks() {
        tasks.clear();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        loadTasksForDate();
    }

    private void updateDateDisplay() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault());
        sdf.setTimeZone(userTimeZone);
        dateHeader.setText(sdf.format(currentDate.getTime()));
    }

    public String getCurrentDateString() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sdf.setTimeZone(userTimeZone);
        return sdf.format(currentDate.getTime());
    }

    private void loadPoints() {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            Log.w("TaskList", "No user logged in");
            pointsCounter.setText("Points: 0");
            return;
        }
        
        String userId = currentUser.getUid();
        db.collection("users").document(userId)
                .get().addOnSuccessListener(doc -> {
                    if (doc.exists() && doc.contains("points")) {
                        Long pts = doc.getLong("points");
                        pointsCounter.setText("Points: " + (pts != null ? pts : 0));
                    } else {
                        Log.w("TaskList", "Missing points for user: " + userId);
                        pointsCounter.setText("Points: 0");
                    }
                }).addOnFailureListener(e -> {
                    Log.e("TaskList", "Failed to load points for user: " + userId, e);
                    pointsCounter.setText("Points: Error");
                });
    }

    private void loadTasksForDate() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w("TaskList", "No user logged in, can't load tasks");
            return;
        }
        
        if (taskListener != null) {
            taskListener.remove();
        }

        String dateString = getCurrentDateString();
        Log.d("TaskList", "Loading tasks for: " + dateString);

        Calendar dayStart = (Calendar) currentDate.clone();
        dayStart.set(Calendar.HOUR_OF_DAY, 0);
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        dayStart.set(Calendar.MILLISECOND, 0);

        Calendar dayEnd = (Calendar) dayStart.clone();
        dayEnd.add(Calendar.DAY_OF_YEAR, 1);

        long dayStartMillis = dayStart.getTimeInMillis();
        long dayEndMillis = dayEnd.getTimeInMillis();

        db.collection("users")
                .document(user.getUid())
                .collection("tasks")
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    ArrayList<Task> newTasks = new ArrayList<>();

                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        try {
                            Task task = doc.toObject(Task.class);
                            if (task != null) {
                                task.setId(doc.getId());

                                List<Map<String, Object>> rawSteps = (List<Map<String, Object>>) doc.get("steps");

                                Log.d("TaskList", "Task: " + task.getId() + 
                                      ", Name: " + task.getName() + 
                                      ", Date: " + task.getDate() + 
                                      ", RecurrenceID: " + task.getRecurrenceGroupId() +
                                      ", HasSteps: " + doc.contains("steps") +
                                      ", StepsType: " + (doc.get("steps") != null ? doc.get("steps").getClass().getSimpleName() : "null") +
                                      ", StepsSize: " + (rawSteps != null ? rawSteps.size() : "null"));
                                
                                if (rawSteps != null && !rawSteps.isEmpty()) {
                                    List<Task.Subtask> subtasks = new ArrayList<>();
                                    for (int i = 0; i < rawSteps.size(); i++) {
                                        Map<String, Object> stepMap = rawSteps.get(i);
                                        if (stepMap != null) {
                                            String description = (String) stepMap.get("description");
                                            Boolean completed = (Boolean) stepMap.get("completed");
                                            Number stabilityNumber = (Number) stepMap.get("stability");
                                            int stability = stabilityNumber != null ? stabilityNumber.intValue() : 0;

                                            if (description != null && !description.trim().isEmpty()) {
                                                Task.Subtask subtask = new Task.Subtask(
                                                    description.trim(),
                                                    completed != null ? completed : false,
                                                    stability
                                                );
                                                subtasks.add(subtask);
                                                Log.d("TaskList", "  Added subtask " + i + ": '" + description + "' (completed: " + completed + ")");
                                            } else {
                                                Log.w("TaskList", "  Skipping empty subtask " + i + " with description: '" + description + "'");
                                            }
                                        } else {
                                            Log.w("TaskList", "  Skipping null subtask at index " + i);
                                        }
                                    }
                                    task.setSteps(subtasks);
                                    Log.d("TaskList", "Final subtasks count for " + task.getId() + ": " + subtasks.size());
                                } else {
                                    task.setSteps(new ArrayList<>());
                                    Log.d("TaskList", "No valid steps found for " + task.getId() + ", initialized empty list");
                                }

                                boolean belongsOnThisDay = false;

                                if (dateString.equals(task.getDate())) {
                                    belongsOnThisDay = true;
                                }
                                else if (task.getDeadlineTimestamp() > 0) {
                                    if (task.getDeadlineTimestamp() >= dayStartMillis &&
                                            task.getDeadlineTimestamp() < dayEndMillis) {

                                        if (!dateString.equals(task.getDate())) {
                                            doc.getReference().update("date", dateString);
                                            task.setDate(dateString);
                                        }

                                        belongsOnThisDay = true;
                                    }
                                }

                                if (belongsOnThisDay) {
                                    newTasks.add(task);
                                }
                            }
                        } catch (Exception e) {
                            Log.e("TaskList", "Error processing task: " + doc.getId(), e);
                        }
                    }

                    Collections.sort(newTasks, (t1, t2) -> {
                        String priority1 = t1.getPriority();
                        String priority2 = t2.getPriority();

                        if (priority1 == null) priority1 = Task.TaskPriority.MEDIUM.name();
                        if (priority2 == null) priority2 = Task.TaskPriority.MEDIUM.name();

                        int p1Value = 0, p2Value = 0;
                        try {
                            p1Value = Task.TaskPriority.valueOf(priority1).getValue();
                            p2Value = Task.TaskPriority.valueOf(priority2).getValue();
                        } catch (IllegalArgumentException e) {
                            Log.w("TaskList", "Invalid priority: " + e.getMessage());
                        }
                        
                        int priorityCompare = Integer.compare(p2Value, p1Value); // Descending order
                        
                        if (priorityCompare != 0) {
                            return priorityCompare;
                        }
                        

                        if (t1.getDeadlineTimestamp() == 0 && t2.getDeadlineTimestamp() == 0) {
                            return 0;
                        } else if (t1.getDeadlineTimestamp() == 0) {
                            return -1;
                        } else if (t2.getDeadlineTimestamp() == 0) {
                            return 1;
                        } else {
                            return Long.compare(t1.getDeadlineTimestamp(), t2.getDeadlineTimestamp());
                        }
                    });

                    Log.d("TaskList", "Loaded " + newTasks.size() + " tasks for " + dateString);

                    tasks.clear();
                    tasks.addAll(newTasks);
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();

                        if (dateString.equals(actualTodayDateString)) {
                            adapter.updateCompletedDates(currentDate.getTimeInMillis());
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e("TaskList", "Error loading tasks: ", e);
                    Toast.makeText(this, "Error loading tasks", Toast.LENGTH_SHORT).show();
                });
    }

    void updateStreakDisplay() {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) return;

        db.collection("users")
                .document(currentUser.getUid())
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists() && documentSnapshot.contains("streak")) {
                        long streak = documentSnapshot.getLong("streak");
                        streakCounter.setText(String.valueOf(streak));
                        boolean showFlame = streak > 0;
                        flameAnimation.setVisibility(showFlame ? View.VISIBLE : View.GONE);
                        if (showFlame && !flameAnimation.isAnimating()) {
                            flameAnimation.playAnimation();
                        } else if (!showFlame) {
                            flameAnimation.cancelAnimation();
                        }
                    } else {
                        streakCounter.setText("0");
                        flameAnimation.setVisibility(View.GONE);
                        flameAnimation.cancelAnimation();
                        Log.w("UpdateStreakDisplay", "User doc missing or no streak field.");
                    }
                }).addOnFailureListener(e -> {
                    Log.e("UpdateStreakDisplay", "Failed to load streak", e);
                    streakCounter.setText("?"); // Indicate error
                    flameAnimation.setVisibility(View.GONE);
                    flameAnimation.cancelAnimation();
                });
    }

    private void loadInitialStreak() {
        updateStreakDisplay();
    }

    public void showDeleteDialog(String taskId) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Task")
                .setMessage("Are you sure you want to delete this task?")
                .setPositiveButton("Delete", (dialog, which) -> deleteTask(taskId))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteTask(String taskId) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Authentication error", Toast.LENGTH_SHORT).show();
            return;
        }

        Task taskToDelete = null;
        for (Task task : tasks) {
            if (task.getId().equals(taskId)) {
                taskToDelete = task;
                break;
            }
        }

        if (taskToDelete == null) {
            Log.e("DeleteTask", "Task not found in local list: " + taskId);
            Toast.makeText(this, "Task not found", Toast.LENGTH_SHORT).show();
            return;
        }

        final long taskDeadlineTimestamp = taskToDelete.getDeadlineTimestamp();
        final boolean wasCompleted = taskToDelete.isCompleted();

        db.collection("users")
                .document(user.getUid())
                .collection("tasks")
                .document(taskId)
                .delete()
                .addOnSuccessListener(aVoid -> {
                    Log.d("DeleteTask", "Task deleted successfully: " + taskId);
                    Toast.makeText(this, "Task deleted", Toast.LENGTH_SHORT).show();

                    if (wasCompleted) {
                        if (adapter != null && adapter instanceof TaskListAdapter)
                            adapter.updateCompletedDates(taskDeadlineTimestamp);
                        else {
                            Log.e("DeleteTask", "Adapter is null or not TaskListAdapter, cannot call updateCompletedDates");
                        }
                    }

                    refreshTasks();
                })
                .addOnFailureListener(e -> {
                    Log.e("DeleteTask", "Error deleting task: " + taskId, e);
                    Toast.makeText(this, "Error deleting task", Toast.LENGTH_SHORT).show();
                });
    }

    private void showStreakInfoDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Streak Info")
                .setMessage("Your current streak represents the number of consecutive days you have completed ALL tasks in your local timezone (Europe/Bucharest). Keep it up!")
                .setPositiveButton("OK", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (taskListener != null) {
            taskListener.remove();
        }
        if (flameAnimation != null) {
            flameAnimation.cancelAnimation();
        }
        if (confettiView != null) {
            confettiView.cancelAnimation();
        }
    }


    public void awardDailyBonusInTransaction(DocumentReference userRef, String dateString) {
        db.runTransaction(transaction -> { // Use the class db instance
            DocumentSnapshot userSnapshot = transaction.get(userRef);
            if (!userSnapshot.exists()) {
                Log.e("Transaction", "User document does not exist during daily bonus award for user: " + userRef.getId());
                throw new FirebaseFirestoreException("User document not found", FirebaseFirestoreException.Code.ABORTED);
            }

            List<String> awardedDates = new ArrayList<>();
            if (userSnapshot.contains("dailyBonusAwardedDates") && userSnapshot.get("dailyBonusAwardedDates") instanceof List) {
                try {
                    List<?> rawList = (List<?>) userSnapshot.get("dailyBonusAwardedDates");
                    if (rawList != null) {
                        for (Object item : rawList) {
                            if (item instanceof String) {
                                awardedDates.add((String) item);
                            }
                        }
                    }
                } catch (ClassCastException e) {
                    Log.e("Transaction", "Firestore 'dailyBonusAwardedDates' format error.", e);
                    // Decide: Throw error or reset the list? Resetting might be safer.
                    awardedDates = new ArrayList<>(); // Reset list if format is wrong
                    transaction.update(userRef, "dailyBonusAwardedDates", awardedDates); // Persist the reset
                }
            }


            if (awardedDates.contains(dateString)) {
                Log.d("Transaction", "Daily bonus for " + dateString + " was already awarded (re-checked in transaction).");
                return null;
            }

            long currentPoints = userSnapshot.contains("points") ? userSnapshot.getLong("points") : 0L;
            int bonusPoints = 25;

            awardedDates.add(dateString);
            Collections.sort(awardedDates);

            transaction.update(userRef, "points", currentPoints + bonusPoints);
            transaction.update(userRef, "dailyBonusAwardedDates", awardedDates);

            Log.d("Transaction", "Awarding daily bonus (" + bonusPoints + " points) for date: " + dateString);
            return bonusPoints;
        }).addOnSuccessListener(bonusAwarded -> {
            if (bonusAwarded == null) {
                Log.d("TaskListActivity", "Daily bonus transaction finished, but bonus was likely already awarded.");
                return; // Do nothing further
            }
            Log.d("TaskListActivity", "Daily bonus awarded successfully in transaction.");
            loadPoints(); // Refresh points display
            Toast.makeText(this, "Daily Bonus: +" + bonusAwarded + " Points!", Toast.LENGTH_SHORT).show();
        }).addOnFailureListener(e -> {
            Log.e("TaskListActivity", "Failed to award daily bonus transaction for " + dateString, e);
            if (e instanceof FirebaseFirestoreException && ((FirebaseFirestoreException) e).getCode() == FirebaseFirestoreException.Code.ABORTED) {
                Toast.makeText(this, "Could not award daily bonus: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Error awarding daily bonus. Please try again.", Toast.LENGTH_SHORT).show();
            }
        });
    }


    private void recalcStreakOnStartup() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w("RecalcStreak", "No user logged in.");
            return;
        }
        DocumentReference userRef = db.collection("users").document(user.getUid());

        userRef.get().addOnSuccessListener(userDoc -> {
            if (!userDoc.exists()) {
                Log.w("RecalcStreak", "User document does not exist.");
                updateStreakDisplay();
                return;
            }


            List<String> completedDates = new ArrayList<>();
            if (userDoc.contains("completedDates") && userDoc.get("completedDates") instanceof List) {
                try {
                    List<?> rawList = (List<?>) userDoc.get("completedDates");
                    if (rawList != null) {
                        for (Object item : rawList) {
                            if (item instanceof String) {
                                completedDates.add((String) item);
                            }
                        }
                    }
                } catch (ClassCastException e) {
                    Log.e("RecalcStreak", "Firestore 'completedDates' format error.", e);
                    completedDates = new ArrayList<>(); // Use empty list if format is wrong
                }
            }

            String currentDateStr = getCurrentDateString();

            boolean todayInCompletedDates = completedDates.contains(actualTodayDateString);
            
            Log.d("RecalcStreak", "Checking streak state - today: " + actualTodayDateString + 
                                  ", in completedDates: " + todayInCompletedDates);

            List<String> finalCompletedDates = completedDates;
            List<String> finalCompletedDates1 = completedDates;
            db.collection("users").document(user.getUid())
                .collection("tasks")
                .whereEqualTo("date", actualTodayDateString)
                .get()
                .addOnSuccessListener(taskSnapshots -> {
                    boolean hasIncompleteTasks = false;

                    if (!taskSnapshots.isEmpty()) {
                        for (DocumentSnapshot taskDoc : taskSnapshots) {
                            Boolean completed = taskDoc.getBoolean("completed");
                            if (completed == null || !completed) {
                                hasIncompleteTasks = true;
                                break;
                            }
                        }
                    }
                    
                    Log.d("RecalcStreak", "Today's tasks: " + taskSnapshots.size() + 
                                          ", has incomplete tasks: " + hasIncompleteTasks);

                    if (hasIncompleteTasks && todayInCompletedDates) {
                        finalCompletedDates.remove(actualTodayDateString);
                        Log.d("RecalcStreak", "Removed today from completedDates due to incomplete tasks");
                    }

                    if (streakHelper != null) {
                        streakHelper.updateStreak(userRef, finalCompletedDates, currentDateStr, new StreakHelper.StreakUpdateCallback() {
                            @Override public void onSuccess(int newStreak) {
                                Log.d("TaskListActivity", "Initial streak recalculation/update successful. New streak: " + newStreak);
                                updateStreakDisplay(); // Update the UI with the potentially new streak value
                            }
                            @Override public void onFailure() {
                                Log.e("TaskListActivity", "Initial streak recalculation/update failed.");
                                // Maybe show an error or just log it
                                updateStreakDisplay();
                            }
                        });
                    } else {
                        Log.e("TaskListActivity", "streakHelper is null during recalcStreakOnStartup.");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e("RecalcStreak", "Failed to query today's tasks", e);

                    if (streakHelper != null) {
                        streakHelper.updateStreak(userRef, finalCompletedDates1, currentDateStr, new StreakHelper.StreakUpdateCallback() {
                            @Override public void onSuccess(int newStreak) {
                                Log.d("TaskListActivity", "Fallback streak calculation successful. New streak: " + newStreak);
                                updateStreakDisplay();
                            }
                            @Override public void onFailure() {
                                Log.e("TaskListActivity", "Fallback streak calculation failed.");
                                updateStreakDisplay();
                            }
                        });
                    }
                });
        }).addOnFailureListener(e -> {
            Log.e("TaskListActivity", "Failed to load user data for initial streak recalculation.", e);

            updateStreakDisplay();
        });
    }

    public void showEditAllTasksDialog(String recurrenceGroupId) {
        if (recurrenceGroupId == null || recurrenceGroupId.isEmpty()) {
            Toast.makeText(this, "Cannot find recurring tasks", Toast.LENGTH_SHORT).show();
            return;
        }
        
        new AlertDialog.Builder(this)
                .setTitle("Edit All Tasks in Series")
                .setMessage("Are you sure you want to edit all tasks in this recurring series? This will affect all tasks across multiple days.")
                .setPositiveButton("Edit All", (dialog, which) -> {
                    findTaskWithMaxRecurrenceDaysInSeries(recurrenceGroupId);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void findTaskWithMaxRecurrenceDaysInSeries(String recurrenceGroupId) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Authentication error", Toast.LENGTH_SHORT).show();
            return;
        }

        db.collection("users")
                .document(user.getUid())
                .collection("tasks")
                .whereEqualTo("recurrenceGroupId", recurrenceGroupId)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (queryDocumentSnapshots.isEmpty()) {
                        Toast.makeText(this, "No tasks found in this series", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String taskIdToEdit = null;
                    int maxRecurrenceDays = 0;

                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        Task task = doc.toObject(Task.class);
                        if (task != null) {
                            task.setId(doc.getId());
                            if (task.getRecurrenceDays() > maxRecurrenceDays) {
                                maxRecurrenceDays = task.getRecurrenceDays();
                                taskIdToEdit = task.getId();
                            }
                        }
                    }

                    if (taskIdToEdit != null) {
                        Intent intent = new Intent(this, AddTaskActivity.class);
                        intent.putExtra("TASK_ID", taskIdToEdit);
                        intent.putExtra("EDIT_SERIES", true);
                        startActivity(intent);
                    } else {
                        Task task = queryDocumentSnapshots.getDocuments().get(0).toObject(Task.class);
                        if (task != null) {
                            String id = queryDocumentSnapshots.getDocuments().get(0).getId();
                            Intent intent = new Intent(this, AddTaskActivity.class);
                            intent.putExtra("TASK_ID", id);
                            intent.putExtra("EDIT_SERIES", true);
                            startActivity(intent);
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e("TaskListActivity", "Error finding task in series", e);
                    Toast.makeText(this, "Error finding tasks in series", Toast.LENGTH_SHORT).show();
                });
    }

    public void showDeleteSeriesDialog(String recurrenceGroupId) {
        if (recurrenceGroupId == null || recurrenceGroupId.isEmpty()) {
            Toast.makeText(this, "Cannot find recurring tasks", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Delete All Tasks in Series")
                .setMessage("Are you sure you want to delete ALL tasks in this recurring series? This will affect all tasks across multiple days.")
                .setPositiveButton("Delete All", (dialog, which) -> deleteTaskSeries(recurrenceGroupId))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteTaskSeries(String recurrenceGroupId) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Authentication error", Toast.LENGTH_SHORT).show();
            return;
        }

        db.collection("users")
                .document(user.getUid())
                .collection("tasks")
                .whereEqualTo("recurrenceGroupId", recurrenceGroupId)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (queryDocumentSnapshots.isEmpty()) {
                        Toast.makeText(this, "No tasks found in this series", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    WriteBatch batch = db.batch();
                    final int[] count = {0};

                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        batch.delete(doc.getReference());
                        count[0]++;
                    }

                    batch.commit()
                            .addOnSuccessListener(aVoid -> {
                                Toast.makeText(this, count[0] + " recurring tasks deleted", Toast.LENGTH_SHORT).show();
                                refreshTasks();
                            })
                            .addOnFailureListener(e -> {
                                Log.e("TaskListActivity", "Error deleting task series", e);
                                Toast.makeText(this, "Error deleting recurring tasks", Toast.LENGTH_SHORT).show();
                            });
                })
                .addOnFailureListener(e -> {
                    Log.e("TaskListActivity", "Error finding tasks in series", e);
                    Toast.makeText(this, "Error finding tasks in series", Toast.LENGTH_SHORT).show();
                });
    }

}


