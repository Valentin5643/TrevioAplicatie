package com.example.myapplication.aplicatiamea;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class TaskScheduler {

    /**
     * Checks if a task is scheduled for the actual current day based on timezone and date string.
     */
    public static boolean isTaskScheduledForToday(Task task, TimeZone userTimeZone, String actualTodayDateString) {
        String taskDateString = null;
        if (task.getDeadlineTimestamp() > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            sdf.setTimeZone(userTimeZone);
            taskDateString = sdf.format(new Date(task.getDeadlineTimestamp()));
        } else if (task.getDate() != null && !task.getDate().isEmpty()) {
            taskDateString = task.getDate();
        }

        // Debug log
        Log.d("TaskScheduler", "Task: " + task.getName() + ", Date: " + taskDateString + ", Today: " + actualTodayDateString);
        return actualTodayDateString.equals(taskDateString);
    }
} 