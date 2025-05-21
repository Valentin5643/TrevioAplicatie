package com.example.myapplication.aplicatiamea;

import android.content.Intent;
import android.os.Bundle;
import android.app.Activity;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class LogIn extends Activity {
    private static final String TAG = "LogIn";
    private EditText loginEmail, loginPassword;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_in);

        // Check existing login status
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if(currentUser != null) {
            startActivity(new Intent(LogIn.this, LoggedInActivity.class));
            finish();
            return;
        }

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        loginEmail = findViewById(R.id.emailInput);
        loginPassword = findViewById(R.id.passwordInput);
        Button btnLogin = findViewById(R.id.button);
        TextView registerRedirectText = findViewById(R.id.textView);

        btnLogin.setOnClickListener(v -> attemptLogin());
        registerRedirectText.setOnClickListener(v -> {
            startActivity(new Intent(LogIn.this, MainActivity.class));
            finish();
        });
    }

    private void attemptLogin() {
        String email = loginEmail.getText().toString().trim();
        String password = loginPassword.getText().toString().trim();

        if (email.isEmpty()) {
            loginEmail.setError("Email required!");
            return;
        }
        if (password.isEmpty()) {
            loginPassword.setError("Password required!");
            return;
        }

        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        // Ensure username exists for this user
                        ensureUsernameExists(email);
                        
                        Toast.makeText(LogIn.this, "Login successful", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(LogIn.this, LoggedInActivity.class));
                        finish();
                    } else {
                        Toast.makeText(LogIn.this, "Login failed: " +
                                task.getException().getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }
    
    private void ensureUsernameExists(String email) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            String userId = currentUser.getUid();
            DocumentReference userRef = db.collection("users").document(userId);
            
            userRef.get().addOnSuccessListener(documentSnapshot -> {
                if (!documentSnapshot.exists()) {
                    // Create new user document with basic fields, but don't generate a username automatically
                    Map<String, Object> userData = new HashMap<>();
                    userData.put("points", 0L);
                    userData.put("streak", 0L);
                    userData.put("xp", 0L);
                    userData.put("level", 1L);
                    userData.put("goldCoins", 0L);
                    userData.put("activeEffects", new HashMap<String, Object>());
                    
                    userRef.set(userData)
                        .addOnFailureListener(e -> Log.e(TAG, "Error creating user document", e));
                }
                // We don't add or modify username during login
            }).addOnFailureListener(e -> Log.e(TAG, "Error checking user document", e));
        }
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
}