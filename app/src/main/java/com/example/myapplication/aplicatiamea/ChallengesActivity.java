package com.example.myapplication.aplicatiamea;

import android.os.Bundle;
import android.app.Activity;
import android.widget.Button;
import java.util.List;
import java.util.ArrayList;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.example.myapplication.aplicatiamea.repository.QuestManager;
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

public class ChallengesActivity extends Activity {
    private static final String TAG = "ChallengesActivity";
    private QuestManager questManager;
    private String uid;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate started");
        
        try {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false); // Enable edge-to-edge
            setContentView(R.layout.activity_challenges);

            View rootLayout = findViewById(R.id.rootLayoutChallenges);
            View contentScrollView = findViewById(R.id.contentScrollViewChallenges); // Target content area

            ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                // Apply padding to the content area (ScrollView)
                contentScrollView.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return WindowInsetsCompat.CONSUMED;
            });

            MaterialButton back = findViewById(R.id.buttonBackChallenges);
            back.setOnClickListener(v -> finish());

            // Check if user is logged in
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if (currentUser == null) {
                Log.e(TAG, "Firebase user is null, returning to main screen");
                Toast.makeText(this, "You need to be logged in to view quests", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            uid = currentUser.getUid();
            Log.d(TAG, "User ID: " + uid);
            
            try {
                // Ensure quests are (re)issued per period
                questManager = new QuestManager(uid, this);
                
                // First, make sure we have 3 daily quests by issuing more if needed
                questManager.issueDailyQuests();
                // And ensure we have 2 weekly quests
                questManager.issueWeeklyQuests();

                // Contest containers
                LinearLayout weeklyContainer = findViewById(R.id.weeklyChallengesContainer);
                LinearLayout dailyContainer = findViewById(R.id.dailyChallengesContainer);
                LayoutInflater inflater = LayoutInflater.from(this);

                // Calculate period boundaries
                Calendar cal = Calendar.getInstance(TimeZone.getDefault());
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                long startOfDay = cal.getTimeInMillis();

                // Get the first day of the week based on the locale
                cal.set(Calendar.DAY_OF_WEEK, cal.getFirstDayOfWeek());
                long startOfWeek = cal.getTimeInMillis();

                Log.d(TAG, "Start of day: " + startOfDay + ", Start of week: " + startOfWeek);

                // Keep track of the quests we're displaying for consistency
                final Set<String> currentlyDisplayedDailyQuestIds = new HashSet<>();
                final Set<String> currentlyDisplayedWeeklyQuestIds = new HashSet<>();

                // Weekly quests listener (should return at most one per period)
                FirebaseFirestore.getInstance()
                    .collection("users").document(uid)
                    .collection("questInstances")
                    .whereEqualTo("frequency", "WEEKLY")
                    .whereGreaterThanOrEqualTo("startedAt", startOfWeek)
                    .addSnapshotListener((QuerySnapshot snap, FirebaseFirestoreException err) -> {
                        if (err != null) {
                            Log.e(TAG, "Error in weekly quests listener", err);
                            QuestManager.handleFirestoreError(err, this, true);
                            return;
                        }
                        if (snap == null) {
                            Log.e(TAG, "Weekly quests snapshot is null");
                            return;
                        }
                        Log.d(TAG, "Weekly quests count: " + snap.size());
                        weeklyContainer.removeAllViews();
                        try {
                            List<DocumentSnapshot> sorted = new ArrayList<>(snap.getDocuments());
                            // Sort by startedAt descending
                            sorted.sort((a, b) -> {
                                Long sa = a.getLong("startedAt");
                                Long sb = b.getLong("startedAt");
                                if (sa == null) sa = 0L;
                                if (sb == null) sb = 0L;
                                return Long.compare(sb, sa);
                            });
                            
                            // Prioritize quests we were already showing
                            if (!currentlyDisplayedWeeklyQuestIds.isEmpty()) {
                                sorted.sort((a, b) -> {
                                    boolean aDisplayed = currentlyDisplayedWeeklyQuestIds.contains(a.getId());
                                    boolean bDisplayed = currentlyDisplayedWeeklyQuestIds.contains(b.getId());
                                    if (aDisplayed && !bDisplayed) return -1;
                                    if (!aDisplayed && bDisplayed) return 1;
                                    return 0;
                                });
                            }
                            
                            // Filter out duplicate quests by title
                            List<DocumentSnapshot> uniqueDocs = new ArrayList<>();
                            Set<String> seenTitles = new HashSet<>();
                            for (DocumentSnapshot d : sorted) {
                                String t = d.getString("title");
                                if (t != null && seenTitles.add(t)) {
                                    uniqueDocs.add(d);
                                }
                            }
                            // Prefer not-completed, not-reward-claimed
                            List<DocumentSnapshot> active = new ArrayList<>();
                            List<DocumentSnapshot> completed = new ArrayList<>();
                            for (DocumentSnapshot doc : uniqueDocs) {
                                boolean isCompleted = false;
                                Long currNode = doc.getLong("currentNode");
                                Long totalNodes = doc.getLong("totalNodes");
                                int curr = currNode != null ? currNode.intValue() : 0;
                                int total = totalNodes != null ? totalNodes.intValue() : 0;
                                boolean rewardClaimed = doc.contains("rewardClaimed") && Boolean.TRUE.equals(doc.getBoolean("rewardClaimed"));
                                String status = doc.getString("status");
                                isCompleted = curr >= total || "COMPLETED".equals(status);
                                if (!isCompleted && !rewardClaimed) {
                                    active.add(doc);
                                } else {
                                    completed.add(doc);
                                }
                            }
                            
                            // Reset the currently displayed IDs
                            currentlyDisplayedWeeklyQuestIds.clear();
                            
                            int shown = 0;
                            for (DocumentSnapshot doc : active) {
                                if (shown++ >= 2) break;
                                currentlyDisplayedWeeklyQuestIds.add(doc.getId());
                                logQuestDisplay(doc);
                                View item = inflater.inflate(R.layout.quest_item, weeklyContainer, false);
                                setupQuestItemView(doc, item);
                                weeklyContainer.addView(item);
                            }
                            for (DocumentSnapshot doc : completed) {
                                if (shown++ >= 2) break;
                                currentlyDisplayedWeeklyQuestIds.add(doc.getId());
                                logQuestDisplay(doc);
                                View item = inflater.inflate(R.layout.quest_item, weeklyContainer, false);
                                setupQuestItemView(doc, item);
                                weeklyContainer.addView(item);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error setting up weekly quest items", e);
                            Toast.makeText(this, "Error loading weekly quests", Toast.LENGTH_SHORT).show();
                        }
                    });

                // Daily quests listener (up to 3 per day)
                FirebaseFirestore.getInstance()
                    .collection("users").document(uid)
                    .collection("questInstances")
                    .whereEqualTo("frequency", "DAILY")
                    .whereGreaterThanOrEqualTo("startedAt", startOfDay)
                    .addSnapshotListener((QuerySnapshot snap, FirebaseFirestoreException err) -> {
                        if (err != null) {
                            Log.e(TAG, "Error in daily quests listener", err);
                            QuestManager.handleFirestoreError(err, this, true);
                            return;
                        }
                        if (snap == null) {
                            Log.e(TAG, "Daily quests snapshot is null");
                            return;
                        }
                        
                        // Get all daily quests for today
                        List<DocumentSnapshot> todayQuests = new ArrayList<>();
                        for (DocumentSnapshot doc : snap.getDocuments()) {
                            Long startedAt = doc.getLong("startedAt");
                            if (startedAt != null && startedAt >= startOfDay) {
                                todayQuests.add(doc);
                            }
                        }
                        
                        Log.d(TAG, "Daily quests count: " + snap.size() + ", Today's quests: " + todayQuests.size());
                        
                        if (todayQuests.size() > 3) {
                            Log.d(TAG, "Found more than 3 daily quests (" + todayQuests.size() + "), will only display 3");
                            // Let QuestManager handle the cleanup in the background
                            questManager.cleanupAllExpiredQuests();
                        } else if (todayQuests.size() < 3) {
                            // If we have fewer than 3 quests, generate more via QuestManager
                            Log.d(TAG, "Found fewer than 3 daily quests (" + todayQuests.size() + "), issuing more");
                            questManager.issueDailyQuests();
                            // Continue with what we have for now
                        }
                        
                        try {
                            // Sort to prioritize quests we were already showing
                            if (!currentlyDisplayedDailyQuestIds.isEmpty()) {
                                todayQuests.sort((a, b) -> {
                                    boolean aDisplayed = currentlyDisplayedDailyQuestIds.contains(a.getId());
                                    boolean bDisplayed = currentlyDisplayedDailyQuestIds.contains(b.getId());
                                    if (aDisplayed && !bDisplayed) return -1;
                                    if (!aDisplayed && bDisplayed) return 1;
                                    return 0;
                                });
                            }
                            
                            // Sort by completion status and then by startedAt
                            todayQuests.sort((a, b) -> {
                                // First prioritize already displayed quests
                                boolean aDisplayed = currentlyDisplayedDailyQuestIds.contains(a.getId());
                                boolean bDisplayed = currentlyDisplayedDailyQuestIds.contains(b.getId());
                                if (aDisplayed && !bDisplayed) return -1;
                                if (!aDisplayed && bDisplayed) return 1;
                                
                                // For newly displayed quests, prioritize non-completed over completed
                                Boolean aCompleted = a.getBoolean("completed");
                                Boolean bCompleted = b.getBoolean("completed");
                                boolean isACompleted = aCompleted != null && aCompleted;
                                boolean isBCompleted = bCompleted != null && bCompleted;
                                
                                if (!isACompleted && isBCompleted) return -1;
                                if (isACompleted && !isBCompleted) return 1;
                                
                                // Finally sort by startedAt timestamp (newer first)
                                Long sa = a.getLong("startedAt");
                                Long sb = b.getLong("startedAt");
                                if (sa == null) sa = 0L;
                                if (sb == null) sb = 0L;
                                return Long.compare(sb, sa);
                            });
                            
                            // Active and completed quests
                            List<DocumentSnapshot> activeQuests = new ArrayList<>();
                            List<DocumentSnapshot> completedQuests = new ArrayList<>();
                            
                            for (DocumentSnapshot doc : todayQuests) {
                                Long currNode = doc.getLong("currentNode");
                                Long totalNodes = doc.getLong("totalNodes");
                                int curr = currNode != null ? currNode.intValue() : 0;
                                int total = totalNodes != null ? totalNodes.intValue() : 0;
                                String status = doc.getString("status");
                                boolean isCompleted = curr >= total || "COMPLETED".equals(status);
                                
                                if (isCompleted) {
                                    completedQuests.add(doc);
                                } else {
                                    activeQuests.add(doc);
                                }
                            }
                            
                            // Clear the container and the tracking set
                            dailyContainer.removeAllViews();
                            currentlyDisplayedDailyQuestIds.clear();
                            
                            // Plan which quests to display (prioritize active quests)
                            List<DocumentSnapshot> displayQuests = new ArrayList<>();
                            
                            // First add active quests
                            displayQuests.addAll(activeQuests);
                            
                            // Then add completed quests if needed
                            for (DocumentSnapshot doc : completedQuests) {
                                if (displayQuests.size() < 3) {
                                    displayQuests.add(doc);
                                } else {
                                    break;
                                }
                            }
                            
                            // Ensure consistent display - always use the same quests until they're gone
                            if (displayQuests.size() > 3) {
                                displayQuests = displayQuests.subList(0, 3);
                            }
                            
                            // Display quests
                            for (DocumentSnapshot doc : displayQuests) {
                                currentlyDisplayedDailyQuestIds.add(doc.getId());
                                logQuestDisplay(doc);
                                View item = inflater.inflate(R.layout.quest_item, dailyContainer, false);
                                setupQuestItemView(doc, item);
                                dailyContainer.addView(item);
                            }
                            
                            // If we're not showing 3 quests yet, request more
                            if (displayQuests.size() < 3) {
                                Log.d(TAG, "Displaying only " + displayQuests.size() + " quests (need 3), requesting more");
                                questManager.issueDailyQuests();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error setting up daily quest items", e);
                            Toast.makeText(this, "Error loading daily quests", Toast.LENGTH_SHORT).show();
                        }
                    });
            } catch (Exception e) {
                Log.e(TAG, "Error initializing questManager or listeners", e);
                Toast.makeText(this, "Could not load quests", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Fatal error in onCreate", e);
            Toast.makeText(this, "An error occurred loading the quest screen", Toast.LENGTH_SHORT).show();
            finish();
        }
        
        Log.d(TAG, "onCreate completed");
    }
    
    private void setupQuestItemView(DocumentSnapshot doc, View questItemView) {
        TextView titleText = questItemView.findViewById(R.id.questTitle);
        TextView descText = questItemView.findViewById(R.id.questDescription);
        TextView progressText = questItemView.findViewById(R.id.questProgressText);
        ProgressBar progressBar = questItemView.findViewById(R.id.questProgressBar);
        Button btnClaimReward = questItemView.findViewById(R.id.btnClaimReward);
        
        // Get quest data
        String title = doc.getString("title");
        String description = doc.getString("description");
        Long currentNode = doc.getLong("currentNode");
        Long totalNodes = doc.getLong("totalNodes");
        Long reward = doc.getLong("rewardPoints");
        Boolean completed = doc.getBoolean("completed");
        Boolean rewardClaimed = doc.getBoolean("rewardClaimed");
        
        // Set basic info
        titleText.setText(title);
        descText.setText(description);
        
        // Handle null values
        int curr = (currentNode != null) ? currentNode.intValue() : 0;
        int total = (totalNodes != null) ? totalNodes.intValue() : 1;
        int rewardPoints = (reward != null) ? reward.intValue() : 50;
        boolean isClaimed = (rewardClaimed != null) && rewardClaimed;
        
        // Update progress display
        progressBar.setMax(total);
        progressBar.setProgress(curr);
        
        // Check if quest progress is complete (based on current/total nodes)
        boolean isProgressComplete = curr >= total;
        
        // Update the UI based on quest status
        if (isProgressComplete) {
            // Quest progress is complete
            progressBar.setProgress(total); // Ensure the progress bar shows complete
            
            if (isClaimed) {
                // Quest is completed and reward is claimed
                progressText.setText("Completed ✓ (Claimed)");
                btnClaimReward.setVisibility(View.VISIBLE);
                btnClaimReward.setEnabled(false);
                btnClaimReward.setText("Claimed");
                btnClaimReward.setAlpha(0.6f);
            } else {
                // Quest is completed but reward not claimed yet
                progressText.setText("Completed ✓");
                btnClaimReward.setVisibility(View.VISIBLE);
                btnClaimReward.setEnabled(true);
                btnClaimReward.setText("Claim");
                
                // Set up the claim reward button with immediate UI update
                final String questId = doc.getId();
                btnClaimReward.setOnClickListener(v -> {
                    // Immediately disable and update the button
                    btnClaimReward.setEnabled(false);
                    btnClaimReward.setText("Claimed");
                    btnClaimReward.setAlpha(0.6f);
                    progressText.setText("Completed ✓ (Claimed)");
                    // Trigger reward claim
                    claimQuestReward(questId, title, rewardPoints);
                });
            }
        } else {
            // Quest is not completed yet
            progressText.setText(curr + "/" + total);
            btnClaimReward.setVisibility(View.GONE);
        }
    }
    
    private void claimQuestReward(String questId, String questTitle, int rewardAmount) {
        try {
            questManager.claimQuestReward(questId, new QuestManager.RewardClaimListener() {
                @Override
                public void onRewardClaimed(boolean success, String message) {
                    runOnUiThread(() -> {
                        if (success) {
                            Toast.makeText(ChallengesActivity.this, 
                                    "Quest '" + questTitle + "' completed!", 
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(ChallengesActivity.this, 
                                    message, 
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error claiming quest reward", e);
            Toast.makeText(this, "Error claiming quest reward", Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy called");
    }

    private void logQuestDisplay(DocumentSnapshot doc) {
        String title = doc.getString("title");
        String docId = doc.getId();
        Long currNode = doc.getLong("currentNode");
        Long totalNodes = doc.getLong("totalNodes");
        String status = doc.getString("status");
        Boolean rewardClaimed = doc.getBoolean("rewardClaimed");
        Log.d(TAG, "[UI] Showing quest: title=" + title + ", docId=" + docId + ", currentNode=" + currNode + ", totalNodes=" + totalNodes + ", status=" + status + ", rewardClaimed=" + rewardClaimed);
    }
} 