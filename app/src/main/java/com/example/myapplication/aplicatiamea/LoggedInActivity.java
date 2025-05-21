package com.example.myapplication.aplicatiamea;

import android.content.Intent;
import android.os.Bundle;
import android.app.Activity;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.example.myapplication.aplicatiamea.repository.QuestManager;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Calendar;

public class LoggedInActivity extends Activity {
    private static final String TAG = "LoggedInActivity";
    private static final int ADD_TASK_REQUEST = 1;
    private Button btnCreateTask;
    private Button btnViewTasks;
    private Button btnAdventureHub;
    private Button btnLogout;
    private TextView welcomeText;
    private ImageView logoImage;
    private ConstraintLayout mainLayout;
    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logged_in);

        db = FirebaseFirestore.getInstance();
        
        initializeViews();
        checkDarkModePreference();
        setupGreeting();
        setupButtons();
        
        // Check if daily and weekly quests are needed
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null) {
            QuestManager qm = new QuestManager(currentUser.getUid(), this);
            qm.issueDailyQuests();
            qm.issueWeeklyQuest();
        }
    }

    private void initializeViews() {
        btnCreateTask = findViewById(R.id.button4);
        btnViewTasks = findViewById(R.id.buttonTasks);
        btnLogout = findViewById(R.id.btnLogout);
        btnAdventureHub = findViewById(R.id.btnAdventureHub);
        welcomeText = findViewById(R.id.welcomeText);
        logoImage = findViewById(R.id.logoImage);
        mainLayout = findViewById(R.id.main);
    }

    private void checkDarkModePreference() {
        // This would normally be loaded from SharedPreferences
        // For now we'll just use a placeholder
    }

    private void setupGreeting() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String greeting = getTimeBasedGreeting();
        
        if (user != null) {
            String uid = user.getUid();
            DocumentReference userRef = db.collection("users").document(uid);
            
            // Set a temporary greeting while we fetch the username
            welcomeText.setText(greeting + "!");
            
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

    private void setupButtons() {
        btnCreateTask.setOnClickListener(v -> startActivityForResult(new Intent(LoggedInActivity.this, AddTaskActivity.class), ADD_TASK_REQUEST));

        btnViewTasks.setOnClickListener(v -> startActivity(new Intent(LoggedInActivity.this, TaskListActivity.class)));

        btnLogout.setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();
            startActivity(new Intent(LoggedInActivity.this, MainActivity.class));
            finish();
        });

        btnAdventureHub.setOnClickListener(v -> startActivity(new Intent(LoggedInActivity.this, ManageFeaturesActivity.class)));
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
}