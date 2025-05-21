package com.example.myapplication.aplicatiamea.worker;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.firebase.auth.FirebaseAuth;
import com.example.myapplication.aplicatiamea.repository.QuestManager;


public class QuestWorker extends Worker {
    private static final String TAG = "QuestWorker";

    public QuestWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid()
                : null;
        if (uid == null) {
            Log.w(TAG, "No user logged in; skipping quest issuance");
            return Result.retry();
        }
        QuestManager manager = new QuestManager(uid);
        manager.issueDailyQuests();
        manager.issueWeeklyQuest();
        return Result.success();
    }
} 