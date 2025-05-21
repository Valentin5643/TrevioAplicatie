package com.example.myapplication.aplicatiamea;

import android.os.Bundle;
import android.app.Activity;
import android.view.View;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import com.google.android.material.button.MaterialButton;

/**
 * History Activity - displays information about the app's history and development
 */
public class HistoryActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_history);

        View rootLayout = findViewById(R.id.rootLayoutHistory);
        View contentArea = findViewById(R.id.contentAreaHistory); // Target content area for padding
        // Apply Window Insets listener
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            // Apply padding to the content area instead of the root
            contentArea.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // Setup Back Button
        MaterialButton backButton = findViewById(R.id.buttonBackHistory);
        backButton.setOnClickListener(v -> finish());
    }
}