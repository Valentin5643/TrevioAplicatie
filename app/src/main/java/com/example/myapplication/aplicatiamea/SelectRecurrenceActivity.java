package com.example.myapplication.aplicatiamea;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.NumberPicker;

import androidx.appcompat.app.AppCompatActivity;

import com.example.myapplication.aplicatiamea.util.ThemeHelper;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

public class SelectRecurrenceActivity extends AppCompatActivity {

    private static final String TAG = "SelectRecurrenceActivity";

    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 30;
    private static final int DEFAULT_DAYS = 1;
    

    private NumberPicker daysNumPicker;
    private MaterialButton saveBtn;
    private MaterialButton cancelBtn;
    private MaterialToolbar toolbar;
    


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        ThemeHelper.applyUserTheme(this);
        
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_select_recurrence);


        daysNumPicker = findViewById(R.id.numberPickerDays);
        saveBtn = findViewById(R.id.btnSave);
        cancelBtn = findViewById(R.id.btnCancel);
        toolbar = findViewById(R.id.topAppBar);


        setupTopBar();
        setupNumPicker();
        initButtons();
    }

    private void setupTopBar() {

        toolbar.setNavigationOnClickListener(v -> {

            setResult(RESULT_CANCELED);
            finish();
        });
    }

    private void setupNumPicker() {
        daysNumPicker.setMinValue(MIN_DAYS);
        daysNumPicker.setMaxValue(MAX_DAYS);

        Intent intent = getIntent();
        int currentDays = intent.getIntExtra("RECURRENCE_DAYS", DEFAULT_DAYS);
        daysNumPicker.setValue(currentDays);
        daysNumPicker.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
    }

    private void initButtons() {

        saveBtn.setOnClickListener(v -> {

            int selectedDays = daysNumPicker.getValue();
            Log.d(TAG, "Selected recurrence: " + selectedDays + " days");

            Intent resultData = new Intent();
            resultData.putExtra("RECURRENCE_DAYS", selectedDays);
            setResult(RESULT_OK, resultData);
            finish();
        });

        cancelBtn.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }
    

    private boolean isValidSelection() {
        return true;
    }
} 