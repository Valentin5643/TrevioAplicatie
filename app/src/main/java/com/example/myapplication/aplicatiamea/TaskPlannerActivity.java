package com.example.myapplication.aplicatiamea;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.view.View;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import com.example.myapplication.aplicatiamea.util.ThemeHelper;

/**
 * Quick task planning screen - lets users plan and track tasks without dates.
 */
public class TaskPlannerActivity extends Activity {

    private static final String TAG = "TaskPlanner";
    
    // Firebase stuff
    private FirebaseFirestore db;
    private FirebaseAuth auth;
    private DocumentReference userRef;
    private CollectionReference tasksRef;
    private ListenerRegistration taskListener;
    
    // UI components
    private TaskAdapter adapter;
    private RecyclerView rvTasks;
    private TextInputEditText etNewTask;
    private MaterialButton btnAddTask;
    private MaterialToolbar topAppBar;
    private ProgressBar loadingBar;
    private View emptyStateView;
    
    // Track task update operations
    private final AtomicBoolean taskUpdateInProgress = new AtomicBoolean(false);
    private final Map<String, Long> lastUpdateTimes = new HashMap<>();
    private static final long UPDATE_THROTTLE_MS = 500; // Prevent double clicks

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyUserTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task_planner);

        // Find UI components
        rvTasks = findViewById(R.id.rvTasks);
        etNewTask = findViewById(R.id.etNewTask);
        btnAddTask = findViewById(R.id.btnAddTask);
        topAppBar = findViewById(R.id.topAppBar);
        loadingBar = findViewById(R.id.progressBar);
        emptyStateView = findViewById(R.id.emptyView);
        
        if (emptyStateView == null) {
            Log.w(TAG, "Empty state view not found in layout");
        } else {
            emptyStateView.setVisibility(View.GONE);
        }
        
        showLoading(true);
        
        // Setup Firebase stuff
        setupFirebase();
        
        // Setup UI components
        setupToolbar();
        setupRecyclerView();
        setupAddTask();
    }
    
    private void setupFirebase() {
        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = auth.getCurrentUser();

        if (currentUser == null) {
            Toast.makeText(this, "Not logged in. Sign in first.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        userRef = db.collection("users").document(currentUser.getUid());
        tasksRef = userRef.collection("tasks");
    }

    private void setupToolbar() {
        topAppBar.setNavigationOnClickListener(v -> finish());
    }

    private void setupRecyclerView() {
        List<Task> taskList = new ArrayList<>();
        adapter = new TaskAdapter(taskList, this, userRef);
        rvTasks.setLayoutManager(new LinearLayoutManager(this));
        rvTasks.setAdapter(adapter);

        // Set up listener for task changes
        startListeningForTasks();
    }
    
    private void startListeningForTasks() {
        // Clean up existing listener first
        if (taskListener != null) {
            taskListener.remove();
        }
        
        // New query - only get tasks without dates (daily planner tasks)
        taskListener = tasksRef
            .whereEqualTo("date", null)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener((snapshots, e) -> {
                showLoading(false);
                
                // Handle errors
                if (e != null) {
                    Log.w(TAG, "Task listen failed", e);
                    if (!isNetworkAvailable()) {
                        Toast.makeText(this, "No connection - using cached data", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Error loading tasks: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                
                if (snapshots == null || snapshots.isEmpty()) {
                    // Show empty state if no tasks
                    showEmptyState(true);
                    adapter.setTasks(new ArrayList<>());
                    return;
                }
                
                // Found tasks, convert and display them
                showEmptyState(false);
                List<Task> newTasks = new ArrayList<>();
                
                for (DocumentSnapshot doc : snapshots) {
                    Task task = convertSnapshotToTask(doc);
                    if (task != null) {
                        newTasks.add(task);
                    }
                }
                
                adapter.setTasks(newTasks);
            });
    }
    
    // Custom converter to ensure subtasks are properly loaded
    private Task convertSnapshotToTask(DocumentSnapshot doc) {
        Task task = doc.toObject(Task.class);
        if (task != null) {
            task.setId(doc.getId());
            
            // Explicitly retrieve and set the steps/subtasks with their completion status
            List<Map<String, Object>> rawSteps = (List<Map<String, Object>>) doc.get("steps");
            if (rawSteps != null && !rawSteps.isEmpty()) {
                List<Task.Subtask> subtasks = new ArrayList<>();
                for (Map<String, Object> stepMap : rawSteps) {
                    String description = (String) stepMap.get("description");
                    Boolean completed = (Boolean) stepMap.get("completed");
                    Number stabilityObj = (Number) stepMap.get("stability");
                    int stability = stabilityObj != null ? stabilityObj.intValue() : 0;
                    
                    Task.Subtask subtask = new Task.Subtask(
                        description != null ? description : "",
                        completed != null ? completed : false,
                        stability
                    );
                    subtasks.add(subtask);
                }
                task.setSteps(subtasks);
            } else {
                // Always ensure we have an initialized list
                task.setSteps(new ArrayList<>());
            }
        }
        return task;
    }

    private void setupAddTask() {
        // Disable add button until we have text
        btnAddTask.setEnabled(false);
        
        etNewTask.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                btnAddTask.setEnabled(s != null && s.toString().trim().length() > 0);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnAddTask.setOnClickListener(v -> {
            String description = Objects.requireNonNull(etNewTask.getText()).toString().trim();
            if (!description.isEmpty()) {
                // Check for network before trying to add
                if (!isNetworkAvailable()) {
                    Toast.makeText(this, "No internet connection, can't add task now", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                addTask(description);
                btnAddTask.setEnabled(false); // Prevent double submits
            }
        });
    }

    private void addTask(String description) {
        // Show quick loading state
        btnAddTask.setText("Adding...");
        
        Task newTask = new Task();
        newTask.setName(description);
        newTask.setCompleted(false);
        newTask.setCreatedAt(new java.util.Date());
        
        // Initialize empty subtasks list to ensure the field exists
        newTask.setSteps(new ArrayList<>());
        
        tasksRef.add(newTask)
                .addOnSuccessListener(documentReference -> {
                    Log.d(TAG, "Added task: " + documentReference.getId());
                    etNewTask.setText(""); // Clear input
                    hideKeyboard();
                    btnAddTask.setText("Add");
                    btnAddTask.setEnabled(false); // Keep disabled until new text

                    // Don't need to save empty subtasks again
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Failed to add task", e);
                    btnAddTask.setText("Add");
                    btnAddTask.setEnabled(true);
                    
                    // Better user feedback based on error
                    String errorMsg = "Couldn't save task";
                    if (e.getMessage() != null && e.getMessage().contains("permission")) {
                        errorMsg = "You don't have permission to add tasks";
                    } else if (!isNetworkAvailable()) {
                        errorMsg = "No internet connection";
                    }
                    Toast.makeText(TaskPlannerActivity.this, errorMsg, Toast.LENGTH_SHORT).show();
                });
    }
    
    /**
     * Updates a subtask completion state. Uses transactions to make sure things 
     * don't get messed up if multiple updates happen at the same time.
     */
    public void updateSubtaskCompletion(String taskId, int subtaskIndex, boolean isCompleted) {
        if (taskId == null) {
            Log.e(TAG, "Can't update subtask - missing task ID");
            return;
        }
        
        // Prevent rapid clicks on the same item
        String key = taskId + "_" + subtaskIndex;
        long now = System.currentTimeMillis();
        Long lastUpdate = lastUpdateTimes.get(key);
        if (lastUpdate != null && now - lastUpdate < UPDATE_THROTTLE_MS) {
            Log.d(TAG, "Ignored rapid subtask update: " + key);
            return;
        }
        lastUpdateTimes.put(key, now);

        DocumentReference taskRef = tasksRef.document(taskId);

        // Network check
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "No connection - changes will sync when online", 
                           Toast.LENGTH_SHORT).show();
        }

        db.runTransaction(transaction -> {
            DocumentSnapshot taskSnapshot = transaction.get(taskRef);
            if (!taskSnapshot.exists()) {
                throw new FirebaseFirestoreException("Task not found: " + taskId, 
                                         FirebaseFirestoreException.Code.NOT_FOUND);
            }

            // Get the current task object
            Task task = taskSnapshot.toObject(Task.class);
            if (task == null) {
                throw new FirebaseFirestoreException("Couldn't parse task", 
                                         FirebaseFirestoreException.Code.INVALID_ARGUMENT);
            }

            task.setId(taskSnapshot.getId());
            
            // Make sure we have a steps list
            List<Task.Subtask> steps = task.getSteps();
            if (steps == null) {
                steps = new ArrayList<>();
                task.setSteps(steps);
            }
            
            // Make sure there are enough steps
            while (steps.size() <= subtaskIndex) {
                steps.add(new Task.Subtask("", false));
            }

            // Update the subtask
            Task.Subtask stepToUpdate = steps.get(subtaskIndex);
            stepToUpdate.setCompleted(isCompleted);
            
            // Convert steps to Firestore format
            List<Map<String, Object>> stepsAsMaps = new ArrayList<>();
            for (Task.Subtask step : steps) {
                Map<String, Object> stepMap = new HashMap<>();
                stepMap.put("description", step.getDescription());
                stepMap.put("completed", step.isCompleted());
                stepMap.put("stability", step.getStability());
                stepsAsMaps.add(stepMap);
            }

            // Update Firestore (just the steps array)
            transaction.update(taskRef, "steps", stepsAsMaps);
            
            // We used to auto-complete parent tasks when all subtasks were done,
            // but got rid of that since it was confusing - leaving this comment
            
            return steps;
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to update subtask", e);
            
            String errorMsg = "Couldn't update subtask";
            if (e instanceof FirebaseFirestoreException) {
                FirebaseFirestoreException ffe = (FirebaseFirestoreException) e;
                if (ffe.getCode() == FirebaseFirestoreException.Code.NOT_FOUND) {
                    errorMsg = "Task was deleted";
                } else if (ffe.getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    errorMsg = "You don't have permission to edit this task";
                }
            }
            
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Updates main task completion status
     */
    public void updateTaskCompletion(String taskId, boolean isCompleted) {
        // Don't allow updates while one is in progress
        if (!taskUpdateInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Task update already in progress, ignored");
            return;
        }
        
        if (taskId == null) {
            taskUpdateInProgress.set(false);
            return;
        }
        
        DocumentReference taskRef = tasksRef.document(taskId);
        
        // Use transaction to prevent glitchy updates
        db.runTransaction(transaction -> {
            DocumentSnapshot taskSnapshot = transaction.get(taskRef);
            if (!taskSnapshot.exists()) {
                throw new FirebaseFirestoreException("Task " + taskId + " doesn't exist", 
                                         FirebaseFirestoreException.Code.NOT_FOUND);
            }
            
            // Just update the main task completed status
            transaction.update(taskRef, "completed", isCompleted);
            
            // Also update timestamp so it appears at right spot in lists
            transaction.update(taskRef, "updatedAt", com.google.firebase.Timestamp.now());
            
            return true;
        }).addOnSuccessListener(result -> {
            Log.d(TAG, "Task completion update success");
            taskUpdateInProgress.set(false);
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to update task", e);
            taskUpdateInProgress.set(false);
            
            // Only show errors if we're marking complete (less annoying)
            if (isCompleted) {
                Toast.makeText(this, "Couldn't update task: " + e.getMessage(), 
                              Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View view = getCurrentFocus();
        if (view == null) {
            view = new View(this);
        }
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    /**
     * Updates subtasks for a task in firestore
     */
    private void saveSubtasksToFirestore(String taskId, List<Task.Subtask> subtasks) {
        if (subtasks == null || subtasks.isEmpty()) {
            // Don't waste time saving empty list
            return;
        }
        
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) return;
        
        DocumentReference taskRef = db
            .collection("users").document(user.getUid())
            .collection("tasks").document(taskId);

        List<Map<String, Object>> stepsAsMaps = new ArrayList<>();
        for (Task.Subtask step : subtasks) {
            Map<String, Object> stepMap = new HashMap<>();
            stepMap.put("description", step.getDescription());
            stepMap.put("completed", step.isCompleted());
            stepMap.put("stability", step.getStability());
            stepsAsMaps.add(stepMap);
        }
        
        taskRef.update("steps", stepsAsMaps)
            .addOnFailureListener(e -> Log.e(TAG, "Failed to save subtasks: " + e.getMessage()));
    }
    
    /**
     * Checks if we have an internet connection
     */
    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) 
            getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (connectivityManager == null) {
            return false;
        }
        
        NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }
    
    /**
     * Shows/hides loading indicator
     */
    private void showLoading(boolean isLoading) {
        if (loadingBar != null) {
            loadingBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        }
    }
    
    /**
     * Shows/hides empty state view
     */
    private void showEmptyState(boolean isEmpty) {
        if (emptyStateView != null) {
            emptyStateView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up listener to prevent memory leaks
        if (taskListener != null) {
            taskListener.remove();
            taskListener = null;
        }
    }
} 