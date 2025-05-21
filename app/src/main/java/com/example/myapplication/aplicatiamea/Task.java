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
    private final String priority; // Added priority field
    private String description;
    private long deadlineTimestamp = 0; // 0 or -1 indicates no deadline set
    private List<Subtask> steps;
    private long dateTimeTimestamp = 0; // Added for combined date and time
    private String date;
    private String recurrenceGroupId; // New field for recurrence
    private int recurrenceDays = 0; // New field for number of days to repeat (0 = no recurrence)

    public Task() {
        this.steps = new ArrayList<>(); // Initialize to avoid null
        this.priority = TaskPriority.MEDIUM.name(); // Default to medium priority
        this.recurrenceGroupId = null; // Default to null
        this.recurrenceDays = 0; // Default to no recurrence
    }

    protected Task(Parcel in) {
        id = in.readString();
        name = in.readString();
        completed = in.readByte() != 0;
        difficulty = in.readString();
        priority = in.readString(); // Read priority
        long createdAtMillis = in.readLong();
        createdAt = createdAtMillis == -1 ? null : new Date(createdAtMillis);
        description = in.readString();
        deadlineTimestamp = in.readLong();
        steps = in.createTypedArrayList(Subtask.CREATOR);
        dateTimeTimestamp = in.readLong(); // Added
        date = in.readString();
        recurrenceGroupId = in.readString(); // Read new field
        recurrenceDays = in.readInt(); // Read recurrence days
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

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }

    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public String getPriority() { return priority; } // Getter for priority

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public long getDeadlineTimestamp() {
        return deadlineTimestamp;
    }
    public void setDeadlineTimestamp(long deadlineTimestamp) {
        this.deadlineTimestamp = deadlineTimestamp;
    }

    public List<Subtask> getSteps() {
        return steps == null ? new ArrayList<>() : steps;
    }
    public void setSteps(List<Subtask> steps) {
        this.steps = steps;
    }
    public long getDateTimeTimestamp() { return dateTimeTimestamp; }
    public void setDateTimeTimestamp(long dateTimeTimestamp) { this.dateTimeTimestamp = dateTimeTimestamp; }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public String getRecurrenceGroupId() { return recurrenceGroupId; } // Getter for recurrenceGroupId
    public void setRecurrenceGroupId(String recurrenceGroupId) { this.recurrenceGroupId = recurrenceGroupId; } // Setter for recurrenceGroupId

    public int getRecurrenceDays() { return recurrenceDays; } // Getter for recurrenceDays
    public void setRecurrenceDays(int recurrenceDays) { this.recurrenceDays = recurrenceDays; } // Setter for recurrenceDays

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
        dest.writeString(priority); // Write priority
        dest.writeLong(createdAt != null ? createdAt.getTime() : -1);
        dest.writeString(description);
        dest.writeLong(deadlineTimestamp);
        dest.writeTypedList(steps);
        dest.writeLong(dateTimeTimestamp);
        dest.writeString(date);
        dest.writeString(recurrenceGroupId); // Write new field
        dest.writeInt(recurrenceDays); // Write recurrence days
    }

    public static class Subtask implements Parcelable {
        private String description;
        private boolean completed;
        private int stability; // Add this field for Firestore serialization

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

    /**
     * Defines difficulty levels for tasks with associated point values.
     */
    public enum TaskDifficulty {
        SMALL(10),
        MEDIUM(50),
        LARGE(100);

        private final long points;

        TaskDifficulty(long points) {
            this.points = points;
        }

        public long getPoints() {
            return points;
        }
    }
    
    /**
     * Defines priority levels for tasks.
     * Higher priority tasks will appear first in the task list.
     */
    public enum TaskPriority {
        HIGH(3),
        MEDIUM(2),
        LOW(1);
        
        private final int value;
        
        TaskPriority(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
    }
}