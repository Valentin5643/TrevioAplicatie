package com.example.myapplication.aplicatiamea;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

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
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class TaskPlannerActivity extends Activity {

    private static final String TAG = "TaskPlannerActivity";

    private FirebaseFirestore db;
    private FirebaseAuth auth;
    private DocumentReference userRef;
    private CollectionReference tasksRef;
    private TaskAdapter adapter;

    private RecyclerView rvTasks;
    private TextInputEditText etNewTask;
    private MaterialButton btnAddTask;
    private MaterialToolbar topAppBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task_planner);

        // Initialize Firebase
        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = auth.getCurrentUser();

        if (currentUser == null) {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        userRef = db.collection("users").document(currentUser.getUid());
        tasksRef = userRef.collection("tasks");

        // Initialize Views
        rvTasks = findViewById(R.id.rvTasks);
        etNewTask = findViewById(R.id.etNewTask);
        btnAddTask = findViewById(R.id.btnAddTask);
        topAppBar = findViewById(R.id.topAppBar);

        setupToolbar();
        setupRecyclerView();
        setupAddTask();
    }

    private void setupToolbar() {
        topAppBar.setNavigationOnClickListener(v -> finish());
    }

    private void setupRecyclerView() {
        List<Task> taskList = new ArrayList<>();
        adapter = new TaskAdapter(taskList, this, userRef, null, null);
        rvTasks.setLayoutManager(new LinearLayoutManager(this));
        rvTasks.setAdapter(adapter);

        // Listen for Firestore changes and update the adapter
        tasksRef.orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener((snapshots, e) -> {
                if (e != null) {
                    Log.w(TAG, "Listen failed.", e);
                    return;
                }
                List<Task> newTasks = new ArrayList<>();
                for (DocumentSnapshot doc : snapshots) {
                    Task task = convertSnapshotToTask(doc);
                    if (task != null) newTasks.add(task);
                }
                adapter.setTasks(newTasks); // You need to add setTasks() to TaskAdapter if not present
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
                    Task.Subtask subtask = new Task.Subtask(
                        description != null ? description : "",
                        completed != null ? completed : false
                    );
                    subtasks.add(subtask);
                }
                task.setSteps(subtasks);
                Log.d(TAG, "Loaded " + subtasks.size() + " subtasks for task: " + task.getId() + 
                      ", Task completed: " + task.isCompleted());

                // Log each subtask completion status
                for (int i = 0; i < subtasks.size(); i++) {
                    Log.d(TAG, "Subtask " + i + ": " + subtasks.get(i).getDescription() + 
                          " - Completed: " + subtasks.get(i).isCompleted());
                }
            }
        }
        return task;
    }

    private void setupAddTask() {
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
                addTask(description);
            }
        });
    }

    private void addTask(String description) {
        Task newTask = new Task();
        newTask.setName(description);
        newTask.setCompleted(false);
        newTask.setCreatedAt(new java.util.Date());
        
        // Initialize empty subtasks list to ensure the field exists
        newTask.setSteps(new ArrayList<>());
        
        tasksRef.add(newTask)
                .addOnSuccessListener(documentReference -> {
                    Log.d(TAG, "Task added with ID: " + documentReference.getId());
                    etNewTask.setText(""); // Clear input
                    hideKeyboard();

                    // --- Weekly Quest Integration --- 
                    // questManager.updateQuestProgress("plan_tasks", 1); // Uncomment and implement if needed
                    // --- End Quest Integration --- 

                    // Save the full subtasks list for a given task
                    saveSubtasksToFirestore(documentReference.getId(), newTask.getSteps());

                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Error adding task", e);
                    Toast.makeText(TaskPlannerActivity.this, "Error adding task", Toast.LENGTH_SHORT).show();
                });
    }
    
    /**
     * Update a subtask's completion status in Firestore
     */
    public void updateSubtaskCompletion(String taskId, int subtaskIndex, boolean isCompleted) {
        if (taskId == null) {
            Log.e(TAG, "Cannot update subtask - task ID is null");
            return;
        }

        Log.d(TAG, "updateSubtaskCompletion: taskId=" + taskId + ", subtaskIndex=" + subtaskIndex + ", isCompleted=" + isCompleted);

        DocumentReference taskRef = tasksRef.document(taskId);

        db.runTransaction(transaction -> {
            DocumentSnapshot taskSnapshot = transaction.get(taskRef);
            if (!taskSnapshot.exists()) {
                Log.e(TAG, "Task document does not exist: " + taskId);
                throw new FirebaseFirestoreException("Task document does not exist: " + taskId, FirebaseFirestoreException.Code.NOT_FOUND);
            }

            // Get the current task object
            Task task = taskSnapshot.toObject(Task.class);
            if (task == null) {
                Log.e(TAG, "Failed to convert document to Task");
                throw new FirebaseFirestoreException("Failed to convert document to Task", FirebaseFirestoreException.Code.INVALID_ARGUMENT);
            }

            task.setId(taskSnapshot.getId());
            Log.d(TAG, "Retrieved task: " + task.getId());

            // Get the steps list
            List<Task.Subtask> steps = task.getSteps();
            if (steps == null) {
                steps = new ArrayList<>();
                task.setSteps(steps);
                Log.d(TAG, "Created new steps list for task");
            }
            
            // Ensure we have enough steps
            while (steps.size() <= subtaskIndex) {
                steps.add(new Task.Subtask("", false));
                Log.d(TAG, "Added empty subtask to reach index " + subtaskIndex);
            }

            // Get the specific subtask to update
            Task.Subtask stepToUpdate = steps.get(subtaskIndex);
            
            // Log the current state before making changes
            Log.d(TAG, "Current subtask state before update: " + 
                "index=" + subtaskIndex + 
                ", description='" + stepToUpdate.getDescription() + 
                "', completed=" + stepToUpdate.isCompleted());
            
            // Update the completion status
            stepToUpdate.setCompleted(isCompleted);
            
            // Log the new state
            Log.d(TAG, "New subtask state after update: " + 
                "index=" + subtaskIndex + 
                ", description='" + stepToUpdate.getDescription() + 
                "', completed=" + stepToUpdate.isCompleted());

            // Check if all subtasks are now complete
            boolean allSubtasksComplete = true;
            for (int i = 0; i < steps.size(); i++) {
                Task.Subtask step = steps.get(i);
                if (!step.isCompleted()) {
                    allSubtasksComplete = false;
                    Log.d(TAG, "Found incomplete subtask at index " + i + 
                        ", description='" + step.getDescription() + 
                        "', completed=" + step.isCompleted());
                    break;
                }
            }
            Log.d(TAG, "All subtasks complete: " + allSubtasksComplete);

            // Convert steps to a format Firestore can store
            List<Map<String, Object>> stepsAsMaps = new ArrayList<>();
            for (Task.Subtask step : steps) {
                Map<String, Object> stepMap = new HashMap<>();
                stepMap.put("description", step.getDescription());
                stepMap.put("completed", step.isCompleted());
                stepMap.put("stability", step.getStability());
                stepsAsMaps.add(stepMap);
            }

            Log.d(TAG, "Preparing to update Firestore with " + stepsAsMaps.size() + " subtasks");

            // Update the steps field in Firestore
            transaction.update(taskRef, "steps", stepsAsMaps);
            Log.d(TAG, "Updated 'steps' field in transaction");

            // MODIFIED: Don't auto-complete parent task when all subtasks are complete
            // Keep the parent task completion status separate from subtask states
            // The task completion is only changed when the user explicitly checks the main task checkbox
            
            /* Original auto-completion code removed:
            // Also update the task's overall completion status if needed
            Boolean taskCompleted = taskSnapshot.getBoolean("completed");
            if ((taskCompleted == null || !taskCompleted) && allSubtasksComplete) {
                transaction.update(taskRef, "completed", true);
                Log.d(TAG, "All subtasks complete - setting task as complete");
            } else if (taskCompleted != null && taskCompleted && !allSubtasksComplete) {
                transaction.update(taskRef, "completed", false);
                Log.d(TAG, "Not all subtasks complete - setting task as incomplete");
            }
            */
            
            Log.d(TAG, "All subtasks complete: " + allSubtasksComplete + 
                  ", Not changing parent task completion state automatically");

            return steps;
        }).addOnSuccessListener(result -> Log.d(TAG, "Subtask update transaction SUCCESSFUL for taskId=" + taskId +
                   ", subtaskIndex=" + subtaskIndex +
                   ", isCompleted=" + isCompleted)).addOnFailureListener(e -> {
            Log.e(TAG, "Error updating subtask", e);
            Toast.makeText(this, "Error updating subtask: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Update a task's completion status while preserving subtask states
     */
    public void updateTaskCompletion(String taskId, boolean isCompleted) {
        if (taskId == null) {
            Log.e(TAG, "Cannot update task - task ID is null");
            return;
        }
        
        DocumentReference taskRef = tasksRef.document(taskId);
        Log.d(TAG, "Updating task completion: " + taskId + ", completed: " + isCompleted);
        
        // Use a transaction to ensure atomicity
        db.runTransaction(transaction -> {
            DocumentSnapshot taskSnapshot = transaction.get(taskRef);
            if (!taskSnapshot.exists()) {
                throw new FirebaseFirestoreException("Task document does not exist: " + taskId, FirebaseFirestoreException.Code.NOT_FOUND);
            }
            
            // Update only the completion status field, preserving subtasks
            transaction.update(taskRef, "completed", isCompleted);
            
            return true;
        }).addOnSuccessListener(result -> Log.d(TAG, "Task completion update successful")).addOnFailureListener(e -> {
            Log.e(TAG, "Error updating task completion", e);
            Toast.makeText(this, "Error updating task: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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

    // Save the full subtasks list for a given task
    private void saveSubtasksToFirestore(String taskId, List<Task.Subtask> subtasks) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        DocumentReference taskRef = FirebaseFirestore.getInstance()
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
        taskRef.update("steps", stepsAsMaps);
    }
} 