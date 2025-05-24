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
    
    // UI elements
    private Button btnCreateTask;
    private Button btnViewTasks;
    private Button btnAdventureHub;
    private Button btnLogout;
    private MaterialButton themeToggleBtn; 
    private TextView welcomeText;
    private ImageView logoImage;
    private ConstraintLayout mainLayout;
    
    // Firebase stuff
    private FirebaseFirestore db;
    
    // flag for tutorial seen status - might use later
    private boolean tutorialSeen = false;
    
    // tracks button click counts - useful for analytics maybe
    private int[] clickCounts = {0, 0, 0, 0};
    
    // debug flag - set to true to enable debug mode
    private final boolean DEBUG_MODE = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // apply theme crap
        ThemeHelper.applyUserTheme(this);
        
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logged_in);

        // get firebase instance
        db = FirebaseFirestore.getInstance();
        
        // setup the UI
        getViews();
        updateThemeBtnIcon();
        showUserGreeting();
        setupClicks();
        
        // Check if daily and weekly quests are needed
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null) {
            // initialize quest manager and create quests if needed
            QuestManager qm = new QuestManager(currentUser.getUid(), this);
            qm.issueDailyQuests();
            qm.issueWeeklyQuest();
        }
        
        // tried to add animation here but didn't work right
        // logoImage.startAnimation(AnimationUtils.loadAnimation(this, R.anim.logo_anim));
        
        if (DEBUG_MODE) {
            Log.d(TAG, "onCreate finished");
        }
    }

    // find all the views we need
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

    // update the theme button icon based on current theme
    private void updateThemeBtnIcon() {
        String currentTheme = ThemeHelper.getCurrentThemePreference(this);
        if (ThemeHelper.LIGHT.equals(currentTheme)) {
            themeToggleBtn.setIcon(getDrawable(R.drawable.ic_theme_light));
        } else {
            themeToggleBtn.setIcon(getDrawable(R.drawable.ic_theme_dark));
        }
    }

    // show greeting with username if available
    private void showUserGreeting() {
        // get the firebase user
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        
        // get appropriate greeting based on time of day
        String greeting = getTimeBasedGreeting();
        
        if (user != null) {
            // get user's firestore doc
            String uid = user.getUid();
            DocumentReference userRef = db.collection("users").document(uid);
            
            // set default greeting while loading
            welcomeText.setText(greeting + "!");
            
            // try to load username from firestore
            userRef.get().addOnSuccessListener(documentSnapshot -> {
                String displayName;
                
                if (documentSnapshot.exists() && documentSnapshot.contains("username")) {
                    // Use username from Firestore if it exists
                    displayName = documentSnapshot.getString("username");
                    if (displayName != null && !displayName.isEmpty()) {
                        welcomeText.setText(greeting + " " + displayName + "!");
                        return; // Exit early if we found a username
                    }
                }
                
                // Only fall back to email if no username found in Firestore
                if (user.getEmail() != null) {
                    String email = user.getEmail();
                    int atIndex = email.indexOf('@');
                    if (atIndex > 0) {
                        displayName = email.substring(0, atIndex);
                        // Capitalize first letter
                        displayName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
                        welcomeText.setText(greeting + " " + displayName + "!");
                    } else {
                        welcomeText.setText(greeting + "!");
                    }
                } else {
                    welcomeText.setText(greeting + "!");
                }
            }).addOnFailureListener(e -> {
                // Handle failure by displaying just the greeting
                Log.e(TAG, "Error fetching username", e);
                welcomeText.setText(greeting + "!");
            });
        } else {
            // default greeting if not logged in - shouldn't happen
            welcomeText.setText(greeting + "!");
        }
    }

    // pick greeting based on time of day
    private String getTimeBasedGreeting() {
        Calendar c = Calendar.getInstance();
        int timeOfDay = c.get(Calendar.HOUR_OF_DAY);

        // tried a bunch of different time ranges, these seemed best
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

    // setup all button click handlers
    private void setupClicks() {
        // create task button
        btnCreateTask.setOnClickListener(v -> {
            clickCounts[0]++;
            startActivityForResult(new Intent(LoggedInActivity.this, AddTaskActivity.class), ADD_TASK_REQUEST);
        });

        // view tasks button
        btnViewTasks.setOnClickListener(v -> {
            clickCounts[1]++;
            startActivity(new Intent(LoggedInActivity.this, TaskListActivity.class));
        });

        // logout button - meh this should probably show a confirmation dialog
        btnLogout.setOnClickListener(v -> {
            clickCounts[2]++;
            FirebaseAuth.getInstance().signOut();
            startActivity(new Intent(LoggedInActivity.this, MainActivity.class));
            finish();
        });

        // adventure hub button
        btnAdventureHub.setOnClickListener(v -> {
            clickCounts[3]++;
            startActivity(new Intent(LoggedInActivity.this, ManageFeaturesActivity.class));
        });
        
        // theme toggle button - this could use a transition animation
        themeToggleBtn.setOnClickListener(v -> {
            // Cycle theme modes: Light -> Dark -> System -> Light
            ThemeHelper.toggleDarkMode(this);
            
            // Update button icon for next theme
            updateThemeBtnIcon();
            
            // Recreate the activity to apply theme change
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
                
                // should also refresh task list in background, maybe later
                // refreshTasksInBackground();
            }
        }
    }
    
    // fetch stats for the profile page - not used yet
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
                        // would show these somewhere on the profile
                        // profilePoints.setText(String.valueOf(points));
                        // profileStreak.setText(String.valueOf(streak));
                        // profileLevel.setText(String.valueOf(level));
                    }
                });
        }
    }
    
    // user tracking - remove before shipping to prod
    @Override
    protected void onPause() {
        super.onPause();
        // Log.d(TAG, "User left main screen. Clicks: " + 
        //           clickCounts[0] + ", " + 
        //           clickCounts[1] + ", " + 
        //           clickCounts[2] + ", " + 
        //           clickCounts[3]);
    }
    
    // for debugging errors - uncomment for local testing
    /*@Override
    public void onBackPressed() {
        super.onBackPressed();
        Log.d(TAG, "Back button pressed - activity finished");
    }*/
}