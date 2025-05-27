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

public class TaskPlannerActivity extends Activity {

    private static final String TAG = "TaskPlanner";
    
    // Firebase stuff
    private FirebaseFirestore db;
    private FirebaseAuth auth;
    private DocumentReference userRef;
    private CollectionReference tasksRef;
    private ListenerRegistration taskListener;

    private TaskAdapter adapter;
    private RecyclerView rvTasks;
    private TextInputEditText etNewTask;
    private MaterialButton btnAddTask;
    private MaterialToolbar topAppBar;
    private ProgressBar loadingBar;
    private View emptyStateView;

    private final AtomicBoolean taskUpdateInProgress = new AtomicBoolean(false);
    private final Map<String, Long> lastUpdateTimes = new HashMap<>();

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

        setupFirebase();

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

        startListeningForTasks();
    }
    
    private void startListeningForTasks() {
        if (taskListener != null) {
            taskListener.remove();
        }

        taskListener = tasksRef
            .whereEqualTo("date", null)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener((snapshots, e) -> {
                showLoading(false);

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
                    showEmptyState(true);
                    adapter.setTasks(new ArrayList<>());
                    return;
                }

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

    private Task convertSnapshotToTask(DocumentSnapshot doc) {
        Task task = doc.toObject(Task.class);
        if (task != null) {
            task.setId(doc.getId());

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
                task.setSteps(new ArrayList<>());
            }
        }
        return task;
    }

    private void setupAddTask() {
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
                if (!isNetworkAvailable()) {
                    Toast.makeText(this, "No internet connection, can't add task now", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                addTask(description);
                btnAddTask.setEnabled(false);
            }
        });
    }

    private void addTask(String description) {
        btnAddTask.setText("Adding...");
        
        Task newTask = new Task();
        newTask.setName(description);
        newTask.setCompleted(false);
        newTask.setCreatedAt(new java.util.Date());

        newTask.setSteps(new ArrayList<>());
        
        tasksRef.add(newTask)
                .addOnSuccessListener(documentReference -> {
                    Log.d(TAG, "Added task: " + documentReference.getId());
                    etNewTask.setText("");
                    hideKeyboard();
                    btnAddTask.setText("Add");
                    btnAddTask.setEnabled(false);

                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Failed to add task", e);
                    btnAddTask.setText("Add");
                    btnAddTask.setEnabled(true);

                    String errorMsg = "Couldn't save task";
                    if (e.getMessage() != null && e.getMessage().contains("permission")) {
                        errorMsg = "You don't have permission to add tasks";
                    } else if (!isNetworkAvailable()) {
                        errorMsg = "No internet connection";
                    }
                    Toast.makeText(TaskPlannerActivity.this, errorMsg, Toast.LENGTH_SHORT).show();
                });
    }

    public void updateTaskCompletion(String taskId, boolean isCompleted) {
        if (!taskUpdateInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Task update already in progress, ignored");
            return;
        }
        
        if (taskId == null) {
            taskUpdateInProgress.set(false);
            return;
        }
        
        DocumentReference taskRef = tasksRef.document(taskId);

        db.runTransaction(transaction -> {
            DocumentSnapshot taskSnapshot = transaction.get(taskRef);
            if (!taskSnapshot.exists()) {
                throw new FirebaseFirestoreException("Task " + taskId + " doesn't exist", 
                                         FirebaseFirestoreException.Code.NOT_FOUND);
            }

            transaction.update(taskRef, "completed", isCompleted);

            transaction.update(taskRef, "updatedAt", com.google.firebase.Timestamp.now());
            
            return true;
        }).addOnSuccessListener(result -> {
            Log.d(TAG, "Task completion update success");
            taskUpdateInProgress.set(false);
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to update task", e);
            taskUpdateInProgress.set(false);

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

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) 
            getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (connectivityManager == null) {
            return false;
        }
        
        NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }

    private void showLoading(boolean isLoading) {
        if (loadingBar != null) {
            loadingBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        }
    }

    private void showEmptyState(boolean isEmpty) {
        if (emptyStateView != null) {
            emptyStateView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (taskListener != null) {
            taskListener.remove();
            taskListener = null;
        }
    }
} 