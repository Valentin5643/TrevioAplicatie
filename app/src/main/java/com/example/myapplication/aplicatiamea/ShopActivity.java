package com.example.myapplication.aplicatiamea;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.app.Activity;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.DocumentSnapshot;
import java.util.ArrayList;
import java.util.List;
import android.widget.ImageView;
import com.example.myapplication.aplicatiamea.util.ThemeHelper;

import com.google.firebase.firestore.DocumentReference;
import java.util.Map;
import java.util.HashMap;
import android.view.View;
import java.lang.reflect.Field;
import java.util.Random;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.widget.Toast;
import com.google.firebase.auth.FirebaseUser;
import androidx.annotation.NonNull;
import android.graphics.Rect;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.google.android.gms.security.ProviderInstaller;
import android.content.Context;
import android.util.Log;
import java.util.Collections;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;

public class ShopActivity extends Activity {
    private static final String TAG = "ShopActivity";
    

    private Button backBtn;
    private TextView coinsTv;
    private RecyclerView itemsRv;
    private List<ShopItem> items = new ArrayList<>();
    private Map<String, ShopItem> itemCatalog = new HashMap<>();
    private ShopAdapter adapter;
    

    private ImageView avatarBase;
    private ImageView avatarArmor;
    private ImageView avatarWeapon;
    private ImageView avatarHelmet;
    private ImageView avatarGreaves; 
    private ImageView avatarBoots;

    private ImageView slotArmor;
    private ImageView slotWeapon;
    private ImageView slotHelmet;
    private ImageView slotGreaves;
    private ImageView slotBoots;

    private FirebaseFirestore db;
    private FirebaseAuth auth;
    private DocumentReference userRef;

    private String lastShopDate;
    private boolean shopDateLoaded = false;
    private boolean pendingResume = false;
    private List<ShopItem> currentShopList = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {

        ThemeHelper.applyUserTheme(this);
        
        super.onCreate(savedInstanceState);
        

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_shop);


        View rootLayout = findViewById(R.id.shopRoot);


        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            rootLayout.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        

        try {
            ProviderInstaller.installIfNeeded(this);
        } catch (GooglePlayServicesRepairableException e) {
            GoogleApiAvailability.getInstance()
                    .showErrorNotification(this, e.getConnectionStatusCode());
        } catch (GooglePlayServicesNotAvailableException e) {
            Toast.makeText(this, "Google Play Services not available", Toast.LENGTH_SHORT).show();
        }
        

        findViews();
        

        backBtn.setOnClickListener(v -> finish());


        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();
        FirebaseUser user = auth.getCurrentUser();

        if (user == null) {
            Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }


        initializeItems();


        userRef = db.collection("users").document(user.getUid());
        

        adapter = new ShopAdapter(this, new ArrayList<>(), userRef);
        itemsRv.setLayoutManager(new LinearLayoutManager(this));
        itemsRv.setAdapter(adapter);
        

        itemsRv.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, 
                                      @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                outRect.bottom = 8; // 8dp gap
            }
        });


        slotWeapon.setImageResource(R.drawable.placeholder_weapon);
        slotArmor.setImageResource(R.drawable.placeholder_chestplate);


        setupEquipmentSlotClickListeners();
        

        loadInitialUserDataAndSetupListener();


        userRef.get().addOnSuccessListener(doc -> {
            if (doc.exists() && doc.contains("lastShopDate")) {
                lastShopDate = doc.getString("lastShopDate");
            } else {
                lastShopDate = null;
            }
            shopDateLoaded = true;
            
            if (pendingResume) {
                pendingResume = false;
                checkShopReset();
            } else {
                 if (currentShopList == null) {
                    generateDailyShopAndInventory(doc);
                 }
            }
        }).addOnFailureListener(e -> {
            lastShopDate = null;
            shopDateLoaded = true;
            if (pendingResume) {
                pendingResume = false;
                checkShopReset();
            }
        });
    }
    

    private void findViews() {
        coinsTv = findViewById(R.id.coinsTv);
        itemsRv = findViewById(R.id.itemsRecyclerView);
        avatarBase = findViewById(R.id.ivAvatarBase);
        avatarArmor = findViewById(R.id.ivAvatarChestplate);
        avatarWeapon = findViewById(R.id.ivAvatarWeapon);
        slotArmor = findViewById(R.id.ivSlotChestplate);
        slotWeapon = findViewById(R.id.ivSlotWeapon);
        backBtn = findViewById(R.id.backBtn);
        slotHelmet = findViewById(R.id.ivSlotHelmet);
        avatarHelmet = findViewById(R.id.ivAvatarHelmet);
        slotGreaves = findViewById(R.id.ivSlotGreaves);
        avatarGreaves = findViewById(R.id.ivAvatarGreaves);
        slotBoots = findViewById(R.id.ivSlotBoots);
        avatarBoots = findViewById(R.id.ivAvatarBoots);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!shopDateLoaded) {
            pendingResume = true;
            return;
        }
        checkShopReset();
    }

    private void checkShopReset() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!today.equals(lastShopDate)) {
            lastShopDate = today;
            if (userRef != null) {
                userRef.update("lastShopDate", today);
                userRef.update("boughtShopItems", new ArrayList<String>());
            }
            recreate(); //
        } else {

            userRef.get().addOnSuccessListener(documentSnapshot -> {
                if (documentSnapshot.exists()) {

                    updateInventoryAndEquipment(documentSnapshot);
                }
            }).addOnFailureListener(e -> Log.e(TAG, "Failed to get user data: " + e.getMessage()));
        }
    }

    private void setupEquipmentSlotClickListeners() {
        slotArmor.setOnClickListener(v -> {
            if (avatarArmor.getVisibility() == View.VISIBLE) {
                unequipItem("armor");
            }
        });
        slotWeapon.setOnClickListener(v -> {
            if (avatarWeapon.getVisibility() == View.VISIBLE) {
                unequipItem("weapon");
            }
        });
        slotHelmet.setOnClickListener(v -> {
            if (avatarHelmet.getVisibility() == View.VISIBLE) {
                unequipItem("helmet");
            }
        });
        slotGreaves.setOnClickListener(v -> {
            if (avatarGreaves.getVisibility() == View.VISIBLE) {
                unequipItem("greaves");
            }
        });
        slotBoots.setOnClickListener(v -> {
            if (avatarBoots.getVisibility() == View.VISIBLE) {
                unequipItem("boots");
            }
        });
    }

    private void unequipItem(String slot) {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) return;

        userRef.get().addOnSuccessListener(documentSnapshot -> {
            if (documentSnapshot.exists()) {
                Map<String, Object> equipped = (Map<String, Object>) documentSnapshot.get("equipped");
                if (equipped != null && equipped.containsKey(slot)) {
                    equipped.remove(slot);
                    userRef.update("equipped", equipped)
                        .addOnSuccessListener(aVoid -> {
                            Toast.makeText(this, "Item unequipped", Toast.LENGTH_SHORT).show();
                        })
                        .addOnFailureListener(e -> Toast.makeText(this, "Failed to unequip item", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private void initializeItems() {
        items = new ArrayList<>();
        itemCatalog = new HashMap<>(); 
        ShopItem newItem;

        try {
            Context context = ShopActivity.this;
            Field[] drawableFields = R.drawable.class.getFields();
            String packageName = context.getPackageName();
            
            List<String> weaponIds = new ArrayList<>();
            for (Field field : drawableFields) {
                String fieldName = field.getName();
                if (fieldName.startsWith("weapon_")) {
                    int resId = context.getResources().getIdentifier(fieldName, "drawable", packageName);
                    if (resId != 0) {
                        weaponIds.add(fieldName);
                    } else {
                        Log.w(TAG, "Drawable resource missing for potential item: " + fieldName + ", skipping.");
                    }
                }
            }
            Collections.sort(weaponIds);
            int totalWeapons = weaponIds.size();
            int level1Weapons = (int) (totalWeapons * 0.4); // 40% at level 1 (down from 70%)
            int level2Weapons = (int) (totalWeapons * 0.25); // 25% at level 2 (up from 15%)
            int level3Weapons = (int) (totalWeapons * 0.2); // 20% at level 3 (up from 10%)
            int level4Weapons = (int) (totalWeapons * 0.1); // 10% at level 4 (up from 3%)
            int weaponCounter = 0;
            int baseWeaponPrice = 100;
            for (String weaponId : weaponIds) { // Iterate only over existing drawable IDs
                 int level; String tier;
                 if (weaponCounter < level1Weapons) { level = 1; tier = "Basic"; } 
                 else if (weaponCounter < level1Weapons + level2Weapons) { level = 2; tier = "Improved"; }
                 else if (weaponCounter < level1Weapons + level2Weapons + level3Weapons) { level = 3; tier = "Advanced"; }
                 else if (weaponCounter < level1Weapons + level2Weapons + level3Weapons + level4Weapons) { level = 4; tier = "Superior"; }
                 else { level = 5; tier = "Legendary"; }
                 String displayName = "Weapon " + weaponId.replaceAll("weapon_", "");
                 int price = baseWeaponPrice + (level * 30) + (weaponCounter * 5);
                newItem = new ShopItem(weaponId, displayName, price, level, 1, "", tier + " weapon gear.", "weapon");
                itemCatalog.put(newItem.getId(), newItem);
                weaponCounter++;
            }

            List<String> armorIds = new ArrayList<>();
            for (Field field : drawableFields) {
                String fieldName = field.getName();
                if (fieldName.startsWith("chestplate_")) { 
                    int resId = context.getResources().getIdentifier(fieldName, "drawable", packageName);
                    if (resId != 0) {
                        armorIds.add(fieldName);
                    } else {
                        Log.w(TAG, "Drawable resource missing for potential item: " + fieldName + ", skipping.");
                    }
                }
            }
            Collections.sort(armorIds);
             int totalArmors = armorIds.size();
             int level1Armors = (int) (totalArmors * 0.4); // 40% at level 1 (down from 70%)
             int level2Armors = (int) (totalArmors * 0.25); // 25% at level 2 (up from 15%)
             int level3Armors = (int) (totalArmors * 0.2); // 20% at level 3 (up from 10%)
             int level4Armors = (int) (totalArmors * 0.1); // 10% at level 4 (up from 3%)
            int armorCounter = 0;
            int baseArmorPrice = 120;
            for (String armorId : armorIds) {
                 int level; String tier;
                 if (armorCounter < level1Armors) { level = 1; tier = "Basic"; }
                 else if (armorCounter < level1Armors + level2Armors) { level = 2; tier = "Improved"; }
                 else if (armorCounter < level1Armors + level2Armors + level3Armors) { level = 3; tier = "Advanced"; }
                 else if (armorCounter < level1Armors + level2Armors + level3Armors + level4Armors) { level = 4; tier = "Superior"; }
                 else { level = 5; tier = "Legendary"; }
                 String displayName = "Chestplate " + armorId.replaceAll("chestplate_", "");
                 int price = baseArmorPrice + (level * 35) + (armorCounter * 7);
                newItem = new ShopItem(armorId, displayName, price, level, 1, "", tier + " chestplate gear.", "armor");
                itemCatalog.put(newItem.getId(), newItem);
                armorCounter++;
            }

            List<String> helmetIds = new ArrayList<>();
            for (Field field : drawableFields) {
                String fieldName = field.getName();
                if (fieldName.startsWith("helmet_")) {
                    int resId = context.getResources().getIdentifier(fieldName, "drawable", packageName);
                    if (resId != 0) {
                        helmetIds.add(fieldName);
                    } else {
                         Log.w(TAG, "Drawable resource missing for potential item: " + fieldName + ", skipping.");
                     }
                }
            }
            Collections.sort(helmetIds);
            int totalHelmets = helmetIds.size();
            int level1Helmets = (int) (totalHelmets * 0.4); // 40% level 1
            int level2Helmets = (int) (totalHelmets * 0.25); // 25% level 2
            int level3Helmets = (int) (totalHelmets * 0.2); // 20% level 3
            int level4Helmets = (int) (totalHelmets * 0.1); // 10% level 4
            int helmetCounter = 0;
            int baseHelmetPrice = 110;
            for (String helmetId : helmetIds) {
                int level; String tier;
                if (helmetCounter < level1Helmets) { level = 1; tier = "Basic"; }
                else if (helmetCounter < level1Helmets + level2Helmets) { level = 2; tier = "Improved"; }
                else if (helmetCounter < level1Helmets + level2Helmets + level3Helmets) { level = 3; tier = "Advanced"; }
                else if (helmetCounter < level1Helmets + level2Helmets + level3Helmets + level4Helmets) { level = 4; tier = "Superior"; }
                else { level = 5; tier = "Legendary"; }
                String displayName = "Helmet " + helmetId.replaceAll("helmet_", "");
                int price = baseHelmetPrice + (level * 25) + (helmetCounter * 7);
                newItem = new ShopItem(helmetId, displayName, price, level, 1, "", tier + " helmet gear.", "helmet");
                itemCatalog.put(newItem.getId(), newItem);
                helmetCounter++;
            }

             List<String> greavesIds = new ArrayList<>();
             for (Field field : drawableFields) {
                 String fieldName = field.getName();
                 if (fieldName.startsWith("greaves_")) {
                     int resId = context.getResources().getIdentifier(fieldName, "drawable", packageName);
                     if (resId != 0) {
                         greavesIds.add(fieldName);
                     } else {
                          Log.w(TAG, "Drawable resource missing for potential item: " + fieldName + ", skipping.");
                      }
                 }
             }
             Collections.sort(greavesIds);
            int totalGreaves = greavesIds.size();
            int level1Greaves = (int) (totalGreaves * 0.4); // 40% level 1
            int level2Greaves = (int) (totalGreaves * 0.25); // 25% level 2
            int level3Greaves = (int) (totalGreaves * 0.2); // 20% level 3
            int level4Greaves = (int) (totalGreaves * 0.1); // 10% level 4
            int greavesCounter = 0;
            int baseGreavesPrice = 115;
            for (String greavesId : greavesIds) {
                int level; String tier;
                if (greavesCounter < level1Greaves) { level = 1; tier = "Basic"; }
                else if (greavesCounter < level1Greaves + level2Greaves) { level = 2; tier = "Improved"; }
                else if (greavesCounter < level1Greaves + level2Greaves + level3Greaves) { level = 3; tier = "Advanced"; }
                else if (greavesCounter < level1Greaves + level2Greaves + level3Greaves + level4Greaves) { level = 4; tier = "Superior"; }
                else { level = 5; tier = "Legendary"; }
                String displayName = "Greaves " + greavesId.replaceAll("greaves_", "");
                int price = baseGreavesPrice + (level * 25) + (greavesCounter * 7);
                newItem = new ShopItem(greavesId, displayName, price, level, 1, "", tier + " greaves gear.", "greaves");
                itemCatalog.put(newItem.getId(), newItem);
                greavesCounter++;
            }

             List<String> bootsIds = new ArrayList<>();
             for (Field field : drawableFields) {
                 String fieldName = field.getName();
                 if (fieldName.startsWith("boots_")) {
                     int resId = context.getResources().getIdentifier(fieldName, "drawable", packageName);
                     if (resId != 0) {
                         bootsIds.add(fieldName);
                     } else {
                          Log.w(TAG, "Drawable resource missing for potential item: " + fieldName + ", skipping.");
                      }
                 }
             }
             Collections.sort(bootsIds);
            int totalBoots = bootsIds.size();
            int level1Boots = (int) (totalBoots * 0.4); // 40% level 1
            int level2Boots = (int) (totalBoots * 0.25); // 25% level 2
            int level3Boots = (int) (totalBoots * 0.2); // 20% level 3
            int level4Boots = (int) (totalBoots * 0.1); // 10% level 4
            int bootsCounter = 0;
            int baseBootsPrice = 105;
            for (String bootsId : bootsIds) {
                int level; String tier;
                if (bootsCounter < level1Boots) { level = 1; tier = "Basic"; }
                else if (bootsCounter < level1Boots + level2Boots) { level = 2; tier = "Improved"; }
                else if (bootsCounter < level1Boots + level2Boots + level3Boots) { level = 3; tier = "Advanced"; }
                else if (bootsCounter < level1Boots + level2Boots + level3Boots + level4Boots) { level = 4; tier = "Superior"; }
                else { level = 5; tier = "Legendary"; }
                String displayName = "Boots " + bootsId.replaceAll("boots_", "");
                int price = baseBootsPrice + (level * 25) + (bootsCounter * 7);
                newItem = new ShopItem(bootsId, displayName, price, level, 1, "", tier + " boots gear.", "boots");
                itemCatalog.put(newItem.getId(), newItem);
                bootsCounter++;
            }
            
            Log.d(TAG, "Loaded (with drawable check): " + weaponIds.size() + " weapons, " + armorIds.size() + " armors, " + helmetIds.size() + " helmets, " + greavesIds.size() + " greaves, " + bootsIds.size() + " boots.");
            
        } catch (Exception e) {
            Log.e(TAG, "Error loading items dynamically: " + e.getMessage());
            e.printStackTrace();
            newItem = new ShopItem("weapon_1", "Weapon 1", 120, 1, 1, "", "Basic weapon gear.", "weapon");
            itemCatalog.put(newItem.getId(), newItem);
            newItem = new ShopItem("chestplate_1", "Chestplate 1", 100, 1, 1, "", "Basic chestplate gear.", "armor");
            itemCatalog.put(newItem.getId(), newItem);
        }


             newItem = new ShopItem("points_1000", "Bronze Achiever", 1000, 3, 1, "", "Achievement badge for earning 1000 points.", "badge");
             itemCatalog.put(newItem.getId(), newItem);

        newItem = new ShopItem("points_2500", "Silver Achiever", 2500, 5, 1, "", "Achievement badge for earning 2500 points.", "badge"); itemCatalog.put(newItem.getId(), newItem);
        newItem = new ShopItem("points_5000", "Gold Achiever", 5000, 10, 1, "", "Achievement badge for earning 5000 points.", "badge"); itemCatalog.put(newItem.getId(), newItem);
        newItem = new ShopItem("points_10000", "Platinum Achiever", 10000, 15, 1, "", "Achievement badge for earning 10000 points.", "badge"); itemCatalog.put(newItem.getId(), newItem);
        newItem = new ShopItem("quests_completed_10", "Adventurer", 500, 2, 1, "", "Completed 10 quests.", "badge"); itemCatalog.put(newItem.getId(), newItem);
        newItem = new ShopItem("quests_completed_20", "Hero", 1000, 4, 1, "", "Completed 20 quests.", "badge"); itemCatalog.put(newItem.getId(), newItem);
        newItem = new ShopItem("quests_completed_50", "Legend", 2500, 8, 1, "", "Completed 50 quests.", "badge"); itemCatalog.put(newItem.getId(), newItem);
        newItem = new ShopItem("items_badge_10", "Collector", 400, 2, 1, "", "Collected 10 unique items.", "badge"); itemCatalog.put(newItem.getId(), newItem);
        newItem = new ShopItem("items_badge_20", "Hoarder", 800, 3, 1, "", "Collected 20 unique items.", "badge"); itemCatalog.put(newItem.getId(), newItem);
        newItem = new ShopItem("items_badge_50", "Treasure Hunter", 2000, 5, 1, "", "Collected 50 unique items.", "badge"); itemCatalog.put(newItem.getId(), newItem);
        newItem = new ShopItem("mystery_badge", "Mystery Achievement", 1500, 5, 1, "", "A special achievement with mysterious powers.", "badge"); itemCatalog.put(newItem.getId(), newItem);



        newItem = new ShopItem("potion_1", "Strength Potion", 160, 1, 1, "potion001", "", "consumable"); itemCatalog.put(newItem.getId(), newItem);
        newItem = new ShopItem("potion_2", "Rage Draught", 175, 1, 1, "potion002", "", "consumable"); itemCatalog.put(newItem.getId(), newItem);
         newItem = new ShopItem("potion_3", "Dexterity Serum", 190, 1, 1, "potion003", "", "consumable"); itemCatalog.put(newItem.getId(), newItem);
         newItem = new ShopItem("potion_4", "Mindmeld Elixir", 200, 1, 1, "potion004", "", "consumable"); itemCatalog.put(newItem.getId(), newItem);
         newItem = new ShopItem("potion_5", "Vigor Potion", 220, 1, 1, "potion005", "", "consumable"); itemCatalog.put(newItem.getId(), newItem);
         newItem = new ShopItem("potion_6", "Giant Strength Tonic", 240, 1, 1, "potion006", "", "consumable"); itemCatalog.put(newItem.getId(), newItem);
         newItem = new ShopItem("potion_7", "Clarity Elixir", 255, 1, 1, "potion007", "", "consumable"); itemCatalog.put(newItem.getId(), newItem);
         newItem = new ShopItem("potion_8", "Midas Brew", 270, 1, 1, "potion008", "", "consumable"); itemCatalog.put(newItem.getId(), newItem);
         newItem = new ShopItem("potion_9", "Efficiency Serum", 290, 1, 1, "potion009", "", "consumable"); itemCatalog.put(newItem.getId(), newItem);
         newItem = new ShopItem("potion_10", "Insight Infusion", 300, 1, 1, "potion000", "", "consumable"); itemCatalog.put(newItem.getId(), newItem);
    }

    private void onItemClick(ShopItem item) {

        Toast.makeText(this, "Clicked: " + item.getName(), Toast.LENGTH_SHORT).show();
    }

    private void loadGoldCoins() {
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .addSnapshotListener((docSnap, error) -> {
                if (docSnap != null && docSnap.exists()) {
                    Long coins = docSnap.getLong("goldCoins");
                    coinsTv.setText("Coins: " + (coins != null ? coins : 0));
                }
            });
    }

    private void loadInitialUserDataAndSetupListener() {
        userRef.addSnapshotListener((docSnap, error) -> {
            if (error != null) {
                Log.e(TAG, "Listen failed.", error);

                return;
            }

            if (docSnap != null && docSnap.exists()) {
                Log.d(TAG, "Snapshot listener triggered.");

                if (currentShopList == null) {
                    Log.d(TAG, "Generating shop/inventory list from snapshot listener (likely initial load or after reset).");
                    generateDailyShopAndInventory(docSnap);
                } else {
                    Log.d(TAG, "Snapshot listener update: Updating inventory/equipped items, but NOT regenerating shop list.");

                    updateInventoryAndEquipment(docSnap);
                }
                

                Long coins = docSnap.getLong("goldCoins");
                coinsTv.setText("Coins: " + (coins != null ? coins : 0));
                updateCharacterVisuals(docSnap); // Extracted character/slot visual updates

            } else {
                Log.d(TAG, "Current data: null");

                Toast.makeText(this, "User data not found.", Toast.LENGTH_SHORT).show();

                adapter.setItems(new ArrayList<>(), new ArrayList<>()); // Clear adapter
                currentShopList = new ArrayList<>(); // Clear cache
            }
        });
    }
    private void generateDailyShopAndInventory(DocumentSnapshot docSnap) {
        Log.d(TAG, "Executing generateDailyShopAndInventory");
        if (itemCatalog == null || itemCatalog.isEmpty()) {
            Log.e(TAG, "Item catalog is empty, cannot generate shop.");
            initializeItems();
            if (itemCatalog.isEmpty()) return;
        }


        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        List<String> shopItemIds = null;
        if (docSnap.contains("shopItems") && docSnap.contains("lastShopDate")) {
            String lastShopDate = docSnap.getString("lastShopDate");
            if (today.equals(lastShopDate)) {
                Object shopItemsObj = docSnap.get("shopItems");
                if (shopItemsObj instanceof List<?>) {
                    shopItemIds = new ArrayList<>();
                    for (Object o : (List<?>) shopItemsObj) {
                        if (o instanceof String) shopItemIds.add((String) o);
                    }
                }
            }
        }
        if (shopItemIds != null && !shopItemIds.isEmpty()) {

            List<ShopItem> loadedShopItems = new ArrayList<>();
            for (String id : shopItemIds) {
                if (itemCatalog.containsKey(id)) {
                    loadedShopItems.add(itemCatalog.get(id));
                }
            }
            currentShopList = loadedShopItems;
            Log.d(TAG, "Loaded shop list from Firestore for today: " + currentShopList.size() + " items.");
            updateInventoryAndEquipment(docSnap);
            return;
        }

        Map<String, Long> invMap = new HashMap<>();
        Map<String, Object> rawInv = (Map<String, Object>) docSnap.get("inventory");
        if (rawInv != null) {
            for (Map.Entry<String, Object> e : rawInv.entrySet()) {
                try { invMap.put(e.getKey(), ((Number)e.getValue()).longValue()); } catch (Exception ignored) {}
            }
        }

        List<String> boughtShopItems = new ArrayList<>();
        if (docSnap.contains("boughtShopItems")) {
            Object boughtObj = docSnap.get("boughtShopItems");
            if (boughtObj instanceof List<?>) {
                for (Object o : (List<?>) boughtObj) {
                    if (o instanceof String) boughtShopItems.add((String) o);
                }
            }
        }


        long userLevel = 1;
        if (docSnap.contains("level")) {
            Object levelObj = docSnap.get("level");
            if (levelObj instanceof Number) {
                userLevel = ((Number) levelObj).longValue();
            }
        }


        List<ShopItem> eligible = new ArrayList<>();
        for (ShopItem item : itemCatalog.values()) {
            // More strict level requirement implementation
            long itemLevel = item.getMinLevel();
            String itemSlot = item.getSlot();
            boolean isEquipment = "weapon".equals(itemSlot) || "armor".equals(itemSlot) || 
                                 "helmet".equals(itemSlot) || "greaves".equals(itemSlot) ||
                                 "boots".equals(itemSlot);
            

            boolean isConsumable = "consumable".equals(itemSlot);
            

            boolean isBadge = "badge".equals(itemSlot);
            
            if (isConsumable || (isBadge && itemLevel <= userLevel) || (isEquipment && itemLevel <= userLevel)) {

                if (!isEquipment || applyLevelBasedFiltering(itemSlot, userLevel)) {
                    eligible.add(item);
                }
            }
        }


        Map<String, List<ShopItem>> slotPools = new HashMap<>();
        slotPools.put("weapon", new ArrayList<>());
        slotPools.put("armor", new ArrayList<>());
        slotPools.put("helmet", new ArrayList<>());
        slotPools.put("greaves", new ArrayList<>());
        slotPools.put("boots", new ArrayList<>());
        List<ShopItem> potionPool = new ArrayList<>();
        for (ShopItem si : eligible) {
            if (invMap.getOrDefault(si.getId(), 0L) > 0) {
                continue;
            }
            if (boughtShopItems.contains(si.getId())) {
                continue;
            }
            if (slotPools.containsKey(si.getSlot())) {
                slotPools.get(si.getSlot()).add(si);
            } else if ("consumable".equals(si.getSlot())) {
                potionPool.add(si);
            }
        }
        List<String> availableSlots = new ArrayList<>();
        for (String slot : slotPools.keySet()) {
            if (!slotPools.get(slot).isEmpty()) {
                availableSlots.add(slot);
            }
        }
        long seed = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()).hashCode();
        List<ShopItem> dailyShopItems = new ArrayList<>();
        Random shopRand = new Random(seed);
        final int SHOP_SIZE = 4;
        int itemsAdded = 0;
        boolean potionAdded = false;
        

        if (!potionPool.isEmpty() && shopRand.nextInt(100) < 25) {
            Collections.shuffle(potionPool, shopRand);
            dailyShopItems.add(potionPool.get(0));
            itemsAdded++;
            potionAdded = true;
            Log.d(TAG, "Added potion to shop on first attempt: " + potionPool.get(0).getName());
        }
        

        Collections.shuffle(availableSlots, shopRand);
        for (String slot : availableSlots) {
            if (itemsAdded >= SHOP_SIZE) break;
            List<ShopItem> pool = slotPools.get(slot);
            if (pool == null || pool.isEmpty()) continue;
            Collections.shuffle(pool, shopRand);
            ShopItem equipItem = pool.get(0);
            if (!dailyShopItems.contains(equipItem)) {
                dailyShopItems.add(equipItem);
                itemsAdded++;
            }
        }
        

        if (!potionAdded && !potionPool.isEmpty() && itemsAdded < SHOP_SIZE) {
            Collections.shuffle(potionPool, shopRand);
            dailyShopItems.add(potionPool.get(0));
            Log.d(TAG, "Added potion to shop on second attempt: " + potionPool.get(0).getName());
        }
        
        Collections.shuffle(dailyShopItems, shopRand);



        currentShopList = new ArrayList<>(dailyShopItems);

        List<String> shopIdsToSave = new ArrayList<>();
        for (ShopItem item : currentShopList) shopIdsToSave.add(item.getId());
        Map<String, Object> shopData = new HashMap<>();
        shopData.put("shopItems", shopIdsToSave);
        shopData.put("lastShopDate", today);
        userRef.update(shopData);

        updateInventoryAndEquipment(docSnap);
    }


    private void updateInventoryAndEquipment(DocumentSnapshot docSnap) {
        Log.d(TAG, "Executing updateInventoryAndEquipment");
        

        Map<String, Long> invMap = new HashMap<>();
        Map<String, Object> rawInv = (Map<String, Object>) docSnap.get("inventory");
        if (rawInv != null) {
            for (Map.Entry<String, Object> e : rawInv.entrySet()) {
                try { 
                    invMap.put(e.getKey(), ((Number)e.getValue()).longValue()); 
                } catch (Exception ignored) {
                    Log.w(TAG, "Error converting inventory value for key: " + e.getKey());
                }
            }
        }

        // Get boughtShopItems array from Firestore
        List<String> boughtShopItems = new ArrayList<>();
        if (docSnap.contains("boughtShopItems")) {
            Object boughtObj = docSnap.get("boughtShopItems");
            if (boughtObj instanceof List<?>) {
                for (Object o : (List<?>) boughtObj) {
                    if (o instanceof String) boughtShopItems.add((String) o);
                }
            }
        }

        Map<String, Object> equipped = (Map<String, Object>) docSnap.get("equipped");
        String armorId = equipped != null ? (String) equipped.get("armor") : null;
        String weaponId = equipped != null ? (String) equipped.get("weapon") : null;
        String helmetId = equipped != null ? (String) equipped.get("helmet") : null;
        String greavesId = equipped != null ? (String) equipped.get("greaves") : null;
        String bootsId = equipped != null ? (String) equipped.get("boots") : null;
        String supportId = equipped != null && equipped.containsKey("support")
                ? (String) equipped.get("support") : null;

        adapter.setEquipped(armorId, weaponId, helmetId, greavesId, bootsId, supportId);
        List<ShopItem> inventoryItems = new ArrayList<>();
        for (ShopItem item : itemCatalog.values()) { 
            String itemId = item.getId();
            if (itemId.equals("placeholder_weapon") || itemId.equals("placeholder_chestplate")) continue;
            
            long quantityOwned = invMap.getOrDefault(itemId, 0L);
            if (quantityOwned > 0) {
                 inventoryItems.add(item);
            }
        }

        if (currentShopList == null) {
            Log.w(TAG, "currentShopList is null in updateInventoryAndEquipment, initializing empty.");
            currentShopList = new ArrayList<>();
        }

        List<ShopItem> filteredShopItems = new ArrayList<>();
        for (ShopItem shopItem : currentShopList) {
            if (invMap.getOrDefault(shopItem.getId(), 0L) == 0L && !boughtShopItems.contains(shopItem.getId())) {
                filteredShopItems.add(shopItem);
            } else {
                Log.d(TAG, "Filtering out bought item from shop: " + shopItem.getId());
            }
        }

        currentShopList = new ArrayList<>(filteredShopItems);

        adapter.setItems(filteredShopItems, inventoryItems);
        adapter.setInventory(invMap);

        TextView tvItemsEmpty = findViewById(R.id.emptyItemsTv);
        if (tvItemsEmpty != null) {
            tvItemsEmpty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        }
    }
    

    private void updateCharacterVisuals(DocumentSnapshot docSnap) {
         try {
             Long charResLong = docSnap.getLong("characterRes");
             Field[] fields = R.drawable.class.getFields();
              List<Integer> charIds = new ArrayList<>();
              for (Field f : fields) {
                  String name = f.getName();
                  if (name.startsWith("character_")) {
                      charIds.add(f.getInt(null));
                  }
              }
              if (!charIds.isEmpty()) {
                  int resToUse;
                  if (charResLong == null || !charIds.contains(charResLong.intValue())) {
                      resToUse = charIds.get(new Random().nextInt(charIds.size()));
                      userRef.update("characterRes", resToUse);
                  } else {
                      resToUse = charResLong.intValue();
                  }
                  avatarBase.setImageResource(resToUse);
                  
                  String resName = "";
                  for (Field f : fields) {
                      if (f.getInt(null) == resToUse) {
                          resName = f.getName();
                          break;
                      }
                  }
                  
                  if (resName.matches("character_[1-7]")) {
                      List<Integer> hairIds = new ArrayList<>();
                      for (Field f : fields) {
                          String name = f.getName();
                          if (name.startsWith("hair_")) {
                              hairIds.add(f.getInt(null));
                          }
                      }
                      if (!hairIds.isEmpty()) {
                          Long hairResLong = docSnap.getLong("hairRes");
                          int hairToUse;
                          if (hairResLong == null || !hairIds.contains(hairResLong.intValue())) {
                              hairToUse = hairIds.get(new Random().nextInt(hairIds.size()));
                              userRef.update("hairRes", hairToUse);
                          } else {
                              hairToUse = hairResLong.intValue();
                          }
                          ImageView ivHair = findViewById(R.id.ivAvatarHair);
                          if (ivHair != null) {
                              ivHair.setImageResource(hairToUse);
                              ivHair.setVisibility(View.VISIBLE);
                          }
                      }
                  }
              }
         } catch (Exception e) {
             e.printStackTrace();
         }

         Map<String, Object> equipped = (Map<String, Object>) docSnap.get("equipped");
         String armorId = equipped != null ? (String) equipped.get("armor") : null;
         String weaponId = equipped != null ? (String) equipped.get("weapon") : null;
         String helmetId = equipped != null ? (String) equipped.get("helmet") : null;
         String greavesId = equipped != null ? (String) equipped.get("greaves") : null;
         String bootsId = equipped != null ? (String) equipped.get("boots") : null;
          if (armorId != null) {
              ShopItem equippedArmor = itemCatalog.get(armorId);
              if (equippedArmor != null) {
                  int resId = getResources().getIdentifier(armorId, "drawable", getPackageName());
                  if (resId != 0) {
                      avatarArmor.setImageResource(resId);
                      avatarArmor.setVisibility(View.VISIBLE);
                      slotArmor.setImageResource(resId);
                  }
              } else {
                  avatarArmor.setVisibility(View.GONE);
                  slotArmor.setImageResource(R.drawable.placeholder_chestplate);
              }
          } else {
              avatarArmor.setVisibility(View.GONE);
              slotArmor.setImageResource(R.drawable.placeholder_chestplate);
          }

          if (weaponId != null) {
              ShopItem equippedWeapon = itemCatalog.get(weaponId); 
              if (equippedWeapon != null) {
                  if (!weaponId.equals("placeholder_weapon")) {
                      int resId = getResources().getIdentifier(weaponId, "drawable", getPackageName());
                      if (resId != 0) {
                          avatarWeapon.setImageResource(resId);
                          avatarWeapon.setVisibility(View.VISIBLE);
                          slotWeapon.setImageResource(resId);
                      }
                  }
              } else {
                  avatarWeapon.setVisibility(View.GONE);
                  slotWeapon.setImageResource(R.drawable.placeholder_weapon);
              }
          } else {
              avatarWeapon.setVisibility(View.GONE);
              slotWeapon.setImageResource(R.drawable.placeholder_weapon);
          }

          if (helmetId != null) {
              ShopItem equippedHelmet = itemCatalog.get(helmetId);
              if (equippedHelmet != null) {
                  @SuppressLint("DiscouragedApi") int resId = getResources().getIdentifier(helmetId, "drawable", getPackageName());
                  if (resId != 0) {
                      avatarHelmet.setImageResource(resId);
                      avatarHelmet.setVisibility(View.VISIBLE);
                      slotHelmet.setImageResource(resId);
                  }
              } else {
                  avatarHelmet.setVisibility(View.GONE);
                  slotHelmet.setImageResource(R.drawable.placeholder_helmet);
              }
          } else {
              avatarHelmet.setVisibility(View.GONE);
              slotHelmet.setImageResource(R.drawable.placeholder_helmet);
          }

          if (greavesId != null) {
              ShopItem equippedGreaves = itemCatalog.get(greavesId);
              if (equippedGreaves != null) {
                  @SuppressLint("DiscouragedApi") int resId = getResources().getIdentifier(greavesId, "drawable", getPackageName());
                  if (resId != 0) {
                      avatarGreaves.setImageResource(resId);
                      avatarGreaves.setVisibility(View.VISIBLE);
                      slotGreaves.setImageResource(resId);
                  }
              } else {
                  avatarGreaves.setVisibility(View.GONE);
                  slotGreaves.setImageResource(R.drawable.placeholder_greaves);
              }
          } else {
              avatarGreaves.setVisibility(View.GONE);
              slotGreaves.setImageResource(R.drawable.placeholder_greaves);
          }

          if (bootsId != null) {
              ShopItem equippedBoots = itemCatalog.get(bootsId);
              if (equippedBoots != null) {
                  @SuppressLint("DiscouragedApi") int resId = getResources().getIdentifier(bootsId, "drawable", getPackageName());
                  if (resId != 0) {
                      avatarBoots.setImageResource(resId);
                      avatarBoots.setVisibility(View.VISIBLE);
                      slotBoots.setImageResource(resId);
                  }
              } else {
                  avatarBoots.setVisibility(View.GONE);
                  slotBoots.setImageResource(R.drawable.placeholder_boots);
              }
          } else {
              avatarBoots.setVisibility(View.GONE);
              slotBoots.setImageResource(R.drawable.placeholder_boots);
          }
    }


    private boolean applyLevelBasedFiltering(String itemSlot, long userLevel) {
        int maxLevel = XpCalculator.MAX_LEVEL;
        double minProbability = 1.0 / 3.0;
        double fraction = (double)(userLevel - 1) / (maxLevel - 1);
        double threshold = minProbability + fraction * (1.0 - minProbability);
        Random rand = new Random(itemSlot.hashCode() + Long.valueOf(userLevel).hashCode());
        return rand.nextDouble() < threshold;
    }
}