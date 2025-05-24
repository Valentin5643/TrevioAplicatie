package com.example.myapplication.aplicatiamea;

import android.os.Parcel;
import android.os.Parcelable;

import com.google.firebase.firestore.DocumentId;

import java.util.Date;
import java.util.List;
import java.util.ArrayList;

// Task model - the backbone of our productivity gamification system
// Had to make this Parcelable for passing between activities (Android requirement)
public class Task implements Parcelable {
    @DocumentId
    private String id;
    private String name;
    private boolean completed;
    private Date createdAt;
    private String difficulty;  // Maps to TaskDifficulty enum
    private final String priority; // Immutable after creation - prevents gaming the system
    private String description;
    private long deadlineTimestamp = 0; // Unix timestamp, 0 means no deadline set
    private List<Subtask> actionItems;  // renamed from "steps" - more descriptive for productivity context
    private long scheduleTimestamp = 0; // Combined date+time when user wants to tackle this
    private String targetDate;  // "yyyy-MM-dd" format for easy sorting/filtering
    private String recurrenceGroupId; // Links recurring task instances together
    private int recurrenceDays = 0; // Repeat interval in days (0 = one-time task)
    private Integer reminderMinutes = 0; // Alert timing before task (null would break some UI)

    // Firestore needs this empty constructor or everything breaks
    public Task() {
        this.actionItems = new ArrayList<>(); // Prevent null pointer crashes in UI
        this.priority = TaskPriority.MEDIUM.name(); // Sensible default - most tasks aren't urgent
    }

    // Android IPC constructor for passing between activities/fragments
    protected Task(Parcel in) {
        id = in.readString();
        name = in.readString();
        completed = in.readByte() != 0;
        difficulty = in.readString();
        priority = in.readString();
        long createdAtMillis = in.readLong();
        createdAt = createdAtMillis == -1 ? null : new Date(createdAtMillis);
        description = in.readString();
        deadlineTimestamp = in.readLong();
        actionItems = in.createTypedArrayList(Subtask.CREATOR);
        scheduleTimestamp = in.readLong();
        targetDate = in.readString();
        recurrenceGroupId = in.readString();
        recurrenceDays = in.readInt();
        reminderMinutes = in.readInt();
    }

    // Android Parcelable boilerplate - required but boring
    public static final Creator<Task> CREATOR = new Creator<>() {
        @Override
        public Task createFromParcel(Parcel in) {
            return new Task(in);
        }

        @Override
        public Task[] newArray(int size) {
            return new Task[size];
        }
    };

    // Standard getters/setters - nothing fancy here
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    // Priority is final (set in constructor) - prevents users from constantly bumping everything to "HIGH"
    public String getPriority() { return priority; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public long getDeadlineTimestamp() {
        return deadlineTimestamp;
    }
    
    public void setDeadlineTimestamp(long deadlineTimestamp) {
        this.deadlineTimestamp = deadlineTimestamp;
    }

    // Convenience method for TaskAdapter compatibility
    public Date getDueDate() {
        return deadlineTimestamp > 0 ? new Date(deadlineTimestamp) : null;
    }

    public List<Subtask> getSteps() {
        // Keep old method name for backward compatibility with existing code
        return getActionItems();
    }
    
    public List<Subtask> getActionItems() {
        // Defensive programming - some old tasks might have null lists
        return actionItems == null ? new ArrayList<>() : actionItems;
    }
    
    public void setSteps(List<Subtask> steps) {
        // Legacy method - redirects to new naming
        this.actionItems = steps;
    }
    
    public void setActionItems(List<Subtask> items) {
        this.actionItems = items;
    }
    
    public long getDateTimeTimestamp() { return scheduleTimestamp; }
    public void setDateTimeTimestamp(long timestamp) { this.scheduleTimestamp = timestamp; }

    public String getDate() { return targetDate; }
    public void setDate(String date) { this.targetDate = date; }

    public String getRecurrenceGroupId() { return recurrenceGroupId; }
    public void setRecurrenceGroupId(String recurrenceGroupId) { this.recurrenceGroupId = recurrenceGroupId; }

    public int getRecurrenceDays() { return recurrenceDays; }
    public void setRecurrenceDays(int recurrenceDays) { this.recurrenceDays = recurrenceDays; }

    public Integer getReminderMinutes() { return reminderMinutes; }
    public void setReminderMinutes(Integer reminderMinutes) { this.reminderMinutes = reminderMinutes; }

    // Android Parcelable implementation - required boilerplate
    @Override
    public int describeContents() {
        return 0; // We don't use file descriptors, so always 0
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeString(name);
        dest.writeByte((byte) (completed ? 1 : 0));
        dest.writeString(difficulty);
        dest.writeString(priority);
        dest.writeLong(createdAt != null ? createdAt.getTime() : -1);
        dest.writeString(description);
        dest.writeLong(deadlineTimestamp);
        dest.writeTypedList(actionItems);
        dest.writeLong(scheduleTimestamp);
        dest.writeString(targetDate);
        dest.writeString(recurrenceGroupId);
        dest.writeInt(recurrenceDays);
        dest.writeInt(reminderMinutes != null ? reminderMinutes : 0);
    }

    // Individual action items within a bigger task
    public static class Subtask implements Parcelable {
        private String description;
        private boolean completed;
        private int stability; // Backend analytics field - we don't use it but can't remove it

        // Firestore deserialization constructor
        public Subtask() {
            this.description = "";
            this.completed = false;
            this.stability = 0;
        }

        // Most common constructor - just description and completion state
        public Subtask(String description, boolean completed) {
            this.description = description;
            this.completed = completed;
            this.stability = 0; // Default value for analytics field
        }

        // Full constructor including analytics field
        public Subtask(String description, boolean completed, int stability) {
            this.description = description;
            this.completed = completed;
            this.stability = stability;
        }

        // Android Parcelable constructor
        protected Subtask(Parcel in) {
            description = in.readString();
            completed = in.readByte() != 0;
            stability = in.readInt();
        }

        // Parcelable creator - more Android boilerplate
        public static final Creator<Subtask> CREATOR = new Creator<>() {
            @Override
            public Subtask createFromParcel(Parcel in) {
                return new Subtask(in);
            }

            @Override
            public Subtask[] newArray(int size) {
                return new Subtask[size];
            }
        };

        // Basic accessors
        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public boolean isCompleted() {
            return completed;
        }

        public void setCompleted(boolean completed) {
            this.completed = completed;
        }

        // Analytics field that backend team wanted but we never actually use
        public int getStability() {
            return stability;
        }

        // More Android Parcelable requirements
        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(description);
            dest.writeByte((byte) (completed ? 1 : 0));
            dest.writeInt(stability);
        }
    }

    // XP rewards based on estimated effort - tuned from user feedback
    public enum TaskDifficulty {
        SMALL(10),   // Quick tasks, 5-15 mins
        MEDIUM(50),  // Standard tasks, 30-60 mins  
        LARGE(100);  // Big tasks, 1+ hours

        private final long xpReward;

        TaskDifficulty(long points) {
            this.xpReward = points;
        }

        public long getPoints() {
            return xpReward;
        }
    }
    
    // Task importance for sorting and visual emphasis in UI
    public enum TaskPriority {
        HIGH(3),   // Urgent/important stuff
        MEDIUM(2), // Normal priority (most tasks)
        LOW(1);    // Nice-to-have, when you get around to it
        
        private final int sortValue;
        
        TaskPriority(int value) {
            this.sortValue = value;
        }
        
        public int getValue() {
            return sortValue;
        }
    }
}