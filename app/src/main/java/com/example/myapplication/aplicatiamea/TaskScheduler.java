package com.example.myapplication.aplicatiamea;

import android.util.Log;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

public class TaskScheduler {
    private static final String TAG = "TaskSched";

    private static final ConcurrentHashMap<Long, String> timestampToDateCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 250;
    private static final long CACHE_CLEANUP_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);
    private static long lastCacheCleanupTime = 0;

    private static final ThreadLocal<SimpleDateFormat> dateFormatter = 
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd", Locale.US));

    public static boolean isTaskScheduledForToday(Task task, TimeZone userTimeZone, String actualTodayDateString) {
        if (task == null || actualTodayDateString == null) {
            return false;
        }
        
        try {
            if (actualTodayDateString.length() != 10 || !actualTodayDateString.contains("-")) {
                Log.e(TAG, "Got weird date format: " + actualTodayDateString + ", defaulting to false");
                return false;
            }

            String taskDate = task.getDate();
            if (taskDate != null && taskDate.length() == 10 && taskDate.equals(actualTodayDateString)) {
                return true;
            }

            if (task.getDeadlineTimestamp() > 0) {
                String dateStr = getDateStringFromTimestamp(task.getDeadlineTimestamp(), userTimeZone);
                return actualTodayDateString.equals(dateStr);
            }

            if (taskDate != null && !taskDate.isEmpty()) {
                try {
                    SimpleDateFormat fmt = getFormatter();
                    fmt.setTimeZone(userTimeZone);
                    Date parsedDate = fmt.parse(taskDate);
                    
                    if (parsedDate != null) {
                        String normalizedStr = fmt.format(parsedDate);
                        boolean matches = actualTodayDateString.equals(normalizedStr);
                        

                        if (matches && task.getId() != null) {
                            Log.d(TAG, "Using date string fallback for task: " + task.getId() + 
                                       ", might need to fix this data");
                        }
                        
                        return matches;
                    }
                } catch (ParseException e) {
                    Log.w(TAG, "Bad date format in task: " + task.getId() + " - " + taskDate);
                }
            }

            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "Critical error in task date processing: " + e.getMessage());
            return false;
        }
    }

    private static String getDateStringFromTimestamp(long timestamp, TimeZone userTimeZone) {
        cleanupCacheIfNeeded();
        String cachedDate = timestampToDateCache.get(timestamp);
        if (cachedDate != null) {
            return cachedDate;
        }

        SimpleDateFormat fmt = getFormatter();
        fmt.setTimeZone(userTimeZone);
        String dateStr = fmt.format(new Date(timestamp));

        long now = System.currentTimeMillis();
        if (Math.abs(timestamp - now) < TimeUnit.DAYS.toMillis(90)) {
            timestampToDateCache.put(timestamp, dateStr);
        }
        
        return dateStr;
    }

    private static void cleanupCacheIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCacheCleanupTime > CACHE_CLEANUP_INTERVAL_MS || 
            timestampToDateCache.size() > MAX_CACHE_SIZE) {

            if (timestampToDateCache.size() > 50) {
                Log.d(TAG, "Clearing date cache with " + timestampToDateCache.size() + " entries");
            }
            
            timestampToDateCache.clear();
            lastCacheCleanupTime = now;
        }
    }

    private static SimpleDateFormat getFormatter() {
        SimpleDateFormat fmt = dateFormatter.get();
        if (fmt == null) {
            fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            dateFormatter.set(fmt);
        }
        return fmt;
    }
}