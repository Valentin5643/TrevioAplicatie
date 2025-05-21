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

public class SubtaskTransactionHandler {

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
            Log.e("SubtaskHandler", "User or Task ID null in updateSubtaskCompletion");
            if (callback != null) callback.onFailure(new IllegalArgumentException("User or Task ID null"));
            return;
        }
        DocumentReference taskRef = db.collection("users").document(user.getUid())
                .collection("tasks").document(taskId);
        db.runTransaction(transaction -> {
            DocumentSnapshot taskSnapshot = transaction.get(taskRef);
            if (!taskSnapshot.exists()) {
                throw new FirebaseFirestoreException("Task not found: " + taskId,
                        FirebaseFirestoreException.Code.ABORTED);
            }

            Boolean parentCompletedFromDB = taskSnapshot.getBoolean("completed");
            boolean parentOriginallyCompletedFromDB = parentCompletedFromDB != null && parentCompletedFromDB;

            Object rawSteps = taskSnapshot.get("steps");
            if (!(rawSteps instanceof List)) {
                throw new FirebaseFirestoreException("'steps' field is not a List or null.",
                        FirebaseFirestoreException.Code.ABORTED);
            }

            List<Map<String, Object>> stepsMapList = new ArrayList<>();
            List<?> rawList = (List<?>) rawSteps;
            for (Object item : rawList) {
                if (item instanceof Map) {
                    stepsMapList.add(new HashMap<>((Map<String, Object>) item));
                } else throw new FirebaseFirestoreException("Item in 'steps' list is not a Map.",
                        FirebaseFirestoreException.Code.DATA_LOSS);
            }

            if (subtaskIndex < 0 || subtaskIndex >= stepsMapList.size()) {
                throw new FirebaseFirestoreException("Subtask index out of bounds for task: " + taskId,
                        FirebaseFirestoreException.Code.ABORTED);
            }

            Map<String, Object> stepToUpdate = stepsMapList.get(subtaskIndex);
            Object currentCompleted = stepToUpdate.get("completed");
            if (currentCompleted instanceof Boolean && ((Boolean) currentCompleted) == isCompleted) {
                return new SubtaskTransactionResult(stepsMapList, parentOriginallyCompletedFromDB);
            }

            stepToUpdate.put("completed", isCompleted);
            if (!stepToUpdate.containsKey("stability")) {
                stepToUpdate.put("stability", 0);
            }

            transaction.update(taskRef, "steps", stepsMapList);
            return new SubtaskTransactionResult(stepsMapList, parentOriginallyCompletedFromDB);
        }).addOnSuccessListener(transactionResult -> {
            if (callback != null) callback.onSuccess(transactionResult);
        }).addOnFailureListener(e -> {
            if (callback != null) callback.onFailure(e);
        });
    }
} 