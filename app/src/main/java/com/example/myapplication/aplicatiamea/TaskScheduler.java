package com.example.myapplication.aplicatiamea;

import android.util.Log;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles task dates/times and figures out when they should appear.
 * This class deals with the super annoying timezone problems
 */
public class TaskScheduler {
    private static final String TAG = "TaskSched";
    
    // Cache date strings so we don't have to keep converting - massive perf boost
    private static final ConcurrentHashMap<Long, String> timestampToDateCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 250; // Keep memory usage reasonable
    private static final long CACHE_CLEANUP_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);
    private static long lastCacheCleanupTime = 0;
    
    // Keep our own formatter instance to avoid creating lots of objects
    private static final ThreadLocal<SimpleDateFormat> dateFormatter = 
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd", Locale.US));
    
    /**
     * Checks if a task should show up for a specific day
     * 
     * This has burned me like 5 times already with timezone stuff,
     * so I've added a ton of safeguards and fallbacks
     */
    public static boolean isTaskScheduledForToday(Task task, TimeZone userTimeZone, String actualTodayDateString) {
        if (task == null || actualTodayDateString == null) {
            return false;
        }
        
        try {
            // Sanity check the date format since I found some corrupted data on prod
            if (actualTodayDateString.length() != 10 || !actualTodayDateString.contains("-")) {
                Log.e(TAG, "Got weird date format: " + actualTodayDateString + ", defaulting to false");
                return false;
            }
            
            // First try the explicit date field - fastest path
            String taskDate = task.getDate();
            if (taskDate != null && taskDate.length() == 10 && taskDate.equals(actualTodayDateString)) {
                return true;
            }
            
            // Next try the timestamp since it's timezone-aware already
            if (task.getDeadlineTimestamp() > 0) {
                String dateStr = getDateStringFromTimestamp(task.getDeadlineTimestamp(), userTimeZone);
                return actualTodayDateString.equals(dateStr);
            }
            
            // Final desperate attempt - try to parse the date string if it exists
            // This is sketchy but might save tasks with bad data
            if (taskDate != null && !taskDate.isEmpty()) {
                try {
                    SimpleDateFormat fmt = getFormatter();
                    fmt.setTimeZone(userTimeZone);
                    Date parsedDate = fmt.parse(taskDate);
                    
                    if (parsedDate != null) {
                        // Generate a fresh clean string to make sure it's normalized
                        String normalizedStr = fmt.format(parsedDate);
                        boolean matches = actualTodayDateString.equals(normalizedStr);
                        
                        // If we had to use this fallback, we should fix the task data
                        if (matches && task.getId() != null) {
                            Log.d(TAG, "Using date string fallback for task: " + task.getId() + 
                                       ", might need to fix this data");
                        }
                        
                        return matches;
                    }
                } catch (ParseException e) {
                    // Not much we can do with bad data
                    Log.w(TAG, "Bad date format in task: " + task.getId() + " - " + taskDate);
                }
            }
            
            // No usable date data at all
            return false;
            
        } catch (Exception e) {
            // Ultimate safety net - don't crash the whole app over a date!
            Log.e(TAG, "Critical error in task date processing: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Checks if a task is scheduled for a specific date
     */
    public static boolean isTaskScheduledForDate(Task task, String targetDateString, TimeZone userTimeZone) {
        if (task == null || targetDateString == null) {
            return false;
        }
        
        try {
            // Try the date string first (most direct path)
            if (targetDateString.equals(task.getDate())) {
                return true;
            }
            
            // Try the timestamp (most reliable for timezone handling)
            if (task.getDeadlineTimestamp() > 0) {
                String dateString = getDateStringFromTimestamp(task.getDeadlineTimestamp(), userTimeZone);
                return targetDateString.equals(dateString);
            }
            
            // No valid date info
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking task schedule: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Gets the date string based on a timestamp - cached for performance
     */
    private static String getDateStringFromTimestamp(long timestamp, TimeZone userTimeZone) {
        // Check cache first for super fast lookups
        cleanupCacheIfNeeded();
        String cachedDate = timestampToDateCache.get(timestamp);
        if (cachedDate != null) {
            return cachedDate;
        }
        
        // Format the timestamp
        SimpleDateFormat fmt = getFormatter();
        fmt.setTimeZone(userTimeZone);
        String dateStr = fmt.format(new Date(timestamp));
        
        // Only cache recent dates to avoid memory issues
        // We could use a LRU cache but that's overkill for our needs
        long now = System.currentTimeMillis();
        if (Math.abs(timestamp - now) < TimeUnit.DAYS.toMillis(90)) {
            timestampToDateCache.put(timestamp, dateStr);
        }
        
        return dateStr;
    }
    
    /**
     * Clears cache periodically to avoid memory leaks
     */
    private static void cleanupCacheIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCacheCleanupTime > CACHE_CLEANUP_INTERVAL_MS || 
            timestampToDateCache.size() > MAX_CACHE_SIZE) {
            
            // Only log if we're actually clearing a non-trivial amount
            if (timestampToDateCache.size() > 50) {
                Log.d(TAG, "Clearing date cache with " + timestampToDateCache.size() + " entries");
            }
            
            timestampToDateCache.clear();
            lastCacheCleanupTime = now;
        }
    }
    
    /**
     * Thread-safe date formatter getter
     */
    private static SimpleDateFormat getFormatter() {
        SimpleDateFormat fmt = dateFormatter.get();
        if (fmt == null) {
            fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            dateFormatter.set(fmt);
        }
        return fmt;
    }
    
    /**
     * Creates a full date string for a given timestamp, with proper timezone
     */
    public static String getReadableDateString(long timestamp, TimeZone timeZone) {
        if (timestamp <= 0) {
            return "No date set";
        }
        
        try {
            // Use a nice readable format
            SimpleDateFormat fmt = new SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault());
            fmt.setTimeZone(timeZone);
            return fmt.format(new Date(timestamp));
        } catch (Exception e) {
            // Better safe than sorry
            Log.e(TAG, "Date formatting error: " + e.getMessage());
            return "Unknown date";
        }
    }
} 