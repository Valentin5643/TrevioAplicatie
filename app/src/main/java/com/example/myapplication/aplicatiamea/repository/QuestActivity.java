package com.example.myapplication.aplicatiamea.repository;

import android.os.Bundle;
import android.app.Activity;
import android.widget.Button;
import java.util.List;
import java.util.ArrayList;

import com.example.myapplication.aplicatiamea.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.FirebaseFirestoreException;
import java.util.Calendar;
import java.util.TimeZone;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.util.Log;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import com.google.android.material.button.MaterialButton;
import java.util.HashSet;
import java.util.Set;
import com.example.myapplication.aplicatiamea.util.ThemeHelper;
import com.google.firebase.firestore.DocumentReference;

public class QuestActivity extends Activity {
    private static final String TAG = "QuestScreen";
    private QuestManager questManager;
    private String uid;

    private final Set<String> displayedDailyQuestIds = new HashSet<>();
    private final Set<String> displayedWeeklyQuestIds = new HashSet<>();

    private LinearLayout dailyContainer;
    private LinearLayout weeklyContainer;
    private TextView emptyStateView;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyUserTheme(this);
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false); 
        setContentView(R.layout.activity_challenges);

        setupEdgeToEdgeUI();
        setupBackButton();
        

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            Log.e(TAG, "Not logged in, can't load quests");
            Toast.makeText(this, "Login required", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        uid = currentUser.getUid();

        weeklyContainer = findViewById(R.id.weeklyChallengesContainer);
        dailyContainer = findViewById(R.id.dailyChallengesContainer);
        emptyStateView = findViewById(R.id.emptyStateMessage);
        
        if (emptyStateView == null) {
            Log.w(TAG, "Empty state view not found in layout");
        } else {
            emptyStateView.setVisibility(View.GONE); // Hide initially
        }
        
        try {
            questManager = new QuestManager(uid, this);

            questManager.issueDailyQuests();
            questManager.makeWeeklyQuests();

            setupQuestListeners();
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize quest system: " + e.getMessage());
            Toast.makeText(this, "Quest system error", Toast.LENGTH_SHORT).show();

            if (emptyStateView != null) {
                emptyStateView.setText("Failed to load quests. Try again later.");
                emptyStateView.setVisibility(View.VISIBLE);
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();

        if (questManager != null && uid != null) {
            questManager.cleanupExpiredTimeBoundQuests();

            FirebaseFirestore db = FirebaseFirestore.getInstance();
            DocumentReference userRef = db.collection("users").document(uid);

            questManager.forceQuestRefresh(userRef);

            db.collection("users").document(uid)
              .collection("questInstances")
              .whereEqualTo("status", "COMPLETED")
              .get()
              .addOnSuccessListener(snapshot -> {
                  if (!snapshot.isEmpty()) {
                      Log.d(TAG, "Found " + snapshot.size() + " completed quests, refreshing UI");

                      setupQuestListeners();
                  }
              })
              .addOnFailureListener(e -> Log.e(TAG, "Failed to query completed quests: " + e.getMessage()));

            questManager.issueDailyQuests();
            questManager.makeWeeklyQuests();
        }
    }
    
    private void setupEdgeToEdgeUI() {
        View rootLayout = findViewById(R.id.challengesRoot);
        View contentScrollView = findViewById(R.id.contentScrollView);

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            contentScrollView.setPadding(
                contentScrollView.getPaddingLeft() + insets.left,
                contentScrollView.getPaddingTop() + insets.top,
                contentScrollView.getPaddingRight() + insets.right,
                contentScrollView.getPaddingBottom() + insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }
    
    private void setupBackButton() {
        MaterialButton back = findViewById(R.id.buttonBackChallenges);
        back.setOnClickListener(v -> finish());
    }
    
    private void setupQuestListeners() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        
        Calendar cal = Calendar.getInstance(TimeZone.getDefault());
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long startOfDay = cal.getTimeInMillis();

        cal.set(Calendar.DAY_OF_WEEK, cal.getFirstDayOfWeek());
        long startOfWeek = cal.getTimeInMillis();

        setupWeeklyQuestListener(db, startOfWeek);
        setupDailyQuestListener(db, startOfDay);
    }
    
    private void setupWeeklyQuestListener(FirebaseFirestore db, long startOfWeek) {
        db.collection("users").document(uid)
            .collection("questInstances")
            .whereEqualTo("frequency", "WEEKLY")
            .whereGreaterThanOrEqualTo("startedAt", startOfWeek)
            .addSnapshotListener((QuerySnapshot snap, FirebaseFirestoreException err) -> {
                if (err != null) {
                    handleFirestoreError("weekly quests", err);
                    return;
                }
                
                if (snap == null || snap.isEmpty()) {
                    Log.d(TAG, "No weekly quests found, triggering generation");
                    questManager.makeWeeklyQuests();
                    return;
                }
                
                updateWeeklyQuestList(snap);
            });
    }
    
    private void setupDailyQuestListener(FirebaseFirestore db, long startOfDay) {
        db.collection("users").document(uid)
            .collection("questInstances")
            .whereEqualTo("frequency", "DAILY")
            .whereGreaterThanOrEqualTo("startedAt", startOfDay)
            .addSnapshotListener((QuerySnapshot snap, FirebaseFirestoreException err) -> {
                if (err != null) {
                    handleFirestoreError("daily quests", err);
                    return;
                }
                
                if (snap == null) {
                    Log.e(TAG, "Daily quests snapshot is null");
                    return;
                }
                
                int questCount = snap.size();
                if (questCount > 3) {
                    questManager.cleanupAllExpiredQuests();
                } else if (questCount < 3) {
                    questManager.issueDailyQuests();
                }
                
                updateDailyQuestList(snap);
            });
    }
    
    private void handleFirestoreError(String questType, FirebaseFirestoreException err) {
        Log.e(TAG, "Error loading " + questType + ": " + err.getMessage());
        
        if (QuestManager.handleFirestoreError(err, this, true)) {
            return;
        }
        
        Toast.makeText(this,
            "Failed to load " + questType + ". Check your connection.", 
            Toast.LENGTH_SHORT).show();
    }
    
    private void updateWeeklyQuestList(QuerySnapshot snap) {
        weeklyContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        
        try {
            List<DocumentSnapshot> questDocs = new ArrayList<>(snap.getDocuments());
            
            sortQuestList(questDocs, displayedWeeklyQuestIds);
            
            List<DocumentSnapshot> uniqueQuests = filterUniqueQuests(questDocs);
            
            List<DocumentSnapshot> activeQuests = new ArrayList<>();
            List<DocumentSnapshot> completedQuests = new ArrayList<>();
            sortByCompletion(uniqueQuests, activeQuests, completedQuests);
            
            displayedWeeklyQuestIds.clear();
            
            int displayCount = 0;
            
            for (DocumentSnapshot doc : activeQuests) {
                if (displayCount >= 2) break;
                
                displayedWeeklyQuestIds.add(doc.getId());
                logQuestDisplay(doc);
                
                View questView = inflater.inflate(R.layout.quest_item, weeklyContainer, false);
                setupQuestItemView(doc, questView);
                weeklyContainer.addView(questView);
                
                displayCount++;
            }
            
            for (DocumentSnapshot doc : completedQuests) {
                if (displayCount >= 2) break;
                
                displayedWeeklyQuestIds.add(doc.getId());
                logQuestDisplay(doc);
                
                View questView = inflater.inflate(R.layout.quest_item, weeklyContainer, false);
                setupQuestItemView(doc, questView);
                weeklyContainer.addView(questView);
                
                displayCount++;
            }
            
            if (displayCount == 0) {
                questManager.makeWeeklyQuests();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error displaying weekly quests", e);
            Toast.makeText(this, "Couldn't display weekly quests", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void updateDailyQuestList(QuerySnapshot snap) {
        dailyContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        
        try {
            List<DocumentSnapshot> questDocs = new ArrayList<>(snap.getDocuments());
            
            sortQuestList(questDocs, displayedDailyQuestIds);
            
            List<DocumentSnapshot> activeQuests = new ArrayList<>();
            List<DocumentSnapshot> completedQuests = new ArrayList<>();
            sortByCompletion(questDocs, activeQuests, completedQuests);
            
            displayedDailyQuestIds.clear();
            
            List<DocumentSnapshot> displayQuests = new ArrayList<>(activeQuests);
            
            for (DocumentSnapshot doc : completedQuests) {
                if (displayQuests.size() < 3) {
                    displayQuests.add(doc);
                } else {
                    break;
                }
            }
            
            for (DocumentSnapshot doc : displayQuests) {
                displayedDailyQuestIds.add(doc.getId());
                logQuestDisplay(doc);
                
                View questView = inflater.inflate(R.layout.quest_item, dailyContainer, false);
                setupQuestItemView(doc, questView);
                dailyContainer.addView(questView);
            }
            
            if (displayQuests.size() < 3) {
                questManager.issueDailyQuests();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error displaying daily quests", e);
            Toast.makeText(this, "Couldn't display daily quests", Toast.LENGTH_SHORT).show();
        }
    }


    private void sortQuestList(List<DocumentSnapshot> quests, Set<String> displayedIds) {
        if (!displayedIds.isEmpty()) {
            quests.sort((a, b) -> {
                boolean aDisplayed = displayedIds.contains(a.getId());
                boolean bDisplayed = displayedIds.contains(b.getId());
                if (aDisplayed && !bDisplayed) return -1;
                if (!aDisplayed && bDisplayed) return 1;
                return 0;
            });
        }
    }
    private List<DocumentSnapshot> filterUniqueQuests(List<DocumentSnapshot> quests) {
        List<DocumentSnapshot> uniqueQuests = new ArrayList<>();
        Set<String> seenTitles = new HashSet<>();
        
        for (DocumentSnapshot doc : quests) {
            String title = doc.getString("title");
            if (title != null && seenTitles.add(title)) {
                uniqueQuests.add(doc);
            }
        }
        
        return uniqueQuests;
    }
    private void sortByCompletion(List<DocumentSnapshot> quests, 
                                  List<DocumentSnapshot> activeQuests,
                                  List<DocumentSnapshot> completedQuests) {
        for (DocumentSnapshot doc : quests) {
            Long currNode = doc.getLong("currentNode");
            Long totalNodes = doc.getLong("totalNodes");
            int curr = currNode != null ? currNode.intValue() : 0;
            int total = totalNodes != null ? totalNodes.intValue() : 0;
            boolean rewardClaimed = doc.contains("rewardClaimed") && 
                                    Boolean.TRUE.equals(doc.getBoolean("rewardClaimed"));
            String status = doc.getString("status");
            boolean isCompleted = curr >= total || "COMPLETED".equals(status);
            
            if (isCompleted || rewardClaimed) {
                completedQuests.add(doc);
            } else {
                activeQuests.add(doc);
            }
        }
    }
    
    private void setupQuestItemView(DocumentSnapshot doc, View questItemView) {
        TextView titleText = questItemView.findViewById(R.id.questTitle);
        TextView descText = questItemView.findViewById(R.id.questDescription);
        TextView progressText = questItemView.findViewById(R.id.questProgressText);
        ProgressBar progressBar = questItemView.findViewById(R.id.questProgressBar);
        Button btnClaimReward = questItemView.findViewById(R.id.btnClaimReward);
        
        String title = doc.getString("title");
        String description = doc.getString("description");
        Long currentNode = doc.getLong("currentNode");
        Long totalNodes = doc.getLong("totalNodes");
        Long reward = doc.getLong("rewardPoints");
        Boolean rewardClaimed = doc.getBoolean("rewardClaimed");
        
        titleText.setText(title != null ? title : "Unknown Quest");
        descText.setText(description != null ? description : "");
        
        int curr = currentNode != null ? currentNode.intValue() : 0;
        int total = totalNodes != null ? totalNodes.intValue() : 1;
        int rewardPoints = reward != null ? reward.intValue() : 50;
        boolean isClaimed = rewardClaimed != null && rewardClaimed;
        
        boolean isComplete = curr >= total;
        progressBar.setMax(total);
        progressBar.setProgress(curr);
        
        updateQuestStateUI(progressText, btnClaimReward, isComplete, isClaimed, curr, total);
        
        if (isComplete && !isClaimed) {
            final String questId = doc.getId();
            setupClaimButton(btnClaimReward, progressText, questId, title, rewardPoints);
        }
    }
    
    private void updateQuestStateUI(TextView progressText, Button claimBtn, 
                                   boolean isComplete, boolean isClaimed,
                                   int current, int total) {
        if (isComplete) {
            if (isClaimed) {
                progressText.setText("Completed ✓ (Claimed)");
                claimBtn.setVisibility(View.VISIBLE);
                claimBtn.setEnabled(false);
                claimBtn.setText("Claimed");
                claimBtn.setAlpha(0.6f);
            } else {
                progressText.setText("Completed ✓");
                claimBtn.setVisibility(View.VISIBLE);
                claimBtn.setEnabled(true);
                claimBtn.setText("Claim");
            }
        } else {
            progressText.setText(current + "/" + total);
            claimBtn.setVisibility(View.GONE);
        }
    }
    
    private void setupClaimButton(Button claimBtn, TextView progressText, 
                                 String questId, String questTitle, int rewardAmount) {
        claimBtn.setOnClickListener(v -> {
            claimBtn.setEnabled(false);
            claimBtn.setText("Claiming...");
            // Handle reward claim
            questManager.claimQuestReward(questId, new RewardClaimListener() {
                @Override
                public void onRewardClaimed(boolean success, String message) {
                    runOnUiThread(() -> {
                        if (success) {
                            // Update button fully
                            claimBtn.setText("Claimed");
                            claimBtn.setAlpha(0.6f);
                            progressText.setText("Completed ✓ (Claimed)");
                            // Show toast
                            Toast.makeText(QuestActivity.this,
                                    "Quest '" + questTitle + "' completed! +" + rewardAmount + " coins", 
                                    Toast.LENGTH_SHORT).show();
                            reloadQuestsFromFirestore();
                        } else {
                            claimBtn.setEnabled(true);
                            claimBtn.setText("Claim");
                            Toast.makeText(QuestActivity.this, message, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        });
    }
    
    private void reloadQuestsFromFirestore() {
        setupQuestListeners();
    }
    
    private void logQuestDisplay(DocumentSnapshot doc) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return;
        
        String title = doc.getString("title");
        String id = doc.getId();
        Long currNode = doc.getLong("currentNode");
        Long totalNodes = doc.getLong("totalNodes");
        String status = doc.getString("status");
        Boolean claimed = doc.getBoolean("rewardClaimed");
        
        Log.d(TAG, "Quest: " + title + " [" + id + "], " + 
               currNode + "/" + totalNodes + ", " + 
               status + (claimed != null && claimed ? " (claimed)" : ""));
    }
} 