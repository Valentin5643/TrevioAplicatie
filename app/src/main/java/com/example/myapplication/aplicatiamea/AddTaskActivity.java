package com.example.myapplication.aplicatiamea;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;
import com.google.firebase.firestore.DocumentSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import androidx.annotation.NonNull;
import android.graphics.Paint;
import android.widget.ImageButton;
import android.view.LayoutInflater;
import android.view.ViewGroup;

// Define the interface outside the classes
interface OnSubtaskInteractionListener {

    void onSubtaskDeleteClicked(int position);
}

public class AddTaskActivity extends AppCompatActivity implements OnSubtaskInteractionListener {
    private EditText etTaskName;
    private EditText etTaskDescription;
    private Spinner spinnerDifficulty;
    private Spinner spinnerPriority;
    private TextView tvSelectedTime;
    private TextView tvSelectedDeadline;
    private Button btnSelectTime;
    private Button btnSetDeadline;
    private Button btnSaveTask;
    private Button btnSetRecurrence;
    private TextView tvSelectedRecurrence;

    private RecyclerView rvTaskSteps;
    private EditText etNewStep;
    private Button btnAddStep;
    private SubtaskAdapter subtaskAdapter;
    private List<Task.Subtask> subtaskList;

    private Calendar selectedDateCalendar;
    private Calendar selectedTimeCalendar;
    private Calendar selectedDeadlineCalendar;

    private FirebaseFirestore db;
    private FirebaseAuth auth;

    private String taskId;
    private Task existingTask;
    private int recurrenceDays = 1;
    private boolean isEditingSeries = false;
    private String recurrenceGroupId = null;

    private ActivityResultLauncher<Intent> recurrenceActivityResultLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_task);

        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();

        etTaskName = findViewById(R.id.etTaskName);
        etTaskDescription = findViewById(R.id.etTaskDescription);
        spinnerDifficulty = findViewById(R.id.spinnerDifficulty);
        spinnerPriority = findViewById(R.id.spinnerPriority);
        tvSelectedTime = findViewById(R.id.tvSelectedTime);
        tvSelectedDeadline = findViewById(R.id.tvSelectedDeadline);
        btnSelectTime = findViewById(R.id.btnSelectTime);
        btnSetDeadline = findViewById(R.id.btnSetDeadline);
        btnSaveTask = findViewById(R.id.btnSaveTask);
        btnSetRecurrence = findViewById(R.id.btnSetRecurrence);
        tvSelectedRecurrence = findViewById(R.id.tvSelectedRecurrence);

        rvTaskSteps = findViewById(R.id.rvTaskSteps);
        etNewStep = findViewById(R.id.etNewStep);
        btnAddStep = findViewById(R.id.btnAddStep);

        selectedDateCalendar = Calendar.getInstance();
        selectedTimeCalendar = Calendar.getInstance();
        selectedDeadlineCalendar = null;

        setupDifficultySpinner();
        setupPrioritySpinner();

        setupStepsRecyclerView();

        setupClickListeners();

        recurrenceActivityResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        recurrenceDays = result.getData().getIntExtra("RECURRENCE_DAYS", 1);
                        updateRecurrenceDisplay();
                    }
                });

        taskId = getIntent().getStringExtra("TASK_ID");
        isEditingSeries = getIntent().getBooleanExtra("EDIT_SERIES", false);
        
        if (taskId != null) {
            if (isEditingSeries) {
                setTitle("Edit Recurring Series");
                btnSetRecurrence.setVisibility(View.VISIBLE);
                tvSelectedRecurrence.setVisibility(View.VISIBLE);
            } else {
                setTitle("Edit Task");
                btnSetRecurrence.setVisibility(View.GONE);
                tvSelectedRecurrence.setVisibility(View.GONE);
            }
            loadExistingTaskData();
        } else {
            setTitle("Add New Task");
            selectedDeadlineCalendar = Calendar.getInstance();
            selectedDeadlineCalendar.set(Calendar.HOUR_OF_DAY, 23);
            selectedDeadlineCalendar.set(Calendar.MINUTE, 59);
            selectedDeadlineCalendar.set(Calendar.SECOND, 59);
            updateDeadlineDisplay();
            updateRecurrenceDisplay();
        }
    }

    private void setupDifficultySpinner() {
        List<String> difficultyList = new ArrayList<>();
        for (Task.TaskDifficulty d : Task.TaskDifficulty.values()) {
            difficultyList.add(d.name());
        }
        ArrayAdapter<String> diffAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, difficultyList);
        diffAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDifficulty.setAdapter(diffAdapter);
    }
    
    private void setupPrioritySpinner() {
        List<String> priorityList = new ArrayList<>();
        for (Task.TaskPriority p : Task.TaskPriority.values()) {
            priorityList.add(p.name());
        }
        ArrayAdapter<String> priorityAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, priorityList);
        priorityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPriority.setAdapter(priorityAdapter);
        
        int highPriorityPosition = priorityAdapter.getPosition(Task.TaskPriority.HIGH.name());
        if (highPriorityPosition >= 0) {
            spinnerPriority.setSelection(highPriorityPosition);
        }
    }

    private void setupStepsRecyclerView() {
        subtaskList = new ArrayList<>();
        subtaskAdapter = new SubtaskAdapter(subtaskList, this);
        rvTaskSteps.setLayoutManager(new LinearLayoutManager(this));
        rvTaskSteps.setAdapter(subtaskAdapter);
        rvTaskSteps.setNestedScrollingEnabled(false);
    }

    private void setupClickListeners() {
        btnSelectTime.setOnClickListener(v -> showTimePicker(selectedTimeCalendar, this::updateTimeDisplay));
        btnSetDeadline.setOnClickListener(v -> showDeadlinePicker());
        btnAddStep.setOnClickListener(v -> addStep());
        btnSaveTask.setOnClickListener(v -> saveTask());
        btnSetRecurrence.setOnClickListener(v -> openRecurrenceSelection());
    }

    private void openRecurrenceSelection() {
        Intent intent = new Intent(this, SelectRecurrenceActivity.class);
        intent.putExtra("RECURRENCE_DAYS", recurrenceDays);
        recurrenceActivityResultLauncher.launch(intent);
    }

    private void updateRecurrenceDisplay() {
        if (recurrenceDays > 1) {
            tvSelectedRecurrence.setText("Repeats daily for " + recurrenceDays + " days");
        } else {
            tvSelectedRecurrence.setText("No Repetition");
        }
    }

    private interface TimeUpdateCallback {
        void onUpdate();
    }

    private void showTimePicker(Calendar calendar, TimeUpdateCallback callback) {
        new TimePickerDialog(this, (view, hourOfDay, minute) -> {
            calendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
            calendar.set(Calendar.MINUTE, minute);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            callback.onUpdate();
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show();
    }

    private void showDeadlinePicker() {
        if (selectedDeadlineCalendar == null) {
            selectedDeadlineCalendar = Calendar.getInstance();
        }
        new DatePickerDialog(this, (dateView, year, month, dayOfMonth) -> {
            selectedDeadlineCalendar.set(Calendar.YEAR, year);
            selectedDeadlineCalendar.set(Calendar.MONTH, month);
            selectedDeadlineCalendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            if (selectedTimeCalendar != null) {
                selectedDeadlineCalendar.set(Calendar.HOUR_OF_DAY, selectedTimeCalendar.get(Calendar.HOUR_OF_DAY));
                selectedDeadlineCalendar.set(Calendar.MINUTE, selectedTimeCalendar.get(Calendar.MINUTE));
                selectedDeadlineCalendar.set(Calendar.SECOND, 0);
                selectedDeadlineCalendar.set(Calendar.MILLISECOND, 0);
            } else {
                selectedDeadlineCalendar.set(Calendar.HOUR_OF_DAY, 0);
                selectedDeadlineCalendar.set(Calendar.MINUTE, 0);
                selectedDeadlineCalendar.set(Calendar.SECOND, 0);
                selectedDeadlineCalendar.set(Calendar.MILLISECOND, 0);
            }
            updateDeadlineDisplay();
        }, selectedDeadlineCalendar.get(Calendar.YEAR), selectedDeadlineCalendar.get(Calendar.MONTH), selectedDeadlineCalendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateTimeDisplay() {
        SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        tvSelectedTime.setText(timeFormat.format(selectedTimeCalendar.getTime()));
    }

    private void updateDeadlineDisplay() {
        if (selectedDeadlineCalendar != null) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM dd, yyyy", Locale.getDefault());
            tvSelectedDeadline.setText(dateFormat.format(selectedDeadlineCalendar.getTime()));
        } else {
            tvSelectedDeadline.setText("Not Set");
        }
    }

    private void addStep() {
        String stepDescription = etNewStep.getText().toString().trim();
        if (!stepDescription.isEmpty()) {
            Task.Subtask newSubtask = new Task.Subtask(stepDescription, false);
            subtaskList.add(newSubtask);
            subtaskAdapter.notifyItemInserted(subtaskList.size() - 1);
            etNewStep.setText("");
        } else {
            Toast.makeText(this, "Step description cannot be empty", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveStepsToFirestore(String taskId, List<Task.Subtask> steps) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        DocumentReference taskRef = FirebaseFirestore.getInstance()
            .collection("users").document(user.getUid())
            .collection("tasks").document(taskId);

        List<Map<String, Object>> stepsAsMaps = new ArrayList<>();
        for (Task.Subtask step : steps) {
            Map<String, Object> stepMap = new HashMap<>();
            stepMap.put("description", step.getDescription());
            stepMap.put("completed", step.isCompleted());
            stepMap.put("stability", step.getStability());
            stepsAsMaps.add(stepMap);
        }
        taskRef.update("steps", stepsAsMaps);
    }

    @Override
    public void onSubtaskDeleteClicked(int position) {
        if (position >= 0 && position < subtaskList.size()) {
            subtaskList.remove(position);
            subtaskAdapter.notifyItemRemoved(position);
            subtaskAdapter.notifyItemRangeChanged(position, subtaskList.size());
        }
    }

    private void loadExistingTaskData() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Authentication error", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        DocumentReference taskRef = db.collection("users").document(user.getUid()).collection("tasks").document(taskId);
        taskRef.get().addOnSuccessListener(documentSnapshot -> {
            if (documentSnapshot.exists()) {
                existingTask = documentSnapshot.toObject(Task.class);
                if (existingTask != null) {
                    populateUIFromTask(existingTask);
                } else {
                    Toast.makeText(this, "Failed to load task data.", Toast.LENGTH_SHORT).show();
                    finish();
                }
            } else {
                Toast.makeText(this, "Task not found.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }).addOnFailureListener(e -> {
            Toast.makeText(this, "Error loading task: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e("AddTaskActivity", "Error loading task", e);
            finish();
        });
    }

    private void populateUIFromTask(Task task) {
        etTaskName.setText(task.getName());
        etTaskDescription.setText(task.getDescription());

        ArrayAdapter<String> diffAdapter = (ArrayAdapter<String>) spinnerDifficulty.getAdapter();
        if (diffAdapter != null) {
            int spinnerPosition = diffAdapter.getPosition(task.getDifficulty());
            spinnerDifficulty.setSelection(Math.max(spinnerPosition, 0));
        }
        
        ArrayAdapter<String> priorityAdapter = (ArrayAdapter<String>) spinnerPriority.getAdapter();
        if (priorityAdapter != null && task.getPriority() != null) {
            int priorityPosition = priorityAdapter.getPosition(task.getPriority());
            spinnerPriority.setSelection(Math.max(priorityPosition, 0));
        } else {
            int mediumPosition = priorityAdapter.getPosition(Task.TaskPriority.MEDIUM.name());
            spinnerPriority.setSelection(Math.max(mediumPosition, 0));
        }

        if (task.getDateTimeTimestamp() > 0) {
            selectedDateCalendar.setTimeInMillis(task.getDateTimeTimestamp());
            selectedTimeCalendar.setTimeInMillis(task.getDateTimeTimestamp());
            updateTimeDisplay();
        } else {
            tvSelectedTime.setText("Set Time");
        }

        if (task.getDeadlineTimestamp() > 0) {
            selectedDeadlineCalendar = Calendar.getInstance();
            selectedDeadlineCalendar.setTimeInMillis(task.getDeadlineTimestamp());
            updateDeadlineDisplay();
        } else {
            selectedDeadlineCalendar = null;
            updateDeadlineDisplay();
        }

        if (task.getSteps() != null) {
            subtaskList.clear();
            subtaskList.addAll(task.getSteps());
            subtaskAdapter.notifyDataSetChanged();
        }
        
        // Load recurrence information if this is part of a recurring series
        if (task.getRecurrenceGroupId() != null) {
            recurrenceGroupId = task.getRecurrenceGroupId();
            
            // If editing the whole series, make the recurrence field visible and editable
            if (isEditingSeries) {
                recurrenceDays = task.getRecurrenceDays();
                if (recurrenceDays <= 1) {
                    // If this specific task doesn't have recurrenceDays set, use a default
                    recurrenceDays = 2;
                }
                tvSelectedRecurrence.setVisibility(View.VISIBLE);
                btnSetRecurrence.setVisibility(View.VISIBLE);
            } else if (task.getRecurrenceDays() > 1) {
                // Just viewing a single task
                recurrenceDays = task.getRecurrenceDays();
                tvSelectedRecurrence.setVisibility(View.VISIBLE);
                btnSetRecurrence.setVisibility(View.GONE); // Not editable for individual task
            } else {
                recurrenceDays = 0;
            }
            updateRecurrenceDisplay();
        } else {
            recurrenceGroupId = null;
            recurrenceDays = 0;
            updateRecurrenceDisplay();
        }
    }

    private void saveTask() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Authentication required", Toast.LENGTH_SHORT).show();
            return;
        }

        String taskName = etTaskName.getText().toString().trim();
        if (taskName.isEmpty()) {
            etTaskName.setError("Task name required");
            etTaskName.requestFocus();
            return;
        }
        String taskDescription = etTaskDescription.getText().toString().trim();
        String selectedDiff = spinnerDifficulty.getSelectedItem().toString();
        String selectedPriority = spinnerPriority.getSelectedItem().toString();

        if (isEditingSeries && recurrenceGroupId != null) {
            // Editing all tasks in a series
            updateTaskSeries(user, taskName, taskDescription, selectedDiff, selectedPriority);
        } else if (taskId != null) {
            // Editing a single task
            saveSingleTask(user, taskName, taskDescription, selectedDiff, selectedPriority, recurrenceGroupId);
        } else if (recurrenceDays > 1) {
            // Creating a new recurring task series
            saveRecurringTasks(user, taskName, taskDescription, selectedDiff, selectedPriority);
        } else {
            // Creating a new single task
            saveSingleTask(user, taskName, taskDescription, selectedDiff, selectedPriority, null);
        }
    }

    private void updateTaskSeries(FirebaseUser user, String taskName, String taskDescription, 
                                  String selectedDiff, String selectedPriority) {
        if (recurrenceGroupId == null) {
            Toast.makeText(this, "Cannot find recurring tasks to update", Toast.LENGTH_SHORT).show();
            return;
        }

        // First get all tasks in the series
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
                final int[] taskCount = {0};

                for (DocumentSnapshot doc : queryDocumentSnapshots) {
                    Task seriesTask = doc.toObject(Task.class);
                    if (seriesTask != null) {
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("name", taskName);
                        updates.put("description", taskDescription);
                        updates.put("difficulty", selectedDiff);
                        updates.put("priority", selectedPriority);
                        updates.put("steps", subtaskList);
                        updates.put("updatedAt", FieldValue.serverTimestamp());
                        
                        // Update main attributes but preserve individual task's date/time/completion
                        batch.update(doc.getReference(), updates);
                        taskCount[0]++;
                    }
                }

                // For the first task in the series, also update the recurrenceDays value
                if (taskCount[0] > 0 && taskId != null) {
                    batch.update(
                        db.collection("users").document(user.getUid()).collection("tasks").document(taskId),
                        "recurrenceDays", recurrenceDays
                    );
                }

                batch.commit()
                    .addOnSuccessListener(aVoid -> {
                        Toast.makeText(this, "Updated " + taskCount[0] + " tasks in series", Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(this, "Error updating task series: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        Log.e("AddTaskActivity", "Error updating task series", e);
                    });
            })
            .addOnFailureListener(e -> {
                Toast.makeText(this, "Error loading tasks in series: " + e.getMessage(), Toast.LENGTH_LONG).show();
                Log.e("AddTaskActivity", "Error loading tasks in series", e);
            });
    }

    private void saveRecurringTasks(FirebaseUser user, String taskName, String taskDescription, String selectedDiff, String selectedPriority) {
        WriteBatch batch = db.batch();
        String recurrenceGroupId = UUID.randomUUID().toString();
        Calendar taskCalendar = Calendar.getInstance();

        int hourOfDay = 23;
        int minute = 59;
        if (selectedDeadlineCalendar != null && selectedTimeCalendar != null) {
            hourOfDay = selectedTimeCalendar.get(Calendar.HOUR_OF_DAY);
            minute = selectedTimeCalendar.get(Calendar.MINUTE);
        } else if (selectedTimeCalendar != null) {
            hourOfDay = selectedTimeCalendar.get(Calendar.HOUR_OF_DAY);
            minute = selectedTimeCalendar.get(Calendar.MINUTE);
        }

        for (int i = 0; i < recurrenceDays; i++) {
            Calendar currentDayCalendar = (Calendar) taskCalendar.clone();
            if (i > 0) {
                currentDayCalendar.add(Calendar.DAY_OF_YEAR, i);
            }
            if (selectedDeadlineCalendar != null && i == 0) {
                currentDayCalendar.setTimeInMillis(selectedDeadlineCalendar.getTimeInMillis());
            } else if (selectedDeadlineCalendar != null) {
                Calendar incrementedDeadline = (Calendar) selectedDeadlineCalendar.clone();
                incrementedDeadline.add(Calendar.DAY_OF_YEAR, i);
                currentDayCalendar.setTimeInMillis(incrementedDeadline.getTimeInMillis());
            }

            currentDayCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
            currentDayCalendar.set(Calendar.MINUTE, minute);
            currentDayCalendar.set(Calendar.SECOND, 0);
            currentDayCalendar.set(Calendar.MILLISECOND, 0);
            long deadlineTimestamp = currentDayCalendar.getTimeInMillis();

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            String dateString = sdf.format(new Date(deadlineTimestamp));

            Map<String, Object> taskData = new HashMap<>();
            taskData.put("name", taskName);
            taskData.put("description", taskDescription);
            taskData.put("difficulty", selectedDiff);
            taskData.put("priority", selectedPriority);
            taskData.put("dateTimeTimestamp", deadlineTimestamp);
            taskData.put("deadlineTimestamp", deadlineTimestamp);
            taskData.put("date", dateString);
            taskData.put("steps", subtaskList);
            taskData.put("completed", false);
            taskData.put("userId", user.getUid());
            taskData.put("createdAt", FieldValue.serverTimestamp());
            taskData.put("updatedAt", FieldValue.serverTimestamp());
            taskData.put("recurrenceGroupId", recurrenceGroupId);
            
            // Store the recurrenceDays value only on the first task of the series
            if (i == 0) {
                taskData.put("recurrenceDays", recurrenceDays);
            } else {
                taskData.put("recurrenceDays", 0);
            }

            DocumentReference taskRef = db.collection("users").document(user.getUid()).collection("tasks").document();
            
            // [DEBUG] Log the task data being saved, especially subtasks
            Log.d("DEBUG_SUBTASKS", "Creating recurring task instance " + i + 
                  ", Date: " + dateString + 
                  ", RecurrenceGroupId: " + recurrenceGroupId + 
                  ", RecurrenceDays: " + (i == 0 ? recurrenceDays : 0) +
                  ", Subtasks count: " + (subtaskList != null ? subtaskList.size() : "null"));
            if (subtaskList != null && !subtaskList.isEmpty()) {
                for (int j = 0; j < subtaskList.size(); j++) {
                    Task.Subtask subtask = subtaskList.get(j);
                    Log.d("DEBUG_SUBTASKS", "  Subtask " + j + ": " + 
                          "description='" + subtask.getDescription() + "', " +
                          "completed=" + subtask.isCompleted());
                }
            }
            
            batch.set(taskRef, taskData);
        }

        batch.commit()
                .addOnSuccessListener(aVoid -> handleSaveSuccess("Recurring tasks saved successfully!"))
                .addOnFailureListener(this::handleSaveFailure);
    }

    private void saveSingleTask(FirebaseUser user, String taskName, String taskDescription, String selectedDiff, String selectedPriority, String recurrenceGroupId) {
        long deadlineTimestamp;
        if (selectedDeadlineCalendar != null) {
            Calendar combined = (Calendar) selectedDeadlineCalendar.clone();
            if (selectedTimeCalendar != null) {
                combined.set(Calendar.HOUR_OF_DAY, selectedTimeCalendar.get(Calendar.HOUR_OF_DAY));
                combined.set(Calendar.MINUTE, selectedTimeCalendar.get(Calendar.MINUTE));
            } else {
                combined.set(Calendar.HOUR_OF_DAY, 23);
                combined.set(Calendar.MINUTE, 59);
            }
            combined.set(Calendar.SECOND, 0);
            combined.set(Calendar.MILLISECOND, 0);
            deadlineTimestamp = combined.getTimeInMillis();
        } else if (selectedTimeCalendar != null) {
            Calendar todayWithTime = Calendar.getInstance();
            todayWithTime.set(Calendar.HOUR_OF_DAY, selectedTimeCalendar.get(Calendar.HOUR_OF_DAY));
            todayWithTime.set(Calendar.MINUTE, selectedTimeCalendar.get(Calendar.MINUTE));
            todayWithTime.set(Calendar.SECOND, 0);
            todayWithTime.set(Calendar.MILLISECOND, 0);
            deadlineTimestamp = todayWithTime.getTimeInMillis();
        }
        else {
            Calendar todayDefault = Calendar.getInstance();
            todayDefault.set(Calendar.HOUR_OF_DAY, 23);
            todayDefault.set(Calendar.MINUTE, 59);
            todayDefault.set(Calendar.SECOND, 0);
            todayDefault.set(Calendar.MILLISECOND, 0);
            deadlineTimestamp = todayDefault.getTimeInMillis();
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String dateString = sdf.format(new Date(deadlineTimestamp));

        Log.d("AddTaskActivity", "Saving task with deadline date: " + dateString +
                ", timestamp: " + deadlineTimestamp + ", priority: " + selectedPriority);

        Map<String, Object> taskData = new HashMap<>();
        taskData.put("name", taskName);
        taskData.put("description", taskDescription);
        taskData.put("difficulty", selectedDiff);
        taskData.put("priority", selectedPriority);
        taskData.put("dateTimeTimestamp", deadlineTimestamp);
        taskData.put("deadlineTimestamp", deadlineTimestamp);
        taskData.put("date", dateString);
        taskData.put("steps", subtaskList);
        taskData.put("completed", existingTask != null && existingTask.isCompleted());
        taskData.put("userId", user.getUid());
        taskData.put("updatedAt", FieldValue.serverTimestamp());
        if (taskId == null) {
            taskData.put("createdAt", FieldValue.serverTimestamp());
        }
        if (recurrenceGroupId != null) {
            taskData.put("recurrenceGroupId", recurrenceGroupId);
        }
        
        // For a single task, store recurrenceDays as 0 or 1 to indicate no repetition
        taskData.put("recurrenceDays", recurrenceDays <= 1 ? 0 : recurrenceDays);

        DocumentReference taskRef;
        if (taskId != null) {
            taskRef = db.collection("users").document(user.getUid()).collection("tasks").document(taskId);
            taskRef.update(taskData)
                    .addOnSuccessListener(aVoid -> handleSaveSuccess("Task updated successfully!"))
                    .addOnFailureListener(this::handleSaveFailure);
        } else {
            taskRef = db.collection("users").document(user.getUid()).collection("tasks").document();
            taskRef.set(taskData)
                    .addOnSuccessListener(aVoid -> handleSaveSuccess("Task saved successfully!"))
                    .addOnFailureListener(this::handleSaveFailure);
        }
    }

    private void handleSaveSuccess(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void handleSaveFailure(Exception e) {
        Toast.makeText(this, "Error saving task: " + e.getMessage(), Toast.LENGTH_LONG).show();
        Log.e("AddTaskActivity", "Error saving task", e);
    }

    public static class SubtaskAdapter extends RecyclerView.Adapter<SubtaskAdapter.SubtaskViewHolder> {
        private final List<Task.Subtask> subtaskList;
        private final OnSubtaskInteractionListener listener;

        public SubtaskAdapter(List<Task.Subtask> subtaskList, OnSubtaskInteractionListener listener) {
            this.subtaskList = subtaskList;
            this.listener = listener;
        }

        @NonNull
        @Override
        public SubtaskViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_subtask, parent, false);
            return new SubtaskViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull SubtaskViewHolder holder, int position) {
            holder.bind(subtaskList.get(position), listener, position);
        }

        @Override
        public int getItemCount() {
            return subtaskList.size();
        }

        static class SubtaskViewHolder extends RecyclerView.ViewHolder {
            TextView tvDescription;
            ImageButton btnDelete;

            public SubtaskViewHolder(@NonNull View itemView) {
                super(itemView);
                tvDescription = itemView.findViewById(R.id.tvSubtaskDescription);
                btnDelete = itemView.findViewById(R.id.btnDeleteSubtask);
            }

            public void bind(Task.Subtask subtask, OnSubtaskInteractionListener listener, int position) {
                tvDescription.setText(subtask.getDescription());
                if (subtask.isCompleted()) {
                    tvDescription.setPaintFlags(tvDescription.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                    tvDescription.setAlpha(0.6f);
                } else {
                    tvDescription.setPaintFlags(tvDescription.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
                    tvDescription.setAlpha(1.0f);
                }
                btnDelete.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onSubtaskDeleteClicked(position);
                    }
                });
            }
        }
    }
}