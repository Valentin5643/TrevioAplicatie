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
import com.example.myapplication.aplicatiamea.util.NativeLibraryHelper;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String LOG_TAG = "UserRegistration";

    private EditText userEmailField, userPasswordField, usernameField;
    private Button createAccountButton;
    private TextView existingUserLink;

    private FirebaseAuth authService;
    private FirebaseFirestore databaseService;
    private String pendingUserEmail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                Log.e(LOG_TAG, "Critical error in thread " + thread.getName(), throwable);
                try {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, 
                            "Something went wrong. Please restart the app.", 
                            Toast.LENGTH_LONG).show();
                    });
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Even the error toast failed", e);
                }
                System.exit(1);
            }
        });

        try {
            ThemeHelper.applyUserTheme(this);
            
            super.onCreate(savedInstanceState);

            int playServicesAvailability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this);
            if (playServicesAvailability != ConnectionResult.SUCCESS) {
                Log.w(LOG_TAG, "Google Play Services issue detected: " + playServicesAvailability);
                if (GoogleApiAvailability.getInstance().isUserResolvableError(playServicesAvailability)) {
                    GoogleApiAvailability.getInstance().getErrorDialog(this, playServicesAvailability, 9000).show();
                } else {
                    Toast.makeText(this, "Some features may not work without Google Play Services", Toast.LENGTH_LONG).show();
                }
            }
            
            setContentView(R.layout.activity_main);
            validateGooglePlayServices();
            setupFirebaseServices();
            
            // library for more performance operations
            if (!NativeLibraryHelper.isPenguinLibraryLoaded()) {
                NativeLibraryHelper.loadPenguinLibrary();
            }

            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if(currentUser != null) {
                startActivity(new Intent(MainActivity.this, LoggedInActivity.class));
                finish();
                return;
            }

            authService = FirebaseAuth.getInstance();
            databaseService = FirebaseFirestore.getInstance();

            initializeUIComponents();
            setupUserInteractions();
            configureModernUI();
            
        } catch (Exception e) {
            Log.e(LOG_TAG, "Fatal error during app initialization", e);
            Toast.makeText(this, "App failed to start. Please try again.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void setupUI() {
        try {
            Log.d(LOG_TAG, "UI configuration completed successfully");
        } catch (Exception e) {
            Log.e(LOG_TAG, "UI setup encountered an error", e);
            Toast.makeText(this, "Interface setup failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupFirebaseServices() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this);
                Log.d(LOG_TAG, "Firebase initialized successfully");
            } else {
                Log.d(LOG_TAG, "Firebase was already initialized");
            }

            authService = FirebaseAuth.getInstance();
            databaseService = FirebaseFirestore.getInstance();
            
        } catch (Exception e) {
            Log.e(LOG_TAG, "Firebase initialization failed", e);

            String userMessage = "App initialization failed";
            if (e.getMessage() != null) {
                if (e.getMessage().contains("API key")) {
                    userMessage = "Configuration error. Please contact support.";
                } else if (e.getMessage().contains("network")) {
                    userMessage = "Network error. Check your internet connection.";
                } else if (e.getMessage().contains("Google Play")) {
                    userMessage = "Google Play Services error. Please update.";
                }
            }
            
            Toast.makeText(this, userMessage + " Restart recommended.", Toast.LENGTH_LONG).show();

            try {
                authService = FirebaseAuth.getInstance();
                databaseService = FirebaseFirestore.getInstance();
            } catch (Exception fallbackError) {
                Log.e(LOG_TAG, "Even fallback Firebase initialization failed", fallbackError);
            }
        }
    }

    private void initializeUIComponents() {
        userEmailField = findViewById(R.id.emailInput);
        userPasswordField = findViewById(R.id.passwordInput);
        usernameField = findViewById(R.id.usernameInput);

        createAccountButton = findViewById(R.id.signupBtn);
        existingUserLink = findViewById(R.id.loginLinkTv);
    }
    

    private void setupUserInteractions() {
        createAccountButton.setOnClickListener(v -> initiateUserRegistration());

        existingUserLink.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, LogIn.class));
            finish();
        });
    }
    

    private void configureModernUI() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (view, windowInsets) -> {
            Insets systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());

            view.setPadding(
                systemBarsInsets.left,
                systemBarsInsets.top,
                systemBarsInsets.right,
                systemBarsInsets.bottom
            );
            
            return WindowInsetsCompat.CONSUMED;
        });

        WindowInsetsControllerCompat windowInsetsController = 
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (windowInsetsController != null) {
            windowInsetsController.setAppearanceLightStatusBars(true);
        }
    }

    private void validateGooglePlayServices() {
        try {
            GoogleApiAvailability playServicesChecker = GoogleApiAvailability.getInstance();
            int availabilityStatus = playServicesChecker.isGooglePlayServicesAvailable(this);
            
            if (availabilityStatus != ConnectionResult.SUCCESS) {
                Log.w(LOG_TAG, "Google Play Services not fully available: " + availabilityStatus);
                
                if (playServicesChecker.isUserResolvableError(availabilityStatus)) {
                    playServicesChecker.getErrorDialog(this, availabilityStatus, 9001).show();
                } else {
                    Toast.makeText(this, "Device not supported for full functionality", Toast.LENGTH_LONG).show();
                }
            } else {
                Log.d(LOG_TAG, "Google Play Services are available and up to date");
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error checking Google Play Services", e);
        }
    }

    private void initiateUserRegistration() {
        String email = userEmailField.getText().toString().trim();
        String password = userPasswordField.getText().toString().trim();
        String username = usernameField.getText().toString().trim();

        if (email.isEmpty()) {
            userEmailField.setError("Email is required");
            userEmailField.requestFocus();
            return;
        }
        
        if (password.isEmpty()) {
            userPasswordField.setError("Password is required");
            userPasswordField.requestFocus();
            return;
        }
        
        if (password.length() < 6) {
            userPasswordField.setError("Password must be at least 6 characters");
            userPasswordField.requestFocus();
            return;
        }
        
        if (username.isEmpty()) {
            usernameField.setError("Username is required");
            usernameField.requestFocus();
            return;
        }

        pendingUserEmail = email;

        createAccountButton.setEnabled(false);
        createAccountButton.setText("Creating Account...");

        authService.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this, task -> {
                if (task.isSuccessful()) {
                    Log.d(LOG_TAG, "User account created successfully");

                    saveUserProfileData(username, email, () -> {
                        Toast.makeText(MainActivity.this, "Welcome to Trevio!", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(MainActivity.this, LoggedInActivity.class));
                        finish();
                    });
                } else {
                    Log.w(LOG_TAG, "User registration failed", task.getException());

                    createAccountButton.setEnabled(true);
                    createAccountButton.setText("Sign Up");
                    String errorMessage = "Registration failed";
                    if (task.getException() != null) {
                        String exceptionMessage = task.getException().getMessage();
                        if (exceptionMessage != null) {
                            if (exceptionMessage.contains("email address is already in use")) {
                                errorMessage = "This email is already registered. Try logging in instead.";
                            } else if (exceptionMessage.contains("email address is badly formatted")) {
                                errorMessage = "Please enter a valid email address.";
                            } else if (exceptionMessage.contains("network error")) {
                                errorMessage = "Network error. Please check your connection.";
                            }
                        }
                    }
                    
                    Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                }
            });
    }
    

    private String generateUsernameFromEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "user" + System.currentTimeMillis(); // Fallback to timestamp
        }
        String baseUsername = email.substring(0, email.indexOf("@"));
        return baseUsername.replaceAll("[^a-zA-Z0-9]", ""); // Remove special characters
    }

    private void saveUserProfileData(String username, String email, Runnable onSuccess) {
        FirebaseUser currentUser = authService.getCurrentUser();
        if (currentUser == null) {
            Log.e(LOG_TAG, "No authenticated user found when trying to save profile");
            Toast.makeText(this, "Authentication error. Please try again.", Toast.LENGTH_SHORT).show();
            return;
        }
        
        Map<String, Object> userProfile = new HashMap<>();
        userProfile.put("username", username);
        userProfile.put("email", email);
        userProfile.put("createdAt", System.currentTimeMillis());
        userProfile.put("level", 1); // Everyone starts at level 1
        userProfile.put("xp", 0); // No XP to start with
        userProfile.put("coins", 100); // Give new users some starting coins
        userProfile.put("streak", 0); // No streak yet
        userProfile.put("tasksCompleted", 0); // Track total completed tasks
        
        databaseService.collection("users").document(currentUser.getUid())
            .set(userProfile)
            .addOnSuccessListener(aVoid -> {
                Log.d(LOG_TAG, "User profile saved successfully");
                if (onSuccess != null) {
                    onSuccess.run();
                }
            })
            .addOnFailureListener(e -> {
                Log.e(LOG_TAG, "Failed to save user profile", e);
                Toast.makeText(MainActivity.this, "Profile setup failed. Please try again.", Toast.LENGTH_SHORT).show();
                
                createAccountButton.setEnabled(true);
                createAccountButton.setText("Sign Up");
            });
    }
}