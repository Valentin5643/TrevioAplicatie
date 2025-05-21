package com.example.myapplication.aplicatiamea;

import android.content.Context;
import android.graphics.Paint;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.aplicatiamea.repository.QuestManager;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.util.TimeZone;

public class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.TaskViewHolder> {

    // Use Long for FieldValue.increment
    private static final long STREAK_XP_MULTIPLIER = 5L;
    private static final String TAG = "TaskAdapter";

    private final Context context;
    private final DocumentReference userRef;
    private final int textColor;
    private final int disabledColor;
    private final TimeZone userTimeZone;
    private final SimpleDateFormat dateFormat;
    private final QuestManager questManager;
    private final StreakHelper streakHelper;
    private final List<Task> tasks;

    public TaskAdapter(List<Task> tasks, Context context, DocumentReference userRef,
                       QuestManager questManager, StreakHelper streakHelper) {
        this.tasks = tasks;
        this.context = context;
        this.userRef = userRef;
        this.textColor = ContextCompat.getColor(context, R.color.black);
        this.disabledColor = ContextCompat.getColor(context, R.color.gray);
        this.userTimeZone = TimeZone.getTimeZone("Europe/Bucharest");
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        this.dateFormat.setTimeZone(userTimeZone);
        this.questManager = questManager;
        this.streakHelper = streakHelper;
    }

    @Override
    public void onBindViewHolder(@NonNull TaskViewHolder holder, int position) {
        holder.bind(tasks.get(position));
    }

    @NonNull
    @Override
    public TaskViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_task, parent, false);
        return new TaskViewHolder(view);
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    class TaskViewHolder extends RecyclerView.ViewHolder {
        MaterialCheckBox cbTaskCompleted;
        TextView tvTaskName;
        TextView tvTaskDescription;
        LinearLayout subtasksContainer;
        ImageView ivRecurringIndicator;

        TaskViewHolder(@NonNull View itemView) {
            super(itemView);
            cbTaskCompleted = itemView.findViewById(R.id.cbTaskCompleted);
            tvTaskName = itemView.findViewById(R.id.tvTaskName);
            tvTaskDescription = itemView.findViewById(R.id.tvTaskDescription);
            subtasksContainer = itemView.findViewById(R.id.subtasksContainer);
            ivRecurringIndicator = itemView.findViewById(R.id.ivRecurringIndicator);
            
            // If the subtasksContainer isn't in the layout, we'll create it
            if (subtasksContainer == null) {
                subtasksContainer = new LinearLayout(itemView.getContext());
                subtasksContainer.setOrientation(LinearLayout.VERTICAL);
                subtasksContainer.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ));
                
                // Find a suitable parent view to add the subtasks container to
                ViewGroup parent = itemView.findViewById(R.id.taskItemContainer);
                if (parent == null) {
                    parent = (ViewGroup) itemView; // Fallback to the main itemView
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
            updateTextStyle(task.isCompleted()); // Set initial style

            // Check if task is part of a recurring series
            boolean isRecurring = task.getRecurrenceDays() > 1 || 
                               (task.getRecurrenceGroupId() != null && !task.getRecurrenceGroupId().isEmpty());
            if (ivRecurringIndicator != null) {
                ivRecurringIndicator.setVisibility(isRecurring ? View.VISIBLE : View.GONE);
            }

            cbTaskCompleted.setOnCheckedChangeListener(null);
            cbTaskCompleted.setChecked(task.isCompleted()); // Set initial state

            cbTaskCompleted.setOnCheckedChangeListener((buttonView, isChecked) -> {
                 int currentPosition = getBindingAdapterPosition();
                 if (currentPosition == RecyclerView.NO_POSITION) {
                     Log.w(TAG, "ViewHolder position is invalid, skipping update.");
                     return;
                 }
                 Task currentTask = tasks.get(currentPosition);
                 if (currentTask == null || currentTask.getId() == null) {
                     Log.e(TAG, "Task or Task ID is null at position " + currentPosition);
                     buttonView.setChecked(!isChecked);
                     return;
                 }

                 // Use the improved handling in TaskPlannerActivity if available
                 if (context instanceof TaskPlannerActivity) {
                     // Directly update the task completion state without affecting subtasks
                     ((TaskPlannerActivity) context).updateTaskCompletion(currentTask.getId(), isChecked);
                     // Optimistically update UI
                     updateTextStyle(isChecked);
                     return;
                 }

                 // Fallback to original method
                 DocumentReference taskDocRef = userRef.collection("tasks").document(currentTask.getId());
                 taskDocRef.update("completed", isChecked)
                     .addOnSuccessListener(aVoid -> {
                         Log.d(TAG, "Task " + currentTask.getId() + " completion updated to " + isChecked);
                         handleTaskCompletionChange(currentTask, isChecked);
                     })
                     .addOnFailureListener(e -> {
                         Log.e(TAG, "Failed to update task " + currentTask.getId(), e);
                         if (getBindingAdapterPosition() == currentPosition) {
                            buttonView.setChecked(!isChecked);
                            updateTextStyle(!isChecked);
                            Toast.makeText(itemView.getContext(), "Failed to update task", Toast.LENGTH_SHORT).show();
                         }
                     });
            });
            
            // Clear existing subtasks
            subtasksContainer.removeAllViews();
            
            // Display subtasks if available
            List<Task.Subtask> subtasks = task.getSteps();
            if (subtasks != null && !subtasks.isEmpty()) {
                Log.d(TAG, "Binding " + subtasks.size() + " subtasks for task: " + task.getId());
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
                        
                        // Apply styles based on completion
                        updateSubtaskTextStyle(tvSubtaskDesc, subtask.isCompleted());
                        
                        // Handle checking/unchecking
                        final int index = i;
                        cbSubtask.setOnCheckedChangeListener((buttonView, isChecked) -> {
                            Log.d("TaskPlannerAdapter", "Subtask checkbox toggled: taskId=" + task.getId() + ", subtaskIndex=" + index + ", isChecked=" + isChecked);
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
            int primaryColor = ContextCompat.getColor(context, R.color.taskItemTextPrimary);
            int secondaryColor = ContextCompat.getColor(context, R.color.taskItemTextSecondary);

            if (isCompleted) {
                flags |= Paint.STRIKE_THRU_TEXT_FLAG;
                alpha = 0.7f; // Adjusted alpha for completed
                primaryColor = ContextCompat.getColor(context, R.color.taskItemCompletedText);
                secondaryColor = ContextCompat.getColor(context, R.color.taskItemCompletedText);
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
        
        // Helper method to update subtask text style
        private void updateSubtaskTextStyle(TextView tvSubtaskDesc, boolean isCompleted) {
            if (isCompleted) {
                tvSubtaskDesc.setPaintFlags(tvSubtaskDesc.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                tvSubtaskDesc.setAlpha(0.7f);
            } else {
                tvSubtaskDesc.setPaintFlags(tvSubtaskDesc.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
                tvSubtaskDesc.setAlpha(1.0f);
            }
        }
    }
    
    private void updateSubtaskCompletion(String taskId, int subtaskIndex, boolean isCompleted, 
                                         Task.Subtask subtask, TextView tvSubtaskDesc) {
        Log.d(TAG, "Updating subtask completion: task=" + taskId + ", index=" + subtaskIndex + ", completed=" + isCompleted);
        
        // Optimistically update UI
        subtask.setCompleted(isCompleted);
        if (isCompleted) {
            tvSubtaskDesc.setPaintFlags(tvSubtaskDesc.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            tvSubtaskDesc.setAlpha(0.7f);
        } else {
            tvSubtaskDesc.setPaintFlags(tvSubtaskDesc.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
            tvSubtaskDesc.setAlpha(1.0f);
        }
        
        // Delegate to TaskPlannerActivity if we're in that context
        if (context instanceof TaskPlannerActivity) {
            ((TaskPlannerActivity) context).updateSubtaskCompletion(taskId, subtaskIndex, isCompleted);
            return;
        }
        
        // Otherwise handle it directly
        DocumentReference taskRef = userRef.collection("tasks").document(taskId);
        
        FirebaseFirestore.getInstance().runTransaction(transaction -> {
            // Get the current document
            DocumentSnapshot taskSnapshot = transaction.get(taskRef);
            if (!taskSnapshot.exists()) {
                throw new FirebaseFirestoreException("Task document doesn't exist: " + taskId, 
                    FirebaseFirestoreException.Code.NOT_FOUND);
            }
            
            // Get the task object
            Task task = taskSnapshot.toObject(Task.class);
            if (task == null) {
                throw new FirebaseFirestoreException("Failed to convert document to Task", 
                    FirebaseFirestoreException.Code.INVALID_ARGUMENT);
            }
            
            // Set the document ID
            task.setId(taskSnapshot.getId());
            
            // Get steps list
            List<Task.Subtask> steps = task.getSteps();
            if (steps == null) {
                steps = new ArrayList<>();
                task.setSteps(steps);
            }
            
            // Make sure we have enough steps
            while (steps.size() <= subtaskIndex) {
                steps.add(new Task.Subtask("", false));
            }
            
            // Update the specific subtask
            Task.Subtask stepToUpdate = steps.get(subtaskIndex);
            stepToUpdate.setCompleted(isCompleted);
            
            // Check if all subtasks are completed
            boolean allCompleted = true;
            for (Task.Subtask step : steps) {
                if (!step.isCompleted()) {
                    allCompleted = false;
                    break;
                }
            }
            
            // Convert steps to a List<Map> for Firestore
            List<Map<String, Object>> stepsAsMaps = new ArrayList<>();
            for (Task.Subtask step : steps) {
                Map<String, Object> stepMap = new HashMap<>();
                stepMap.put("description", step.getDescription());
                stepMap.put("completed", step.isCompleted());
                stepMap.put("stability", step.getStability());
                stepsAsMaps.add(stepMap);
            }
            
            // Update the document
            transaction.update(taskRef, "steps", stepsAsMaps);
            
            // Update task completion status if needed
            if (allCompleted != task.isCompleted()) {
                transaction.update(taskRef, "completed", allCompleted);
            }
            
            return null;
        }).addOnSuccessListener(result -> Log.d(TAG, "Successfully updated subtask completion status")).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to update subtask: " + e.getMessage());
            // Revert UI optimistic update on failure
            subtask.setCompleted(!isCompleted);
            if (!isCompleted) {
                tvSubtaskDesc.setPaintFlags(tvSubtaskDesc.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                tvSubtaskDesc.setAlpha(0.7f);
            } else {
                tvSubtaskDesc.setPaintFlags(tvSubtaskDesc.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
                tvSubtaskDesc.setAlpha(1.0f);
            }
            Toast.makeText(context, "Failed to update subtask: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        });
    }

    private void handleTaskCompletionChange(Task task, boolean isCompleted) {
        if (questManager != null) {
            if (isCompleted) {
                questManager.recordTaskCompletion(task, userRef);
            } else {
                questManager.recordTaskUndo(task, userRef);
            }
        } else {
             Log.e("TaskAdapter", "QuestManager is null, cannot update quest progress.");
        }

        updateCompletedDatesAndStreak(task.getDateTimeTimestamp(), isCompleted); // Pass completion status
    }

    private void updateCompletedDatesAndStreak(long dateTimeTimestamp, boolean taskJustCompleted) {
         // Convert timestamp to "yyyy-MM-dd" string for querying and storage
         Date taskDate = new Date(dateTimeTimestamp);
         String dateString = dateFormat.format(taskDate); // dateFormat already includes timezone

         if (userRef == null || streakHelper == null) {
            Log.e("TaskAdapter", "Cannot update completed dates: dateString, userRef, or streakHelper is null.");
            return;
        }
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        // Query based on the formatted date string
        userRef.collection("tasks").whereEqualTo("date", dateString).get().addOnSuccessListener(querySnapshot -> {
            final String finalDateString = dateString;
            db.runTransaction(transaction -> {
                DocumentSnapshot userDoc = transaction.get(userRef);
                List<String> completedDates = (List<String>) userDoc.get("completedDates");
                if (completedDates == null) completedDates = new ArrayList<>();
                List<String> bonusAwardedDates = (List<String>) userDoc.get("bonusAwardedDates");
                if (bonusAwardedDates == null) bonusAwardedDates = new ArrayList<>();

                boolean allTasksForDateCompleted = true;
                if (querySnapshot.isEmpty()) {
                    allTasksForDateCompleted = false;
                } else {
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        Boolean taskCompleted = doc.getBoolean("completed");
                         if (taskCompleted == null || !taskCompleted) {
                            allTasksForDateCompleted = false;
                            break;
                        }
                    }
                }

                boolean changed = false;
                boolean bonusChanged = false;
                if (allTasksForDateCompleted) {
                    if (!completedDates.contains(finalDateString)) {
                        completedDates.add(finalDateString);
                        changed = true;
                    }
                    // Award bonus if not already awarded
                    if (!bonusAwardedDates.contains(finalDateString)) {
                        bonusAwardedDates.add(finalDateString);
                        bonusChanged = true;
                    }
                } else {
                    if (completedDates.remove(finalDateString)) {
                        changed = true;
                    }
                    // Revert bonus if it was awarded
                    if (bonusAwardedDates.remove(finalDateString)) {
                        bonusChanged = true;
                    }
                }

                if (changed) {
                     Collections.sort(completedDates);
                     transaction.update(userRef, "completedDates", completedDates);
                }
                if (bonusChanged) {
                    Collections.sort(bonusAwardedDates);
                    transaction.update(userRef, "bonusAwardedDates", bonusAwardedDates);
                }
                // Return a structure containing both the list and the completion status for the date, and bonus change
                return new Pair<>(new Pair<>(completedDates, allTasksForDateCompleted), new Pair<>(bonusAwardedDates, bonusChanged));

            }).addOnSuccessListener(resultPair -> {
                 Pair<List<String>, Boolean> completedPair = resultPair.first;
                 Pair<List<String>, Boolean> bonusPair = resultPair.second;
                 List<String> updatedCompletedDates = completedPair.first;
                 boolean areAllTasksDoneForDate = completedPair.second;
                 List<String> updatedBonusAwardedDates = bonusPair.first;
                 boolean bonusChanged = bonusPair.second;

                 String currentDateString = getCurrentDateStringFromContext();
                 if (currentDateString != null) {
                     streakHelper.updateStreak(userRef,
                         updatedCompletedDates,
                         currentDateString, // Use the currently viewed date by the user/activity
                         new StreakHelper.StreakUpdateCallback() {
                             @Override
                             public void onSuccess(int newStreak) {
                                 Log.d("TaskAdapter", "Streak possibly updated to " + newStreak);
                                 // Only add bonus XP if the task was just completed AND all tasks for the date are now complete AND bonus was just awarded
                                 if (taskJustCompleted && areAllTasksDoneForDate && bonusChanged && updatedBonusAwardedDates.contains(dateString) && newStreak > 0) {
                                     long bonusXp = STREAK_XP_MULTIPLIER * newStreak;
                                     userRef.update("xp", FieldValue.increment(bonusXp))
                                         .addOnSuccessListener(aVoid -> Log.d("TaskAdapter", "User bonus XP added: " + bonusXp))
                                         .addOnFailureListener(e -> Log.e("TaskAdapter", "Failed to add bonus XP", e));
                                 }
                                 // If a task was uncompleted and bonus was just reverted, subtract the bonus
                                 if (!taskJustCompleted && bonusChanged && !updatedBonusAwardedDates.contains(dateString)) {
                                     // Use previous streak value (before this uncompletion), so we fetch it from Firestore
                                     userRef.get().addOnSuccessListener(userDoc -> {
                                         Long streakVal = userDoc.getLong("streak");
                                         int streakToUse = (streakVal != null && streakVal > 0) ? streakVal.intValue() : 1;
                                         long bonusXp = STREAK_XP_MULTIPLIER * streakToUse;
                                         userRef.update("xp", FieldValue.increment(-bonusXp))
                                             .addOnSuccessListener(aVoid -> Log.d("TaskAdapter", "User bonus XP reverted: -" + bonusXp))
                                             .addOnFailureListener(e -> Log.e("TaskAdapter", "Failed to revert bonus XP", e));
                                     });
                                 }
                             }
                             @Override public void onFailure() { Log.e("TaskAdapter", "Streak update failed after task completion change."); }
                         });
                 }
            }).addOnFailureListener(e -> Log.e("TaskAdapter", "Transaction/Streak update failed for date " + finalDateString, e));
        }).addOnFailureListener(e -> Log.e("TaskAdapter", "Failed to query tasks for date " + dateString + " for streak update", e));
    }

    // Need android.util.Pair
    private static class Pair<F, S> {
        public final F first;
        public final S second;
        public Pair(F first, S second) {
            this.first = first;
            this.second = second;
        }
    }

    private String getCurrentDateStringFromContext() {
        if (context instanceof TaskListActivity) {
            return ((TaskListActivity) context).getCurrentDateString();
        }
        Log.w("TaskAdapter", "Context is not TaskListActivity, cannot get current date string.");
         SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
         sdf.setTimeZone(userTimeZone);
         return sdf.format(new java.util.Date());
    }

    // Add this method to allow dynamic updates
    public void setTasks(List<Task> newTasks) {
        this.tasks.clear();
        this.tasks.addAll(newTasks);
        notifyDataSetChanged();
    }
}