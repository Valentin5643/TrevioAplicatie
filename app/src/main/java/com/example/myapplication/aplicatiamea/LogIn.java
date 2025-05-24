package com.example.myapplication.aplicatiamea;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.graphics.Insets;

import com.example.myapplication.aplicatiamea.util.ThemeHelper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class LogIn extends AppCompatActivity {
    private static final String TAG = "LogIn";
    
    // UI stuff
    private EditText emailInput, passwordInput;
    private Button loginButton;
    private TextView signUp;
    
    // Firebase junk
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    
    // misc flags
    private boolean loginInProgress = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // setup theme
        ThemeHelper.applyUserTheme(this);
        
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_in);

        // Skip login screen if user already authenticated - saves time on app restart
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if(user != null) {
                startActivity(new Intent(LogIn.this, LoggedInActivity.class));
                finish();
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking current user", e);
        }

        // Firebase connection test - fail early if services unavailable
        try {
            mAuth = FirebaseAuth.getInstance();
            db = FirebaseFirestore.getInstance();
            
            if (mAuth == null || db == null) {
                throw new Exception("Firebase services not available");
            }
        } catch (Exception e) {
            Log.e(TAG, "Firebase initialization failed in LogIn", e);
            Toast.makeText(this, "Authentication service unavailable. Please check your connection.", Toast.LENGTH_LONG).show();
            
            // Try to continue anyway - some functionality might still work
            try {
                mAuth = FirebaseAuth.getInstance();
                db = FirebaseFirestore.getInstance();
            } catch (Exception e2) {
                Log.e(TAG, "Failed to initialize Firebase services", e2);
            }
        }
        
        // Get UI things
        findViews();

        // Debug credentials for faster testing during development
        if (emailInput.getText().toString().isEmpty()) {
            //loginEmail.setText("test@test.com");
            //loginPassword.setText("testtest");
        }

        // click handlers
        loginButton.setOnClickListener(v -> doLogin());
        signUp.setOnClickListener(v -> {
            startActivity(new Intent(LogIn.this, MainActivity.class));
            finish();
        });

        setupEdgeToEdgeUI();
    }

    private void findViews() {
        emailInput = findViewById(R.id.emailInput);
        passwordInput = findViewById(R.id.passwordInput);
        loginButton = findViewById(R.id.loginBtn);
        signUp = findViewById(R.id.signupLinkTv);
    }

    private void setupEdgeToEdgeUI() {
        View rootLayout = findViewById(R.id.loginRoot);
        WindowInsetsControllerCompat windowInsetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        windowInsetsController.setAppearanceLightStatusBars(false); // Dark icons on light background
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            rootLayout.setPadding(0, systemBars.top, 0, 0);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void doLogin() {
        // Prevent double-tap login attempts that could cause race conditions
        if (loginInProgress) {
            return;
        }
        
        if (mAuth == null) {
            Toast.makeText(this, "Authentication service not available. Please restart the app.", Toast.LENGTH_LONG).show();
            return;
        }
        
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString().trim();

        if (email.isEmpty()) {
            emailInput.setError("Email required!");
            return;
        }
        if (password.isEmpty()) {
            passwordInput.setError("Password required!");
            return;
        }

        // Clear previous error states
        emailInput.setError(null);
        passwordInput.setError(null);
        
        loginInProgress = true;

        mAuth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this, task -> {
                loginInProgress = false;
                
                if (task.isSuccessful()) {
                    // User doc creation happens on first login for new users
                    createUserDataIfMissing(email);
                    
                    Toast.makeText(LogIn.this, "Login successful", Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(LogIn.this, LoggedInActivity.class));
                    finish();
                } else {
                    // Better error messages based on Firebase auth error codes
                    String errorMessage = "Login failed";
                    Exception exception = task.getException();
                    
                    if (exception != null) {
                        String exceptionMessage = exception.getMessage();
                        if (exceptionMessage != null) {
                            if (exceptionMessage.contains("network")) {
                                errorMessage = "Network error. Please check your connection.";
                            } else if (exceptionMessage.contains("password") || exceptionMessage.contains("INVALID_PASSWORD")) {
                                errorMessage = "Invalid password. Please try again.";
                            } else if (exceptionMessage.contains("email") || exceptionMessage.contains("INVALID_EMAIL")) {
                                errorMessage = "Invalid email address.";
                            } else if (exceptionMessage.contains("USER_NOT_FOUND")) {
                                errorMessage = "No account found with this email.";
                            } else if (exceptionMessage.contains("TOO_MANY_REQUESTS")) {
                                errorMessage = "Too many failed attempts. Please try again later.";
                            } else if (exceptionMessage.contains("USER_DISABLED")) {
                                errorMessage = "This account has been disabled.";
                            } else {
                                errorMessage = "Login failed: " + exceptionMessage;
                            }
                        }
                        Log.e(TAG, "Login failed", exception);
                    }
                    
                    Toast.makeText(LogIn.this, errorMessage, Toast.LENGTH_LONG).show();
                }
            })
            .addOnFailureListener(e -> {
                loginInProgress = false;
                Log.e(TAG, "Login task failed", e);
                Toast.makeText(LogIn.this, "Login failed due to connection issues. Please try again.", Toast.LENGTH_LONG).show();
            });
    }
    
    // Initialize default user data for first-time users - prevents null reference errors later
    private void createUserDataIfMissing(String email) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;
            
        String userId = user.getUid();
        DocumentReference userRef = db.collection("users").document(userId);
            
        userRef.get().addOnSuccessListener(doc -> {
            if (!doc.exists()) {
                // Starting values for new users - tuned based on user feedback
                Map<String, Object> userData = new HashMap<>();
                userData.put("points", 0L);
                userData.put("streak", 0L);
                userData.put("xp", 0L);
                userData.put("level", 1L);
                userData.put("goldCoins", 100L); // give new users 100 coins to start
                userData.put("activeEffects", new HashMap<String, Object>());
                
                userRef.set(userData)
                    .addOnFailureListener(e -> Log.e(TAG, "Error creating user doc", e));
            }
        }).addOnFailureListener(e -> Log.e(TAG, "Error checking user doc", e));
    }
    
    // Username extraction for display purposes - not used currently but kept for future features
    private String getUsernameFromEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex > 0) {
            String username = email.substring(0, atIndex);
            // Capitalize first letter
            return username.substring(0, 1).toUpperCase() + username.substring(1);
        }
        return email; // backup
    }
}