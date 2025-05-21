package com.example.myapplication.aplicatiamea;

import android.content.Intent;
import android.os.Bundle;
import android.app.Activity;
import android.view.View;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import com.google.android.material.button.MaterialButton;

/**
 * Adventure Hub Activity - central hub for challenges, levels, and shop features
 */
public class ManageFeaturesActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Shop seeding removed
        super.onCreate(savedInstanceState);
        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_manage_features);

        View rootLayout = findViewById(R.id.rootLayoutManageFeatures);
        View contentArea = findViewById(R.id.contentAreaManageFeatures); // Target content area for padding
        // Apply Window Insets listener
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            // Apply padding to the content area instead of the root
            contentArea.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // Setup Navigation Buttons
        MaterialButton btnChallenges = findViewById(R.id.btnNavigateChallenges);
        MaterialButton btnLevels = findViewById(R.id.btnNavigateLevels);
        MaterialButton btnShop = findViewById(R.id.btnNavigateShop);
        MaterialButton btnHistory = findViewById(R.id.btnNavigateHistory);
        MaterialButton backButton = findViewById(R.id.buttonBackManageFeatures);

        btnChallenges.setOnClickListener(v -> startActivity(new Intent(this, ChallengesActivity.class)));
        btnLevels.setOnClickListener(v -> startActivity(new Intent(this, LevelsActivity.class)));
        btnShop.setOnClickListener(v -> startActivity(new Intent(this, ShopActivity.class)));
        btnHistory.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        backButton.setOnClickListener(v -> finish());
    }
}