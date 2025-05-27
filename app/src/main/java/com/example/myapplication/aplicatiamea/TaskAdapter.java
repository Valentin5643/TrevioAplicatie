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
import com.google.firebase.auth.FirebaseUser;
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

public class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.TaskViewHolder> {

    private static final long STREAK_XP_MULTIPLIER = 5L;
    private static final String TAG = "TaskAdapter";

    public static final int SORT_PRIORITY = 0;
    public static final int SORT_NAME = 1;
    public static final int SORT_DUE_DATE = 2;
    public static final int SORT_CREATED = 3;

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

    private final List<Task> allTasks = new ArrayList<>();
    private final List<Task> filteredTasks = new ArrayList<>();

    private final Map<String, Boolean> taskCompletionUpdates = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor backgroundExecutor = Executors.newSingleThreadExecutor();

    private int currentSortMethod = SORT_PRIORITY;
    private int currentFilter = FILTER_ALL;
    private boolean showCompleted = true;

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

        if (tasks != null) {
            this.allTasks.addAll(tasks);
            this.filteredTasks.addAll(tasks);
        }

        scheduleTaskBatchUpdates();
    }

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
    

    public void applyFiltersAndSort() {
        backgroundExecutor.execute(() -> {
            List<Task> result = new ArrayList<>(allTasks);
            if (currentFilter != FILTER_ALL) {
                List<Task> filtered = new ArrayList<>();
                String today = getCurrentDateString();
                
                for (Task task : result) {
                    boolean include = false;
                    
                    switch (currentFilter) {
                        case FILTER_TODAY:
                            include = TaskScheduler.isTaskScheduledForToday(task, userTimeZone, today);
                            break;
                        case FILTER_INCOMPLETE:
                            include = !task.isCompleted();
                            break;
                        case FILTER_COMPLETE:
                            include = task.isCompleted();
                            break;
                        case FILTER_RECURRING:
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
                List<Task> filtered = new ArrayList<>();
                for (Task task : result) {
                    if (!task.isCompleted()) {
                        filtered.add(task);
                    }
                }
                result = filtered;
            }

            if (currentSortMethod == SORT_NAME) {
                Collections.sort(result, (t1, t2) -> {
                    String name1 = t1.getName() != null ? t1.getName() : "";
                    String name2 = t2.getName() != null ? t2.getName() : "";
                    return name1.compareToIgnoreCase(name2);
                });
            } else if (currentSortMethod == SORT_DUE_DATE) {
                Collections.sort(result, (t1, t2) -> {
                    Date date1 = t1.getDeadlineTimestamp() > 0 ? new Date(t1.getDeadlineTimestamp()) : null;
                    Date date2 = t2.getDeadlineTimestamp() > 0 ? new Date(t2.getDeadlineTimestamp()) : null;

                    if (date1 == null && date2 == null) return 0;
                    if (date1 == null) return 1;
                    if (date2 == null) return -1;
                    
                    return date1.compareTo(date2);
                });
            } else if (currentSortMethod == SORT_CREATED) {
                Collections.sort(result, (t1, t2) -> {
                    Long timestamp1 = t1.getCreatedAt() != null ? t1.getCreatedAt().getTime() : null;
                    Long timestamp2 = t2.getCreatedAt() != null ? t2.getCreatedAt().getTime() : null;
                    
                    if (timestamp1 == null && timestamp2 == null) return 0;
                    if (timestamp1 == null) return 1;
                    if (timestamp2 == null) return -1;

                    return timestamp2.compareTo(timestamp1);
                });
            } else {

                Collections.sort(result, (t1, t2) -> {
                    if (t1.isCompleted() != t2.isCompleted()) {
                        return t1.isCompleted() ? 1 : -1;
                    }

                    int p1 = getPriorityValue(t1.getPriority());
                    int p2 = getPriorityValue(t2.getPriority());
                    if (p1 != p2) {
                        return p2 - p1;
                    }
                    
                    // Then by name
                    String name1 = t1.getName() != null ? t1.getName() : "";
                    String name2 = t2.getName() != null ? t2.getName() : "";
                    return name1.compareToIgnoreCase(name2);
                });
            }

            final List<Task> finalResult = new ArrayList<>(result);

            mainHandler.post(() -> {
                DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new TaskDiffCallback(filteredTasks, finalResult));
                filteredTasks.clear();
                filteredTasks.addAll(finalResult);
                diffResult.dispatchUpdatesTo(this);
            });
        });
    }
    

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

    public void setFilter(int filterType) {
        if (currentFilter != filterType) {
            currentFilter = filterType;
            applyFiltersAndSort();
        }
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

            if (subtasksContainer == null) {
                subtasksContainer = new LinearLayout(itemView.getContext());
                subtasksContainer.setOrientation(LinearLayout.VERTICAL);
                subtasksContainer.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ));

                ViewGroup parent = itemView.findViewById(R.id.taskContent);
                if (parent == null) {
                    parent = (ViewGroup) itemView;
                }
                parent.addView(subtasksContainer);
            }
        }

        void bind(Task task) {
            tvTaskName.setText(task.getName());

            if (task.getDescription() != null && !task.getDescription().isEmpty()) {
                tvTaskDescription.setText(task.getDescription());
                tvTaskDescription.setVisibility(View.VISIBLE);
            } else {
                tvTaskDescription.setVisibility(View.GONE);
            }
            
            updateTextStyle(task.isCompleted());

            boolean isRecurring = task.getRecurrenceDays() > 1 || 
                              (task.getRecurrenceGroupId() != null && !task.getRecurrenceGroupId().isEmpty());
            if (ivRecurringIndicator != null) {
                ivRecurringIndicator.setVisibility(isRecurring ? View.VISIBLE : View.GONE);
            }

            if (ivPriorityIndicator != null) {
                int priorityValue = getPriorityValue(task.getPriority());
                if (priorityValue > 0) {
                    ivPriorityIndicator.setVisibility(View.VISIBLE);

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

                if (context instanceof TaskPlannerActivity) {
                    ((TaskPlannerActivity) context).updateTaskCompletion(currentTask.getId(), isChecked);
                    updateTextStyle(isChecked);
                    return;
                }

                updateTaskCompletionState(currentTask, isChecked);

                currentTask.setCompleted(isChecked);
                updateTextStyle(isChecked);
            });

            setupSubtasks(task);

            boolean isCompleted = task.isCompleted();
            cbTaskCompleted.setChecked(isCompleted);

            if (isCompleted) {
                tvTaskName.setPaintFlags(tvTaskName.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                tvTaskName.setAlpha(0.6f);
                tvTaskDescription.setAlpha(0.6f);
            } else {
                tvTaskName.setPaintFlags(tvTaskName.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
                tvTaskName.setAlpha(1.0f);
                tvTaskDescription.setAlpha(1.0f);
            }

            boolean isBeingUpdated = tasksBeingUpdated.contains(task.getId());
            boolean canModify = interactionHandler != null ? interactionHandler.canUserModifyTask(task) : true;
            
            cbTaskCompleted.setEnabled(canModify && !isBeingUpdated);

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

                        cbSubtask.setOnCheckedChangeListener(null);
                        cbSubtask.setChecked(subtask.isCompleted());
                        updateSubtaskStyle(tvSubtaskDesc, subtask.isCompleted());

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

    private void updateSubtaskCompletion(String taskId, int index, boolean isCompleted, 
                                       Task.Subtask subtask, TextView tvSubtaskDesc) {
        if (taskId == null) return;
        
        DocumentReference taskRef = userRef.collection("tasks").document(taskId);

        boolean wasCompleted = subtask.isCompleted();
        subtask.setCompleted(isCompleted);
        if (tvSubtaskDesc != null) {
            updateSubtaskStyle(tvSubtaskDesc, isCompleted);
        }

        String fieldPath = "steps." + index + ".completed";
        taskRef.update(fieldPath, isCompleted)
            .addOnSuccessListener(aVoid -> {
                Log.d("QuestDebug", "Firestore subtask update succeeded for subtask " + index + " of task " + taskId);
                Task task = findTaskById(taskId);
                if (task != null) {
                    checkAllSubtasksCompleted(task);
                }
                Log.d("QuestDebug", "questManager=" + questManager + ", userRef=" + userRef);
                if (!wasCompleted && isCompleted) {
                    Log.d("QuestDebug", "Subtask checkbox toggled: isCompleted=" + isCompleted);
                    QuestManager qm = questManager;
                    if (qm == null) {
                        Log.d("QuestDebug", "questManager was null, creating inline.");
                        FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
                        if (user != null) {
                            DocumentReference fallbackUserRef = com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(user.getUid());
                            qm = new com.example.myapplication.aplicatiamea.repository.QuestManager(user.getUid(), context);
                            qm.recordSubtaskCompletion(1, fallbackUserRef);
                        } else {
                            Log.e("QuestDebug", "No FirebaseUser available for fallback QuestManager.");
                        }
                    } else {
                        qm.recordSubtaskCompletion(1, userRef);
                    }
                } else if (wasCompleted && !isCompleted) {
                    Log.d("QuestDebug", "Subtask checkbox toggled: isCompleted=" + isCompleted);
                    QuestManager qm = questManager;
                    if (qm == null) {
                        Log.d("QuestDebug", "questManager was null, creating inline.");
                        FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
                        if (user != null) {
                            DocumentReference fallbackUserRef = com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(user.getUid());
                            qm = new com.example.myapplication.aplicatiamea.repository.QuestManager(user.getUid(), context);
                            qm.recordSubtaskCompletion(-1, fallbackUserRef);
                        } else {
                            Log.e("QuestDebug", "No FirebaseUser available for fallback QuestManager.");
                        }
                    } else {
                        qm.recordSubtaskCompletion(-1, userRef);
                    }
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
            Log.d(TAG, "All subtasks completed, auto-completing task: " + task.getId());
            updateTaskCompletionState(task, true);
            

            task.setCompleted(true);

            int position = filteredTasks.indexOf(task);
            if (position >= 0) {
                notifyItemChanged(position);
            }
        }
    }

    private void updateTaskCompletionState(Task task, boolean isCompleted) {
        String taskId = task.getId();
        if (taskId == null) return;

        taskCompletionUpdates.put(taskId, isCompleted);

        handleTaskCompletionChange(task, isCompleted);
    }

    private void scheduleTaskBatchUpdates() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                flushTaskCompletionUpdates();
                mainHandler.postDelayed(this, 2000); // Run every 2 seconds
            }
        }, 2000);
    }
    

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

                taskCompletionUpdates.putAll(updates);

                Toast.makeText(context, "Failed to update tasks: " + e.getMessage(), 
                              Toast.LENGTH_SHORT).show();
            });
    }


    private void handleTaskCompletionChange(Task task, boolean isCompleted) {
        if (task == null) return;

        task.setCompleted(isCompleted);

        if (isCompleted) {
            long now = System.currentTimeMillis();
            updateCompletedDatesAndStreak(now, true);
        }

        if (currentFilter == FILTER_COMPLETE && !isCompleted) {
            removeFromFilteredListIfNeeded(task);
        } else if (currentFilter == FILTER_INCOMPLETE && isCompleted) {
            removeFromFilteredListIfNeeded(task);
        } else if (!showCompleted && isCompleted) {
            removeFromFilteredListIfNeeded(task);
        } else {
            int position = filteredTasks.indexOf(task);
            if (position >= 0) {
                notifyItemChanged(position);
            }
        }
    }

    private void removeFromFilteredListIfNeeded(Task task) {
        int position = filteredTasks.indexOf(task);
        if (position >= 0) {
            filteredTasks.remove(position);
            notifyItemRemoved(position);
        }
    }

    private void updateCompletedDatesAndStreak(long timestamp, boolean completed) {
        if (!completed) return;
        
        final String dateString = dateFormat.format(new Date(timestamp));
        
        userRef.get().addOnSuccessListener(documentSnapshot -> {
            if (!documentSnapshot.exists()) {
                Log.e(TAG, "User document not found");
                return;
            }

            Map<String, Object> updates = new HashMap<>();

            updates.put("completedDates", FieldValue.arrayUnion(dateString));

            userRef.update(updates)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Added completed date: " + dateString);

                    streakHelper.calculateStreak(new StreakHelper.StreakCallback() {
                        @Override
                        public void onSuccess(int streak) {
                            if (streak > 0) {
                                long streakXp = streak * STREAK_XP_MULTIPLIER;
                                Log.d(TAG, "Current streak: " + streak + " days, awarding " + streakXp + " XP");

                                if (questManager != null) {
                                    questManager.incrementXp(streakXp);

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
    

    public void setTasks(List<Task> newTasks) {
        backgroundExecutor.execute(() -> {
            allTasks.clear();
            if (newTasks != null) {
                allTasks.addAll(newTasks);
            }

            applyFiltersAndSort();
        });
    }

    private String getCurrentDateString() {
        return dateFormat.format(new Date());
    }

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

    public void updateSubtaskStyle(TextView textView, boolean completed) {
        if (completed) {
            textView.setPaintFlags(textView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            textView.setAlpha(0.6f);
        } else {
            textView.setPaintFlags(textView.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
            textView.setAlpha(1.0f);
        }
    }

    private void setupTaskInteractions(TaskViewHolder holder, Task task, boolean canModify, boolean isUpdating) {
        if (isUpdating) {
            holder.itemView.setOnClickListener(null);
            holder.itemView.setOnLongClickListener(null);
            holder.cbTaskCompleted.setOnCheckedChangeListener(null);
            return;
        }
        

        holder.cbTaskCompleted.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (canModify && interactionHandler != null) {
                markTaskAsUpdating(task.getId());
                interactionHandler.onTaskToggled(task, isChecked);
            } else {
                buttonView.setChecked(!isChecked);
            }
        });

        holder.itemView.setOnClickListener(v -> {
            if (canModify && interactionHandler != null) {
                interactionHandler.onTaskClicked(task);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (canModify && interactionHandler != null) {
                interactionHandler.onTaskLongPressed(task);
                return true;
            }
            return false;
        });
    }

    public void markTaskAsUpdating(String taskId) {
        tasksBeingUpdated.add(taskId);
        refreshTaskById(taskId);
    }

    private void refreshTaskById(String taskId) {
        for (int i = 0; i < filteredTasks.size(); i++) {
            if (filteredTasks.get(i).getId().equals(taskId)) {
                notifyItemChanged(i);
                break;
            }
        }
    }

    public void clearPendingUpdates() {
        tasksBeingUpdated.clear();
        notifyDataSetChanged();
    }
}