package com.example.myapplication.aplicatiamea;

import android.os.Parcel;
import android.os.Parcelable;

import com.google.firebase.firestore.DocumentId;

import java.util.Date;
import java.util.List;
import java.util.ArrayList;

public class Task implements Parcelable {
    @DocumentId
    private String id;
    private String name;
    private boolean completed;
    private Date createdAt;
    private String difficulty;
    private final String priority;
    private String description;
    private long deadlineTimestamp = 0;
    private List<Subtask> actionItems;
    private long scheduleTimestamp = 0;
    private String targetDate;
    private String recurrenceGroupId;
    private int recurrenceDays = 0;
    private Integer reminderMinutes = 0;

    public Task() {
        this.actionItems = new ArrayList<>();
        this.priority = TaskPriority.MEDIUM.name();
    }

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
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
    public String getDifficulty() { return difficulty; }


    public String getPriority() { return priority; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public long getDeadlineTimestamp() {
        return deadlineTimestamp;
    }
    
    public void setDeadlineTimestamp(long deadlineTimestamp) {
        this.deadlineTimestamp = deadlineTimestamp;
    }


    public Date getDueDate() {
        return deadlineTimestamp > 0 ? new Date(deadlineTimestamp) : null;
    }

    public List<Subtask> getSteps() {
        return getActionItems();
    }
    
    public List<Subtask> getActionItems() {
        return actionItems == null ? new ArrayList<>() : actionItems;
    }
    
    public void setSteps(List<Subtask> steps) {
        this.actionItems = steps;
    }
    
    public long getDateTimeTimestamp() { return scheduleTimestamp; }

    public String getDate() { return targetDate; }
    public void setDate(String date) { this.targetDate = date; }

    public String getRecurrenceGroupId() { return recurrenceGroupId; }

    public int getRecurrenceDays() { return recurrenceDays; }
    public Integer getReminderMinutes() { return reminderMinutes; }

    @Override
    public int describeContents() {
        return 0;
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

    public static class Subtask implements Parcelable {
        private String description;
        private boolean completed;
        private int stability;

        public Subtask() {
            this.description = "";
            this.completed = false;
            this.stability = 0;
        }

        public Subtask(String description, boolean completed) {
            this.description = description;
            this.completed = completed;
            this.stability = 0;
        }

        public Subtask(String description, boolean completed, int stability) {
            this.description = description;
            this.completed = completed;
            this.stability = stability;
        }

        protected Subtask(Parcel in) {
            description = in.readString();
            completed = in.readByte() != 0;
            stability = in.readInt();
        }

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

        public int getStability() {
            return stability;
        }

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

    public enum TaskDifficulty {
        SMALL(10),   // Quick tasks, 5-15 mins
        MEDIUM(50),  // Standard tasks, 30-60 mins  
        LARGE(100);  // Big tasks, 1+ hours

        private final long xpReward;

        TaskDifficulty(long points) {
            this.xpReward = points;
        }

    }

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