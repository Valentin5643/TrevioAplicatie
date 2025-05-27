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
import java.util.Map;
import java.util.HashMap;

public class TaskListAdapter extends RecyclerView.Adapter<TaskListAdapter.ViewHolder> {
    private static final String TAG = "TaskListAdapter"; 
    

    private List<Task> tasks;
    private Context ctx;
    private FirebaseFirestore db;
    
    // time stuff
    private TimeZone tz;
    private String today;
    
    // anti-spam protection
    private ConcurrentHashMap<String, Boolean> taskLocks = new ConcurrentHashMap<>(); 
    private ConcurrentHashMap<String, Boolean> subtaskLocks = new ConcurrentHashMap<>();
    private long COOLDOWN_MS;

    private StreakHelper streakHelper;

    private int lastNotifiedPos = -1;
    private boolean isDestroyed = false;

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
        this.ctx = context;
        this.db = db;
        this.tz = userTimeZone;
        this.today = actualTodayDateString;

        this.taskLocks = tasksInProgress != null ? tasksInProgress : new ConcurrentHashMap<>();
        this.subtaskLocks = subtasksInProgress != null ? subtasksInProgress : new ConcurrentHashMap<>();

        this.COOLDOWN_MS = antiSpamCooldown;
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
        LinearLayout subtaskContainer;
        ImageView recurringIcon;

        ViewHolder(View itemView) {
            super(itemView);
            checkBox = itemView.findViewById(R.id.checkBoxCompleted);
            taskText = itemView.findViewById(R.id.taskText);
            taskTime = itemView.findViewById(R.id.taskTime);
            editButton = itemView.findViewById(R.id.editButton);
            subtaskContainer = itemView.findViewById(R.id.llSubtasksContainer);
            recurringIcon = itemView.findViewById(R.id.ivRecurringIndicator);
        }

        void bind(Task task) {

            taskText.setText(task.getName());
            TextView descView = itemView.findViewById(R.id.taskDescription);
            if (task.getDescription() != null && !task.getDescription().isEmpty()) {
                descView.setText(task.getDescription());
                descView.setVisibility(View.VISIBLE);
            } else {
                descView.setText("");
                descView.setVisibility(View.GONE);
            }

            boolean isRecurring = task.getRecurrenceDays() > 1 || 
                               (task.getRecurrenceGroupId() != null && !task.getRecurrenceGroupId().isEmpty());
            recurringIcon.setVisibility(isRecurring ? View.VISIBLE : View.GONE);

            if (task.getDeadlineTimestamp() > 0) {
                SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
                timeFormat.setTimeZone(tz);
                taskTime.setText("⏰ " + timeFormat.format(new Date(task.getDeadlineTimestamp())));
                taskTime.setVisibility(View.VISIBLE);
            } else {
                taskTime.setVisibility(View.GONE);
            }


            updateTextStyle(task.isCompleted());

            checkBox.setOnCheckedChangeListener(null);
            checkBox.setChecked(task.isCompleted());


            final Context itemContext = itemView.getContext();
            boolean isForToday = TaskScheduler.isTaskScheduledForToday(task, tz, today);
            boolean canEdit = isForToday;
            

            boolean isTaskBusy = taskLocks.getOrDefault(task.getId(), false);
            checkBox.setEnabled(canEdit && !isTaskBusy);


            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!canEdit || taskLocks.getOrDefault(task.getId(), false)) {

                    checkBox.setOnCheckedChangeListener(null);
                    checkBox.setChecked(!isChecked);

                    checkBox.setOnCheckedChangeListener((b, c) -> {});
                    if (taskLocks.getOrDefault(task.getId(), false)) {
                        Toast.makeText(itemContext, "Please wait...", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    Task t = tasks.get(pos);
                    taskLocks.put(t.getId(), true);
                    checkBox.setEnabled(false);
                    updateTaskCompletion(t, itemContext);
                    new Handler().postDelayed(() -> {
                        taskLocks.put(t.getId(), false);
                        if (pos < tasks.size()) {
                            notifyItemChanged(pos);
                        }
                    }, COOLDOWN_MS);
                }
            });

            editButton.setEnabled(canEdit);
            editButton.setOnClickListener(v -> {
                if (!canEdit) return;

                boolean isRecurringTask = task.getRecurrenceDays() > 1 || 
                                     (task.getRecurrenceGroupId() != null && !task.getRecurrenceGroupId().isEmpty());
                
                if (isRecurringTask) {

                    AlertDialog.Builder builder = new AlertDialog.Builder(itemContext);
                    builder.setTitle("Task Options");
                    builder.setItems(new CharSequence[]{
                            "Edit This Task", 
                            "Edit All Tasks in Series", 
                            "Delete This Task",
                            "Delete All Tasks in Series"
                    }, (dialog, which) -> {
                        switch (which) {
                            case 0:
                                openEditTaskActivity(itemContext, task.getId(), false);
                                break;
                            case 1:
                                if (itemContext instanceof TaskListActivity) {
                                    ((TaskListActivity) itemContext).showEditAllTasksDialog(
                                            task.getRecurrenceGroupId());
                                }
                                break;
                            case 2:
                                if (itemContext instanceof TaskListActivity) {
                                    ((TaskListActivity) itemContext).showDeleteDialog(task.getId());
                                }
                                break;
                            case 3:
                                if (itemContext instanceof TaskListActivity) {
                                    ((TaskListActivity) itemContext).showDeleteSeriesDialog(
                                            task.getRecurrenceGroupId());
                                }
                                break;
                        }
                    });
                    builder.show();
                } else {
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
                    boolean isPartOfSeries = task.getRecurrenceDays() > 1 || 
                                         (task.getRecurrenceGroupId() != null && !task.getRecurrenceGroupId().isEmpty());
                    
                    if (isPartOfSeries) {
                        editButton.performClick();
                    } else {
                        ((TaskListActivity) itemContext).showDeleteDialog(task.getId());
                    }
                }
                return true;
            });

            subtaskContainer.removeAllViews();
            List<Task.Subtask> steps = task.getSteps();
            if (steps != null && !steps.isEmpty()) {

                subtaskContainer.setVisibility(View.VISIBLE);
                for (int i = 0; i < steps.size(); i++) {
                    Task.Subtask subtask = steps.get(i);
                    Log.d("DEBUG_SUBTASKS", "  Subtask " + i + ": " + 
                          "description='" + subtask.getDescription() + "', " +
                          "completed=" + subtask.isCompleted());
                    
                    View subtaskView = LayoutInflater.from(ctx)
                        .inflate(R.layout.item_subtask_display, subtaskContainer, false);
                    MaterialCheckBox cb = subtaskView.findViewById(R.id.cbSubtaskCompleted);
                    TextView tvDesc = subtaskView.findViewById(R.id.tvSubtaskDescription);
                    tvDesc.setText(subtask.getDescription());
                    cb.setChecked(subtask.isCompleted());

                    String subId = task.getId() + "_" + i;
                    boolean subProcessing = subtaskLocks.getOrDefault(subId, false);
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
                        if (!canEdit || subtaskLocks.getOrDefault(subId, false)) {
                            cb.setOnCheckedChangeListener(null);
                            cb.setChecked(!isChecked);
                            cb.setOnCheckedChangeListener((b, c) -> {});
                            if (subtaskLocks.getOrDefault(subId, false)) {
                                Toast.makeText(itemContext, "Please wait...", Toast.LENGTH_SHORT).show();
                            }
                            new Handler().postDelayed(() -> cb.setOnCheckedChangeListener((b, c) -> handleSubtaskToggle(subId, task.getId(), index, c, cb, tvDesc)), 250);
                            return;
                        }
                        handleSubtaskToggle(subId, task.getId(), index, isChecked, cb, tvDesc);
                    });
                    subtaskContainer.addView(subtaskView);
                }
            } else {
                subtaskContainer.setVisibility(View.GONE);
            }
        }

        private void handleSubtaskToggle(String subId, String taskId, int index, boolean isChecked,
                                         CheckBox cb, TextView tvDesc) {
            subtaskLocks.put(subId, true);
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
                subtaskLocks.put(subId, false);
                cb.setEnabled(true);
            }, COOLDOWN_MS);
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
            if (isDestroyed) return;
            
            SubtaskTransactionHandler.updateSubtaskCompletion(db, taskId, subtaskIndex, isCompleted,
                new SubtaskTransactionHandler.SubtaskTransactionCallback() {
                    @Override
                    public void onSuccess(SubtaskTransactionHandler.SubtaskTransactionResult result) {
                        if (isDestroyed) return;
                        
                        subtaskLocks.put(taskId + "_" + subtaskIndex, false);
                        Task localTask = findTaskById(taskId);
                        if (localTask != null && localTask.getSteps() != null && subtaskIndex < localTask.getSteps().size()) {
                            localTask.getSteps().get(subtaskIndex).setCompleted(isCompleted);
                            notifyDataSetChanged();
                        }
                        Log.d("QuestDebug", "Firestore subtask update succeeded for subtask " + subtaskIndex + " of task " + taskId);
                        Log.d("QuestDebug", "questManager is not available in TaskListAdapter, using fallback. userRef will be constructed inline.");
                        Log.d("QuestDebug", "Subtask checkbox toggled: isCompleted=" + isCompleted);
                        FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
                        if (user != null) {
                            DocumentReference fallbackUserRef = com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(user.getUid());
                            com.example.myapplication.aplicatiamea.repository.QuestManager qm = new com.example.myapplication.aplicatiamea.repository.QuestManager(user.getUid(), ctx);
                            qm.recordSubtaskCompletion(isCompleted ? 1 : -1, fallbackUserRef);
                        } else {
                            Log.e("QuestDebug", "No FirebaseUser available for fallback QuestManager.");
                        }
                    }
                    @Override
                    public void onFailure(Exception e) {
                        if (isDestroyed) return;
                        
                        subtaskLocks.put(taskId + "_" + subtaskIndex, false);
                        Toast.makeText(ctx, "Error updating subtask.", Toast.LENGTH_SHORT).show();
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
            if (task == null || task.getId() == null || task.getId().isEmpty()) {
                Log.e(TAG, "Task is null or has invalid ID, can't update completion");
                return;
            }


            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) {
                Toast.makeText(context, "Please log in to save task state", Toast.LENGTH_SHORT).show();
                return;
            }

            DocumentReference userRef = db.collection("users").document(user.getUid());
            DocumentReference taskRef = userRef.collection("tasks").document(task.getId());

            Map<String, Object> updates = new HashMap<>();
            updates.put("completed", desiredState);

            if (desiredState && task.getSteps() != null && !task.getSteps().isEmpty()) {
                List<Map<String, Object>> updatedSteps = new ArrayList<>();
                
                for (Task.Subtask subtask : task.getSteps()) {
                    Map<String, Object> stepMap = new HashMap<>();
                    stepMap.put("description", subtask.getDescription());
                    stepMap.put("completed", true);
                    stepMap.put("stability", subtask.getStability());
                    updatedSteps.add(stepMap);
                }
                
                updates.put("steps", updatedSteps);
                Log.d(TAG, "Also marking all " + updatedSteps.size() + " subtasks as complete");
            }

            long now = System.currentTimeMillis();
            if (desiredState) {
                updates.put("completedAt", now);
            } else {
                updates.put("completedAt", null);
            }

            taskRef.update(updates)
                .addOnSuccessListener(aVoid -> {
                    String actionText = desiredState ? "completed" : "marked incomplete";
                    Log.d(TAG, "Task " + task.getName() + " " + actionText);

                    task.setCompleted(desiredState);
                    if (desiredState && task.getSteps() != null) {
                        for (Task.Subtask subtask : task.getSteps()) {
                            subtask.setCompleted(true);
                        }
                    }

                    notifyItemChanged(getAdapterPosition());

                    QuestManager questManager = new QuestManager(user.getUid(), context);
                    if (desiredState) {
                        questManager.recordTaskCompletion(task, userRef);

                        if (streakHelper != null) {
                            updateCompletedDates(now);
                        }
                    } else {
                        questManager.undoTaskCompletion(task, userRef);

                        if (streakHelper != null) {
                            updateCompletedDates(now);
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to update task " + task.getId(), e);
                    Toast.makeText(context, "Failed to update task: " + e.getMessage(), 
                           Toast.LENGTH_SHORT).show();

                    checkBox.setOnCheckedChangeListener(null);
                    checkBox.setChecked(!desiredState);
                    checkBox.setOnCheckedChangeListener((b, c) -> {});
                });
        }

        private void openEditTaskActivity(Context context, String taskId, boolean isEditingSeries) {
            Intent intent = new Intent(context, AddTaskActivity.class);
            intent.putExtra("TASK_ID", taskId);
            intent.putExtra("EDIT_SERIES", isEditingSeries);
            context.startActivity(intent);
        }
    }

    public void updateCompletedDates(long dateTimeTimestamp) {
        if (isDestroyed) return;
        if (dateTimeTimestamp <= 0) {
            Log.w(TAG, "Invalid timestamp for updateCompletedDates: " + dateTimeTimestamp);
            return;
        }

        Date taskDate = new Date(dateTimeTimestamp);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sdf.setTimeZone(tz);
        String dateString = sdf.format(taskDate);

        if (dateString == null) {
            Log.e(TAG, "Couldn't format date from timestamp: " + dateTimeTimestamp);
            return;
        }
        
        // need user
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.e(TAG, "User not logged in, can't update completed dates");
            return;
        }

        DocumentReference userRef = db.collection("users").document(user.getUid());
        userRef.collection("tasks").whereEqualTo("date", dateString).get()
            .addOnSuccessListener(querySnapshot -> {
                // need at least one task for the date
                if (querySnapshot.isEmpty()) {
                    Log.d(TAG, "No tasks found for date: " + dateString);
                    return;
                }

                db.runTransaction(transaction -> {
                    DocumentSnapshot userDoc = transaction.get(userRef);
                    List<String> completedDates = userDoc.contains("completedDates")
                            ? (List<String>) userDoc.get("completedDates") : new ArrayList<>();

                    boolean allComplete = true;
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        Boolean comp = doc.getBoolean("completed");
                        if (comp == null || !comp) { 
                            allComplete = false; 
                            break; 
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
                    if (isDestroyed) return;

                    if (streakHelper != null) {
                        streakHelper.updateStreak(userRef, updatedDates, today, new StreakHelper.StreakUpdateCallback() {
                            @Override public void onSuccess(int newStreak) { 
                                if (isDestroyed) return;
                                Log.d(TAG, "Streak updated: " + newStreak); 
                            }
                            @Override public void onFailure() { 
                                if (isDestroyed) return;
                                Log.e(TAG, "Streak update failed"); 
                            }
                        });
                    }
                }).addOnFailureListener(e -> {
                    if (isDestroyed) return;
                    Log.e(TAG, "Transaction failed: " + e.getMessage());
                });
            })
            .addOnFailureListener(e -> {
                if (isDestroyed) return;
                Log.e(TAG, "Failed to query tasks: " + e.getMessage());
            });
    }
}