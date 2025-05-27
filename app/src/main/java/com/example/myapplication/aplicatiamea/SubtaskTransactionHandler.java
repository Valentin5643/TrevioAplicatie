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

    public interface SubtaskTransactionCallback {
        void onSuccess(SubtaskTransactionResult result);
        void onFailure(Exception e);
    }

    public static class SubtaskTransactionResult {
        public final List<Map<String, Object>> updatedStepsMapList;
        public final boolean originalParentCompletionState;

        public SubtaskTransactionResult(List<Map<String, Object>> updatedStepsMapList, boolean originalParentCompletionState) {
            this.updatedStepsMapList = updatedStepsMapList;
            this.originalParentCompletionState = originalParentCompletionState;
        }
    }

    public static void updateSubtaskCompletion(FirebaseFirestore db, String taskId, int subtaskIndex,
                                               boolean isCompleted, SubtaskTransactionCallback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || taskId == null) {
            Log.e(TAG, "No user/task? No update for you!");
            if (callback != null) callback.onFailure(new IllegalArgumentException("No user or task ID"));
            return;
        }
        

        DocumentReference taskDoc = db.collection("users").document(user.getUid())
                .collection("tasks").document(taskId);

        db.runTransaction(tx -> {

            DocumentSnapshot snapshot = tx.get(taskDoc);
            if (!snapshot.exists()) {
                throw new FirebaseFirestoreException("Task " + taskId + " pulled a disappearing act!",
                        FirebaseFirestoreException.Code.ABORTED);
            }

            Boolean parentCompleted = snapshot.getBoolean("completed");
            boolean wasCompleted = parentCompleted != null && parentCompleted;

            Object rawSteps = snapshot.get("steps");
            if (!(rawSteps instanceof List)) {
                throw new FirebaseFirestoreException("Steps aren't a list? What happened here?",
                        FirebaseFirestoreException.Code.ABORTED);
            }

            List<Map<String, Object>> steps = new ArrayList<>();
            List<?> rawList = (List<?>) rawSteps;
            
            for (Object item : rawList) {
                if (item instanceof Map) {

                    steps.add(new HashMap<>((Map<String, Object>) item));
                } else {
                    throw new FirebaseFirestoreException("Found non-map in steps list - gremlins in the database?",
                            FirebaseFirestoreException.Code.DATA_LOSS);
                }
            }

            if (subtaskIndex < 0 || subtaskIndex >= steps.size()) {
                throw new FirebaseFirestoreException("Index " + subtaskIndex + " is out of bounds! We only have " + steps.size() + " steps",
                        FirebaseFirestoreException.Code.ABORTED);
            }

            Map<String, Object> subtask = steps.get(subtaskIndex);

            Object currentState = subtask.get("completed");
            if (currentState instanceof Boolean && ((Boolean) currentState) == isCompleted) {
                return new SubtaskTransactionResult(steps, wasCompleted);
            }

            subtask.put("completed", isCompleted);

            if (!subtask.containsKey("stability")) {
                subtask.put("stability", 0);
            }

            tx.update(taskDoc, "steps", steps);
            return new SubtaskTransactionResult(steps, wasCompleted);
        }).addOnSuccessListener(result -> {
            if (callback != null) callback.onSuccess(result);
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Subtask update failed because: " + e.getMessage());
            if (callback != null) callback.onFailure(e);
        });
    }
} 