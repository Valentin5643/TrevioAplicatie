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


public class RewardManager {
    private static final String TAG = "⭐Rewards";
    

    private final FirebaseFirestore db;
    

    private final String userId;
    private final DocumentReference userRef;
    private Context context;
    

    private static final int MIN_XP_TO_LOG = 10;

    private final AtomicLong pendingXp = new AtomicLong(0);
    private long lastXpFlushTime = 0;
    private static final long XP_FLUSH_INTERVAL_MS = 5000; // 5 seconds

    private int cachedPlayerLevel = 0;

    private final Queue<Map<String, Object>> pendingTransactions = new ConcurrentLinkedQueue<>();
    private boolean isRetrying = false;
    private static final int MAX_RETRY_COUNT = 3;
    private static final int RETRY_DELAY_MS = 10000; // 10 seconds

    private ScheduledExecutorService retryExecutor;

    public RewardManager(String userId) {
        this.userId = userId;
        this.db = FirebaseFirestore.getInstance();
        this.userRef = db.collection("users").document(userId);
        

        userRef.get().addOnSuccessListener(doc -> {
            if (doc.exists() && doc.contains("level")) {
                Long level = doc.getLong("level");
                if (level != null) {
                    cachedPlayerLevel = level.intValue();
                }
            }
        });
        

        retryExecutor = Executors.newSingleThreadScheduledExecutor();
        scheduleRetryCheck();
    }
    

    public void setContext(Context context) {
        this.context = context;
    }


    @Deprecated
    public void awardTaskCompletionXp() {

        Log.d(TAG, "Task XP deprecated - ignoring call");
    }


    @Deprecated
    public void deductXp() {

    }


    public void awardXpForBadge(String badgeId, long amount) {
        if (amount <= 0) return;
        flushPendingXpIfNeeded();
        

        Map<String, Object> xpTransaction = new HashMap<>();
        xpTransaction.put("timestamp", FieldValue.serverTimestamp());
        xpTransaction.put("type", "BADGE_AWARD");
        xpTransaction.put("badgeId", badgeId);
        xpTransaction.put("amount", amount);
        xpTransaction.put("level", cachedPlayerLevel);
        

        final long finalAmount = amount;
        db.runTransaction((Transaction.Function<Void>) transaction -> {
            transaction.update(userRef, "xp", FieldValue.increment(finalAmount));
            DocumentReference historyRef = userRef.collection("xpTransactions").document();
            transaction.set(historyRef, xpTransaction);
            
            return null;
        }).addOnSuccessListener(aVoid -> {
            Log.d(TAG, "Added " + finalAmount + "XP for badge: " + badgeId);
            checkLevelUp();
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to award badge XP: " + e.getMessage());
            if (e instanceof FirebaseFirestoreException) {
                addPendingXp(finalAmount);
                pendingTransactions.add(xpTransaction);
            }
        });
    }


    public void awardXpForQuest(String questTitle, long amount) {
        if (amount <= 0) return;
        flushPendingXpIfNeeded();

        Map<String, Object> effects = getActiveEffects();
        if (EffectManager.isMindmeldXpBoostActive(effects)) {
            amount = Math.round(amount * EffectManager.mindmeldXpBoostMultiplier);
            Log.d(TAG, "Applied Mindmeld boost: " + amount + " XP for quest");
        }
        
        final long finalAmount = amount;
        

        if (!isNetworkAvailable()) {
            Log.d(TAG, "No network - queuing quest XP for later: " + finalAmount);
            addPendingXp(finalAmount);
            
            Map<String, Object> xpTransaction = new HashMap<>();
            xpTransaction.put("type", "QUEST_COMPLETION");
            xpTransaction.put("questTitle", questTitle);
            xpTransaction.put("amount", finalAmount);
            xpTransaction.put("level", cachedPlayerLevel);
            xpTransaction.put("isOffline", true);
            
            pendingTransactions.add(xpTransaction);
            return;
        }
        
        Map<String, Object> xpTransaction = new HashMap<>();
        xpTransaction.put("timestamp", FieldValue.serverTimestamp());
        xpTransaction.put("type", "QUEST_COMPLETION");
        xpTransaction.put("questTitle", questTitle);
        xpTransaction.put("amount", finalAmount);
        xpTransaction.put("level", cachedPlayerLevel);
        
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
            
            addPendingXp(finalAmount);
            pendingTransactions.add(xpTransaction);
        });
    }

    public void deductXpForQuest(String questTitle, long amount) {
        if (amount <= 0) return;
        flushPendingXpIfNeeded();
        
        if (!isNetworkAvailable()) {
            Log.d(TAG, "No network - queuing XP deduction for later: " + amount);
            addPendingXp(-amount); // Negative
            
            Map<String, Object> xpTransaction = new HashMap<>();
            xpTransaction.put("type", "QUEST_UNDO");
            xpTransaction.put("questTitle", questTitle);
            xpTransaction.put("amount", -amount);
            xpTransaction.put("level", cachedPlayerLevel);
            xpTransaction.put("isOffline", true);
            
            pendingTransactions.add(xpTransaction);
            return;
        }
        
        Map<String, Object> xpTransaction = new HashMap<>();
        xpTransaction.put("timestamp", FieldValue.serverTimestamp());
        xpTransaction.put("type", "QUEST_UNDO");
        xpTransaction.put("questTitle", questTitle); 
        xpTransaction.put("amount", -amount);
        xpTransaction.put("level", cachedPlayerLevel);
        
        db.runTransaction((Transaction.Function<Void>) transaction -> {
            if (amount > 0) {
                transaction.update(userRef, "xp", FieldValue.increment(-amount));
            }
            
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

    private void addPendingXp(long amount) {
        if (amount == 0) return;
        pendingXp.addAndGet(amount);
        
        if (pendingXp.get() > 50) {
            flushPendingXp();
        }
    }

    public void flushPendingXp() {
        long xpToApply = pendingXp.getAndSet(0);
        if (xpToApply == 0) return;
        
        Log.d(TAG, "Flushing batched XP: " + xpToApply);
        
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
                pendingXp.addAndGet(xpToApply);
                Log.e(TAG, "Failed to flush pending XP, will retry later", e);
            });
    }
    

    private void flushPendingXpIfNeeded() {
        long now = System.currentTimeMillis();
        if (pendingXp.get() > 0 && now - lastXpFlushTime > XP_FLUSH_INTERVAL_MS) {
            flushPendingXp();
        }
    }

    private void checkLevelUp() {
        userRef.get().addOnSuccessListener(doc -> {
            if (!doc.exists()) return;
            
            Long xp = doc.getLong("xp");
            Long level = doc.getLong("level");
            
            if (xp == null || level == null) return;
            
            int currentLevel = level.intValue();
            cachedPlayerLevel = currentLevel;
            
            long calculatedLevel = XpCalculator.calculateLevelFromXp(xp);
            
            if (calculatedLevel > currentLevel) {
                userRef.update("level", calculatedLevel)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "LEVEL UP! " + currentLevel + " → " + calculatedLevel);
                        cachedPlayerLevel = (int)calculatedLevel;

                        awardLevelUpBonuses(currentLevel, (int)calculatedLevel);
                    });
            }
        });
    }
    

    private void awardLevelUpBonuses(int oldLevel, int newLevel) {
        int totalCoins = 0;
        for (int level = oldLevel + 1; level <= newLevel; level++) {
            int coinsForThisLevel = 100 + (level * 50);
            totalCoins += coinsForThisLevel;
            
            if (level == 10) {
                unlockSpecialReward("LEVEL10_BADGE");
            } else if (level == 25) {
                unlockSpecialReward("LEVEL25_GEAR");
            } else if (level == 50) {
                unlockSpecialReward("LEVEL50_LEGENDARY");
            }
        }
        
        if (totalCoins > 0) {
            final int finalTotalCoins = totalCoins;
            userRef.update("coins", FieldValue.increment(finalTotalCoins))
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Added " + finalTotalCoins + " level-up bonus coins");
                });
        }
    }
    

    private void unlockSpecialReward(String rewardId) {
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
                pendingTransactions.add(rewardData);
            });
    }
    

    private void scheduleRetryCheck() {
        retryExecutor.scheduleWithFixedDelay(() -> {
            retryPendingTransactions();
        }, RETRY_DELAY_MS, RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
    }
    

    private void retryPendingTransactions() {
        if (pendingTransactions.isEmpty() || isRetrying || !isNetworkAvailable()) {
            return;
        }
        
        isRetrying = true;
        
        try {
            flushPendingXp();
            
            int processCount = 0;
            while (!pendingTransactions.isEmpty() && processCount < 10) {
                Map<String, Object> transaction = pendingTransactions.poll();
                if (transaction == null) continue;
                
                if (transaction.containsKey("isOffline")) {
                    transaction.remove("isOffline");
                    transaction.put("timestamp", FieldValue.serverTimestamp());
                    transaction.put("delayed", true);
                }
                
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
    

    private Map<String, Object> getActiveEffects() {
        return new HashMap<>();
    }
    

    private boolean isNetworkAvailable() {
        if (context == null) return true;
        
        ConnectivityManager cm = (ConnectivityManager) 
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (cm == null) return true;
        
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    public void shutdown() {
        if (retryExecutor != null && !retryExecutor.isShutdown()) {
            flushPendingXp();
            retryExecutor.shutdown();
        }
    }
} 