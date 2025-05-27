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
    private static final String TAG = "ChalRefresh";
    
    private static final int MAX_RETRIES = 3;
    private int retryCount = 0;
    
    public ChallengeWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Data inputData = getInputData();
        String frequency = inputData.getString("frequency");
        
        if (frequency == null) {
            Log.e(TAG, "Missing frequency param! Who scheduled this?");
            return Result.failure();
        }
        
        try {
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            long now = System.currentTimeMillis();
            

            boolean isDaily = "DAILY".equalsIgnoreCase(frequency);
            long timeWindow = isDaily ? TimeUnit.DAYS.toMillis(1) : TimeUnit.DAYS.toMillis(7);
            
            Log.d(TAG, "Refreshing " + frequency.toLowerCase() + " challenges...");
            

            db.collection("challenges")
                .whereEqualTo("frequency", frequency)
                .whereLessThanOrEqualTo("lastIssuedAt", now - timeWindow)
                .get()
                .addOnSuccessListener(queryResults -> {
                    int count = queryResults.size();
                    if (count == 0) {
                        Log.d(TAG, "No " + frequency.toLowerCase() + " challenges need refresh");
                        return;
                    }

                    WriteBatch batch = db.batch();
                    for (DocumentSnapshot challenge : queryResults.getDocuments()) {
                        batch.update(challenge.getReference(), "lastIssuedAt", now);
                    }
                    
                    batch.commit()
                         .addOnSuccessListener(unused -> 
                             Log.d(TAG, "Refreshed " + count + " " + frequency.toLowerCase() + " challenges ✅"))
                         .addOnFailureListener(err -> {
                             Log.e(TAG, "Failed to update " + frequency + " challenges: " + err.getMessage());
                             

                             if (retryCount < MAX_RETRIES && isRetryableError(err)) {
                                 retryCount++;
                                 Log.w(TAG, "Retrying batch update (" + retryCount + "/" + MAX_RETRIES + ")");
                                 try { Thread.sleep(3000); } catch (InterruptedException e) {}
                                 doWork(); // Recursive retry
                             }
                         });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Query failed: " + e.getMessage());
                    
                    if (retryCount < MAX_RETRIES && isRetryableError(e)) {
                        retryCount++;
                        Log.w(TAG, "Network issue? Retrying query (" + retryCount + "/" + MAX_RETRIES + ")");
                        try { Thread.sleep(2000); } catch (InterruptedException ie) {}
                        doWork();
                    }
                });
                
            // TODO: This success is lying - we should use a CompletableFuture or something
            //       Fix this in the next sprint - alexT
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Challenge refresh BOOM: " + e.getMessage());
            return Result.failure();
        }
    }
    
    private boolean isRetryableError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        
        return msg.contains("network") ||
               msg.contains("offline") || 
               msg.contains("unavailable") ||
               msg.contains("timeout");
    }
} 