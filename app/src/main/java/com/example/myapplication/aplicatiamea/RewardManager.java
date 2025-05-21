package com.example.myapplication.aplicatiamea;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FieldValue;
import java.util.HashMap;
import java.util.Map;
import android.util.Log;
/**
 * Manages points, badges, and virtual currency (gold coins) for the user.
 * Also handles XP rewards for tasks, badge unlocks, and quest completions.
 */
public class RewardManager {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final String userId;
    private final DocumentReference userRef;

    // Inline badge criteria names to replace BadgeCriteria enum

    public RewardManager(String userId) {
        this.userId = userId;
        this.userRef = db.collection("users").document(userId);
    }

    /**
     * Award XP for task completion with fairness guard.
     * Deprecated: XP is now only awarded for quest completion, not for tasks.
     */
    @Deprecated
    public void awardTaskCompletionXp() {
        // No-op: XP is only awarded for quest completion now.
        Log.d("RewardManager", "awardTaskCompletionXp is deprecated and does nothing.");
    }

    /**
     * Deduct XP when uncompleting a task, ensuring no negative xp.
     * Deprecated: XP is now only awarded/deducted for quest completion, not for tasks.
     */
    @Deprecated
    public void deductXp() {
        // No-op: XP is only awarded/deducted for quest completion now.
        Log.d("RewardManager", "deductXp (task) is deprecated and does nothing.");
    }

    /**
     * Award XP for unlocking a badge.
     * @param badgeId The ID of the badge being unlocked
     * @param amount The amount of XP to award
     */
    public void awardXpForBadge(String badgeId, long amount) {
        if (amount <= 0) return;
        
        // Record the badge XP transaction
        Map<String, Object> xpTransaction = new HashMap<>();
        xpTransaction.put("timestamp", FieldValue.serverTimestamp());
        xpTransaction.put("type", "BADGE_UNLOCK");
        xpTransaction.put("badgeId", badgeId);
        xpTransaction.put("amount", amount);
        
        // Add the transaction to history
        userRef.collection("xpTransactions").add(xpTransaction);
        
        // Update user's total XP
        userRef.update("xp", FieldValue.increment(amount))
            .addOnSuccessListener(aVoid -> Log.d("RewardManager", "Awarded " + amount + " XP for badge: " + badgeId))
            .addOnFailureListener(e -> Log.e("RewardManager", "Failed to award XP for badge", e));
    }

    /**
     * Award XP for completing a quest.
     * @param questTitle The title of the completed quest
     * @param amount The amount of XP to award
     */
    public void awardXpForQuest(String questTitle, long amount) {
        if (amount <= 0) return;
        
        // Record the quest XP transaction
        Map<String, Object> xpTransaction = new HashMap<>();
        xpTransaction.put("timestamp", FieldValue.serverTimestamp());
        xpTransaction.put("type", "QUEST_COMPLETION");
        xpTransaction.put("questTitle", questTitle);
        xpTransaction.put("amount", amount);
        
        // Add the transaction to history
        userRef.collection("xpTransactions").add(xpTransaction);
        
        // Update user's total XP
        userRef.update("xp", FieldValue.increment(amount))
            .addOnSuccessListener(aVoid -> Log.d("RewardManager", "Awarded " + amount + " XP for quest: " + questTitle))
            .addOnFailureListener(e -> Log.e("RewardManager", "Failed to award XP for quest", e));
    }

    /**
     * Deduct XP when a quest completion is undone.
     * @param questTitle The title of the quest being undone
     * @param amount The amount of XP to deduct
     */
    public void deductXpForQuest(String questTitle, long amount) {
        if (amount <= 0) return;
        
        // Record the quest XP transaction
        Map<String, Object> xpTransaction = new HashMap<>();
        xpTransaction.put("timestamp", FieldValue.serverTimestamp());
        xpTransaction.put("type", "QUEST_UNDO");
        xpTransaction.put("questTitle", questTitle);
        xpTransaction.put("amount", -amount);  // Negative amount for deduction
        
        // Add the transaction to history
        userRef.collection("xpTransactions").add(xpTransaction);
        
        // Update user's total XP
        userRef.update("xp", FieldValue.increment(-amount))
            .addOnSuccessListener(aVoid -> Log.d("RewardManager", "Deducted " + amount + " XP for undoing quest: " + questTitle))
            .addOnFailureListener(e -> Log.e("RewardManager", "Failed to deduct XP for quest", e));
    }
} 