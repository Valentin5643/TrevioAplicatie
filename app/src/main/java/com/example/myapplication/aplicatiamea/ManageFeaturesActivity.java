package com.example.myapplication.aplicatiamea;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;

import com.example.myapplication.aplicatiamea.repository.QuestActivity;
import com.google.android.material.button.MaterialButton;
import com.example.myapplication.aplicatiamea.util.ThemeHelper;

public class ManageFeaturesActivity extends AppCompatActivity {
    private MaterialButton challengesBtn;
    private MaterialButton levelsBtn;
    private MaterialButton shopBtn;
    private MaterialButton historyBtn;
    


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        ThemeHelper.applyUserTheme(this);
        
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_manage_features);


        setupInsets();


        initUI();
    }
    

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
        challengesBtn = findViewById(R.id.btnNavigateChallenges);
        levelsBtn = findViewById(R.id.btnNavigateLevels);
        shopBtn = findViewById(R.id.btnNavigateShop);
        historyBtn = findViewById(R.id.btnNavigateHistory);
        MaterialButton backBtn = findViewById(R.id.buttonBackManageFeatures);

        challengesBtn.setOnClickListener(v -> openChallenges());
        levelsBtn.setOnClickListener(v -> openLevels());
        shopBtn.setOnClickListener(v -> openShop());
        historyBtn.setOnClickListener(v -> openHistory());
        

        backBtn.setOnClickListener(v -> finish());
    }
    

    
    private void openChallenges() {

        startActivity(new Intent(this, QuestActivity.class));
    }
    
    private void openLevels() {

        startActivity(new Intent(this, LevelsActivity.class));
    }
    
    private void openShop() {
        Intent i = new Intent(this, ShopActivity.class);
        startActivity(i);
    }
    
    private void openHistory() {
        startActivity(new Intent(this, HistoryActivity.class));
    }
}