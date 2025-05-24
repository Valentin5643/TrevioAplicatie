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

/**
 * User registration screen - the entry point for new users.
 * This is where the journey begins for anyone who hasn't signed up yet.
 * 
 * Note: We've had issues with Google Play Services on some older devices,
 * so there's extra error handling around that. The crash handler was added
 * after we got reports of silent failures on Samsung devices.
 */
public class MainActivity extends AppCompatActivity {

    private static final String LOG_TAG = "UserRegistration";
    
    // Core UI components for user registration
    private EditText userEmailField, userPasswordField, usernameField;
    private Button createAccountButton;
    private TextView existingUserLink;
    
    // Firebase services - the backbone of our authentication
    private FirebaseAuth authService;
    private FirebaseFirestore databaseService;
    private String pendingUserEmail; // Store email for post-registration flow

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Global crash handler - learned this the hard way after production crashes
        // Some devices (especially older Samsung ones) fail silently without this
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                Log.e(LOG_TAG, "Critical error in thread " + thread.getName(), throwable);
                
                // Try to give user some feedback before crashing
                try {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, 
                            "Something went wrong. Please restart the app.", 
                            Toast.LENGTH_LONG).show();
                    });
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Even the error toast failed", e);
                }
                
                // Let Android handle the crash properly
                System.exit(1);
            }
        });

        try {
            // Theme must be applied before setContentView or users see a flash
            ThemeHelper.applyUserTheme(this);
            
            super.onCreate(savedInstanceState);
            
            // Google Play Services check - this breaks on weird Chinese devices
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
            
            // Additional Google Play Services validation
            validateGooglePlayServices();

            // Make sure Firebase is ready to go
            setupFirebaseServices();
            
            // Load our native library for performance-critical operations
            if (!NativeLibraryHelper.isPenguinLibraryLoaded()) {
                NativeLibraryHelper.loadPenguinLibrary();
            }
            
            // Skip registration if user is already authenticated
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if(currentUser != null) {
                // User is already logged in, send them to the main app
                startActivity(new Intent(MainActivity.this, LoggedInActivity.class));
                finish();
                return;
            }

            // Initialize Firebase authentication and database
            authService = FirebaseAuth.getInstance();
            databaseService = FirebaseFirestore.getInstance();
            
            // Wire up the user interface
            initializeUIComponents();
            setupUserInteractions();
            configureModernUI();
            
        } catch (Exception e) {
            Log.e(LOG_TAG, "Fatal error during app initialization", e);
            Toast.makeText(this, "App failed to start. Please try again.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /**
     * Additional UI setup that was getting too complex for onCreate.
     * Keeping this separate makes debugging easier when things break.
     */
    private void setupUI() {
        try {
            // Any additional UI configuration goes here
            // This method exists for future expansion
            Log.d(LOG_TAG, "UI configuration completed successfully");
        } catch (Exception e) {
            Log.e(LOG_TAG, "UI setup encountered an error", e);
            Toast.makeText(this, "Interface setup failed", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Firebase initialization with comprehensive error handling.
     * We've seen this fail in various ways on different devices, so being thorough.
     */
    private void setupFirebaseServices() {
        try {
            // Check if Firebase is already initialized (can happen with multiple activities)
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this);
                Log.d(LOG_TAG, "Firebase initialized successfully");
            } else {
                Log.d(LOG_TAG, "Firebase was already initialized");
            }
            
            // Get our service instances
            authService = FirebaseAuth.getInstance();
            databaseService = FirebaseFirestore.getInstance();
            
        } catch (Exception e) {
            Log.e(LOG_TAG, "Firebase initialization failed", e);
            
            // Provide specific error messages based on the failure type
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
            
            // Try to initialize services anyway for basic functionality
            try {
                authService = FirebaseAuth.getInstance();
                databaseService = FirebaseFirestore.getInstance();
            } catch (Exception fallbackError) {
                Log.e(LOG_TAG, "Even fallback Firebase initialization failed", fallbackError);
            }
        }
    }

    /**
     * Find and initialize all UI components.
     * Separated from onCreate to keep things organized.
     */
    private void initializeUIComponents() {
        // Input fields for user registration
        userEmailField = findViewById(R.id.emailInput);
        userPasswordField = findViewById(R.id.passwordInput);
        usernameField = findViewById(R.id.usernameInput);
        
        // Action buttons
        createAccountButton = findViewById(R.id.signupBtn);
        existingUserLink = findViewById(R.id.loginLinkTv);
    }
    
    /**
     * Set up all user interaction handlers.
     * Click listeners and other UI event handlers go here.
     */
    private void setupUserInteractions() {
        // Main registration action
        createAccountButton.setOnClickListener(v -> initiateUserRegistration());
        
        // Link to login screen for existing users
        existingUserLink.setOnClickListener(v -> {
            // Navigate to login instead of registration
            startActivity(new Intent(MainActivity.this, LogIn.class));
            finish(); // Don't keep registration screen in back stack
        });
    }
    
    /**
     * Configure modern Android UI features like edge-to-edge display.
     * This makes the app look more polished on newer devices.
     */
    private void configureModernUI() {
        // Enable edge-to-edge display for immersive experience
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        
        // Handle system UI insets properly
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (view, windowInsets) -> {
            Insets systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            
            // Apply padding to avoid content being hidden behind system bars
            view.setPadding(
                systemBarsInsets.left,
                systemBarsInsets.top,
                systemBarsInsets.right,
                systemBarsInsets.bottom
            );
            
            return WindowInsetsCompat.CONSUMED;
        });
        
        // Configure status bar appearance
        WindowInsetsControllerCompat windowInsetsController = 
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (windowInsetsController != null) {
            // Make status bar icons dark on light backgrounds
            windowInsetsController.setAppearanceLightStatusBars(true);
        }
    }
    
    /**
     * Additional Google Play Services validation.
     * Some devices lie about having GPS, so we double-check here.
     */
    private void validateGooglePlayServices() {
        try {
            GoogleApiAvailability playServicesChecker = GoogleApiAvailability.getInstance();
            int availabilityStatus = playServicesChecker.isGooglePlayServicesAvailable(this);
            
            if (availabilityStatus != ConnectionResult.SUCCESS) {
                Log.w(LOG_TAG, "Google Play Services not fully available: " + availabilityStatus);
                
                if (playServicesChecker.isUserResolvableError(availabilityStatus)) {
                    // Show dialog to help user fix the issue
                    playServicesChecker.getErrorDialog(this, availabilityStatus, 9001).show();
                } else {
                    // Non-recoverable error
                    Toast.makeText(this, "Device not supported for full functionality", Toast.LENGTH_LONG).show();
                }
            } else {
                Log.d(LOG_TAG, "Google Play Services are available and up to date");
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error checking Google Play Services", e);
            // Continue anyway - app can work without GPS in degraded mode
        }
    }
    
    /**
     * Handle user registration process.
     * This is where the magic happens - creating new user accounts.
     */
    private void initiateUserRegistration() {
        // Get user input
        String email = userEmailField.getText().toString().trim();
        String password = userPasswordField.getText().toString().trim();
        String username = usernameField.getText().toString().trim();
        
        // Basic validation - keep it simple but effective
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
        
        // Store email for later use
        pendingUserEmail = email;
        
        // Disable button to prevent double-submission
        createAccountButton.setEnabled(false);
        createAccountButton.setText("Creating Account...");
        
        // Create the Firebase user account
        authService.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this, task -> {
                if (task.isSuccessful()) {
                    Log.d(LOG_TAG, "User account created successfully");
                    
                    // Save additional user data to Firestore
                    saveUserProfileData(username, email, () -> {
                        // Success! Navigate to main app
                        Toast.makeText(MainActivity.this, "Welcome to Trevio!", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(MainActivity.this, LoggedInActivity.class));
                        finish();
                    });
                } else {
                    // Registration failed
                    Log.w(LOG_TAG, "User registration failed", task.getException());
                    
                    // Re-enable button
                    createAccountButton.setEnabled(true);
                    createAccountButton.setText("Sign Up");
                    
                    // Show user-friendly error message
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
    
    /**
     * Generate a username from email if user didn't provide one.
     * Simple fallback for lazy users who skip the username field.
     */
    private String generateUsernameFromEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "user" + System.currentTimeMillis(); // Fallback to timestamp
        }
        
        // Take the part before @ and clean it up
        String baseUsername = email.substring(0, email.indexOf("@"));
        return baseUsername.replaceAll("[^a-zA-Z0-9]", ""); // Remove special characters
    }
    
    /**
     * Save user profile data to Firestore.
     * This creates the user document that the rest of the app relies on.
     */
    private void saveUserProfileData(String username, String email, Runnable onSuccess) {
        FirebaseUser currentUser = authService.getCurrentUser();
        if (currentUser == null) {
            Log.e(LOG_TAG, "No authenticated user found when trying to save profile");
            Toast.makeText(this, "Authentication error. Please try again.", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Create user profile document
        Map<String, Object> userProfile = new HashMap<>();
        userProfile.put("username", username);
        userProfile.put("email", email);
        userProfile.put("createdAt", System.currentTimeMillis());
        userProfile.put("level", 1); // Everyone starts at level 1
        userProfile.put("xp", 0); // No XP to start with
        userProfile.put("coins", 100); // Give new users some starting coins
        userProfile.put("streak", 0); // No streak yet
        userProfile.put("tasksCompleted", 0); // Track total completed tasks
        
        // Save to Firestore
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
                
                // Re-enable the registration button
                createAccountButton.setEnabled(true);
                createAccountButton.setText("Sign Up");
            });
    }
}