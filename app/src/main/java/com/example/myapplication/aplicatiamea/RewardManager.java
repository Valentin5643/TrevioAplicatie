package com.example.myapplication.aplicatiamea;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Transaction;
import java.util.HashMap;
import java.util.Map;
import android.util.Log;
import java.util.concurrent.atomic.AtomicLong;
import android.net.ConnectivityManager;
import android.content.Context;
import android.net.NetworkInfo;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Rewards and XP accounting system.
 * Most XP is awarded through quest completion
 * This system also handles coins and special achievements.
 * see QuestManager for main reward logic
 */
public class RewardManager {
    private static final String TAG = "⭐Rewards";
    
    // Singleton DB reference
    private final FirebaseFirestore db;
    
    // User info
    private final String userId;
    private final DocumentReference userRef;
    private Context context;
    
    // Prevent spamming database with unneeded history entries 
    // for small amounts (debugging and analytics only really)
    private static final int MIN_XP_TO_LOG = 10;
    
    // Cache pending XP to batch-apply (optimization)
    private final AtomicLong pendingXp = new AtomicLong(0);
    private long lastXpFlushTime = 0;
    private static final long XP_FLUSH_INTERVAL_MS = 5000; // 5 seconds
    
    // Player level cache for logic
    private int cachedPlayerLevel = 0;
    
    // Failover queue for offline or bad networks
    private final Queue<Map<String, Object>> pendingTransactions = new ConcurrentLinkedQueue<>();
    private boolean isRetrying = false;
    private static final int MAX_RETRY_COUNT = 3;
    private static final int RETRY_DELAY_MS = 10000; // 10 seconds
    
    // Background thread for retry logic
    private ScheduledExecutorService retryExecutor;

    /**
     * Create reward manager for a specific user
     * 
     * @param userId Firebase user ID
     */
    public RewardManager(String userId) {
        this.userId = userId;
        this.db = FirebaseFirestore.getInstance();
        this.userRef = db.collection("users").document(userId);
        
        // Preload level if possible
        userRef.get().addOnSuccessListener(doc -> {
            if (doc.exists() && doc.contains("level")) {
                Long level = doc.getLong("level");
                if (level != null) {
                    cachedPlayerLevel = level.intValue();
                }
            }
        });
        
        // Start retry service
        retryExecutor = Executors.newSingleThreadScheduledExecutor();
        scheduleRetryCheck();
    }
    
    /**
     * Set context - needed for network checks
     */
    public void setContext(Context context) {
        this.context = context;
    }

    /**
     * @deprecated Task XP was removed in v1.2 after the farming exploit
     */
    @Deprecated
    public void awardTaskCompletionXp() {
        // No-op since this was exploited by users rapidly creating/completing tasks
        Log.d(TAG, "Task XP deprecated - ignoring call");
    }

    /**
     * @deprecated Task XP deduction also deprecated with task XP
     */
    @Deprecated
    public void deductXp() {
        // No-op - quest system handles XP now
    }

    /**
     * Award XP for a badge. Badges are granted for achievements and milestones.
     * 
     * @param badgeId Badge identifier
     * @param amount XP amount to award
     */
    public void awardXpForBadge(String badgeId, long amount) {
        if (amount <= 0) return;
        flushPendingXpIfNeeded();
        
        // Create transaction record
        Map<String, Object> xpTransaction = new HashMap<>();
        xpTransaction.put("timestamp", FieldValue.serverTimestamp());
        xpTransaction.put("type", "BADGE_AWARD");
        xpTransaction.put("badgeId", badgeId);
        xpTransaction.put("amount", amount);
        xpTransaction.put("level", cachedPlayerLevel);
        
        // Use batched update for efficiency
        final long finalAmount = amount;
        db.runTransaction((Transaction.Function<Void>) transaction -> {
            // Add XP
            transaction.update(userRef, "xp", FieldValue.increment(finalAmount));
            
            // Add history entry
            DocumentReference historyRef = userRef.collection("xpTransactions").document();
            transaction.set(historyRef, xpTransaction);
            
            return null;
        }).addOnSuccessListener(aVoid -> {
            Log.d(TAG, "Added " + finalAmount + "XP for badge: " + badgeId);
            checkLevelUp();
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to award badge XP: " + e.getMessage());
            
            // Don't lose XP on failure - emergency backup
            if (e instanceof FirebaseFirestoreException) {
                addPendingXp(finalAmount);
                pendingTransactions.add(xpTransaction);
            }
        });
    }

    /**
     * Award XP for completing a quest. 
     * This is the primary XP source in the game.
     * 
     * @param questTitle Quest name (for logging)
     * @param amount Base XP amount
     */
    public void awardXpForQuest(String questTitle, long amount) {
        if (amount <= 0) return;
        flushPendingXpIfNeeded();
        
        // Calculate XP with any active effects
        Map<String, Object> effects = getActiveEffects();
        if (EffectManager.isMindmeldXpBoostActive(effects)) {
            amount = Math.round(amount * EffectManager.mindmeldXpBoostMultiplier);
            Log.d(TAG, "Applied Mindmeld boost: " + amount + " XP for quest");
        }
        
        final long finalAmount = amount;
        
        // Quick offline check to avoid Firebase exceptions
        if (!isNetworkAvailable()) {
            Log.d(TAG, "No network - queuing quest XP for later: " + finalAmount);
            addPendingXp(finalAmount);
            
            // Also queue the history transaction for later
            Map<String, Object> xpTransaction = new HashMap<>();
            xpTransaction.put("type", "QUEST_COMPLETION");
            xpTransaction.put("questTitle", questTitle);
            xpTransaction.put("amount", finalAmount);
            xpTransaction.put("level", cachedPlayerLevel);
            xpTransaction.put("isOffline", true);
            
            pendingTransactions.add(xpTransaction);
            return;
        }
        
        // Create transaction record
        Map<String, Object> xpTransaction = new HashMap<>();
        xpTransaction.put("timestamp", FieldValue.serverTimestamp());
        xpTransaction.put("type", "QUEST_COMPLETION");
        xpTransaction.put("questTitle", questTitle);
        xpTransaction.put("amount", finalAmount);
        xpTransaction.put("level", cachedPlayerLevel);
        
        // Run as transaction for consistency
        db.runTransaction((Transaction.Function<Void>) transaction -> {
            transaction.update(userRef, "xp", FieldValue.increment(finalAmount));
            
            DocumentReference historyRef = userRef.collection("xpTransactions").document();
            transaction.set(historyRef, xpTransaction);
            
            return null;
        }).addOnSuccessListener(aVoid -> {
            Log.d(TAG, "Awarded " + finalAmount + "XP for quest: " + questTitle);
            checkLevelUp();
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Quest XP failed - will retry: " + e.getMessage());
            
            // On failure, add to pending queue for retry
            addPendingXp(finalAmount);
            pendingTransactions.add(xpTransaction);
        });
    }

    /**
     * Remove XP when a quest is un-completed (through undo feature).
     * 
     * @param questTitle Quest name
     * @param amount XP amount to remove
     */
    public void deductXpForQuest(String questTitle, long amount) {
        if (amount <= 0) return;
        flushPendingXpIfNeeded();
        
        // Offline check
        if (!isNetworkAvailable()) {
            Log.d(TAG, "No network - queuing XP deduction for later: " + amount);
            addPendingXp(-amount); // Negative
            
            // Queue the history transaction
            Map<String, Object> xpTransaction = new HashMap<>();
            xpTransaction.put("type", "QUEST_UNDO");
            xpTransaction.put("questTitle", questTitle);
            xpTransaction.put("amount", -amount);
            xpTransaction.put("level", cachedPlayerLevel);
            xpTransaction.put("isOffline", true);
            
            pendingTransactions.add(xpTransaction);
            return;
        }
        
        // Negative transaction for clarity
        Map<String, Object> xpTransaction = new HashMap<>();
        xpTransaction.put("timestamp", FieldValue.serverTimestamp());
        xpTransaction.put("type", "QUEST_UNDO");
        xpTransaction.put("questTitle", questTitle); 
        xpTransaction.put("amount", -amount);
        xpTransaction.put("level", cachedPlayerLevel);
        
        // Use transaction to ensure consistency
        db.runTransaction((Transaction.Function<Void>) transaction -> {
            // Remove XP but never go below zero
            if (amount > 0) {
                transaction.update(userRef, "xp", FieldValue.increment(-amount));
            }
            
            // Always log the history entry
            DocumentReference historyRef = userRef.collection("xpTransactions").document();
            transaction.set(historyRef, xpTransaction);
            
            return null;
        }).addOnSuccessListener(aVoid -> {
            Log.d(TAG, "Deducted " + amount + "XP for quest: " + questTitle);
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to deduct quest XP", e);
            pendingTransactions.add(xpTransaction);
        });
    }
    
    /**
     * Add small XP amount to pending batch
     */
    private void addPendingXp(long amount) {
        if (amount == 0) return;
        pendingXp.addAndGet(amount);
        
        // Only auto-flush on significant amount
        if (pendingXp.get() > 50) {
            flushPendingXp();
        }
    }
    
    /**
     * Force flush of accumulated XP
     */
    public void flushPendingXp() {
        long xpToApply = pendingXp.getAndSet(0);
        if (xpToApply == 0) return;
        
        Log.d(TAG, "Flushing batched XP: " + xpToApply);
        
        // Skip network call if offline
        if (!isNetworkAvailable()) {
            Log.d(TAG, "No network - keeping XP in queue: " + xpToApply);
            pendingXp.addAndGet(xpToApply); // Put it back
            return;
        }
        
        userRef.update("xp", FieldValue.increment(xpToApply))
            .addOnSuccessListener(aVoid -> {
                lastXpFlushTime = System.currentTimeMillis();
                checkLevelUp();
            })
            .addOnFailureListener(e -> {
                // On failure, put it back in the queue
                pendingXp.addAndGet(xpToApply);
                Log.e(TAG, "Failed to flush pending XP, will retry later", e);
            });
    }
    
    /**
     * Check if we need to flush cached XP
     */
    private void flushPendingXpIfNeeded() {
        long now = System.currentTimeMillis();
        if (pendingXp.get() > 0 && now - lastXpFlushTime > XP_FLUSH_INTERVAL_MS) {
            flushPendingXp();
        }
    }
    
    /**
     * Check if player leveled up and apply level-up rewards
     */
    private void checkLevelUp() {
        // Get current XP and level
        userRef.get().addOnSuccessListener(doc -> {
            if (!doc.exists()) return;
            
            Long xp = doc.getLong("xp");
            Long level = doc.getLong("level");
            
            if (xp == null || level == null) return;
            
            int currentLevel = level.intValue();
            cachedPlayerLevel = currentLevel; // Update cache
            
            // Calculate level from XP
            long calculatedLevel = XpCalculator.calculateLevelFromXp(xp);
            
            // Check if we leveled up
            if (calculatedLevel > currentLevel) {
                // Update level
                userRef.update("level", calculatedLevel)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "LEVEL UP! " + currentLevel + " → " + calculatedLevel);
                        cachedPlayerLevel = (int)calculatedLevel;
                        
                        // Give level-up rewards (coins, etc.)
                        awardLevelUpBonuses(currentLevel, (int)calculatedLevel);
                    });
            }
        });
    }
    
    /**
     * Award coins and other bonuses for leveling up
     */
    private void awardLevelUpBonuses(int oldLevel, int newLevel) {
        // Calculate coin bonus for each level gained
        int totalCoins = 0;
        for (int level = oldLevel + 1; level <= newLevel; level++) {
            // Base coins per level: 100 + 50 per level
            int coinsForThisLevel = 100 + (level * 50);
            totalCoins += coinsForThisLevel;
            
            // Special level achievements
            if (level == 10) {
                // Add first milestone reward at level 10
                unlockSpecialReward("LEVEL10_BADGE");
            } else if (level == 25) {
                // Add second milestone reward at level 25
                unlockSpecialReward("LEVEL25_GEAR");
            } else if (level == 50) {
                // Add third milestone reward at level 50
                unlockSpecialReward("LEVEL50_LEGENDARY");
            }
        }
        
        // Add the coins
        if (totalCoins > 0) {
            final int finalTotalCoins = totalCoins;
            userRef.update("coins", FieldValue.increment(finalTotalCoins))
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Added " + finalTotalCoins + " level-up bonus coins");
                });
        }
    }
    
    /**
     * Unlock a special reward item/badge at milestone levels
     */
    private void unlockSpecialReward(String rewardId) {
        // Add to inventory collection
        Map<String, Object> rewardData = new HashMap<>();
        rewardData.put("id", rewardId);
        rewardData.put("obtained", FieldValue.serverTimestamp());
        rewardData.put("source", "level_reward");
        
        userRef.collection("inventory").document(rewardId)
            .set(rewardData)
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "Unlocked special reward: " + rewardId);
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Failed to unlock reward: " + rewardId, e);
                // Add to pending transactions for retry
                pendingTransactions.add(rewardData);
            });
    }
    
    /**
     * Periodic retry of previously failed transactions
     */
    private void scheduleRetryCheck() {
        retryExecutor.scheduleWithFixedDelay(() -> {
            retryPendingTransactions();
        }, RETRY_DELAY_MS, RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Retry sending queued transactions to Firebase
     */
    private void retryPendingTransactions() {
        if (pendingTransactions.isEmpty() || isRetrying || !isNetworkAvailable()) {
            return;
        }
        
        isRetrying = true;
        
        try {
            // Try to flush any pending XP first
            flushPendingXp();
            
            // Process transaction queue
            int processCount = 0;
            while (!pendingTransactions.isEmpty() && processCount < 10) {
                Map<String, Object> transaction = pendingTransactions.poll();
                if (transaction == null) continue;
                
                // Add server timestamp for offline transactions
                if (transaction.containsKey("isOffline")) {
                    transaction.remove("isOffline");
                    transaction.put("timestamp", FieldValue.serverTimestamp());
                    transaction.put("delayed", true);
                }
                
                // Add to history
                userRef.collection("xpTransactions").document()
                    .set(transaction)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "Successfully retried delayed transaction");
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to retry transaction, will try again", e);
                        pendingTransactions.add(transaction);
                    });
                
                processCount++;
            }
        } finally {
            isRetrying = false;
        }
    }
    
    /**
     * Get active effects from user document
     */
    private Map<String, Object> getActiveEffects() {
        // Should be retrieved from user document's activeEffects field
        // This is a placeholder - real implementation should be async
        return new HashMap<>();
    }
    
    /**
     * Check if network is available
     */
    private boolean isNetworkAvailable() {
        if (context == null) return true; // Assume online if no context
        
        ConnectivityManager cm = (ConnectivityManager) 
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (cm == null) return true; // Fallback
        
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }
    
    /**
     * Cleanup executor on shutdown
     */
    public void shutdown() {
        if (retryExecutor != null && !retryExecutor.isShutdown()) {
            flushPendingXp(); // Last chance to save
            retryExecutor.shutdown();
        }
    }
} 