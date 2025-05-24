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
    
    // task recurrence config stuff
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 30; // don't make this higher or it breaks calendar view
    private static final int DEFAULT_DAYS = 1;
    
    // ui stuff
    private NumberPicker daysNumPicker;
    private MaterialButton saveBtn;
    private MaterialButton cancelBtn;
    private MaterialToolbar toolbar;
    
    // test data for quick debugging - comment when done
    // private final int[] testDaysValues = {1, 3, 7, 14};
    // private int testCounter = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // apply theme crap
        ThemeHelper.applyUserTheme(this);
        
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_select_recurrence);

        // get views
        daysNumPicker = findViewById(R.id.numberPickerDays);
        saveBtn = findViewById(R.id.btnSave);
        cancelBtn = findViewById(R.id.btnCancel);
        toolbar = findViewById(R.id.topAppBar);

        // setup everything
        setupTopBar();
        setupNumPicker();
        initButtons();
    }

    private void setupTopBar() {
        // back button in toolbar
        toolbar.setNavigationOnClickListener(v -> {
            // same as cancel
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    private void setupNumPicker() {
        // configure number picker
        daysNumPicker.setMinValue(MIN_DAYS);
        daysNumPicker.setMaxValue(MAX_DAYS);
        
        // get current value if editing existing
        Intent intent = getIntent();
        int currentDays = intent.getIntExtra("RECURRENCE_DAYS", DEFAULT_DAYS);
        
        // DEBUG: swap this for quick testing
        // int days = testDaysValues[testCounter++ % testDaysValues.length];
        // daysNumPicker.setValue(days);
        
        // set to current or default value
        daysNumPicker.setValue(currentDays);
        
        // limit keyboard input on some devices
        daysNumPicker.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
    }

    private void initButtons() {
        // setup save button
        saveBtn.setOnClickListener(v -> {
            // get selected value
            int selectedDays = daysNumPicker.getValue();
            Log.d(TAG, "Selected recurrence: " + selectedDays + " days");
            
            // return to caller
            Intent resultData = new Intent();
            resultData.putExtra("RECURRENCE_DAYS", selectedDays);
            setResult(RESULT_OK, resultData);
            finish();
        });

        // setup cancel button
        cancelBtn.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }
    
    // might need this if we add validation later
    private boolean isValidSelection() {
        // always valid for now 
        return true;
    }
} 