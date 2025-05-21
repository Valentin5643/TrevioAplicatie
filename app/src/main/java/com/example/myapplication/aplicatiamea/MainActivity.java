package com.example.myapplication.aplicatiamea;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private EditText emailEditText, passwordEditText, usernameEditText;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private String email;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Check Google Play Services availability (will show dialog if necessary)
        checkGooglePlayServices();

        // Check existing login status
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if(currentUser != null) {
            startActivity(new Intent(MainActivity.this, LoggedInActivity.class));
            finish();
            return;
        }

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        emailEditText = findViewById(R.id.emailInput);
        passwordEditText = findViewById(R.id.passwordInput);
        usernameEditText = findViewById(R.id.usernameInput);
        Button signUpButton = findViewById(R.id.button);
        TextView loginRedirectText = findViewById(R.id.textView2);

        signUpButton.setOnClickListener(v -> attemptSignUp());
        loginRedirectText.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, LogIn.class));
            finish();
        });
    }

    /**
     * Check Google Play Services availability and show dialog to user if there's an issue
     */
    private void checkGooglePlayServices() {
        // Check if Google Play Services are available and up to date.
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        int resultCode = apiAvailability.isGooglePlayServicesAvailable(this);
        if (resultCode != ConnectionResult.SUCCESS) {
            // If resolvable, show dialog; otherwise log error
            if (apiAvailability.isUserResolvableError(resultCode)) {
                apiAvailability.getErrorDialog(this, resultCode, 9000).show();
            } else {
                Log.e(TAG, "This device is not supported for Google Play Services");
            }
        }
    }

    private void attemptSignUp() {
        String email = emailEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();
        String username = usernameEditText.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Email and password required", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // If username is empty, extract it from email
        if (username.isEmpty()) {
            username = extractUsernameFromEmail(email);
        }

        // Store final username for use in lambda
        final String finalUsername = username;
        
        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        // Save user data to Firestore including username
                        saveUserToFirestore(finalUsername, email, () -> {
                            Toast.makeText(MainActivity.this, "Sign up successful!", Toast.LENGTH_SHORT).show();
                            startActivity(new Intent(MainActivity.this, LoggedInActivity.class));
                            finish();
                        });
                    } else {
                        String errorMessage = "Sign up failed";
                        if (task.getException() != null) {
                            Log.e(TAG, "Sign up error", task.getException());
                            errorMessage += ": " + task.getException().getMessage();
                        }
                        Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                    }
                });
    }
    
    private String extractUsernameFromEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex > 0) {
            String username = email.substring(0, atIndex);
            // Capitalize first letter
            return username.substring(0, 1).toUpperCase() + username.substring(1);
        }
        return email; // Default to email if @ not found
    }

    // Overload with callback
    private void saveUserToFirestore(String username, String email, Runnable onSuccess) {
        this.email = email;
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            String userId = currentUser.getUid();
            DocumentReference userRef = db.collection("users").document(userId);
            userRef.get().addOnSuccessListener(documentSnapshot -> {
                Map<String, Object> userData = new HashMap<>();
                if (documentSnapshot.exists()) {
                    if (username != null && !username.trim().isEmpty()) {
                        if (!documentSnapshot.contains("username") || 
                            !username.equals(documentSnapshot.getString("username"))) {
                            userRef.update("username", username)
                                .addOnSuccessListener(aVoid -> { if (onSuccess != null) onSuccess.run(); })
                                .addOnFailureListener(e -> Log.e(TAG, "Error updating username", e));
                        } else {
                            if (onSuccess != null) onSuccess.run();
                        }
                    } else {
                        if (onSuccess != null) onSuccess.run();
                    }
                } else {
                    userData.put("points", 0L);
                    userData.put("streak", 0L);
                    userData.put("xp", 0L);
                    userData.put("level", 1L);
                    userData.put("goldCoins", 0L);
                    userData.put("activeEffects", new HashMap<String, Object>());
                    if (username != null && !username.trim().isEmpty()) {
                        userData.put("username", username);
                    }
                    userRef.set(userData)
                        .addOnSuccessListener(aVoid -> { if (onSuccess != null) onSuccess.run(); })
                        .addOnFailureListener(e -> Log.e(TAG, "Error creating user document", e));
                }
            }).addOnFailureListener(e -> Log.e(TAG, "Error checking user document", e));
        }
    }
}