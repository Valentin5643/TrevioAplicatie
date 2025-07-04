package com.example.myapplication.aplicatiamea;

import android.os.Bundle;
import android.app.Activity;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.android.gms.tasks.OnSuccessListener;
import com.airbnb.lottie.LottieAnimationView;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import android.widget.Toast;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import android.view.LayoutInflater;
import android.content.Context;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import com.example.myapplication.aplicatiamea.dialogs.LevelUpDialog;
import com.example.myapplication.aplicatiamea.util.ThemeHelper;

public class LevelsActivity extends Activity {
    private static final String TAG = "LvlsAct";

    private ProgressBar progressBar;
    private TextView tvLevelInfo;
    private MaterialButton buttonBackLevels;
    private LottieAnimationView confettiView;
    private RecyclerView recyclerViewBadges;
    private BadgeAdapter badgeAdapter;
    private List<Badge> badges;
    private boolean isDestroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyUserTheme(this);
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_levels);

        View rootLayout = findViewById(R.id.rootLayoutLevels);
        View contentArea = findViewById(R.id.contentAreaLevels);
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            contentArea.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        progressBar = findViewById(R.id.progressBarLevel);
        tvLevelInfo = findViewById(R.id.tvLevelInfo);
        confettiView = findViewById(R.id.confettiView);
        recyclerViewBadges = findViewById(R.id.recyclerViewBadges);
        buttonBackLevels = findViewById(R.id.buttonBackLevels);
        buttonBackLevels.setOnClickListener(v -> finish());

        initializeBadges();

        badgeAdapter = new BadgeAdapter(this, badges);
        recyclerViewBadges.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewBadges.setAdapter(badgeAdapter);

        fetchUserDataAndUpdateUI();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDestroyed = true;
        if (confettiView != null) {
            confettiView.cancelAnimation();
            confettiView = null;
        }
        if (badgeAdapter != null) {
            badgeAdapter = null;
        }
        if (recyclerViewBadges != null) {
            recyclerViewBadges.setAdapter(null);
            recyclerViewBadges = null;
        }
    }

    private void initializeBadges() {
        badges = new ArrayList<>();
        badges.add(new Badge("quest_10", "Quest Enthusiast", "Complete 10 quests", R.drawable.quests_completed_10, 10, Badge.CRITERIA_QUESTS, true));
        badges.add(new Badge("quest_20", "Quest Master", "Complete 20 quests", R.drawable.quests_completed_20, 20, Badge.CRITERIA_QUESTS, true));
        badges.add(new Badge("quest_50", "Quest Champion", "Complete 50 quests", R.drawable.quests_completed_50, 50, Badge.CRITERIA_QUESTS, true));
        badges.add(new Badge("item_10", "Collector", "Own 10 items", R.drawable.items_badge_10, 10, Badge.CRITERIA_ITEMS, true));
        badges.add(new Badge("item_20", "Treasure Hunter", "Own 20 items", R.drawable.items_badge_20, 20, Badge.CRITERIA_ITEMS, true));
        badges.add(new Badge("item_50", "Item Hoarder", "Own 50 items", R.drawable.items_badge_50, 50, Badge.CRITERIA_ITEMS, true));
        badges.add(new Badge("points_1000", "Point Gatherer", "Earn 1000 points", R.drawable.points_1000, 1000, Badge.CRITERIA_POINTS, true));
        badges.add(new Badge("points_2500", "Point Collector", "Earn 2500 points", R.drawable.points_2500, 2500, Badge.CRITERIA_POINTS, true));
        badges.add(new Badge("points_5000", "Point Master", "Earn 5000 points", R.drawable.points_5000, 5000, Badge.CRITERIA_POINTS, true));
        badges.add(new Badge("points_10000", "Point Champion", "Earn 10000 points", R.drawable.points_10000, 10000, Badge.CRITERIA_POINTS, true));
    }

    private void fetchUserDataAndUpdateUI() {
        if (isDestroyed) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .get().addOnSuccessListener(new OnSuccessListener<DocumentSnapshot>() {
                @Override
                public void onSuccess(DocumentSnapshot doc) {
                    if (isDestroyed) return;
                    if (doc.exists()) {
                        updateLevelInfo(doc);
                        updateBadgesInfo(doc);
                    }
                }
            });
        if (confettiView != null) {
            confettiView.setVisibility(View.GONE);
        }
    }

    private void updateLevelInfo(DocumentSnapshot doc) {
        if (isDestroyed || progressBar == null || tvLevelInfo == null) return;
        Long xpVal = doc.getLong("xp");
        long totalXp = xpVal != null ? xpVal : 0;
        Long currentStoredLevel = doc.getLong("level");
        long storedLevel = currentStoredLevel != null ? currentStoredLevel : 0;
        long level = XpCalculator.calculateLevelFromXp(totalXp);
        boolean hasLeveledUp = level > storedLevel;
        if (currentStoredLevel == null || currentStoredLevel != level) {
            FirebaseFirestore.getInstance()
                .collection("users").document(FirebaseAuth.getInstance().getCurrentUser().getUid())
                .update("level", level);
            if (hasLeveledUp && storedLevel > 0) {
                showLevelUpDialog((int)level);
            }
        }
        if (level >= XpCalculator.MAX_LEVEL) {
            tvLevelInfo.setText("Level " + XpCalculator.MAX_LEVEL + " (Max Level)");
            progressBar.setMax(100);
            progressBar.setProgress(100);
            return;
        }
        long prevThreshold = XpCalculator.getXpRequiredForLevel(level);
        long nextThreshold = XpCalculator.getXpRequiredForLevel(level + 1);
        long xpInCurrentLevel = totalXp - prevThreshold;
        long xpNeededForNextLevel = nextThreshold - prevThreshold;
        if (xpInCurrentLevel < 0) xpInCurrentLevel = 0;
        if (xpNeededForNextLevel <= 0) xpNeededForNextLevel = 1;
        tvLevelInfo.setText("Level " + level + ": " + xpInCurrentLevel + " / " + xpNeededForNextLevel + " XP");
        int maxProgress = (int) Math.min(Integer.MAX_VALUE, xpNeededForNextLevel);
        int currentProgress = (int) Math.min(Integer.MAX_VALUE, xpInCurrentLevel);
        progressBar.setMax(maxProgress);
        progressBar.setProgress(currentProgress);
    }

    private void showLevelUpDialog(int newLevel) {
        if (isDestroyed) return;
        LevelUpDialog dialog = new LevelUpDialog(this, newLevel);
        dialog.setOnRewardClaimedListener(goldAmount -> {
            Toast.makeText(LevelsActivity.this, "Congratulations! You've leveled up to level " + newLevel, Toast.LENGTH_LONG).show();
            if (confettiView != null) {
                confettiView.setVisibility(View.VISIBLE);
                confettiView.playAnimation();
            }
            fetchUserDataAndUpdateUI();
        });
        dialog.show();
    }

    private void updateBadgesInfo(DocumentSnapshot doc) {
        if (isDestroyed || badgeAdapter == null) return;
        Long pointsValue = doc.getLong("points");
        long points = pointsValue != null ? pointsValue : 0;
        int completedQuestsCount = countCompletedQuests(doc);
        int ownedItemsCount = countOwnedItems(doc);
        List<String> newlyUnlockedBadges = new ArrayList<>();
        Map<String, Boolean> unlockedBadges = new HashMap<>();
        if (doc.contains("unlockedBadges")) {
            unlockedBadges = (Map<String, Boolean>) doc.get("unlockedBadges");
        }
        SharedPreferences prefs = getSharedPreferences("celebrated_badges", MODE_PRIVATE);
        java.util.Set<String> celebratedBadges = prefs.getStringSet("celebrated_badges_set", new java.util.HashSet<>());
        java.util.Set<String> badgesToCelebrate = new java.util.HashSet<>();
        for (Badge badge : badges) {
            boolean wasUnlocked = badge.isUnlocked();
            boolean shouldBeUnlocked = false;
            switch (badge.getCriteriaType()) {
                case Badge.CRITERIA_QUESTS:
                    shouldBeUnlocked = completedQuestsCount >= badge.getQuestsRequired();
                    break;
                case Badge.CRITERIA_ITEMS:
                    shouldBeUnlocked = ownedItemsCount >= badge.getItemsRequired();
                    break;
                case Badge.CRITERIA_POINTS:
                default:
                    shouldBeUnlocked = points >= badge.getPointsRequired();
                    break;
            }
            badge.setUnlocked(shouldBeUnlocked);
            if (shouldBeUnlocked && !wasUnlocked) {
                newlyUnlockedBadges.add(badge.getId());
                if (unlockedBadges == null || !unlockedBadges.containsKey(badge.getId())) {
                    FirebaseFirestore.getInstance()
                        .collection("users").document(FirebaseAuth.getInstance().getCurrentUser().getUid())
                        .update("unlockedBadges." + badge.getId(), true)
                        .addOnSuccessListener(aVoid -> {
                            if (isDestroyed) return;
                            Log.d(TAG, "Badge " + badge.getId() + " unlocked and saved to database");
                            awardXpForBadge(badge);
                        });
                }
            }
            if (shouldBeUnlocked && !celebratedBadges.contains(badge.getId())) {
                badgesToCelebrate.add(badge.getId());
            }
        }
        badgeAdapter.updateBadges(badges);
        if (!badgesToCelebrate.isEmpty() && !isDestroyed) {
            if (badgesToCelebrate.size() == 1) {
                String badgeName = getBadgeNameById(badgesToCelebrate.iterator().next());
                Toast.makeText(this, "Congratulations! You've unlocked the " + badgeName + " badge!", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Congratulations! You've unlocked " + badgesToCelebrate.size() + " new badges!", Toast.LENGTH_LONG).show();
            }
            java.util.Set<String> updatedCelebrated = new java.util.HashSet<>(celebratedBadges);
            updatedCelebrated.addAll(badgesToCelebrate);
            prefs.edit().putStringSet("celebrated_badges_set", updatedCelebrated).apply();
        }
    }

    private String getBadgeNameById(String badgeId) {
        for (Badge badge : badges) {
            if (badge.getId().equals(badgeId)) {
                return badge.getName();
            }
        }
        return "Unknown";
    }

    private int countCompletedQuests(DocumentSnapshot userDoc) {
        int count = 0;
        if (userDoc.contains("completedQuestsCount")) {
            Long countVal = userDoc.getLong("completedQuestsCount");
            if (countVal != null) {
                return countVal.intValue();
            }
        }
        FirebaseFirestore.getInstance()
            .collection("users").document(userDoc.getId())
            .collection("questInstances")
            .whereNotEqualTo("completedAt", null)
            .get()
            .addOnSuccessListener(questSnapshot -> {
                if (isDestroyed) return;
                int completedCount = questSnapshot.size();
                FirebaseFirestore.getInstance()
                    .collection("users").document(userDoc.getId())
                    .update("completedQuestsCount", completedCount)
                    .addOnSuccessListener(aVoid -> {
                        if (isDestroyed) return;
                        Log.d(TAG, "Updated completedQuestsCount to " + completedCount);
                        if (completedCount > count) {
                            fetchUserDataAndUpdateUI();
                        }
                    });
            });
        return count;
    }

    private int countOwnedItems(DocumentSnapshot userDoc) {
        if (userDoc.contains("uniqueItemsOwned")) {
            Long countVal = userDoc.getLong("uniqueItemsOwned");
            if (countVal != null) {
                return countVal.intValue();
            }
        }
        int count = 0;
        if (userDoc.contains("inventory")) {
            Map<String, Object> inventory = (Map<String, Object>) userDoc.get("inventory");
            if (inventory != null) {
                for (Map.Entry<String, Object> entry : inventory.entrySet()) {
                    if (entry.getValue() instanceof Number) {
                        long itemCount = ((Number) entry.getValue()).longValue();
                        if (itemCount > 0) {
                            count++;
                        }
                    }
                }
                final int finalCount = count;
                FirebaseFirestore.getInstance()
                    .collection("users").document(userDoc.getId())
                    .update("uniqueItemsOwned", count)
                    .addOnSuccessListener(aVoid -> {
                        if (isDestroyed) return;
                        Log.d(TAG, "Updated uniqueItemsOwned to " + finalCount);
                    });
            }
        }
        return count;
    }

    private void awardXpForBadge(Badge badge) {
        if (isDestroyed) return;
        long xpAmount;
        switch (badge.getCriteriaType()) {
            case Badge.CRITERIA_QUESTS:
                xpAmount = 200;
                break;
            case Badge.CRITERIA_ITEMS:
                xpAmount = 150;
                break;
            case Badge.CRITERIA_POINTS:
            default:
                xpAmount = 100;
                break;
        }
        RewardManager rewardManager = new RewardManager(FirebaseAuth.getInstance().getCurrentUser().getUid());
        rewardManager.awardXpForBadge(badge.getId(), xpAmount);
        Log.d(TAG, "Awarded " + xpAmount + " XP for unlocking badge: " + badge.getName());
        if (!isDestroyed) {
            Toast.makeText(this, "+" + xpAmount + " XP for unlocking " + badge.getName() + "!", Toast.LENGTH_SHORT).show();
        }
    }

    // Moved BadgeAdapter from standalone file into this activity
    public static class BadgeAdapter extends RecyclerView.Adapter<BadgeAdapter.BadgeViewHolder> {
        private List<Badge> badges;
        private final Context context;
        public BadgeAdapter(Context context, List<Badge> badges) {
            this.context = context;
            this.badges = badges;
        }
        @NonNull
        @Override
        public BadgeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_badge, parent, false);
            return new BadgeViewHolder(view);
        }
        @Override
        public void onBindViewHolder(@NonNull BadgeViewHolder holder, int position) {
            Badge badge = badges.get(position);
            if (badge.isUnlocked()) {
                holder.badgeImage.setImageResource(badge.getImageResId());
                holder.badgeImage.setAlpha(1.0f);
                holder.lockOverlay.setVisibility(View.GONE);
            } else {
                holder.badgeImage.setImageResource(R.drawable.mystery_badge);
                holder.lockOverlay.setVisibility(View.GONE);
            }
            holder.badgeName.setText(badge.getName());
            holder.badgeDescription.setText(badge.getDescription());
            if (badge.isUnlocked()) {
                holder.pointsRequired.setVisibility(View.GONE);
            } else {
                holder.pointsRequired.setVisibility(View.VISIBLE);
                String requirementText;
                switch(badge.getCriteriaType()) {
                    case Badge.CRITERIA_QUESTS:
                        requirementText = badge.getRequirementValue() + " quests needed";
                        break;
                    case Badge.CRITERIA_ITEMS:
                        requirementText = badge.getRequirementValue() + " items needed";
                        break;
                    case Badge.CRITERIA_POINTS:
                    default:
                        requirementText = badge.getRequirementValue() + " points required";
                        break;
                }
                holder.pointsRequired.setText(requirementText);
            }
        }
        @Override
        public int getItemCount() {
            return badges != null ? badges.size() : 0;
        }
        public void updateBadges(List<Badge> badges) {
            this.badges = badges;
            notifyDataSetChanged();
        }
        static class BadgeViewHolder extends RecyclerView.ViewHolder {
            ImageView badgeImage;
            ImageView lockOverlay;
            TextView badgeName;
            TextView badgeDescription;
            TextView pointsRequired;
            public BadgeViewHolder(@NonNull View itemView) {
                super(itemView);
                badgeImage = itemView.findViewById(R.id.ivBadge);
                lockOverlay = itemView.findViewById(R.id.ivLockOverlay);
                badgeName = itemView.findViewById(R.id.tvBadgeName);
                badgeDescription = itemView.findViewById(R.id.tvBadgeDescription);
                pointsRequired = itemView.findViewById(R.id.tvPointsRequired);
            }
        }
    }

    public static class Badge {
        public static final int CRITERIA_POINTS = 0;
        public static final int CRITERIA_QUESTS = 1;
        public static final int CRITERIA_ITEMS = 2;
        private final String id;
        private final String name;
        private final String description;
        private final int imageResId;
        private final long requirementValue;
        private boolean unlocked;
        private final int criteriaType;
        private final boolean mysterious;
        public Badge(String id, String name, String description, int imageResId,
                     long requirementValue, int criteriaType, boolean mysterious) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.imageResId = imageResId;
            this.requirementValue = requirementValue;
            this.criteriaType = criteriaType;
            this.mysterious = mysterious;
            this.unlocked = false;
        }
        public String getId() { return id; }
        public String getName() {
            return (mysterious && !unlocked) ? "???" : name;
        }
        public String getDescription() {
            if (mysterious && !unlocked) {
                if (criteriaType == CRITERIA_QUESTS) {
                    return "Complete quests to reveal this badge (" + requirementValue + " needed)";
                } else if (criteriaType == CRITERIA_ITEMS) {
                    return "Collect items to reveal this badge (" + requirementValue + " needed)";
                } else {
                    return "Earn more points to unlock this mysterious badge";
                }
            }
            return description;
        }
        public int getImageResId() { return imageResId; }
        public long getRequirementValue() { return requirementValue; }
        public int getCriteriaType() { return criteriaType; }
        public boolean isUnlocked() { return unlocked; }
        public void setUnlocked(boolean unlocked) { this.unlocked = unlocked; }
        public long getQuestsRequired() { return requirementValue; }
        public long getItemsRequired() { return requirementValue; }
        public long getPointsRequired() { return requirementValue; }
    }
} 