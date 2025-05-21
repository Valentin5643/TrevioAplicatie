package com.example.myapplication.aplicatiamea;

import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import com.example.myapplication.aplicatiamea.repository.QuestManager;

public class TaskListAdapter extends RecyclerView.Adapter<TaskListAdapter.ViewHolder> {
    private final List<Task> tasks;
    private final Context context;
    private final FirebaseFirestore db;
    private final TimeZone userTimeZone;
    private final String actualTodayDateString;
    private final ConcurrentHashMap<String, Boolean> tasksInProgress;
    private final ConcurrentHashMap<String, Boolean> subtasksInProgress;
    private final long ANTI_SPAM_COOLDOWN;
    private final StreakHelper streakHelper;

    public TaskListAdapter(List<Task> tasks,
                           Context context,
                           FirebaseFirestore db,
                           TimeZone userTimeZone,
                           String actualTodayDateString,
                           ConcurrentHashMap<String, Boolean> tasksInProgress,
                           ConcurrentHashMap<String, Boolean> subtasksInProgress,
                           long antiSpamCooldown,
                           StreakHelper streakHelper) {
        this.tasks = tasks;
        this.context = context;
        this.db = db;
        this.userTimeZone = userTimeZone;
        this.actualTodayDateString = actualTodayDateString;
        this.tasksInProgress = tasksInProgress;
        this.subtasksInProgress = subtasksInProgress;
        this.ANTI_SPAM_COOLDOWN = antiSpamCooldown;
        this.streakHelper = streakHelper;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.task_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(tasks.get(position));
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        CheckBox checkBox;
        TextView taskText;
        TextView taskTime;
        Button editButton;
        LinearLayout llSubtasksContainer;
        ImageView ivRecurringIndicator;

        ViewHolder(View itemView) {
            super(itemView);
            checkBox = itemView.findViewById(R.id.checkBoxCompleted);
            taskText = itemView.findViewById(R.id.taskText);
            taskTime = itemView.findViewById(R.id.taskTime);
            editButton = itemView.findViewById(R.id.editButton);
            llSubtasksContainer = itemView.findViewById(R.id.llSubtasksContainer);
            ivRecurringIndicator = itemView.findViewById(R.id.ivRecurringIndicator);
        }

        void bind(Task task) {
            // [DEBUG] Log task being bound in adapter with subtask information
            Log.d("DEBUG_SUBTASKS", "TaskListAdapter binding task: " + task.getId() + 
                  ", Name: " + task.getName() + 
                  ", RecurrenceGroupId: " + task.getRecurrenceGroupId() +
                  ", RecurrenceDays: " + task.getRecurrenceDays() +
                  ", Subtasks count: " + (task.getSteps() != null ? task.getSteps().size() : "null"));
            
            taskText.setText(task.getName());

            TextView taskDescriptionView = itemView.findViewById(R.id.taskDescription);
            if (task.getDescription() != null && !task.getDescription().isEmpty()) {
                taskDescriptionView.setText(task.getDescription());
                taskDescriptionView.setVisibility(View.VISIBLE);
            } else {
                taskDescriptionView.setText("");
                taskDescriptionView.setVisibility(View.GONE);
            }

            // Check if task is part of a recurring series
            boolean isRecurring = task.getRecurrenceDays() > 1 || 
                                 (task.getRecurrenceGroupId() != null && !task.getRecurrenceGroupId().isEmpty());
            ivRecurringIndicator.setVisibility(isRecurring ? View.VISIBLE : View.GONE);

            if (task.getDeadlineTimestamp() > 0) {
                SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
                timeFormat.setTimeZone(userTimeZone);
                taskTime.setText("⏰ " + timeFormat.format(new Date(task.getDeadlineTimestamp())));
                taskTime.setVisibility(View.VISIBLE);
            } else {
                taskTime.setVisibility(View.GONE);
            }

            updateTextStyle(task.isCompleted());

            checkBox.setOnCheckedChangeListener(null);
            checkBox.setChecked(task.isCompleted());

            final Context itemContext = itemView.getContext();
            boolean isForToday = TaskScheduler.isTaskScheduledForToday(task, userTimeZone, actualTodayDateString);
            boolean canEdit = isForToday;
            boolean isProcessing = tasksInProgress.getOrDefault(task.getId(), false);
            checkBox.setEnabled(canEdit && !isProcessing);

            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!canEdit || tasksInProgress.getOrDefault(task.getId(), false)) {
                    // revert
                    checkBox.setOnCheckedChangeListener(null);
                    checkBox.setChecked(!isChecked);
                    // re-attach minimal
                    checkBox.setOnCheckedChangeListener((b, c) -> {});
                    if (tasksInProgress.getOrDefault(task.getId(), false)) {
                        Toast.makeText(itemContext, "Please wait...", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    Task t = tasks.get(pos);
                    tasksInProgress.put(t.getId(), true);
                    checkBox.setEnabled(false);
                    updateTaskCompletion(t, itemContext);
                    new Handler().postDelayed(() -> {
                        tasksInProgress.put(t.getId(), false);
                        if (pos < tasks.size()) {
                            notifyItemChanged(pos);
                        }
                    }, ANTI_SPAM_COOLDOWN);
                }
            });

            editButton.setEnabled(canEdit);
            editButton.setOnClickListener(v -> {
                if (!canEdit) return;
                
                // Check if this is a recurring task
                boolean isRecurringTask = task.getRecurrenceDays() > 1 || 
                                     (task.getRecurrenceGroupId() != null && !task.getRecurrenceGroupId().isEmpty());
                
                if (isRecurringTask) {
                    // Show options for recurring tasks
                    AlertDialog.Builder builder = new AlertDialog.Builder(itemContext);
                    builder.setTitle("Task Options");
                    builder.setItems(new CharSequence[]{
                            "Edit This Task", 
                            "Edit All Tasks in Series", 
                            "Delete This Task",
                            "Delete All Tasks in Series"
                    }, (dialog, which) -> {
                        switch (which) {
                            case 0: // Edit This Task
                                openEditTaskActivity(itemContext, task.getId(), false);
                                break;
                            case 1: // Edit All Tasks in Series
                                if (itemContext instanceof TaskListActivity) {
                                    ((TaskListActivity) itemContext).showEditAllTasksDialog(
                                            task.getRecurrenceGroupId());
                                }
                                break;
                            case 2: // Delete This Task
                                if (itemContext instanceof TaskListActivity) {
                                    ((TaskListActivity) itemContext).showDeleteDialog(task.getId());
                                }
                                break;
                            case 3: // Delete All Tasks in Series
                                if (itemContext instanceof TaskListActivity) {
                                    ((TaskListActivity) itemContext).showDeleteSeriesDialog(
                                            task.getRecurrenceGroupId());
                                }
                                break;
                        }
                    });
                    builder.show();
                } else {
                    // Original behavior for non-recurring tasks
                    AlertDialog.Builder builder = new AlertDialog.Builder(itemContext);
                    builder.setTitle("Task Options");
                    builder.setItems(new CharSequence[]{"Edit Task", "Delete Task"}, (dialog, which) -> {
                        switch (which) {
                            case 0:
                                openEditTaskActivity(itemContext, task.getId(), false);
                                break;
                            case 1:
                                if (itemContext instanceof TaskListActivity) {
                                    ((TaskListActivity) itemContext).showDeleteDialog(task.getId());
                                }
                                break;
                        }
                    });
                    builder.show();
                }
            });
            
            itemView.setOnLongClickListener(v -> {
                if (itemContext instanceof TaskListActivity) {
                    // For recurring tasks, show the special options dialog
                    boolean isPartOfSeries = task.getRecurrenceDays() > 1 || 
                                         (task.getRecurrenceGroupId() != null && !task.getRecurrenceGroupId().isEmpty());
                    
                    if (isPartOfSeries) {
                        editButton.performClick(); // Reuse the options dialog
                    } else {
                        ((TaskListActivity) itemContext).showDeleteDialog(task.getId());
                    }
                }
                return true;
            });

            // subtasks
            llSubtasksContainer.removeAllViews();
            List<Task.Subtask> steps = task.getSteps();
            if (steps != null && !steps.isEmpty()) {
                // [DEBUG] Log that we're displaying subtasks
                Log.d("DEBUG_SUBTASKS", "Displaying " + steps.size() + " subtasks for task: " + task.getId());
                
                llSubtasksContainer.setVisibility(View.VISIBLE);
                for (int i = 0; i < steps.size(); i++) {
                    Task.Subtask subtask = steps.get(i);
                    Log.d("DEBUG_SUBTASKS", "  Subtask " + i + ": " + 
                          "description='" + subtask.getDescription() + "', " +
                          "completed=" + subtask.isCompleted());
                    
                    View subtaskView = LayoutInflater.from(context)
                        .inflate(R.layout.item_subtask_display, llSubtasksContainer, false);
                    MaterialCheckBox cb = subtaskView.findViewById(R.id.cbSubtaskCompleted);
                    TextView tvDesc = subtaskView.findViewById(R.id.tvSubtaskDescription);
                    tvDesc.setText(subtask.getDescription());
                    cb.setChecked(subtask.isCompleted());

                    String subId = task.getId() + "_" + i;
                    boolean subProcessing = subtasksInProgress.getOrDefault(subId, false);
                    cb.setEnabled(canEdit && !subProcessing);

                    if (subtask.isCompleted()) {
                        tvDesc.setPaintFlags(tvDesc.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                        tvDesc.setAlpha(0.7f);
                    } else {
                        tvDesc.setPaintFlags(tvDesc.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
                        tvDesc.setAlpha(1.0f);
                    }

                    int index = i;
                    cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        if (!canEdit || subtasksInProgress.getOrDefault(subId, false)) {
                            cb.setOnCheckedChangeListener(null);
                            cb.setChecked(!isChecked);
                            cb.setOnCheckedChangeListener((b, c) -> {});
                            if (subtasksInProgress.getOrDefault(subId, false)) {
                                Toast.makeText(itemContext, "Please wait...", Toast.LENGTH_SHORT).show();
                            }
                            new Handler().postDelayed(() -> cb.setOnCheckedChangeListener((b, c) -> handleSubtaskToggle(subId, task.getId(), index, c, cb, tvDesc)), 250);
                            return;
                        }
                        handleSubtaskToggle(subId, task.getId(), index, isChecked, cb, tvDesc);
                    });
                    llSubtasksContainer.addView(subtaskView);
                }
            } else {
                // [DEBUG] Log that no subtasks are being displayed
                Log.d("DEBUG_SUBTASKS", "No subtasks to display for task: " + task.getId() + 
                      " (steps is " + (steps == null ? "null" : "empty") + ")");
                
                llSubtasksContainer.setVisibility(View.GONE);
            }
        }

        private void handleSubtaskToggle(String subId, String taskId, int index, boolean isChecked,
                                         CheckBox cb, TextView tvDesc) {
            subtasksInProgress.put(subId, true);
            cb.setEnabled(false);
            updateSubtaskCompletion(taskId, index, isChecked);
            if (isChecked) {
                tvDesc.setPaintFlags(tvDesc.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                tvDesc.setAlpha(0.7f);
            } else {
                tvDesc.setPaintFlags(tvDesc.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
                tvDesc.setAlpha(1.0f);
            }
            new Handler().postDelayed(() -> {
                subtasksInProgress.put(subId, false);
                cb.setEnabled(true);
            }, ANTI_SPAM_COOLDOWN);
        }

        private void updateTextStyle(boolean isCompleted) {
            int flags = taskText.getPaintFlags();
            float alpha = 1.0f;
            if (isCompleted) {
                flags |= Paint.STRIKE_THRU_TEXT_FLAG;
                alpha = 0.7f;
            } else {
                flags &= (~Paint.STRIKE_THRU_TEXT_FLAG);
            }
            taskText.setPaintFlags(flags);
            taskText.setAlpha(alpha);
            taskTime.setPaintFlags(flags);
            taskTime.setAlpha(alpha);
            TextView desc = itemView.findViewById(R.id.taskDescription);
            if (desc != null && desc.getVisibility() == View.VISIBLE) {
                desc.setPaintFlags(flags);
                desc.setAlpha(alpha);
            }
        }

        private void updateSubtaskCompletion(String taskId, int subtaskIndex, boolean isCompleted) {
            SubtaskTransactionHandler.updateSubtaskCompletion(db, taskId, subtaskIndex, isCompleted,
                new SubtaskTransactionHandler.SubtaskTransactionCallback() {
                    @Override
                    public void onSuccess(SubtaskTransactionHandler.SubtaskTransactionResult result) {
                        subtasksInProgress.put(taskId + "_" + subtaskIndex, false);
                        Task localTask = findTaskById(taskId);
                        if (localTask != null && localTask.getSteps() != null && subtaskIndex < localTask.getSteps().size()) {
                            localTask.getSteps().get(subtaskIndex).setCompleted(isCompleted);
                            notifyDataSetChanged();
                        }
                    }
                    @Override
                    public void onFailure(Exception e) {
                        subtasksInProgress.put(taskId + "_" + subtaskIndex, false);
                        Toast.makeText(context, "Error updating subtask.", Toast.LENGTH_SHORT).show();
                        notifyDataSetChanged();
                    }
                });
        }

        private Task findTaskById(String targetId) {
            for (Task t : tasks) {
                if (targetId.equals(t.getId())) return t;
            }
            return null;
        }

        private void updateTaskCompletion(Task task, Context context) {
            updateTaskCompletion(task, context, !task.isCompleted());
        }

        private void updateTaskCompletion(Task task, Context context, boolean desiredState) {
            if (task == null || task.getId() == null) return;
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) {
                Toast.makeText(context, "Authentication error", Toast.LENGTH_SHORT).show();
                return;
            }
            DocumentReference userRef = db.collection("users").document(user.getUid());
            DocumentReference taskRef = userRef.collection("tasks").document(task.getId());
            if (task.isCompleted() == desiredState) return;
            taskRef.update("completed", desiredState)
                .addOnSuccessListener(aVoid -> {
                    int pos = tasks.indexOf(task);
                    if (pos != -1) {
                        task.setCompleted(desiredState);
                        notifyItemChanged(pos);
                    }
                    QuestManager qm = new QuestManager(user.getUid(), context);
                    if (desiredState) {
                        if (context instanceof TaskListActivity) {
                            ((TaskListActivity) context).awardPointsAndXpInTransaction(userRef, task.getDifficulty(), task.getId());
                            qm.recordTaskCompletion(task, userRef);
                        }
                        if (task.getSteps() != null && !task.getSteps().isEmpty()) {
                            int count = 0;
                            for (Task.Subtask s : task.getSteps()) if (s.isCompleted()) count++;
                            if (count > 0) qm.recordSubtaskCompletion(count, userRef);
                        }
                        checkDailyCompletionBonus(task.getDeadlineTimestamp());
                    } else {
                        if (context instanceof TaskListActivity) {
                            ((TaskListActivity) context).deductPointsAndXpInTransaction(userRef, task.getId());
                        }
                        qm.recordTaskUndo(task, userRef);
                    }
                    updateCompletedDates(task.getDeadlineTimestamp());
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(context, "Error updating task. Please try again.", Toast.LENGTH_SHORT).show();
                    int pos = tasks.indexOf(task);
                    if (pos != -1) notifyItemChanged(pos);
                    notifyDataSetChanged();
                });
        }

        private void openEditTaskActivity(Context context, String taskId, boolean isEditingSeries) {
            Intent intent = new Intent(context, AddTaskActivity.class);
            intent.putExtra("TASK_ID", taskId);
            intent.putExtra("EDIT_SERIES", isEditingSeries);
            context.startActivity(intent);
        }
    }

    /**
     * Updates the completedDates list in Firestore and triggers streak update.
     */
    public void updateCompletedDates(long dateTimeTimestamp) {
        Date taskDate = new Date(dateTimeTimestamp);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sdf.setTimeZone(userTimeZone);
        String dateString = sdf.format(taskDate);
        if (dateString == null) return;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        DocumentReference userRef = db.collection("users").document(user.getUid());
        userRef.collection("tasks").whereEqualTo("date", dateString).get()
            .addOnSuccessListener(querySnapshot -> db.runTransaction(transaction -> {
                DocumentSnapshot userDoc = transaction.get(userRef);
                List<String> completedDates = userDoc.contains("completedDates")
                        ? (List<String>) userDoc.get("completedDates") : new ArrayList<>();
                boolean allComplete = !querySnapshot.isEmpty();
                if (allComplete) {
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        Boolean comp = doc.getBoolean("completed");
                        if (comp == null || !comp) { allComplete = false; break; }
                    }
                }
                boolean changed = false;
                if (allComplete && !completedDates.contains(dateString)) {
                    completedDates.add(dateString);
                    changed = true;
                } else if (!allComplete && completedDates.remove(dateString)) {
                    changed = true;
                }
                if (changed) {
                    Collections.sort(completedDates);
                    transaction.update(userRef, "completedDates", completedDates);
                }
                return completedDates;
            }).addOnSuccessListener(updatedDates -> {
                if (streakHelper != null) {
                    streakHelper.updateStreak(userRef, updatedDates, actualTodayDateString, new StreakHelper.StreakUpdateCallback() {
                        @Override public void onSuccess(int newStreak) { Log.d("TaskListAdapter", "Streak updated: " + newStreak); }
                        @Override public void onFailure() { Log.e("TaskListAdapter", "Streak update failed"); }
                    });
                }
            }))
            .addOnFailureListener(e -> Log.e("TaskListAdapter", "Failed to update completedDates", e));
    }

    /**
     * Checks if all tasks for a date are complete and awards daily bonus points.
     */
    public void checkDailyCompletionBonus(long dateTimeTimestamp) {
        Date taskDate = new Date(dateTimeTimestamp);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sdf.setTimeZone(userTimeZone);
        String dateString = sdf.format(taskDate);
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        DocumentReference userRef = db.collection("users").document(user.getUid());
        userRef.collection("tasks").whereEqualTo("date", dateString).get()
            .addOnSuccessListener(querySnapshot -> {
                boolean allDone = !querySnapshot.isEmpty();
                if (allDone) {
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        Boolean comp = doc.getBoolean("completed");
                        if (comp == null || !comp) { allDone = false; break; }
                    }
                }
                if (allDone) {
                    ((TaskListActivity) context).awardDailyBonusInTransaction(userRef, dateString);
                    QuestManager qm = new QuestManager(user.getUid(), context);
                    qm.recordWeeklyDayCompletion();
                }
            });
    }
} 