package com.example.myapplication.aplicatiamea;

import android.util.Log;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Subtask Magician 🧙‍♂️
 * 
 * Handles atomic updates for subtasks through Firebase transactions.
 * One does not simply update a subtask without a transaction...
 */
public class SubtaskTransactionHandler {
    // Good old tag for debugging hell
    private static final String TAG = "SubtaskTx";

    // Callback interface - because Firebase and its endless callbacks...
    public interface SubtaskTransactionCallback {
        void onSuccess(SubtaskTransactionResult result);
        void onFailure(Exception e);
    }

    // Holds our transaction results - simpler than 50 parameters
    public static class SubtaskTransactionResult {
        public final List<Map<String, Object>> updatedStepsMapList;
        public final boolean originalParentCompletionState;

        public SubtaskTransactionResult(List<Map<String, Object>> updatedStepsMapList, boolean originalParentCompletionState) {
            this.updatedStepsMapList = updatedStepsMapList;
            this.originalParentCompletionState = originalParentCompletionState;
        }
    }

    /**
     * The star of the show - updates a subtask's completion state.
     * 
     * Written after 3 cups of coffee and a battle with race conditions.
     * Please send help if you're reading this comment.
     */
    public static void updateSubtaskCompletion(FirebaseFirestore db, String taskId, int subtaskIndex,
                                               boolean isCompleted, SubtaskTransactionCallback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || taskId == null) {
            Log.e(TAG, "No user/task? No update for you!");
            if (callback != null) callback.onFailure(new IllegalArgumentException("No user or task ID"));
            return;
        }
        
        // Get the task doc reference - basic stuff
        DocumentReference taskDoc = db.collection("users").document(user.getUid())
                .collection("tasks").document(taskId);
                
        // The fun begins - transaction time!
        db.runTransaction(tx -> {
            // Grab the latest version
            DocumentSnapshot snapshot = tx.get(taskDoc);
            if (!snapshot.exists()) {
                throw new FirebaseFirestoreException("Task " + taskId + " pulled a disappearing act!",
                        FirebaseFirestoreException.Code.ABORTED);
            }

            // Remember parent completion state for later
            Boolean parentCompleted = snapshot.getBoolean("completed");
            boolean wasCompleted = parentCompleted != null && parentCompleted;

            // Extract the steps list (this is where things get messy)
            Object rawSteps = snapshot.get("steps");
            if (!(rawSteps instanceof List)) {
                throw new FirebaseFirestoreException("Steps aren't a list? What happened here?",
                        FirebaseFirestoreException.Code.ABORTED);
            }

            // Convert to something usable - Firebase gives us weird types
            List<Map<String, Object>> steps = new ArrayList<>();
            List<?> rawList = (List<?>) rawSteps;
            
            for (Object item : rawList) {
                if (item instanceof Map) {
                    // Deep copy because Firebase is sneaky with its objects
                    steps.add(new HashMap<>((Map<String, Object>) item));
                } else {
                    // Data corrupted somehow - blame cosmic rays
                    throw new FirebaseFirestoreException("Found non-map in steps list - gremlins in the database?",
                            FirebaseFirestoreException.Code.DATA_LOSS);
                }
            }

            // Sanity check the index - array out of bounds is no fun
            if (subtaskIndex < 0 || subtaskIndex >= steps.size()) {
                throw new FirebaseFirestoreException("Index " + subtaskIndex + " is out of bounds! We only have " + steps.size() + " steps",
                        FirebaseFirestoreException.Code.ABORTED);
            }

            // Get the subtask we actually care about
            Map<String, Object> subtask = steps.get(subtaskIndex);
            
            // Don't waste a write if nothing changed
            Object currentState = subtask.get("completed");
            if (currentState instanceof Boolean && ((Boolean) currentState) == isCompleted) {
                return new SubtaskTransactionResult(steps, wasCompleted);
            }

            // Actually update the thing
            subtask.put("completed", isCompleted);
            
            // Make sure stability field exists - future me will thank me
            if (!subtask.containsKey("stability")) {
                subtask.put("stability", 0);
            }

            // Send it to Firebase and cross fingers
            tx.update(taskDoc, "steps", steps);
            return new SubtaskTransactionResult(steps, wasCompleted);
        }).addOnSuccessListener(result -> {
            // Victory! 🎉
            if (callback != null) callback.onSuccess(result);
        }).addOnFailureListener(e -> {
            // Failure... 😭
            Log.e(TAG, "Subtask update failed because: " + e.getMessage());
            if (callback != null) callback.onFailure(e);
        });
    }
} 