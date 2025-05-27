package com.example.myapplication.aplicatiamea;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.example.myapplication.aplicatiamea.repository.QuestManager;
import com.example.myapplication.aplicatiamea.util.ThemeHelper;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Calendar;

public class LoggedInActivity extends AppCompatActivity {
    private static final String TAG = "LoggedInActivity";
    private static final int ADD_TASK_REQUEST = 1;

    private Button btnCreateTask;
    private Button btnViewTasks;
    private Button btnAdventureHub;
    private Button btnLogout;
    private MaterialButton themeToggleBtn; 
    private TextView welcomeText;
    private ImageView logoImage;
    private ConstraintLayout mainLayout;

    private FirebaseFirestore db;
    
    //might use later
    private final boolean tutorialSeen = false;
    
    // useful for analytics later
    private final int[] clickCounts = {0, 0, 0, 0};

    private final boolean DEBUG_MODE = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyUserTheme(this);
        
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logged_in);

        db = FirebaseFirestore.getInstance();

        getViews();
        updateThemeBtnIcon();
        showUserGreeting();
        setupClicks();

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null) {
            QuestManager qm = new QuestManager(currentUser.getUid(), this);
            qm.issueDailyQuests();
            qm.issueWeeklyQuest();
        }

        
        if (DEBUG_MODE) {
            Log.d(TAG, "onCreate finished");
        }
    }

    private void getViews() {
        btnCreateTask = findViewById(R.id.button4);
        btnViewTasks = findViewById(R.id.buttonTasks);
        btnLogout = findViewById(R.id.btnLogout);
        btnAdventureHub = findViewById(R.id.btnAdventureHub);
        themeToggleBtn = findViewById(R.id.btnToggleTheme);
        welcomeText = findViewById(R.id.welcomeText);
        logoImage = findViewById(R.id.appLogo);
        mainLayout = findViewById(R.id.homeRoot);
    }

    private void updateThemeBtnIcon() {
        String currentTheme = ThemeHelper.getCurrentThemePreference(this);
        if (ThemeHelper.LIGHT.equals(currentTheme)) {
            themeToggleBtn.setIcon(getDrawable(R.drawable.ic_theme_light));
        } else {
            themeToggleBtn.setIcon(getDrawable(R.drawable.ic_theme_dark));
        }
    }

    private void showUserGreeting() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String greeting = getTimeBasedGreeting();
        
        if (user != null) {
            String uid = user.getUid();
            DocumentReference userRef = db.collection("users").document(uid);

            welcomeText.setText(greeting + "!");
            

            userRef.get().addOnSuccessListener(documentSnapshot -> {
                String displayName;
                
                if (documentSnapshot.exists() && documentSnapshot.contains("username")) {
                    displayName = documentSnapshot.getString("username");
                    if (displayName != null && !displayName.isEmpty()) {
                        welcomeText.setText(greeting + " " + displayName + "!");
                        return; // Exit early if we found a username
                    }
                }

                if (user.getEmail() != null) {
                    String email = user.getEmail();
                    int atIndex = email.indexOf('@');
                    if (atIndex > 0) {
                        displayName = email.substring(0, atIndex);
                        displayName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
                        welcomeText.setText(greeting + " " + displayName + "!");
                    } else {
                        welcomeText.setText(greeting + "!");
                    }
                } else {
                    welcomeText.setText(greeting + "!");
                }
            }).addOnFailureListener(e -> {
                Log.e(TAG, "Error fetching username", e);
                welcomeText.setText(greeting + "!");
            });
        } else {
            welcomeText.setText(greeting + "!");
        }
    }

    private String getTimeBasedGreeting() {
        Calendar c = Calendar.getInstance();
        int timeOfDay = c.get(Calendar.HOUR_OF_DAY);

        if (timeOfDay >= 0 && timeOfDay < 12) {
            return "Good morning";
        } else if (timeOfDay >= 12 && timeOfDay < 16) {
            return "Good afternoon";
        } else if (timeOfDay >= 16 && timeOfDay < 21) {
            return "Good evening";
        } else {
            return "Good night";
        }
    }

    private void setupClicks() {

        btnCreateTask.setOnClickListener(v -> {
            clickCounts[0]++;
            startActivityForResult(new Intent(LoggedInActivity.this, AddTaskActivity.class), ADD_TASK_REQUEST);
        });

        btnViewTasks.setOnClickListener(v -> {
            clickCounts[1]++;
            startActivity(new Intent(LoggedInActivity.this, TaskListActivity.class));
        });

        btnLogout.setOnClickListener(v -> {
            clickCounts[2]++;
            FirebaseAuth.getInstance().signOut();
            startActivity(new Intent(LoggedInActivity.this, MainActivity.class));
            finish();
        });

        btnAdventureHub.setOnClickListener(v -> {
            clickCounts[3]++;
            startActivity(new Intent(LoggedInActivity.this, ManageFeaturesActivity.class));
        });
        
        themeToggleBtn.setOnClickListener(v -> {
            ThemeHelper.toggleDarkMode(this);
            

            updateThemeBtnIcon();
            

            recreate();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == ADD_TASK_REQUEST && resultCode == RESULT_OK && data != null) {
            Task newTask = data.getParcelableExtra("task");
            if(newTask != null) {
                Toast.makeText(this, "Task added successfully", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // will add this later when adding profiles
    private void fetchUserStats() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            String uid = user.getUid();
            db.collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        long points = doc.getLong("points") != null ? doc.getLong("points") : 0;
                        long streak = doc.getLong("streak") != null ? doc.getLong("streak") : 0;
                        long level = doc.getLong("level") != null ? doc.getLong("level") : 1;
                        // profilePoints.setText(String.valueOf(points));
                        // profileStreak.setText(String.valueOf(streak));
                        // profileLevel.setText(String.valueOf(level));
                    }
                });
        }
    }
    


}