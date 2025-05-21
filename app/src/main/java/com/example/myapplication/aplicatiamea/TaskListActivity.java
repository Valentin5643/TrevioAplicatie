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
    private final TimeZone userTimeZone = TimeZone.getTimeZone("Europe/Bucharest");
    private LottieAnimationView confettiView;
    private SharedPreferences prefs;
    private FirebaseFirestore db; // Add Firestore instance variable
    private String actualTodayDateString;

    // Maps to track tasks in progress for anti-spam 
    private final ConcurrentHashMap<String, Boolean> tasksInProgress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> subtasksInProgress = new ConcurrentHashMap<>();
    private static final long ANTI_SPAM_COOLDOWN = 2000; // 2 seconds cooldown

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task_list);
        db = FirebaseFirestore.getInstance(); // Initialize Firestore

        // Ensure daily and weekly quests are issued if needed
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null) {
            QuestManager qm = new QuestManager(currentUser.getUid(), this);
            qm.issueDailyQuests();
            qm.issueWeeklyQuest();
        }

        // Initialize the actual today date string (real calendar today)
        Calendar realToday = Calendar.getInstance(userTimeZone);
        realToday.set(Calendar.HOUR_OF_DAY, 0);
        realToday.set(Calendar.MINUTE, 0);
        realToday.set(Calendar.SECOND, 0);
        realToday.set(Calendar.MILLISECOND, 0);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sdf.setTimeZone(userTimeZone);
        actualTodayDateString = sdf.format(realToday.getTime());
        Log.d("TaskListActivity", "Actual today's date: " + actualTodayDateString);
        // Initialize SharedPreferences for level tracking
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
        // Recalculate streak on startup to include yesterday's completed tasks
        recalcStreakOnStartup();
        rvTasks = findViewById(R.id.rvTasks);
        rvTasks.setLayoutManager(new LinearLayoutManager(this));
        // Pass the list to the adapter
        adapter = new TaskListAdapter(tasks, this, db, userTimeZone, actualTodayDateString, tasksInProgress, subtasksInProgress, ANTI_SPAM_COOLDOWN, streakHelper);
        rvTasks.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Always refresh tasks when returning to this activity
        refreshTasks();

        // Also refresh points and streak display
        loadPoints();
        updateStreakDisplay(); // Use the existing method to update streak UI
    }

    private void initializeViews() {
        flameAnimation = findViewById(R.id.flameAnimation);
        streakCounter = findViewById(R.id.streakCounter);
        dateHeader = findViewById(R.id.dateHeader);
        pointsCounter = findViewById(R.id.pointsCounter);
        // Bind confetti view
        confettiView = findViewById(R.id.confettiView);
        confettiView.setVisibility(View.GONE); // Initially hidden
        flameAnimation.setVisibility(View.GONE); // Initially hidden
    }

    private void setupFirebase() {
        streakHelper = new StreakHelper(this, userTimeZone);
        updateDateDisplay();
        loadTasksForDate(); // Load initial tasks

        // Ensure user document exists with default fields
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null) {
            DocumentReference uref = db.collection("users").document(currentUser.getUid());
            uref.get().addOnSuccessListener(doc -> {
                if (!doc.exists()) {
                    Map<String, Object> defaults = new HashMap<>();

                    // Never create username automatically - it should come from signup

                    defaults.put("points", 0L);
                    defaults.put("streak", 0L);
                    defaults.put("xp", 0L);
                    defaults.put("level", 1L);
                    defaults.put("completedDates", new ArrayList<String>());
                    defaults.put("dailyBonusAwardedDates", new ArrayList<String>());
                    // Add default activeEffects map if needed
                    defaults.put("activeEffects", new HashMap<String, Object>());
                    uref.set(defaults);
                } else {
                    // Username is handled during signup, not here

                    // Clamp xp/level for existing users to avoid negative or missing values
                    Long xpVal = doc.getLong("xp");
                    if (xpVal == null || xpVal < 0) {
                        uref.update("xp", 0L);
                    }
                    Long lvlVal = doc.getLong("level");
                    if (lvlVal == null || lvlVal < 1) {
                        uref.update("level", 1L);
                    }
                    // Ensure activeEffects map exists
                    if (!doc.contains("activeEffects")) {
                        uref.update("activeEffects", new HashMap<String, Object>());
                    }
                    // Ensure goldCoins exists
                    if (!doc.contains("goldCoins")) {
                        uref.update("goldCoins", 0L);
                    }
                }
                loadPoints(); // Load points after ensuring document exists/is updated
            }).addOnFailureListener(e -> Log.e("SetupFirebase", "Error checking/creating user document", e));
        }

        // Quest issuance guards: only issue daily/weekly once per cycle
        prefs = getSharedPreferences("quest_prefs", MODE_PRIVATE);
        if (currentUser != null) {
            // First clean up all expired quests to keep the database tidy
            QuestManager questManager = new QuestManager(currentUser.getUid(), this);
            questManager.cleanupAllExpiredQuests();
            
            int today = Calendar.getInstance(userTimeZone).get(Calendar.DAY_OF_YEAR);
            int lastDaily = prefs.getInt("lastDailyIssueDay", -1);
            if (lastDaily != today) {
                questManager.issueDailyQuests();
                prefs.edit().putInt("lastDailyIssueDay", today).apply();
            }
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
        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());
    }

    private void navigateDay(int days) {
        currentDate.add(Calendar.DAY_OF_YEAR, days);
        updateDateDisplay();
        refreshTasks(); // Reload tasks for the new date, but don't update streak
    }

    private void refreshTasks() {
        // Clear the local list and notify adapter before loading new data
        tasks.clear();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        loadTasksForDate();
    }


    private void updateDateDisplay() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault());
        sdf.setTimeZone(userTimeZone); // Ensure correct timezone
        dateHeader.setText(sdf.format(currentDate.getTime()));
    }

    // Public method to get the current date as yyyy-MM-dd string
    public String getCurrentDateString() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sdf.setTimeZone(userTimeZone); // Use consistent timezone
        return sdf.format(currentDate.getTime());
    }

    private void loadPoints() {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            Log.w("LoadPoints", "No current user.");
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
                        Log.w("LoadPoints", "User doc doesn't exist or missing points field for user: " + userId);
                        pointsCounter.setText("Points: 0");
                    }
                }).addOnFailureListener(e -> {
                    Log.e("LoadPoints", "Failed to load points for user: " + userId, e);
                    pointsCounter.setText("Points: Error");
                });
    }

    private void loadTasksForDate() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w("LoadTasks", "No current user, cannot load tasks.");
            return; // Can't load tasks without a user
        }
        if (taskListener != null) {
            taskListener.remove(); // Remove previous listener to avoid duplicates
        }

        // Determine current day range for timestamp query
        String dateString = getCurrentDateString();
        Log.d("LoadTasks", "Loading tasks for date: " + dateString);

        // Get calendar instances for timestamp comparison
        Calendar dayStart = (Calendar) currentDate.clone();
        dayStart.set(Calendar.HOUR_OF_DAY, 0);
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        dayStart.set(Calendar.MILLISECOND, 0);

        Calendar dayEnd = (Calendar) dayStart.clone();
        dayEnd.add(Calendar.DAY_OF_YEAR, 1);

        long dayStartMillis = dayStart.getTimeInMillis();
        long dayEndMillis = dayEnd.getTimeInMillis();

        // Query tasks using a combination of date string and timestamp range
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
                                
                                                            // Explicitly retrieve and set the steps/subtasks with their completion status
                            List<Map<String, Object>> rawSteps = (List<Map<String, Object>>) doc.get("steps");
                            
                            // [DEBUG] Log task details for debugging
                            Log.d("DEBUG_SUBTASKS", "Processing task: " + task.getId() + 
                                  ", Name: " + task.getName() + 
                                  ", Date: " + task.getDate() + 
                                  ", RecurrenceGroupId: " + task.getRecurrenceGroupId() +
                                  ", RecurrenceDays: " + task.getRecurrenceDays() + 
                                  ", Has 'steps' field: " + doc.contains("steps") + 
                                  ", Raw steps size: " + (rawSteps != null ? rawSteps.size() : "null"));
                            
                            if (rawSteps != null && !rawSteps.isEmpty()) {
                                List<Task.Subtask> subtasks = new ArrayList<>();
                                for (Map<String, Object> stepMap : rawSteps) {
                                    String description = (String) stepMap.get("description");
                                    Boolean completed = (Boolean) stepMap.get("completed");
                                    // Get the stability field, defaulting to 0 if not present
                                    Number stabilityNumber = (Number) stepMap.get("stability");
                                    int stability = stabilityNumber != null ? stabilityNumber.intValue() : 0;
                                    
                                    Task.Subtask subtask = new Task.Subtask(
                                        description != null ? description : "",
                                        completed != null ? completed : false,
                                        stability
                                    );
                                    subtasks.add(subtask);
                                }
                                task.setSteps(subtasks);
                                Log.d("DEBUG_SUBTASKS", "Loaded " + subtasks.size() + " subtasks for task: " + task.getId());
                                
                                // Debug: Log subtask details
                                for (int i = 0; i < subtasks.size(); i++) {
                                    Task.Subtask subtask = subtasks.get(i);
                                    Log.d("DEBUG_SUBTASKS", "  Subtask " + i + ": " + 
                                          "description='" + subtask.getDescription() + "', " +
                                          "completed=" + subtask.isCompleted() + ", " +
                                          "stability=" + subtask.getStability());
                                }
                            } else {
                                Log.d("DEBUG_SUBTASKS", "Task " + task.getId() + " has no steps or rawSteps is null/empty.");
                                
                                // [FIX] Always initialize with empty list to prevent null pointer exceptions
                                // This is especially important for recurring tasks where subtasks might be missing
                                task.setSteps(new ArrayList<>());
                            }

                                // Check if this task belongs on this day (either by date string or timestamp)
                                boolean belongsOnThisDay = false;

                                // First check the date string
                                if (dateString.equals(task.getDate())) {
                                    belongsOnThisDay = true;
                                }
                                // Then check deadline timestamp (in case date string is missing or incorrect)
                                else if (task.getDeadlineTimestamp() > 0) {
                                    // Check if deadline falls within this day's range
                                    if (task.getDeadlineTimestamp() >= dayStartMillis &&
                                            task.getDeadlineTimestamp() < dayEndMillis) {

                                        // If there's a mismatch, update the task's date field for consistency
                                        if (!dateString.equals(task.getDate())) {
                                            Log.d("TaskList", "Fixing task date field for task: " + task.getId());
                                            doc.getReference().update("date", dateString);
                                            task.setDate(dateString);
                                        }

                                        belongsOnThisDay = true;
                                    }
                                }

                                if (belongsOnThisDay) {
                                    // [DEBUG] Log task being added to display
                                    Log.d("DEBUG_SUBTASKS", "Adding task to display: " + task.getId() + 
                                         ", Name: " + task.getName() +
                                         ", RecurrenceGroupId: " + task.getRecurrenceGroupId() +
                                         ", RecurrenceDays: " + task.getRecurrenceDays() +
                                         ", Subtasks count: " + (task.getSteps() != null ? task.getSteps().size() : "null"));
                                    
                                    newTasks.add(task);
                                } else {
                                    Log.d("DEBUG_SUBTASKS", "Task NOT added to display: " + task.getId() + 
                                         " - Does not belong to day: " + dateString);
                                }
                            }
                        } catch (Exception e) {
                            Log.e("LoadTasks", "Error converting document to Task object: " + doc.getId(), e);
                        }
                    }

                    // Sort tasks by priority (HIGH to LOW) first, then by time
                    Collections.sort(newTasks, (t1, t2) -> {
                        // First compare by priority (higher priority first)
                        String priority1 = t1.getPriority();
                        String priority2 = t2.getPriority();
                        
                        // Default to MEDIUM if priority is null
                        if (priority1 == null) priority1 = Task.TaskPriority.MEDIUM.name();
                        if (priority2 == null) priority2 = Task.TaskPriority.MEDIUM.name();
                        
                        // Get priority values for comparison (HIGH=3, MEDIUM=2, LOW=1)
                        int p1Value = 0, p2Value = 0;
                        try {
                            p1Value = Task.TaskPriority.valueOf(priority1).getValue();
                            p2Value = Task.TaskPriority.valueOf(priority2).getValue();
                        } catch (IllegalArgumentException e) {
                            Log.w("LoadTasks", "Invalid priority value: " + e.getMessage());
                        }
                        
                        int priorityCompare = Integer.compare(p2Value, p1Value); // Descending order
                        
                        if (priorityCompare != 0) {
                            // If priorities are different, return that comparison
                            return priorityCompare;
                        }
                        
                        // If priorities are the same, then sort by timestamp/deadline
                        if (t1.getDeadlineTimestamp() == 0 && t2.getDeadlineTimestamp() == 0) {
                            return 0; // Both have no time
                        } else if (t1.getDeadlineTimestamp() == 0) {
                            return -1; // First task has no time, sort it first
                        } else if (t2.getDeadlineTimestamp() == 0) {
                            return 1; // Second task has no time, sort first task later
                        } else {
                            return Long.compare(t1.getDeadlineTimestamp(), t2.getDeadlineTimestamp());
                        }
                    });

                    // Log sorted tasks for debugging
                    for (Task task : newTasks) {
                        Log.d("LoadTasks", "Sorted task: " + task.getName() + 
                                          ", Priority: " + task.getPriority() + 
                                          ", Deadline: " + task.getDeadlineTimestamp());
                    }

                    Log.d("LoadTasks", "Loaded " + newTasks.size() + " tasks for " + dateString);

                    // Update the adapter's list and notify
                    tasks.clear();
                    tasks.addAll(newTasks);
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();

                        // Only check and update streak for the actual current day
                        // not for past or future days being viewed
                        if (dateString.equals(actualTodayDateString)) {
                            // Re-evaluate daily completion now that tasks have loaded (handles subtasks)
                            adapter.updateCompletedDates(currentDate.getTimeInMillis());
                        }
                    } else {
                        Log.w("LoadTasks", "Adapter is null after loading tasks.");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e("LoadTasks", "Error loading tasks: ", e);
                    Toast.makeText(this, "Error loading tasks.", Toast.LENGTH_SHORT).show();
                });
    }

    // Updates streak counter and flame visibility
    void updateStreakDisplay() {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) return;

        // Always load the actual streak value from Firestore
        // regardless of which date is being viewed
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

    // Load initial streak value without relying on listener for the very first display
    private void loadInitialStreak() {
        updateStreakDisplay(); // Just call the regular update method
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

        // Store task info before deletion (to handle streak/completion updates)
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

                    // If the deleted task was completed, we need to update completed dates
                    if (wasCompleted) {
                        // Re-evaluate the completion status for the date
                        if (adapter != null && adapter instanceof TaskListAdapter)
                            adapter.updateCompletedDates(taskDeadlineTimestamp);
                        else {
                            Log.e("DeleteTask", "Adapter is null or not TaskListAdapter, cannot call updateCompletedDates");
                        }
                    }

                    // Also refresh display after deletion
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
            taskListener.remove(); // Stop listening when activity is destroyed
        }
        if (flameAnimation != null) {
            flameAnimation.cancelAnimation(); // Prevent memory leaks
        }
        if (confettiView != null) {
            confettiView.cancelAnimation();
        }
    }

    // --- Helper methods for transactions (Correctly placed inside TaskListActivity) ----

    public void awardPointsAndXpInTransaction(DocumentReference userRef, String difficulty, String taskId) {
        db.runTransaction(transaction -> { // Use the class db instance
            DocumentSnapshot userSnapshot = transaction.get(userRef);
            if (!userSnapshot.exists()) {
                Log.e("Transaction", "User document does not exist during point/XP award for user: " + userRef.getId());
                // Abort the transaction by throwing an exception
                throw new FirebaseFirestoreException("User document not found", FirebaseFirestoreException.Code.ABORTED);
            }

            // Safely get current values, defaulting to 0
            long currentPoints = userSnapshot.contains("points") ? userSnapshot.getLong("points") : 0L;
            long currentCoins = userSnapshot.contains("goldCoins") ? userSnapshot.getLong("goldCoins") : 0L;

            // Calculate base rewards
            int pointsToAdd = 10; // Example: Base points for any task completion
            long coinsToAdd = pointsToAdd; // Example: Base coins equal base points

            // --- Apply Active Effects ---
            Map<String, Object> currentEffects = new HashMap<>();
            if (userSnapshot.contains("activeEffects") && userSnapshot.get("activeEffects") instanceof Map) {
                try {
                    currentEffects = (Map<String, Object>) userSnapshot.get("activeEffects");
                    if (currentEffects == null) currentEffects = new HashMap<>(); // Handle null from Firestore
                } catch (ClassCastException e) {
                    Log.e("Transaction", "Firestore 'activeEffects' is not a Map.", e);
                    currentEffects = new HashMap<>(); // Reset if wrong type
                }
            }
            Map<String, Object> effectsToSave = new HashMap<>(currentEffects); // Create a mutable copy
            boolean effectsModified = false; // Track if effects map needs to be updated

            // Apply Giant Strength Tonic (Coin Boost)
            if (currentEffects.getOrDefault("giantStrengthTonicActive", false) instanceof Boolean && (Boolean)currentEffects.getOrDefault("giantStrengthTonicActive", false)) {
                coinsToAdd = coinsToAdd * EffectManager.GIANT_STRENGTH_MULTIPLIER; // Use constant
                effectsToSave.put("giantStrengthTonicActive", false); // Mark effect as consumed IN THE MAP TO BE SAVED
                effectsModified = true;
                Log.d("Transaction", "Applied Giant Strength Tonic effect. New Coin gain: " + coinsToAdd);
            }
            // Apply Midas Brew (Potential Coin Boost - check if active)
            if (EffectManager.isMidasBrewActive(currentEffects)) { // Pass the map
                coinsToAdd = coinsToAdd * EffectManager.MIDAS_BREW_MULTIPLIER; // Use constant
                // Midas Brew is timed, not consumable per task, so don't modify effectsToSave here
                Log.d("Transaction", "Applied Midas Brew effect. New Coin gain: " + coinsToAdd);
            }
            // Apply Dexterity Serum (decrement uses)
            if (EffectManager.isDexteritySerumActive(currentEffects)) {
                int remaining = currentEffects.getOrDefault("dexterityTasksRemaining", 0) instanceof Number ? 
                    ((Number) currentEffects.getOrDefault("dexterityTasksRemaining", 0)).intValue() : 0;
                
                if (remaining > 0) {
                    // Decrement the counter
                    effectsToSave.put("dexterityTasksRemaining", remaining - 1);
                    effectsModified = true;
                    Log.d("Transaction", "Decremented Dexterity Serum uses: " + remaining + " -> " + (remaining - 1));
                }
            }
            // --- End Apply Effects ---

            // Perform Firestore updates within the transaction
            transaction.update(userRef, "points", currentPoints + pointsToAdd);
            transaction.update(userRef, "goldCoins", currentCoins + coinsToAdd);

            // If any consumable effects were applied, save the updated effects map
            if (effectsModified) {
                transaction.update(userRef, "activeEffects", effectsToSave);
                Log.d("Transaction", "Saving updated activeEffects map.");
            }

            Log.d("Transaction", "Awarding for task " + taskId + ": " + pointsToAdd + " pts, " + coinsToAdd + " coins");
            return null; // No XP awarded for tasks
        }).addOnSuccessListener(x -> {
            Log.d("TaskListActivity", "Points/Coins transaction successful for task " + taskId);
            loadPoints(); // Refresh the points display in the UI
        }).addOnFailureListener(e -> {
            // Handle transaction failure
            Log.e("TaskListActivity", "Failed to award points/coins transaction for task " + taskId, e);
            // Check if it was the specific aborted exception we threw
            if (e instanceof FirebaseFirestoreException && ((FirebaseFirestoreException) e).getCode() == FirebaseFirestoreException.Code.ABORTED) {
                Toast.makeText(this, "Could not update stats: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Error updating points/coins. Please try again.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void deductPointsAndXpInTransaction(DocumentReference userRef, String taskId) {
        db.runTransaction(transaction -> { // Use the class db instance
            DocumentSnapshot userSnapshot = transaction.get(userRef);
            if (!userSnapshot.exists()) {
                Log.e("Transaction", "User document does not exist during point/coin deduction for user: " + userRef.getId());
                throw new FirebaseFirestoreException("User document not found", FirebaseFirestoreException.Code.ABORTED);
            }

            // Safely get current values
            long currentPoints = userSnapshot.contains("points") ? userSnapshot.getLong("points") : 0L;
            long currentCoins = userSnapshot.contains("goldCoins") ? userSnapshot.getLong("goldCoins") : 0L;

            // Calculate base amounts to deduct
            int pointsToDeduct = 10; // Same base points as award
            // Same as base points

            // Perform deductions, ensuring values don't go below zero
            transaction.update(userRef, "points", Math.max(0, currentPoints - pointsToDeduct));
            transaction.update(userRef, "goldCoins", Math.max(0L, currentCoins - pointsToDeduct));

            Log.d("Transaction", "Deducting for undoing task " + taskId + ": " + pointsToDeduct + " pts, " + pointsToDeduct + " coins");
            return null; // No XP deducted for tasks
        }).addOnSuccessListener(result -> {
            Log.d("TaskListActivity", "Points/Coins deduction transaction successful for task undo " + taskId);
            loadPoints(); // Refresh points display
        }).addOnFailureListener(e -> {
            Log.e("TaskListActivity", "Failed to deduct points/coin transaction for task undo " + taskId, e);
            if (e instanceof FirebaseFirestoreException && ((FirebaseFirestoreException) e).getCode() == FirebaseFirestoreException.Code.ABORTED) {
                Toast.makeText(this, "Could not update stats: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Error updating points/coins. Please try again.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void awardDailyBonusInTransaction(DocumentReference userRef, String dateString) {
        db.runTransaction(transaction -> { // Use the class db instance
            DocumentSnapshot userSnapshot = transaction.get(userRef);
            if (!userSnapshot.exists()) {
                Log.e("Transaction", "User document does not exist during daily bonus award for user: " + userRef.getId());
                throw new FirebaseFirestoreException("User document not found", FirebaseFirestoreException.Code.ABORTED);
            }

            // Get current awarded dates list, ensure it's mutable
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


            // Double-check inside transaction if already awarded for this date
            if (awardedDates.contains(dateString)) {
                Log.d("Transaction", "Daily bonus for " + dateString + " was already awarded (re-checked in transaction).");
                return null; // Return null to indicate nothing was done
            }

            // Bonus not yet awarded for this date, proceed to award it
            long currentPoints = userSnapshot.contains("points") ? userSnapshot.getLong("points") : 0L;
            int bonusPoints = 25; // Define the bonus amount

            // Add the current date string to the list
            awardedDates.add(dateString);
            Collections.sort(awardedDates); // Keep the list sorted

            // Update points and the awarded dates list in Firestore
            transaction.update(userRef, "points", currentPoints + bonusPoints);
            transaction.update(userRef, "dailyBonusAwardedDates", awardedDates);

            Log.d("Transaction", "Awarding daily bonus (" + bonusPoints + " points) for date: " + dateString);
            return bonusPoints; // Return the amount awarded for success feedback
        }).addOnSuccessListener(bonusAwarded -> {
            // Check if transaction aborted (returned null because already awarded)
            if (bonusAwarded == null) {
                Log.d("TaskListActivity", "Daily bonus transaction finished, but bonus was likely already awarded.");
                return; // Do nothing further
            }
            // Bonus was successfully awarded
            Log.d("TaskListActivity", "Daily bonus awarded successfully in transaction.");
            loadPoints(); // Refresh points display
            // Show a confirmation message to the user
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


    /**
     * Fetches completedDates list from Firestore and uses StreakHelper to update
     * the streak value based on those dates. Should be called on activity start.
     */
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
                // Handle case where user doc is missing (maybe created just now?)
                updateStreakDisplay(); // Update UI with default 0 streak
                return;
            }

            // Safely get completed dates list
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

            // Get today's date string in the correct format/timezone
            String currentDateStr = getCurrentDateString();
            
            // Check if today's tasks are all completed
            boolean todayInCompletedDates = completedDates.contains(actualTodayDateString);
            
            Log.d("RecalcStreak", "Checking streak state - today: " + actualTodayDateString + 
                                  ", in completedDates: " + todayInCompletedDates);

            // First, query all tasks for today to check if we have any
            List<String> finalCompletedDates = completedDates;
            List<String> finalCompletedDates1 = completedDates;
            db.collection("users").document(user.getUid())
                .collection("tasks")
                .whereEqualTo("date", actualTodayDateString)
                .get()
                .addOnSuccessListener(taskSnapshots -> {
                    boolean hasIncompleteTasks = false;
                    
                    // Check if any of today's tasks are incomplete
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
                    
                    // If today has incomplete tasks, ensure today is not in completedDates
                    if (hasIncompleteTasks && todayInCompletedDates) {
                        // Remove today from completed dates
                        finalCompletedDates.remove(actualTodayDateString);
                        Log.d("RecalcStreak", "Removed today from completedDates due to incomplete tasks");
                    }
                    
                    // Now call StreakHelper with the updated completed dates list
                    if (streakHelper != null) {
                        streakHelper.updateStreak(userRef, finalCompletedDates, currentDateStr, new StreakHelper.StreakUpdateCallback() {
                            @Override public void onSuccess(int newStreak) {
                                Log.d("TaskListActivity", "Initial streak recalculation/update successful. New streak: " + newStreak);
                                updateStreakDisplay(); // Update the UI with the potentially new streak value
                            }
                            @Override public void onFailure() {
                                Log.e("TaskListActivity", "Initial streak recalculation/update failed.");
                                // Maybe show an error or just log it
                                updateStreakDisplay(); // Still try to update UI with potentially stale value
                            }
                        });
                    } else {
                        Log.e("TaskListActivity", "streakHelper is null during recalcStreakOnStartup.");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e("RecalcStreak", "Failed to query today's tasks", e);
                    
                    // Fall back to using StreakHelper without task checking
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
            // Handle failure to load user doc
            updateStreakDisplay(); // Attempt to update UI with default/stale value
        });
    }

    // Call saveSubtasksToFirestore(task.getId(), task.getSteps()) after any subtask add/edit/delete in your UI logic.

    public void showEditAllTasksDialog(String recurrenceGroupId) {
        if (recurrenceGroupId == null || recurrenceGroupId.isEmpty()) {
            Toast.makeText(this, "Cannot find recurring tasks", Toast.LENGTH_SHORT).show();
            return;
        }
        
        new AlertDialog.Builder(this)
                .setTitle("Edit All Tasks in Series")
                .setMessage("Are you sure you want to edit all tasks in this recurring series? This will affect all tasks across multiple days.")
                .setPositiveButton("Edit All", (dialog, which) -> {
                    // Find the first task in the series (ideally the one with recurrenceDays > 1)
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
                        // If no task has recurrenceDays > 0, just pick the first one
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

} // End of TaskListActivity class


