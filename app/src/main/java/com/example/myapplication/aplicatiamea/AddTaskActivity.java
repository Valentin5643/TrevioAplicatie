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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.aplicatiamea.util.ThemeHelper;
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

import com.example.myapplication.aplicatiamea.util.ReminderManager;

interface OnSubtaskInteractionListener {
    void onSubtaskDeleteClicked(int position);
}

public class AddTaskActivity extends AppCompatActivity implements OnSubtaskInteractionListener {
    
    private EditText taskNameField;
    private EditText taskDescriptionField;
    private Spinner difficultySelector;
    private Spinner prioritySelector; 
    private TextView scheduledTimeDisplay;
    private TextView deadlineDisplay;
    private Button timePickerButton;
    private Button deadlinePickerButton;
    private Button saveTaskButton;
    private Button recurrenceButton;
    private TextView recurrenceStatusText;
    private View mainContainer;

    private Button reminderButton;
    private TextView reminderStatusText;

    private RecyclerView subtaskRecyclerView;
    private EditText newSubtaskInput;
    private ImageButton addSubtaskButton;
    private SubtaskAdapter subtaskAdapter;
    private List<Task.Subtask> taskSteps;

    private Calendar selectedDate;
    private Calendar selectedTime;
    private Calendar taskDeadline;
    
    private FirebaseFirestore firestoreDb;
    private FirebaseAuth firebaseAuth;

    private String currentTaskId;
    private Task taskBeingEdited;
    private int recurrenceInterval = 1;
    private boolean isEditingTaskSeries = false;
    private String recurrenceGroupIdentifier = null;
    
    private int reminderOffsetMinutes = 0;
    
    private ActivityResultLauncher<Intent> recurrencePickerLauncher;
    private ActivityResultLauncher<Intent> reminderPickerLauncher;

    private ReminderManager notificationManager;
    
    private static final String LOG_TAG = "TaskCreation";
    private static final boolean ENABLE_DEBUG_LOGGING = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyUserTheme(this);
        
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_task);


        firestoreDb = FirebaseFirestore.getInstance();
        firebaseAuth = FirebaseAuth.getInstance();


        notificationManager = new ReminderManager(this);

        if (ENABLE_DEBUG_LOGGING) {
            Log.d(LOG_TAG, "Task creation activity initialized");
        }


        initializeViewReferences();
        

        configureEdgeToEdgeDisplay();


        selectedDate = Calendar.getInstance();
        selectedTime = Calendar.getInstance();
        taskDeadline = null; // no deadline by default
        
        taskSteps = new ArrayList<>();
        
        configureDifficultyOptions();
        configurePriorityOptions();
        setupSubtaskManagement();
        wireUpButtonHandlers();

        initializeRecurrencePicker();
        initializeReminderPicker();

        currentTaskId = getIntent().getStringExtra("TASK_ID");
        isEditingTaskSeries = getIntent().getBooleanExtra("EDIT_SERIES", false);
        
        if (currentTaskId != null) {
            if (isEditingTaskSeries) {
                setTitle("Edit Recurring Task");
                recurrenceButton.setVisibility(View.VISIBLE);
                recurrenceStatusText.setVisibility(View.VISIBLE);
            } else {
                setTitle("Edit Task");
                recurrenceButton.setVisibility(View.GONE);
                recurrenceStatusText.setVisibility(View.GONE);
            }
            loadExistingTaskData();
        } else {
            setTitle("Create New Task");
            taskDeadline = Calendar.getInstance();
            taskDeadline.set(Calendar.HOUR_OF_DAY, 23);
            taskDeadline.set(Calendar.MINUTE, 59);
            taskDeadline.set(Calendar.SECOND, 59);
            refreshDeadlineDisplay();
            refreshRecurrenceDisplay();
        }
    }
    

    private void initializeViewReferences() {
        mainContainer = findViewById(R.id.addTaskRoot);
        
        taskNameField = findViewById(R.id.etTaskName);
        taskDescriptionField = findViewById(R.id.etTaskDescription);
        
        difficultySelector = findViewById(R.id.spinnerDifficulty);
        prioritySelector = findViewById(R.id.spinnerPriority);
        
        scheduledTimeDisplay = findViewById(R.id.timeValue);
        deadlineDisplay = findViewById(R.id.deadlineValue);
        timePickerButton = findViewById(R.id.timeButton);
        deadlinePickerButton = findViewById(R.id.deadlineButton);
        
        saveTaskButton = findViewById(R.id.saveButton);
        
        recurrenceButton = findViewById(R.id.repeatButton);
        recurrenceStatusText = findViewById(R.id.repeatValue);

        reminderButton = findViewById(R.id.reminderButton);
        reminderStatusText = findViewById(R.id.reminderValue);

        subtaskRecyclerView = findViewById(R.id.stepsList);
        newSubtaskInput = findViewById(R.id.newStepText);
        addSubtaskButton = findViewById(R.id.addStepButton);
    }
    
    private void configureEdgeToEdgeDisplay() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        
        ViewCompat.setOnApplyWindowInsetsListener(mainContainer, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            
            view.setPadding(
                view.getPaddingLeft(), 
                insets.top,
                view.getPaddingRight(), 
                insets.bottom
            );
            
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void configureDifficultyOptions() {
        List<String> diffs = new ArrayList<>();
        for (Task.TaskDifficulty d : Task.TaskDifficulty.values()) {
            diffs.add(d.name());
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, diffs);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        difficultySelector.setAdapter(adapter);
        
    }
    
    private void configurePriorityOptions() {
        List<String> priorities = new ArrayList<>();
        for (Task.TaskPriority p : Task.TaskPriority.values()) {
            priorities.add(p.name());
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, priorities);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        prioritySelector.setAdapter(adapter);
        
        int highPos = adapter.getPosition(Task.TaskPriority.HIGH.name());
        if (highPos >= 0) {
            prioritySelector.setSelection(highPos);
        }
    }

    private void setupSubtaskManagement() {
        subtaskAdapter = new SubtaskAdapter(taskSteps, this);
        subtaskRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        subtaskRecyclerView.setAdapter(subtaskAdapter);
        subtaskRecyclerView.setNestedScrollingEnabled(false); // smoother scrolling
    }

    private void wireUpButtonHandlers() {
        timePickerButton.setOnClickListener(v -> showTimePicker(selectedTime, this::updateTimeDisplay));
        deadlinePickerButton.setOnClickListener(v -> showDeadlinePicker());
        addSubtaskButton.setOnClickListener(v -> addStep());
        saveTaskButton.setOnClickListener(v -> saveTask());
        recurrenceButton.setOnClickListener(v -> openRecurrencePicker());
        reminderButton.setOnClickListener(v -> openReminderPicker());
        
        findViewById(R.id.cancelButton).setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        

    }
    
    private void initializeRecurrencePicker() {
        recurrencePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        recurrenceInterval = result.getData().getIntExtra("RECURRENCE_DAYS", 1);
                        refreshRecurrenceDisplay();
                    }
                });
    }

    private void initializeReminderPicker() {
        reminderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        reminderOffsetMinutes = result.getData().getIntExtra("REMINDER_MINUTES", 0);
                        updateReminderDisplay();
                    }
                });
    }

    private void openRecurrencePicker() {
        Intent intent = new Intent(this, SelectRecurrenceActivity.class);
        intent.putExtra("RECURRENCE_DAYS", recurrenceInterval);
        recurrencePickerLauncher.launch(intent);
    }

    private void openReminderPicker() {
        Intent intent = new Intent(this, SelectReminderActivity.class);
        intent.putExtra("REMINDER_MINUTES", reminderOffsetMinutes);
        reminderPickerLauncher.launch(intent);
    }

    private void refreshRecurrenceDisplay() {
        if (recurrenceInterval > 1) {
            recurrenceStatusText.setText("Repeats for " + recurrenceInterval + " days");
        } else {
            recurrenceStatusText.setText("No Repetition");
        }
    }

    private void updateReminderDisplay() {
        if (reminderOffsetMinutes == 0) {
            reminderStatusText.setText("None");
        } else if (reminderOffsetMinutes < 60) {
            reminderStatusText.setText(reminderOffsetMinutes + " min before");
        } else if (reminderOffsetMinutes == 60) {
            reminderStatusText.setText("1 hour before");
        } else if (reminderOffsetMinutes == 120) {
            reminderStatusText.setText("2 hours before");
        } else {
            int hours = reminderOffsetMinutes / 60;
            int mins = reminderOffsetMinutes % 60;
            if (mins == 0) {
                reminderStatusText.setText(hours + " hours before");
            } else {
                reminderStatusText.setText(hours + "h " + mins + "m before");
            }
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
        if (taskDeadline == null) {
            taskDeadline = Calendar.getInstance();
        }
        new DatePickerDialog(this, (dateView, year, month, dayOfMonth) -> {
            taskDeadline.set(Calendar.YEAR, year);
            taskDeadline.set(Calendar.MONTH, month);
            taskDeadline.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            if (selectedTime != null) {
                taskDeadline.set(Calendar.HOUR_OF_DAY, selectedTime.get(Calendar.HOUR_OF_DAY));
                taskDeadline.set(Calendar.MINUTE, selectedTime.get(Calendar.MINUTE));
                taskDeadline.set(Calendar.SECOND, 0);
                taskDeadline.set(Calendar.MILLISECOND, 0);
            } else {
                taskDeadline.set(Calendar.HOUR_OF_DAY, 0);
                taskDeadline.set(Calendar.MINUTE, 0);
                taskDeadline.set(Calendar.SECOND, 0);
                taskDeadline.set(Calendar.MILLISECOND, 0);
            }
            refreshDeadlineDisplay();
        }, taskDeadline.get(Calendar.YEAR), taskDeadline.get(Calendar.MONTH), taskDeadline.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateTimeDisplay() {
        SimpleDateFormat fmt = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        scheduledTimeDisplay.setText(fmt.format(selectedTime.getTime()));
    }

    private void refreshDeadlineDisplay() {
        if (taskDeadline != null) {
            SimpleDateFormat fmt = new SimpleDateFormat("EEE, MMM dd, yyyy", Locale.getDefault());
            deadlineDisplay.setText(fmt.format(taskDeadline.getTime()));
        } else {
            deadlineDisplay.setText("Not Set");
        }
    }

    private void addStep() {
        String desc = newSubtaskInput.getText().toString().trim();
        if (!desc.isEmpty()) {
            taskSteps.add(new Task.Subtask(desc, false));
            subtaskAdapter.notifyItemInserted(taskSteps.size() - 1);
            newSubtaskInput.setText("");
        } else {
            Toast.makeText(this, "Can't add empty step", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveStepsToFirestore(String taskId, List<Task.Subtask> steps) {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) return;
        
        DocumentReference taskRef = firestoreDb.collection("users").document(user.getUid())
            .collection("tasks").document(taskId);

        List<Map<String, Object>> stepMaps = new ArrayList<>();
        for (Task.Subtask step : steps) {
            Map<String, Object> map = new HashMap<>();
            map.put("description", step.getDescription());
            map.put("completed", step.isCompleted());
            map.put("stability", step.getStability());
            stepMaps.add(map);
        }
        taskRef.update("steps", stepMaps);
    }

    @Override
    public void onSubtaskDeleteClicked(int position) {
        if (position >= 0 && position < taskSteps.size()) {
            taskSteps.remove(position);
            subtaskAdapter.notifyItemRemoved(position);
            subtaskAdapter.notifyItemRangeChanged(position, taskSteps.size());
        }
    }

    private void loadExistingTaskData() {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        firestoreDb.collection("users").document(user.getUid()).collection("tasks").document(currentTaskId)
            .get()
            .addOnSuccessListener(doc -> {
                if (doc.exists()) {
                    taskBeingEdited = doc.toObject(Task.class);
                    if (taskBeingEdited != null) {
                        populateUIFromTask(taskBeingEdited);
                    } else {
                        Toast.makeText(this, "Couldn't load task", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                } else {
                    Toast.makeText(this, "Task not found", Toast.LENGTH_SHORT).show();
                    finish();
                }
            })
            .addOnFailureListener(e -> {
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                Log.e("AddTask", "Failed to load", e);
                finish();
            });
    }

    private void populateUIFromTask(Task task) {
        taskNameField.setText(task.getName());
        taskDescriptionField.setText(task.getDescription());

        ArrayAdapter<String> diffAdapter = (ArrayAdapter<String>) difficultySelector.getAdapter();
        if (diffAdapter != null) {
            int pos = diffAdapter.getPosition(task.getDifficulty());
            difficultySelector.setSelection(Math.max(pos, 0));
        }
        
        ArrayAdapter<String> priorityAdapter = (ArrayAdapter<String>) prioritySelector.getAdapter();
        if (priorityAdapter != null && task.getPriority() != null) {
            int pos = priorityAdapter.getPosition(task.getPriority());
            prioritySelector.setSelection(Math.max(pos, 0));
        } else {
            int mediumPos = priorityAdapter.getPosition(Task.TaskPriority.MEDIUM.name());
            prioritySelector.setSelection(Math.max(mediumPos, 0));
        }

        if (task.getDateTimeTimestamp() > 0) {
            selectedDate.setTimeInMillis(task.getDateTimeTimestamp());
            selectedTime.setTimeInMillis(task.getDateTimeTimestamp());
            updateTimeDisplay();
        } else {
            scheduledTimeDisplay.setText("Set Time");
        }

        if (task.getDeadlineTimestamp() > 0) {
            taskDeadline = Calendar.getInstance();
            taskDeadline.setTimeInMillis(task.getDeadlineTimestamp());
            refreshDeadlineDisplay();
        } else {
            taskDeadline = null;
            refreshDeadlineDisplay();
        }

        if (task.getSteps() != null) {
            taskSteps.clear();
            taskSteps.addAll(task.getSteps());
            subtaskAdapter.notifyDataSetChanged();
        }
        
        if (task.getRecurrenceGroupId() != null) {
            recurrenceGroupIdentifier = task.getRecurrenceGroupId();
            
            if (isEditingTaskSeries) {
                recurrenceInterval = task.getRecurrenceDays();
                if (recurrenceInterval <= 1) {
                    recurrenceInterval = 2;
                }
                recurrenceStatusText.setVisibility(View.VISIBLE);
                recurrenceButton.setVisibility(View.VISIBLE);
            } else if (task.getRecurrenceDays() > 1) {
                recurrenceInterval = task.getRecurrenceDays();
                recurrenceStatusText.setText("Part of " + recurrenceInterval + "-day series");
                recurrenceStatusText.setVisibility(View.VISIBLE);
                recurrenceButton.setVisibility(View.GONE);
            }
            refreshRecurrenceDisplay();
        }

        if (task.getReminderMinutes() != null) {
            reminderOffsetMinutes = task.getReminderMinutes();
        } else {
            reminderOffsetMinutes = 0; // default to no reminder
        }
        updateReminderDisplay();
    }

    private void saveTask() {
        Toast.makeText(this, "Saving...", Toast.LENGTH_SHORT).show();
        
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Login required", Toast.LENGTH_SHORT).show();
            return;
        }

        String name = taskNameField.getText().toString().trim();
        if (name.isEmpty()) {
            taskNameField.setError("Task needs a name");
            taskNameField.requestFocus();
            return;
        }
        
        String desc = taskDescriptionField.getText().toString().trim();
        String diff = difficultySelector.getSelectedItem().toString();
        String priority = prioritySelector.getSelectedItem().toString();

        if (isEditingTaskSeries && recurrenceGroupIdentifier != null) {
            updateTaskSeries(user, name, desc, diff, priority);
        } else if (currentTaskId != null) {
            saveSingleTask(user, name, desc, diff, priority, recurrenceGroupIdentifier);
        } else if (recurrenceInterval > 1) {
            saveRecurringTasks(user, name, desc, diff, priority);
        } else {
            saveSingleTask(user, name, desc, diff, priority, null);
        }
    }

    private void updateTaskSeries(FirebaseUser user, String taskName, String taskDescription, 
                                  String selectedDiff, String selectedPriority) {
        if (recurrenceGroupIdentifier == null) {
            Toast.makeText(this, "Cannot find recurring tasks to update", Toast.LENGTH_SHORT).show();
            return;
        }

        firestoreDb.collection("users")
            .document(user.getUid())
            .collection("tasks")
            .whereEqualTo("recurrenceGroupId", recurrenceGroupIdentifier)
            .get()
            .addOnSuccessListener(queryDocumentSnapshots -> {
                if (queryDocumentSnapshots.isEmpty()) {
                    Toast.makeText(this, "No tasks found in this series", Toast.LENGTH_SHORT).show();
                    return;
                }

                WriteBatch batch = firestoreDb.batch();
                final int[] taskCount = {0};

                List<Map<String, Object>> stepsAsMaps = new ArrayList<>();
                for (Task.Subtask step : taskSteps) {
                    Map<String, Object> stepMap = new HashMap<>();
                    stepMap.put("description", step.getDescription());
                    stepMap.put("completed", step.isCompleted());
                    stepMap.put("stability", step.getStability());
                    stepsAsMaps.add(stepMap);
                }

                for (DocumentSnapshot doc : queryDocumentSnapshots) {
                    Task seriesTask = doc.toObject(Task.class);
                    if (seriesTask != null) {
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("name", taskName);
                        updates.put("description", taskDescription);
                        updates.put("difficulty", selectedDiff);
                        updates.put("priority", selectedPriority);
                        updates.put("steps", stepsAsMaps); // Use the properly formatted subtasks
                        updates.put("updatedAt", FieldValue.serverTimestamp());
                        updates.put("reminderMinutes", reminderOffsetMinutes); // Update reminder data
                        
                        batch.update(doc.getReference(), updates);
                        taskCount[0]++;
                    }
                }

                if (taskCount[0] > 0 && currentTaskId != null) {
                    batch.update(
                        firestoreDb.collection("users").document(user.getUid()).collection("tasks").document(currentTaskId),
                        "recurrenceDays", recurrenceInterval
                    );
                }

                batch.commit()
                    .addOnSuccessListener(aVoid -> {
                        for (DocumentSnapshot doc : queryDocumentSnapshots) {
                            Task seriesTask = doc.toObject(Task.class);
                            if (seriesTask != null) {
                                scheduleTaskReminder(doc.getId(), taskName, taskDescription, seriesTask.getDeadlineTimestamp());
                            }
                        }
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
        WriteBatch batch = firestoreDb.batch();
        String repeatGroupId = UUID.randomUUID().toString();
        Calendar taskCalendar = Calendar.getInstance();

        int hourOfDay = 23;
        int minute = 59;
        if (taskDeadline != null && selectedTime != null) {
            hourOfDay = selectedTime.get(Calendar.HOUR_OF_DAY);
            minute = selectedTime.get(Calendar.MINUTE);
        } else if (selectedTime != null) {
            hourOfDay = selectedTime.get(Calendar.HOUR_OF_DAY);
            minute = selectedTime.get(Calendar.MINUTE);
        }

        List<Map<String, Object>> stepsAsMaps = new ArrayList<>();
        for (Task.Subtask step : taskSteps) {
            Map<String, Object> stepMap = new HashMap<>();
            stepMap.put("description", step.getDescription());
            stepMap.put("completed", step.isCompleted());
            stepMap.put("stability", step.getStability());
            stepsAsMaps.add(stepMap);
        }

        List<String> newTaskIds = new ArrayList<>();
        List<Long> taskTimestamps = new ArrayList<>();

        for (int i = 0; i < recurrenceInterval; i++) {
            Calendar currentDayCalendar = (Calendar) taskCalendar.clone();
            if (i > 0) {
                currentDayCalendar.add(Calendar.DAY_OF_YEAR, i);
            }
            if (taskDeadline != null && i == 0) {
                currentDayCalendar.setTimeInMillis(taskDeadline.getTimeInMillis());
            } else if (taskDeadline != null) {
                Calendar incrementedDeadline = (Calendar) taskDeadline.clone();
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
            taskData.put("steps", stepsAsMaps); // Use the properly formatted subtasks 
            taskData.put("completed", false);
            taskData.put("userId", user.getUid());
            taskData.put("createdAt", FieldValue.serverTimestamp());
            taskData.put("updatedAt", FieldValue.serverTimestamp());
            taskData.put("recurrenceGroupId", repeatGroupId);
            taskData.put("reminderMinutes", reminderOffsetMinutes); // Add reminder data
            
            if (i == 0) {
                taskData.put("recurrenceDays", recurrenceInterval);
            } else {
                taskData.put("recurrenceDays", 0);
            }

            DocumentReference taskRef = firestoreDb.collection("users").document(user.getUid()).collection("tasks").document();
            batch.set(taskRef, taskData);
            
            newTaskIds.add(taskRef.getId());
            taskTimestamps.add(deadlineTimestamp);
        }

        batch.commit()
                .addOnSuccessListener(aVoid -> {
                    for (int i = 0; i < newTaskIds.size(); i++) {
                        scheduleTaskReminder(newTaskIds.get(i), taskName, taskDescription, taskTimestamps.get(i));
                    }
                    handleSaveSuccess("Tasks created for " + recurrenceInterval + " days!");
                })
                .addOnFailureListener(this::handleSaveFailure);
    }

    private void saveSingleTask(FirebaseUser user, String taskName, String taskDescription, String selectedDiff, String selectedPriority, String recurrenceGroupId) {
        long deadlineTimestamp;
        if (taskDeadline != null) {
            Calendar combined = (Calendar) taskDeadline.clone();
            if (selectedTime != null) {
                combined.set(Calendar.HOUR_OF_DAY, selectedTime.get(Calendar.HOUR_OF_DAY));
                combined.set(Calendar.MINUTE, selectedTime.get(Calendar.MINUTE));
            } else {
                combined.set(Calendar.HOUR_OF_DAY, 23);
                combined.set(Calendar.MINUTE, 59);
            }
            combined.set(Calendar.SECOND, 0);
            combined.set(Calendar.MILLISECOND, 0);
            deadlineTimestamp = combined.getTimeInMillis();
        } else if (selectedTime != null) {
            Calendar todayWithTime = Calendar.getInstance();
            todayWithTime.set(Calendar.HOUR_OF_DAY, selectedTime.get(Calendar.HOUR_OF_DAY));
            todayWithTime.set(Calendar.MINUTE, selectedTime.get(Calendar.MINUTE));
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

        if (ENABLE_DEBUG_LOGGING) {
            Log.d(LOG_TAG, "Saving task with deadline date: " + dateString +
                    ", timestamp: " + deadlineTimestamp + ", priority: " + selectedPriority);
        }

        List<Map<String, Object>> stepsAsMaps = new ArrayList<>();
        for (Task.Subtask step : taskSteps) {
            Map<String, Object> stepMap = new HashMap<>();
            stepMap.put("description", step.getDescription());
            stepMap.put("completed", step.isCompleted());
            stepMap.put("stability", step.getStability());
            stepsAsMaps.add(stepMap);
        }

        Map<String, Object> taskData = new HashMap<>();
        taskData.put("name", taskName);
        taskData.put("description", taskDescription);
        taskData.put("difficulty", selectedDiff);
        taskData.put("priority", selectedPriority);
        taskData.put("dateTimeTimestamp", deadlineTimestamp);
        taskData.put("deadlineTimestamp", deadlineTimestamp);
        taskData.put("date", dateString);
        taskData.put("steps", stepsAsMaps); // Use the properly formatted subtasks
        taskData.put("completed", taskBeingEdited != null && taskBeingEdited.isCompleted());
        taskData.put("userId", user.getUid());
        taskData.put("updatedAt", FieldValue.serverTimestamp());
        
        taskData.put("reminderMinutes", reminderOffsetMinutes);
        
        if (currentTaskId == null) {
            taskData.put("createdAt", FieldValue.serverTimestamp());
        }
        
        if (recurrenceGroupId != null) {
            taskData.put("recurrenceGroupId", recurrenceGroupId);
        }
        
        taskData.put("recurrenceDays", recurrenceInterval <= 1 ? 0 : recurrenceInterval);

        DocumentReference taskRef;
        if (currentTaskId != null) {
            taskRef = firestoreDb.collection("users").document(user.getUid()).collection("tasks").document(currentTaskId);
            taskRef.update(taskData)
                    .addOnSuccessListener(aVoid -> {
                        scheduleTaskReminder(currentTaskId, taskName, taskDescription, deadlineTimestamp);
                        handleSaveSuccess("Task updated!");
                    })
                    .addOnFailureListener(this::handleSaveFailure);
        } else {
            // Create new
            taskRef = firestoreDb.collection("users").document(user.getUid()).collection("tasks").document();
            final String newTaskId = taskRef.getId();
            taskRef.set(taskData)
                    .addOnSuccessListener(aVoid -> {
                        scheduleTaskReminder(newTaskId, taskName, taskDescription, deadlineTimestamp);
                        handleSaveSuccess("Task saved!");
                    })
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

    private void scheduleTaskReminder(String taskId, String taskName, String taskDescription, long taskTimeMillis) {
        if (reminderOffsetMinutes > 0) {
            notificationManager.scheduleReminder(taskId, taskName, taskDescription, taskTimeMillis, reminderOffsetMinutes);
        } else {
            notificationManager.cancelReminder(taskId);
        }
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