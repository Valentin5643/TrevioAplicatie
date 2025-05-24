package com.example.myapplication.aplicatiamea;

import android.content.Context;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.CheckBox;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.aplicatiamea.repository.QuestManager;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Handles the task list display - the heart of our productivity system
 * Manages the delicate balance between showing progress and not overwhelming users
 */
public class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.TaskViewHolder> {

    // So streak xp rewards are (5 * streak days) for each completion
    private static final long STREAK_XP_MULTIPLIER = 5L;
    private static final String TAG = "TaskAdapter";
    
    // Sorting constants
    public static final int SORT_PRIORITY = 0;
    public static final int SORT_NAME = 1;
    public static final int SORT_DUE_DATE = 2;
    public static final int SORT_CREATED = 3;
    
    // Filter constants
    public static final int FILTER_ALL = 0;
    public static final int FILTER_TODAY = 1;
    public static final int FILTER_INCOMPLETE = 2;
    public static final int FILTER_COMPLETE = 3;
    public static final int FILTER_RECURRING = 4;

    private final Context context;
    private final DocumentReference userRef;
    private final int textColor;
    private final int disabledColor;
    private final TimeZone userTimeZone;
    private final SimpleDateFormat dateFormat;
    private final QuestManager questManager;
    private final StreakHelper streakHelper;
    private final TaskInteractionHandler interactionHandler;
    
    // Main task list and filtered view list
    private final List<Task> allTasks = new ArrayList<>();
    private final List<Task> filteredTasks = new ArrayList<>();
    
    // Batched operations cache
    private final Map<String, Boolean> taskCompletionUpdates = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor backgroundExecutor = Executors.newSingleThreadExecutor();
    
    // Filtering and sorting settings
    private int currentSortMethod = SORT_PRIORITY;
    private int currentFilter = FILTER_ALL;
    private boolean showCompleted = true;

    // Track which tasks are being updated to prevent UI glitches
    private final java.util.Set<String> tasksBeingUpdated = new java.util.HashSet<>();
    
    public interface TaskInteractionHandler {
        void onTaskToggled(Task task, boolean isCompleted);
        void onTaskClicked(Task task);
        void onTaskLongPressed(Task task);
        boolean canUserModifyTask(Task task);
    }
    
    public TaskAdapter(List<Task> tasks, Context context, DocumentReference userRef,
                       QuestManager questManager, StreakHelper streakHelper, TaskInteractionHandler handler) {
        this.context = context;
        this.userRef = userRef;
        this.textColor = ContextCompat.getColor(context, R.color.black);
        this.disabledColor = ContextCompat.getColor(context, R.color.gray);
        // Romania timezone - move to settings later
        this.userTimeZone = TimeZone.getTimeZone("Europe/Bucharest");
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        this.dateFormat.setTimeZone(userTimeZone);
        this.questManager = questManager;
        this.streakHelper = streakHelper;
        this.interactionHandler = handler;
        
        // Initialize with tasks
        if (tasks != null) {
            this.allTasks.addAll(tasks);
            this.filteredTasks.addAll(tasks);
        }
        
        // Start task batch updater
        scheduleTaskBatchUpdates();
    }

    // Simplified constructor for backward compatibility
    public TaskAdapter(List<Task> tasks, Context context, DocumentReference userRef) {
        this(tasks, context, userRef, null, null, null);
    }

    @Override
    public void onBindViewHolder(@NonNull TaskViewHolder holder, int position) {
        holder.bind(filteredTasks.get(position));
    }

    @NonNull
    @Override
    public TaskViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_task, parent, false);
        return new TaskViewHolder(view);
    }

    @Override
    public int getItemCount() {
        return filteredTasks.size();
    }
    
    /**
     * Find task position by ID in the filtered list
     */
    public int findPositionById(String taskId) {
        if (taskId == null) return -1;
        
        for (int i = 0; i < filteredTasks.size(); i++) {
            if (taskId.equals(filteredTasks.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }
    
    /**
     * Find task by ID in the main list
     */
    @Nullable
    public Task findTaskById(String taskId) {
        if (taskId == null) return null;
        
        for (Task task : allTasks) {
            if (taskId.equals(task.getId())) {
                return task;
            }
        }
        return null;
    }
    
    /**
     * Apply filtering and sorting to the task list
     */
    public void applyFiltersAndSort() {
        backgroundExecutor.execute(() -> {
            List<Task> result = new ArrayList<>(allTasks);
            
            // Apply filters
            if (currentFilter != FILTER_ALL) {
                List<Task> filtered = new ArrayList<>();
                String today = getCurrentDateString();
                
                for (Task task : result) {
                    boolean include = false;
                    
                    switch (currentFilter) {
                        case FILTER_TODAY:
                            // Tasks scheduled for today
                            include = TaskScheduler.isTaskScheduledForToday(task, userTimeZone, today);
                            break;
                        case FILTER_INCOMPLETE:
                            // Only incomplete tasks
                            include = !task.isCompleted();
                            break;
                        case FILTER_COMPLETE:
                            // Only completed tasks
                            include = task.isCompleted();
                            break;
                        case FILTER_RECURRING:
                            // Only recurring tasks
                            include = task.getRecurrenceDays() > 1 || 
                                (task.getRecurrenceGroupId() != null && !task.getRecurrenceGroupId().isEmpty());
                            break;
                    }
                    
                    if (include) {
                        filtered.add(task);
                    }
                }
                
                result = filtered;
            } else if (!showCompleted) {
                // Filter out completed tasks unless specifically viewing completed
                List<Task> filtered = new ArrayList<>();
                for (Task task : result) {
                    if (!task.isCompleted()) {
                        filtered.add(task);
                    }
                }
                result = filtered;
            }
            
            // Apply sort
            if (currentSortMethod == SORT_NAME) {
                // Alphabetical
                Collections.sort(result, (t1, t2) -> {
                    String name1 = t1.getName() != null ? t1.getName() : "";
                    String name2 = t2.getName() != null ? t2.getName() : "";
                    return name1.compareToIgnoreCase(name2);
                });
            } else if (currentSortMethod == SORT_DUE_DATE) {
                // By due date, nulls last
                Collections.sort(result, (t1, t2) -> {
                    // Get deadline timestamp as Date
                    Date date1 = t1.getDeadlineTimestamp() > 0 ? new Date(t1.getDeadlineTimestamp()) : null;
                    Date date2 = t2.getDeadlineTimestamp() > 0 ? new Date(t2.getDeadlineTimestamp()) : null;
                    
                    if (date1 == null && date2 == null) return 0;
                    if (date1 == null) return 1;  // null dates go last
                    if (date2 == null) return -1;
                    
                    return date1.compareTo(date2);
                });
            } else if (currentSortMethod == SORT_CREATED) {
                // By creation time, newest first
                Collections.sort(result, (t1, t2) -> {
                    // Use createdAt field for timestamp
                    Long timestamp1 = t1.getCreatedAt() != null ? t1.getCreatedAt().getTime() : null;
                    Long timestamp2 = t2.getCreatedAt() != null ? t2.getCreatedAt().getTime() : null;
                    
                    if (timestamp1 == null && timestamp2 == null) return 0;
                    if (timestamp1 == null) return 1;
                    if (timestamp2 == null) return -1;
                    
                    // Newest first
                    return timestamp2.compareTo(timestamp1);
                });
            } else {
                // Default priority sort
                Collections.sort(result, (t1, t2) -> {
                    // Incomplete tasks before completed tasks
                    if (t1.isCompleted() != t2.isCompleted()) {
                        return t1.isCompleted() ? 1 : -1;
                    }
                    
                    // Then by priority - convert priority string to int value
                    int p1 = getPriorityValue(t1.getPriority());
                    int p2 = getPriorityValue(t2.getPriority());
                    if (p1 != p2) {
                        return p2 - p1; // Higher priority first
                    }
                    
                    // Then by name
                    String name1 = t1.getName() != null ? t1.getName() : "";
                    String name2 = t2.getName() != null ? t2.getName() : "";
                    return name1.compareToIgnoreCase(name2);
                });
            }
            
            // Make a final copy of the result list for the lambda
            final List<Task> finalResult = new ArrayList<>(result);
            
            // Apply changes on UI thread using DiffUtil
            mainHandler.post(() -> {
                DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new TaskDiffCallback(filteredTasks, finalResult));
                filteredTasks.clear();
                filteredTasks.addAll(finalResult);
                diffResult.dispatchUpdatesTo(this);
            });
        });
    }
    
    /**
     * Convert priority string to integer value
     */
    private int getPriorityValue(String priority) {
        if (priority == null) return 0;
        
        if (Task.TaskPriority.HIGH.name().equals(priority)) {
            return 3;
        } else if (Task.TaskPriority.MEDIUM.name().equals(priority)) {
            return 2;
        } else if (Task.TaskPriority.LOW.name().equals(priority)) {
            return 1;
        }
        return 0;
    }
    
    /**
     * Set filter type
     */
    public void setFilter(int filterType) {
        if (currentFilter != filterType) {
            currentFilter = filterType;
            applyFiltersAndSort();
        }
    }
    
    /**
     * Set sort method
     */
    public void setSortMethod(int sortMethod) {
        if (currentSortMethod != sortMethod) {
            currentSortMethod = sortMethod;
            applyFiltersAndSort();
        }
    }
    
    /**
     * Toggle showing completed tasks
     */
    public void setShowCompleted(boolean show) {
        if (showCompleted != show) {
            showCompleted = show;
            applyFiltersAndSort();
        }
    }
    
    /**
     * Check if a task was filtered out
     */
    public boolean isTaskFiltered(String taskId) {
        Task task = findTaskById(taskId);
        return task != null && !filteredTasks.contains(task);
    }

    class TaskViewHolder extends RecyclerView.ViewHolder {
        MaterialCheckBox cbTaskCompleted;
        TextView tvTaskName;
        TextView tvTaskDescription;
        LinearLayout subtasksContainer;
        ImageView ivRecurringIndicator;
        ImageView ivPriorityIndicator;

        TaskViewHolder(@NonNull View itemView) {
            super(itemView);
            cbTaskCompleted = itemView.findViewById(R.id.taskCompletedCheckbox);
            tvTaskName = itemView.findViewById(R.id.tvTaskName);
            tvTaskDescription = itemView.findViewById(R.id.tvTaskDescription);
            subtasksContainer = itemView.findViewById(R.id.subtasksContainer);
            ivRecurringIndicator = itemView.findViewById(R.id.ivRecurringIndicator);
            ivPriorityIndicator = itemView.findViewById(R.id.ivPriorityIndicator);
            
            // Fallback for older layouts missing the subtasks container
            if (subtasksContainer == null) {
                subtasksContainer = new LinearLayout(itemView.getContext());
                subtasksContainer.setOrientation(LinearLayout.VERTICAL);
                subtasksContainer.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ));
                
                // Try to find the container, if not just add to root
                ViewGroup parent = itemView.findViewById(R.id.taskContent);
                if (parent == null) {
                    parent = (ViewGroup) itemView; // fallback
                }
                parent.addView(subtasksContainer);
            }
        }

        void bind(Task task) {
            tvTaskName.setText(task.getName());
            
            // Description is optional
            if (task.getDescription() != null && !task.getDescription().isEmpty()) {
                tvTaskDescription.setText(task.getDescription());
                tvTaskDescription.setVisibility(View.VISIBLE);
            } else {
                tvTaskDescription.setVisibility(View.GONE);
            }
            
            updateTextStyle(task.isCompleted());

            // Show little recurring icon for repeating tasks
            boolean isRecurring = task.getRecurrenceDays() > 1 || 
                              (task.getRecurrenceGroupId() != null && !task.getRecurrenceGroupId().isEmpty());
            if (ivRecurringIndicator != null) {
                ivRecurringIndicator.setVisibility(isRecurring ? View.VISIBLE : View.GONE);
            }
            
            // Show priority indicator if needed
            if (ivPriorityIndicator != null) {
                int priorityValue = getPriorityValue(task.getPriority());
                if (priorityValue > 0) {
                    ivPriorityIndicator.setVisibility(View.VISIBLE);
                    
                    // Just use colors to indicate priority level since we don't have specific drawable resources
                    if (priorityValue == 3) {
                        ivPriorityIndicator.setColorFilter(
                            ContextCompat.getColor(context, R.color.colorAccent));
                    } else if (priorityValue == 2) {
                        ivPriorityIndicator.setColorFilter(
                            ContextCompat.getColor(context, R.color.colorPrimary));
                    } else {
                        ivPriorityIndicator.setColorFilter(
                            ContextCompat.getColor(context, R.color.gray));
                    }
                } else {
                    ivPriorityIndicator.setVisibility(View.GONE);
                }
            }

            // Set up checkbox - have to clear listener first so it doesn't get triggered during setup
            cbTaskCompleted.setOnCheckedChangeListener(null);
            cbTaskCompleted.setChecked(task.isCompleted());

            cbTaskCompleted.setOnCheckedChangeListener((buttonView, isChecked) -> {
                int pos = getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) {
                    Log.w(TAG, "Invalid position, ignoring task update");
                    return;
                }
                 
                Task currentTask = filteredTasks.get(pos);
                if (currentTask == null || currentTask.getId() == null) {
                    Log.e(TAG, "Task or ID is null at position " + pos);
                    buttonView.setChecked(!isChecked); // revert change
                    return;
                }

                // Let the activity handle it if possible - it's smarter about batching
                if (context instanceof TaskPlannerActivity) {
                    ((TaskPlannerActivity) context).updateTaskCompletion(currentTask.getId(), isChecked);
                    // Be optimistic - UI will update if it fails
                    updateTextStyle(isChecked);
                    return;
                }

                // No activity helper - queue for batch update
                updateTaskCompletionState(currentTask, isChecked);
                
                // Update UI immediately (optimistic)
                currentTask.setCompleted(isChecked);
                updateTextStyle(isChecked);
            });
            
            // Set up subtasks
            setupSubtasks(task);

            // Handle completion state - use task parameter instead of undefined currentTask
            boolean isCompleted = task.isCompleted();
            cbTaskCompleted.setChecked(isCompleted);
            
            // Visual feedback for completed tasks
            if (isCompleted) {
                tvTaskName.setPaintFlags(tvTaskName.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                tvTaskName.setAlpha(0.6f);
                tvTaskDescription.setAlpha(0.6f);
            } else {
                tvTaskName.setPaintFlags(tvTaskName.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
                tvTaskName.setAlpha(1.0f);
                tvTaskDescription.setAlpha(1.0f);
            }
            
            // Handle interaction states
            boolean isBeingUpdated = tasksBeingUpdated.contains(task.getId());
            boolean canModify = interactionHandler != null ? interactionHandler.canUserModifyTask(task) : true;
            
            cbTaskCompleted.setEnabled(canModify && !isBeingUpdated);
            
            // Set up click handlers
            if (interactionHandler != null) {
                setupTaskInteractions(this, task, canModify, isBeingUpdated);
            }
        }
        
        private void setupSubtasks(Task task) {
            subtasksContainer.removeAllViews();
            
            List<Task.Subtask> subtasks = task.getSteps();
            if (subtasks != null && !subtasks.isEmpty()) {
                Log.d(TAG, "Adding " + subtasks.size() + " subtasks for " + task.getId());
                subtasksContainer.setVisibility(View.VISIBLE);
                
                for (int i = 0; i < subtasks.size(); i++) {
                    Task.Subtask subtask = subtasks.get(i);
                    View subtaskView = LayoutInflater.from(context)
                            .inflate(R.layout.item_subtask_display, subtasksContainer, false);
                    
                    MaterialCheckBox cbSubtask = subtaskView.findViewById(R.id.cbSubtaskCompleted);
                    TextView tvSubtaskDesc = subtaskView.findViewById(R.id.tvSubtaskDescription);
                    
                    if (cbSubtask != null && tvSubtaskDesc != null) {
                        tvSubtaskDesc.setText(subtask.getDescription());
                        
                        // Set initial state
                        cbSubtask.setOnCheckedChangeListener(null);
                        cbSubtask.setChecked(subtask.isCompleted());
                        updateSubtaskStyle(tvSubtaskDesc, subtask.isCompleted());
                        
                        // Hook up the subtask checkbox
                        final int index = i;
                        cbSubtask.setOnCheckedChangeListener((buttonView, isChecked) -> {
                            Log.d(TAG, "Subtask toggle: task=" + task.getId() + ", idx=" + index + ", checked=" + isChecked);
                            updateSubtaskCompletion(task.getId(), index, isChecked, subtask, tvSubtaskDesc);
                        });
                    }
                    
                    subtasksContainer.addView(subtaskView);
                }
            } else {
                subtasksContainer.setVisibility(View.GONE);
            }
        }

        private void updateTextStyle(boolean isCompleted) {
            int flags = tvTaskName.getPaintFlags();
            float alpha = 1.0f;
            int primaryColor = ContextCompat.getColor(context, R.color.black);
            int secondaryColor = ContextCompat.getColor(context, R.color.gray);

            if (isCompleted) {
                flags |= Paint.STRIKE_THRU_TEXT_FLAG;
                alpha = 0.7f; 
                primaryColor = ContextCompat.getColor(context, R.color.gray);
                secondaryColor = ContextCompat.getColor(context, R.color.gray);
            } else {
                flags &= (~Paint.STRIKE_THRU_TEXT_FLAG);
            }
            
            tvTaskName.setPaintFlags(flags);
            tvTaskName.setAlpha(alpha);
            tvTaskName.setTextColor(primaryColor);

            if (tvTaskDescription.getVisibility() == View.VISIBLE) {
                tvTaskDescription.setPaintFlags(flags);
                tvTaskDescription.setAlpha(alpha);
                tvTaskDescription.setTextColor(secondaryColor);
            }
        }
        
        // Apply strikethrough and dimming to completed subtasks
        private void updateSubtaskStyle(TextView tv, boolean completed) {
            if (tv == null) return;
            
            if (completed) {
                tv.setPaintFlags(tv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                tv.setAlpha(0.7f);
                tv.setTextColor(ContextCompat.getColor(context, R.color.gray));
            } else {
                tv.setPaintFlags(tv.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
                tv.setAlpha(1.0f);
                tv.setTextColor(ContextCompat.getColor(context, R.color.black));
            }
        }
    }
    
    /**
     * Update subtask completion state and reflect changes in the UI
     */
    private void updateSubtaskCompletion(String taskId, int index, boolean isCompleted, 
                                       Task.Subtask subtask, TextView tvSubtaskDesc) {
        if (taskId == null) return;
        
        DocumentReference taskRef = userRef.collection("tasks").document(taskId);
        
        // Optimistically update UI
        subtask.setCompleted(isCompleted);
        if (tvSubtaskDesc != null) {
            updateSubtaskStyle(tvSubtaskDesc, isCompleted);
        }
        
        // Update in Firestore - uses array indexes, so need to be careful
        String fieldPath = "steps." + index + ".completed";
        taskRef.update(fieldPath, isCompleted)
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "Subtask " + index + " of task " + taskId + " updated: " + isCompleted);
                
                // Get the task and check if all subtasks are completed
                Task task = findTaskById(taskId);
                if (task != null) {
                    checkAllSubtasksCompleted(task);
                }
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Failed to update subtask: " + e.getMessage());
                
                // Revert the optimistic UI update
                subtask.setCompleted(!isCompleted);
                if (tvSubtaskDesc != null) {
                    updateSubtaskStyle(tvSubtaskDesc, !isCompleted);
                }
                
                Toast.makeText(context, "Failed to update subtask: " + e.getMessage(), 
                               Toast.LENGTH_SHORT).show();
            });
    }
    
    /**
     * Check if all subtasks are completed and auto-complete the main task
     */
    private void checkAllSubtasksCompleted(Task task) {
        if (task.isCompleted() || task.getSteps() == null || task.getSteps().isEmpty()) {
            return;
        }
        
        boolean allCompleted = true;
        for (Task.Subtask subtask : task.getSteps()) {
            if (!subtask.isCompleted()) {
                allCompleted = false;
                break;
            }
        }
        
        if (allCompleted && !task.isCompleted()) {
            // Auto-complete the task if all subtasks are completed
            Log.d(TAG, "All subtasks completed, auto-completing task: " + task.getId());
            updateTaskCompletionState(task, true);
            
            // Update internal state
            task.setCompleted(true);
            
            // Notify adapter of change
            int position = filteredTasks.indexOf(task);
            if (position >= 0) {
                notifyItemChanged(position);
            }
        }
    }
    
    /**
     * Queue task completion state change for batch update
     */
    private void updateTaskCompletionState(Task task, boolean isCompleted) {
        String taskId = task.getId();
        if (taskId == null) return;
        
        // Store the task completion state update
        taskCompletionUpdates.put(taskId, isCompleted);
        
        // Handle immediate updates
        handleTaskCompletionChange(task, isCompleted);
    }
    
    /**
     * Schedule periodic batch updates to Firestore
     */
    private void scheduleTaskBatchUpdates() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                flushTaskCompletionUpdates();
                mainHandler.postDelayed(this, 2000); // Run every 2 seconds
            }
        }, 2000);
    }
    
    /**
     * Execute all pending task completion updates as a batch
     */
    private void flushTaskCompletionUpdates() {
        if (taskCompletionUpdates.isEmpty()) {
            return;
        }
        
        Map<String, Boolean> updates = new HashMap<>(taskCompletionUpdates);
        taskCompletionUpdates.clear();
        
        if (updates.isEmpty()) {
            return;
        }
        
        Log.d(TAG, "Flushing " + updates.size() + " task completion updates");
        
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        WriteBatch batch = db.batch();
        
        for (Map.Entry<String, Boolean> entry : updates.entrySet()) {
            String taskId = entry.getKey();
            boolean isCompleted = entry.getValue();
            
            DocumentReference taskRef = userRef.collection("tasks").document(taskId);
            batch.update(taskRef, "completed", isCompleted);
        }
        
        batch.commit()
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "Batch update of " + updates.size() + " tasks succeeded");
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Batch update failed: " + e.getMessage());
                
                // Put failed updates back in the queue
                taskCompletionUpdates.putAll(updates);
                
                // Show error to user
                Toast.makeText(context, "Failed to update tasks: " + e.getMessage(), 
                              Toast.LENGTH_SHORT).show();
            });
    }

    /**
     * Handle UI and logic changes when a task completion state changes
     */
    private void handleTaskCompletionChange(Task task, boolean isCompleted) {
        if (task == null) return;
        
        // Sync the task completion state
        task.setCompleted(isCompleted);
        
        // For completed tasks, update streak and XP rewards
        if (isCompleted) {
            // Record completion time for streaks
            long now = System.currentTimeMillis();
            updateCompletedDatesAndStreak(now, true);
        }
        
        // Update filtered list if needed based on current filter
        if (currentFilter == FILTER_COMPLETE && !isCompleted) {
            // Task was uncompleted while showing only completed tasks
            removeFromFilteredListIfNeeded(task);
        } else if (currentFilter == FILTER_INCOMPLETE && isCompleted) {
            // Task was completed while showing only incomplete tasks
            removeFromFilteredListIfNeeded(task);
        } else if (!showCompleted && isCompleted) {
            // Task was completed while hiding completed tasks
            removeFromFilteredListIfNeeded(task);
        } else {
            // Just notify the item changed
            int position = filteredTasks.indexOf(task);
            if (position >= 0) {
                notifyItemChanged(position);
            }
        }
    }
    
    /**
     * Remove a task from the filtered list if it no longer matches filters
     */
    private void removeFromFilteredListIfNeeded(Task task) {
        int position = filteredTasks.indexOf(task);
        if (position >= 0) {
            filteredTasks.remove(position);
            notifyItemRemoved(position);
        }
    }

    /**
     * Callback interface for streak calculation
     */
    public interface StreakCallback {
        void onSuccess(int streak);
        void onFailure();
    }

    /**
     * Update user's completed dates and calculate streaks
     */
    private void updateCompletedDatesAndStreak(long timestamp, boolean completed) {
        if (!completed) return;
        
        final String dateString = dateFormat.format(new Date(timestamp));
        
        userRef.get().addOnSuccessListener(documentSnapshot -> {
            if (!documentSnapshot.exists()) {
                Log.e(TAG, "User document not found");
                return;
            }
            
            // Update the completed dates
            Map<String, Object> updates = new HashMap<>();
            
            // Add today to completed dates array
            updates.put("completedDates", FieldValue.arrayUnion(dateString));
            
            // Apply updates
            userRef.update(updates)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Added completed date: " + dateString);
                    
                    // Calculate streak
                    streakHelper.calculateStreak(new StreakHelper.StreakCallback() {
                        @Override
                        public void onSuccess(int streak) {
                            // Award streak XP if streak is valid
                            if (streak > 0) {
                                long streakXp = streak * STREAK_XP_MULTIPLIER;
                                Log.d(TAG, "Current streak: " + streak + " days, awarding " + streakXp + " XP");
                                
                                // Use quest manager for consistency
                                if (questManager != null) {
                                    questManager.incrementXp(streakXp);
                                    
                                    // Show streak notification for significant streaks
                                    if (streak % 5 == 0 && streak > 0) {
                                        mainHandler.post(() -> {
                                            Toast.makeText(context, streak + " day streak! +" + streakXp + " XP", 
                                                         Toast.LENGTH_LONG).show();
                                        });
                                    }
                                }
                            }
                        }
                        
                        @Override
                        public void onFailure() {
                            Log.e(TAG, "Failed to calculate streak");
                        }
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to update completed dates", e);
                });
        });
    }
    
    /**
     * Set a new list of tasks, completely replacing the current list
     */
    public void setTasks(List<Task> newTasks) {
        backgroundExecutor.execute(() -> {
            allTasks.clear();
            if (newTasks != null) {
                allTasks.addAll(newTasks);
            }
            
            // Apply current filtering and sorting
            applyFiltersAndSort();
        });
    }
    
    /**
     * Add a task to the adapter
     */
    public void addTask(Task task) {
        if (task == null || task.getId() == null) return;
        
        // Add to the main list
        allTasks.add(task);
        
        // Check if it passes current filters
        boolean shouldShow = true;
        
        if (currentFilter == FILTER_COMPLETE && !task.isCompleted()) {
            shouldShow = false;
        } else if (currentFilter == FILTER_INCOMPLETE && task.isCompleted()) {
            shouldShow = false;
        } else if (currentFilter == FILTER_RECURRING && 
                  (task.getRecurrenceDays() <= 1 && 
                  (task.getRecurrenceGroupId() == null || task.getRecurrenceGroupId().isEmpty()))) {
            shouldShow = false;
        } else if (currentFilter == FILTER_TODAY && !TaskScheduler.isTaskScheduledForToday(task, userTimeZone, getCurrentDateString())) {
            shouldShow = false;
        } else if (!showCompleted && task.isCompleted()) {
            shouldShow = false;
        }
        
        if (shouldShow) {
            // Add to filtered list and resort
            applyFiltersAndSort();
        }
    }
    
    /**
     * Update an existing task in the adapter
     */
    public void updateTask(Task updatedTask) {
        if (updatedTask == null || updatedTask.getId() == null) return;
        
        String taskId = updatedTask.getId();
        
        // Find and update in main list
        for (int i = 0; i < allTasks.size(); i++) {
            Task task = allTasks.get(i);
            if (taskId.equals(task.getId())) {
                allTasks.set(i, updatedTask);
                break;
            }
        }
        
        // Find in filtered list
        int filteredPos = -1;
        for (int i = 0; i < filteredTasks.size(); i++) {
            if (taskId.equals(filteredTasks.get(i).getId())) {
                filteredPos = i;
                break;
            }
        }
        
        // Apply filters and sort
        applyFiltersAndSort();
    }
    
    /**
     * Remove a task from the adapter
     */
    public void removeTask(String taskId) {
        if (taskId == null) return;
        
        // Remove from main list
        Task taskToRemove = null;
        for (Task task : allTasks) {
            if (taskId.equals(task.getId())) {
                taskToRemove = task;
                break;
            }
        }
        
        if (taskToRemove != null) {
            allTasks.remove(taskToRemove);
            
            // Remove from filtered list if present
            int filteredPos = filteredTasks.indexOf(taskToRemove);
            if (filteredPos >= 0) {
                filteredTasks.remove(filteredPos);
                notifyItemRemoved(filteredPos);
            }
        }
    }
    
    /**
     * Get current date string for today in user's timezone
     */
    private String getCurrentDateString() {
        return dateFormat.format(new Date());
    }
    
    /**
     * DiffUtil callback for efficient RecyclerView updates
     */
    private static class TaskDiffCallback extends DiffUtil.Callback {
        private final List<Task> oldList;
        private final List<Task> newList;
        
        TaskDiffCallback(List<Task> oldList, List<Task> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }
        
        @Override
        public int getOldListSize() {
            return oldList.size();
        }
        
        @Override
        public int getNewListSize() {
            return newList.size();
        }
        
        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            String oldId = oldList.get(oldPos).getId();
            String newId = newList.get(newPos).getId();
            return oldId != null && oldId.equals(newId);
        }
        
        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            Task oldTask = oldList.get(oldPos);
            Task newTask = newList.get(newPos);
            
            return oldTask.isCompleted() == newTask.isCompleted() && 
                  (oldTask.getName() != null && oldTask.getName().equals(newTask.getName())) &&
                  (oldTask.getDescription() == null && newTask.getDescription() == null ||
                   oldTask.getDescription() != null && oldTask.getDescription().equals(newTask.getDescription()));
        }
    }
    
    /**
     * Clean up resources when adapter is no longer needed
     */
    public void cleanup() {
        // Flush any pending updates
        flushTaskCompletionUpdates();
        
        // Remove callback
        mainHandler.removeCallbacksAndMessages(null);
    }

    /**
     * Update the subtask text style based on completion status
     *
     * @param textView   The TextView to update
     * @param completed  Whether the subtask is completed
     */
    public void updateSubtaskStyle(TextView textView, boolean completed) {
        if (completed) {
            textView.setPaintFlags(textView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            textView.setAlpha(0.6f);
        } else {
            textView.setPaintFlags(textView.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
            textView.setAlpha(1.0f);
        }
    }

    private int calculateCompletedSteps(Task task) {
        // This would ideally come from the task model
        // For now, just estimate based on completion status
        if (task.isCompleted()) {
            return task.getActionItems().size();
        }
        
        // TODO: Track individual step completion
        return 0;
    }

    private String formatDueDate(java.util.Date dueDate) {
        // Simple date formatting - could be enhanced with relative dates
        java.text.SimpleDateFormat formatter = new java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault());
        return formatter.format(dueDate);
    }

    private boolean isTaskOverdue(Task task) {
        if (task.getDueDate() == null) return false;
        return task.getDueDate().before(new java.util.Date()) && !task.isCompleted();
    }

    private boolean isTaskDueSoon(Task task) {
        if (task.getDueDate() == null) return false;
        
        long oneDayInMillis = 24 * 60 * 60 * 1000;
        long timeUntilDue = task.getDueDate().getTime() - System.currentTimeMillis();
        
        return timeUntilDue > 0 && timeUntilDue <= oneDayInMillis;
    }

    private void setupTaskInteractions(TaskViewHolder holder, Task task, boolean canModify, boolean isUpdating) {
        if (isUpdating) {
            // Disable all interactions during updates
            holder.itemView.setOnClickListener(null);
            holder.itemView.setOnLongClickListener(null);
            holder.cbTaskCompleted.setOnCheckedChangeListener(null);
            return;
        }
        
        // Task completion toggle
        holder.cbTaskCompleted.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (canModify && interactionHandler != null) {
                markTaskAsUpdating(task.getId());
                interactionHandler.onTaskToggled(task, isChecked);
            } else {
                // Reset checkbox if user can't modify
                buttonView.setChecked(!isChecked);
            }
        });
        
        // Task details view
        holder.itemView.setOnClickListener(v -> {
            if (canModify && interactionHandler != null) {
                interactionHandler.onTaskClicked(task);
            }
        });
        
        // Task options menu
        holder.itemView.setOnLongClickListener(v -> {
            if (canModify && interactionHandler != null) {
                interactionHandler.onTaskLongPressed(task);
                return true;
            }
            return false;
        });
    }

    // Mark task as being updated to prevent UI conflicts
    public void markTaskAsUpdating(String taskId) {
        tasksBeingUpdated.add(taskId);
        refreshTaskById(taskId);
    }
    
    // Remove updating state when operation completes
    public void markTaskUpdateComplete(String taskId) {
        tasksBeingUpdated.remove(taskId);
        refreshTaskById(taskId);
    }
    
    // Refresh specific task without full list refresh
    private void refreshTaskById(String taskId) {
        for (int i = 0; i < filteredTasks.size(); i++) {
            if (filteredTasks.get(i).getId().equals(taskId)) {
                notifyItemChanged(i);
                break;
            }
        }
    }
    
    // Clean up any pending operations
    public void clearPendingUpdates() {
        tasksBeingUpdated.clear();
        notifyDataSetChanged();
    }
}