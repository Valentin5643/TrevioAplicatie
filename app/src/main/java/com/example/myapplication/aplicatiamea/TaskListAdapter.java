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
    // debug tag
    private static final String TAG = "TaskListAdapter"; 
    
    // main data stuff
    private List<Task> tasks;  // should be final but need to update sometimes
    private Context ctx;      // activity context
    private FirebaseFirestore db;
    
    // time stuff
    private TimeZone tz;
    private String today;
    
    // anti-spam protection cuz users are CRAZY
    private ConcurrentHashMap<String, Boolean> taskLocks = new ConcurrentHashMap<>(); 
    private ConcurrentHashMap<String, Boolean> subtaskLocks = new ConcurrentHashMap<>();
    private long COOLDOWN_MS;
    
    // streak helper
    private StreakHelper streakHelper;
    
    // debug and testing crap
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
        // basic setup
        this.tasks = tasks;
        this.ctx = context;
        this.db = db;
        this.tz = userTimeZone;
        this.today = actualTodayDateString;
        
        // use passed-in locks if available
        this.taskLocks = tasksInProgress != null ? tasksInProgress : new ConcurrentHashMap<>();
        this.subtaskLocks = subtasksInProgress != null ? subtasksInProgress : new ConcurrentHashMap<>();
        
        // other stuff
        this.COOLDOWN_MS = antiSpamCooldown;
        this.streakHelper = streakHelper;
        
        //Log.d(TAG, "Created adapter with " + tasks.size() + " tasks for date " + actualTodayDateString);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.task_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        // just use bind method in viewholder
        holder.bind(tasks.get(position));
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }
    
    // only used by TaskListActivity when tasks change
    public void updateTasks(List<Task> newTasks) {
        this.tasks = newTasks;
        // could use DiffUtil but meh, too much work for now
        notifyDataSetChanged();
    }
    
    // called when app is exiting to prevent leaks
    public void cleanup() {
        isDestroyed = true;
        // not critical but helps GC
        tasks = null;
        ctx = null;
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        // ui components
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
            // useful for debugging subtask issues
            // Log.d("DEBUG_SUBTASKS", "Binding task: " + task.getId() + 
            //      ", Name: " + task.getName() + 
            //      ", RecurrenceGroupId: " + task.getRecurrenceGroupId() +
            //      ", RecurrenceDays: " + task.getRecurrenceDays() +
            //      ", Subtasks count: " + (task.getSteps() != null ? task.getSteps().size() : "null"));
            
            // basic info
            taskText.setText(task.getName());

            // set description if exists
            TextView descView = itemView.findViewById(R.id.taskDescription);
            if (task.getDescription() != null && !task.getDescription().isEmpty()) {
                descView.setText(task.getDescription());
                descView.setVisibility(View.VISIBLE);
            } else {
                descView.setText("");
                descView.setVisibility(View.GONE);
            }

            // show recurring indicator if needed
            boolean isRecurring = task.getRecurrenceDays() > 1 || 
                               (task.getRecurrenceGroupId() != null && !task.getRecurrenceGroupId().isEmpty());
            recurringIcon.setVisibility(isRecurring ? View.VISIBLE : View.GONE);

            // set time display if task has a deadline
            if (task.getDeadlineTimestamp() > 0) {
                SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
                timeFormat.setTimeZone(tz);
                taskTime.setText("⏰ " + timeFormat.format(new Date(task.getDeadlineTimestamp())));
                taskTime.setVisibility(View.VISIBLE);
            } else {
                taskTime.setVisibility(View.GONE);
            }

            // update text style based on completion
            updateTextStyle(task.isCompleted());

            // handle checkbox state, important to reset listener first!
            checkBox.setOnCheckedChangeListener(null);
            checkBox.setChecked(task.isCompleted());

            // figure out if task can be edited today
            final Context itemContext = itemView.getContext();
            boolean isForToday = TaskScheduler.isTaskScheduledForToday(task, tz, today);
            boolean canEdit = isForToday;  // simple for now, but might add more conditions later
            
            // don't allow edits if task is being processed
            boolean isTaskBusy = taskLocks.getOrDefault(task.getId(), false);
            checkBox.setEnabled(canEdit && !isTaskBusy);

            // now setup the listener for checkbox clicks
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!canEdit || taskLocks.getOrDefault(task.getId(), false)) {
                    // revert
                    checkBox.setOnCheckedChangeListener(null);
                    checkBox.setChecked(!isChecked);
                    // re-attach minimal
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
            subtaskContainer.removeAllViews();
            List<Task.Subtask> steps = task.getSteps();
            if (steps != null && !steps.isEmpty()) {
                // [DEBUG] Log that we're displaying subtasks
                Log.d("DEBUG_SUBTASKS", "Displaying " + steps.size() + " subtasks for task: " + task.getId());
                
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
                // [DEBUG] Log that no subtasks are being displayed
                Log.d("DEBUG_SUBTASKS", "No subtasks to display for task: " + task.getId() + 
                      " (steps is " + (steps == null ? "null" : "empty") + ")");
                
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
            // if the adapter is gone, don't bother
            if (isDestroyed) return;
            
            SubtaskTransactionHandler.updateSubtaskCompletion(db, taskId, subtaskIndex, isCompleted,
                new SubtaskTransactionHandler.SubtaskTransactionCallback() {
                    @Override
                    public void onSuccess(SubtaskTransactionHandler.SubtaskTransactionResult result) {
                        // we might be destroyed by now
                        if (isDestroyed) return;
                        
                        subtaskLocks.put(taskId + "_" + subtaskIndex, false);
                        Task localTask = findTaskById(taskId);
                        if (localTask != null && localTask.getSteps() != null && subtaskIndex < localTask.getSteps().size()) {
                            localTask.getSteps().get(subtaskIndex).setCompleted(isCompleted);
                            notifyDataSetChanged();
                        }
                    }
                    @Override
                    public void onFailure(Exception e) {
                        // bail if destroyed
                        if (isDestroyed) return;
                        
                        subtaskLocks.put(taskId + "_" + subtaskIndex, false);
                        Toast.makeText(ctx, "Error updating subtask.", Toast.LENGTH_SHORT).show();
                        notifyDataSetChanged();
                    }
                });
        }

        private Task findTaskById(String targetId) {
            // simple linear search, don't need hashmap for small lists
            for (Task t : tasks) {
                if (targetId.equals(t.getId())) return t;
            }
            return null;
        }

        private void updateTaskCompletion(Task task, Context context) {
            updateTaskCompletion(task, context, !task.isCompleted());
        }

        private void updateTaskCompletion(Task task, Context context, boolean desiredState) {
            // quick null checks
            if (task == null || task.getId() == null) return;
            if (isDestroyed) return;
            
            // user auth check
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) {
                Toast.makeText(context, "Authentication error", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // grab db refs
            DocumentReference userRef = db.collection("users").document(user.getUid());
            DocumentReference taskRef = userRef.collection("tasks").document(task.getId());
            
            // short-circuit if task is already in the state we want
            if (task.isCompleted() == desiredState) return;
            
            // update the task in firestore
            taskRef.update("completed", desiredState)
                .addOnSuccessListener(aVoid -> {
                    // might be destroyed by now
                    if (isDestroyed) return;
                    
                    // update local state
                    int pos = tasks.indexOf(task);
                    if (pos != -1) {
                        task.setCompleted(desiredState);
                        notifyItemChanged(pos);
                    }
                    
                    // do some quest stuff
                    QuestManager qm = new QuestManager(user.getUid(), context);
                    if (desiredState) {
                        // task completed
                        if (context instanceof TaskListActivity) {
                            // award points and xp
                            ((TaskListActivity) context).awardPointsAndXpInTransaction(userRef, task.getDifficulty(), task.getId());
                            qm.recordTaskCompletion(task, userRef);
                        }
                        
                        // also count subtasks
                        if (task.getSteps() != null && !task.getSteps().isEmpty()) {
                            int count = 0;
                            for (Task.Subtask s : task.getSteps()) if (s.isCompleted()) count++;
                            if (count > 0) qm.trackSubtaskFinish(count, userRef);
                        }
                        
                        // check if we completed all for today
                        checkDailyCompletionBonus(task.getDeadlineTimestamp());
                    } else {
                        // task uncompleted
                        if (context instanceof TaskListActivity) {
                            ((TaskListActivity) context).deductPointsAndXpInTransaction(userRef, task.getId());
                        }
                        qm.recordTaskUndo(task, userRef);
                    }
                    
                    // update streaks
                    updateCompletedDates(task.getDeadlineTimestamp());
                })
                .addOnFailureListener(e -> {
                    // might be destroyed by now
                    if (isDestroyed) return;
                    
                    // show error and refresh UI
                    Log.e(TAG, "Failed to update task: " + e.getMessage());
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
     * This is called whenever a task is completed/uncompleted.
     */
    public void updateCompletedDates(long dateTimeTimestamp) {
        // bail if destroyed
        if (isDestroyed) return;
        
        // can't do anything without a timestamp
        if (dateTimeTimestamp <= 0) {
            Log.w(TAG, "Invalid timestamp for updateCompletedDates: " + dateTimeTimestamp);
            return;
        }

        Date taskDate = new Date(dateTimeTimestamp);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sdf.setTimeZone(tz);
        String dateString = sdf.format(taskDate);
        
        // more validation
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
        
        // do the work
        DocumentReference userRef = db.collection("users").document(user.getUid());
        userRef.collection("tasks").whereEqualTo("date", dateString).get()
            .addOnSuccessListener(querySnapshot -> {
                // need at least one task for the date
                if (querySnapshot.isEmpty()) {
                    Log.d(TAG, "No tasks found for date: " + dateString);
                    return;
                }
                
                // run a transaction to update the completed dates list
                db.runTransaction(transaction -> {
                    DocumentSnapshot userDoc = transaction.get(userRef);
                    List<String> completedDates = userDoc.contains("completedDates")
                            ? (List<String>) userDoc.get("completedDates") : new ArrayList<>();
                    
                    // check if all tasks for the date are completed
                    boolean allComplete = true;
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        Boolean comp = doc.getBoolean("completed");
                        if (comp == null || !comp) { 
                            allComplete = false; 
                            break; 
                        }
                    }
                    
                    // update the list if needed
                    boolean changed = false;
                    if (allComplete && !completedDates.contains(dateString)) {
                        completedDates.add(dateString);
                        changed = true;
                    } else if (!allComplete && completedDates.remove(dateString)) {
                        changed = true;
                    }
                    
                    // only update firestore if we changed something
                    if (changed) {
                        Collections.sort(completedDates);
                        transaction.update(userRef, "completedDates", completedDates);
                    }
                    
                    return completedDates;
                }).addOnSuccessListener(updatedDates -> {
                    // might be destroyed by now
                    if (isDestroyed) return;
                    
                    // update streak if helper exists
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
                    // might be destroyed by now
                    if (isDestroyed) return;
                    Log.e(TAG, "Transaction failed: " + e.getMessage());
                });
            })
            .addOnFailureListener(e -> {
                // might be destroyed by now
                if (isDestroyed) return;
                Log.e(TAG, "Failed to query tasks: " + e.getMessage());
            });
    }

    /**
     * Checks if all tasks for a date are complete and awards daily bonus points.
     * Only called when completing a task (not uncompleting).
     */
    public void checkDailyCompletionBonus(long dateTimeTimestamp) {
        // bail if destroyed
        if (isDestroyed) return;
        
        // can't do anything without a timestamp
        if (dateTimeTimestamp <= 0) return;
        
        // get the date string
        Date taskDate = new Date(dateTimeTimestamp);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sdf.setTimeZone(tz);
        String dateString = sdf.format(taskDate);
        
        // need user
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        
        // check if all tasks for the date are completed
        DocumentReference userRef = db.collection("users").document(user.getUid());
        userRef.collection("tasks").whereEqualTo("date", dateString).get()
            .addOnSuccessListener(querySnapshot -> {
                // no tasks for this date
                if (querySnapshot.isEmpty()) return;
                
                // check if all are completed
                boolean allDone = true;
                for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                    Boolean comp = doc.getBoolean("completed");
                    if (comp == null || !comp) { 
                        allDone = false; 
                        break; 
                    }
                }
                
                // award bonus if all done
                if (allDone) {
                    // give em bonus points
                    ((TaskListActivity) ctx).awardDailyBonusInTransaction(userRef, dateString);
                    
                    // update their weekly quest progress
                    QuestManager qm = new QuestManager(user.getUid(), ctx);
                    qm.logWeeklyDayCompletion();
                    
                    // show notification if we haven't already
                    int pos = getAdapterPosition();
                    if (pos != lastNotifiedPos) {
                        // store so we don't show it again
                        lastNotifiedPos = pos;
                        
                        // maybe show a toast for completion
                        Toast.makeText(ctx, "All tasks completed for today!", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .addOnFailureListener(e -> {
                // silently fail, not critical
                Log.e(TAG, "Failed to query tasks for bonus: " + e.getMessage());
            });
    }
    
    // hacky way to get position in adapter outside viewholder
    private int getAdapterPosition() {
        return tasks != null ? tasks.size() : 0;
    }
} 