package com.example.myapplication.aplicatiamea.worker;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;
import java.util.concurrent.TimeUnit;
import android.util.Log;

public class ChallengeWorker extends Worker {
    public ChallengeWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Data input = getInputData();
        String freqName = input.getString("frequency");
        if (freqName == null) {
            return Result.failure();
        }
        try {
            // Determine interval based on frequency string
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            long now = System.currentTimeMillis();
            boolean isDaily = "DAILY".equalsIgnoreCase(freqName);
            long interval = isDaily ? TimeUnit.DAYS.toMillis(1) : TimeUnit.DAYS.toMillis(7);
            db.collection("challenges")
                .whereEqualTo("frequency", freqName)
                .whereLessThanOrEqualTo("lastIssuedAt", now - interval)
                .get()
                .addOnSuccessListener((QuerySnapshot querySnapshot) -> {
                    WriteBatch batch = db.batch();
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        batch.update(doc.getReference(), "lastIssuedAt", now);
                    }
                    batch.commit()
                         .addOnSuccessListener(aVoid -> Log.d("ChallengeWorker", "Challenges issued: " + querySnapshot.size()))
                         .addOnFailureListener(e -> Log.e("ChallengeWorker", "Failed to commit batch updates", e));
                })
                .addOnFailureListener(e -> Log.e("ChallengeWorker", "Failed to fetch challenges", e));
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.failure();
        }
    }
} 