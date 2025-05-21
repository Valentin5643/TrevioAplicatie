package com.example.myapplication.aplicatiamea;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.NumberPicker;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

public class SelectRecurrenceActivity extends AppCompatActivity {

    private static final String TAG = "SelectRecurrenceActivity";
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 30; // Limit to 30 consecutive days
    private static final int DEFAULT_DAYS = 1;

    private NumberPicker numberPickerDays;
    private MaterialButton btnSave;
    private MaterialButton btnCancel;
    private MaterialToolbar topAppBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_select_recurrence);

        // Initialize views
        numberPickerDays = findViewById(R.id.numberPickerDays);
        btnSave = findViewById(R.id.btnSave);
        btnCancel = findViewById(R.id.btnCancel);
        topAppBar = findViewById(R.id.topAppBar);

        setupToolbar();
        setupNumberPicker();
        setupButtons();
    }

    private void setupToolbar() {
        topAppBar.setNavigationOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    private void setupNumberPicker() {
        numberPickerDays.setMinValue(MIN_DAYS);
        numberPickerDays.setMaxValue(MAX_DAYS);
        
        // Get the currently set recurrence days if we're editing an existing task
        Intent intent = getIntent();
        int currentRecurrenceDays = intent.getIntExtra("RECURRENCE_DAYS", DEFAULT_DAYS);
        
        // Set the number picker to the current value, or default if it's a new task
        numberPickerDays.setValue(currentRecurrenceDays);
    }

    private void setupButtons() {
        btnSave.setOnClickListener(v -> {
            // Get the selected number of days
            int selectedDays = numberPickerDays.getValue();
            Log.d(TAG, "Selected recurrence: " + selectedDays + " days");
            
            // Return the selection to the caller
            Intent resultIntent = new Intent();
            resultIntent.putExtra("RECURRENCE_DAYS", selectedDays);
            setResult(RESULT_OK, resultIntent);
            finish();
        });

        btnCancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }
} 