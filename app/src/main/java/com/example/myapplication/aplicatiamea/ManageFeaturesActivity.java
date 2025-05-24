package com.example.myapplication.aplicatiamea;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import com.google.android.material.button.MaterialButton;
import com.example.myapplication.aplicatiamea.util.ThemeHelper;

// Hub screen for all the cool features
public class ManageFeaturesActivity extends AppCompatActivity {
    // buttons - might need these later for disabled states
    private MaterialButton challengesBtn;
    private MaterialButton levelsBtn;
    private MaterialButton shopBtn;
    private MaterialButton historyBtn;
    
    // auto-increment counters to show button presses for debug
    // private int[] clickCounts = new int[4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // theme first (prevents flash)
        ThemeHelper.applyUserTheme(this);
        
        super.onCreate(savedInstanceState);
        
        // edge-to-edge ui (looks way better)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_manage_features);

        // insets junk
        setupInsets();

        // hook up buttons
        initUI();
    }
    
    // easier to read with this broken out
    private void setupInsets() {
        View root = findViewById(R.id.rootLayoutManageFeatures);
        View content = findViewById(R.id.contentAreaManageFeatures);
        
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets i = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            content.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }
    
    private void initUI() {
        // get button refs
        challengesBtn = findViewById(R.id.btnNavigateChallenges);
        levelsBtn = findViewById(R.id.btnNavigateLevels);
        shopBtn = findViewById(R.id.btnNavigateShop);
        historyBtn = findViewById(R.id.btnNavigateHistory);
        MaterialButton backBtn = findViewById(R.id.buttonBackManageFeatures);

        // set up click listeners
        challengesBtn.setOnClickListener(v -> openChallenges());
        levelsBtn.setOnClickListener(v -> openLevels());
        shopBtn.setOnClickListener(v -> openShop());
        historyBtn.setOnClickListener(v -> openHistory());
        
        // Just close this activity
        backBtn.setOnClickListener(v -> finish());
        
        // TODO: implement analytics for which buttons get clicked most
    }
    
    // click handlers split out to make it easier to add shared logic later
    
    private void openChallenges() {
        // clickCounts[0]++;
        startActivity(new Intent(this, ChallengesActivity.class));
    }
    
    private void openLevels() {
        // clickCounts[1]++;
        startActivity(new Intent(this, LevelsActivity.class));
    }
    
    private void openShop() {
        // clickCounts[2]++;
        // shop needs some extra params maybe later
        Intent i = new Intent(this, ShopActivity.class);
        startActivity(i);
    }
    
    private void openHistory() {
        // clickCounts[3]++;
        startActivity(new Intent(this, HistoryActivity.class));
    }
    
    // Uncomment if we need to track back button clicks
    /*
    @Override
    public void onBackPressed() {
        // Log.d("HUB", "Back pressed");
        super.onBackPressed();
    }
    */
}