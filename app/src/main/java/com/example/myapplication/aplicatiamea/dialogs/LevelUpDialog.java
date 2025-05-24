package com.example.myapplication.aplicatiamea.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.example.myapplication.aplicatiamea.R;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FieldValue;

public class LevelUpDialog extends Dialog {
    private final int newLevel;
    private TextView tvNewLevel;
    private TextView tvGoldAmount;
    private TextView tvItemDescription;
    private Button btnContinue;
    private OnRewardClaimedListener listener;

    /**
     * Interface for handling reward claim events
     */
    public interface OnRewardClaimedListener {
        void onRewardsClaimed(int goldAmount);
    }
    
    public LevelUpDialog(@NonNull Context context, int newLevel) {
        super(context);
        this.newLevel = newLevel;
    }
    
    public void setOnRewardClaimedListener(OnRewardClaimedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_level_up);
        
        // Set dialog properties
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, 
                WindowManager.LayoutParams.WRAP_CONTENT);
        getWindow().setGravity(Gravity.CENTER);
        
        // Initialize views
        tvNewLevel = findViewById(R.id.newLevelText);
        tvGoldAmount = findViewById(R.id.goldAmount);
        tvItemDescription = findViewById(R.id.itemDesc);
        btnContinue = findViewById(R.id.claimButton);
        
        // Set level text
        tvNewLevel.setText("You've reached level " + newLevel + "!");
        
        // Calculate gold bonus based on level
        int goldBonus = calculateGoldBonus(newLevel);
        tvGoldAmount.setText("+" + goldBonus + " gold coins");
        
        // Set item unlock description based on level
        setItemUnlockDescription(newLevel);
        
        // Set continue button click listener
        btnContinue.setOnClickListener(v -> {
            // Award rewards and dismiss dialog
            awardRewards(goldBonus);
            if (listener != null) {
                listener.onRewardsClaimed(goldBonus);
            }
            dismiss();
        });
    }
    
    /**
     * Calculate gold bonus based on level
     * @param level Current level
     * @return Gold amount to award
     */
    private int calculateGoldBonus(int level) {
        // Base amount plus level-based bonus
        return 50 + (level * 10);
    }
    
    /**
     * Set item unlock description based on level
     * @param level Current level
     */
    private void setItemUnlockDescription(int level) {
        String description;
        
        // Different descriptions based on level milestones
        if (level < 5) {
            description = "New basic items available in the shop!";
        } else if (level < 10) {
            description = "Improved tier items are now available!";
        } else if (level < 15) {
            description = "Advanced equipment has been unlocked!";
        } else if (level < 20) {
            description = "Superior gear awaits you in the shop!";
        } else {
            description = "Legendary items have been unlocked!";
        }
        
        tvItemDescription.setText(description);
    }
    
    /**
     * Award gold coins to the user
     * @param goldAmount Amount of gold to award
     */
    private void awardRewards(int goldAmount) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        
        // Get user document reference
        DocumentReference userRef = FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid());
        
        // Award gold coins
        userRef.update("goldCoins", FieldValue.increment(goldAmount));
    }
} 