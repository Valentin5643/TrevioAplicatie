package com.example.myapplication.aplicatiamea;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ShopAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    // view types - don't change these or it breaks!
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_SHOP_ITEM = 1;
    private static final int TYPE_INVENTORY_ITEM = 2;
    private static final int TYPE_EMPTY_SHOP = 3;
    private static final String EMPTY_SHOP_MARKER = "SHOP_EMPTY_MARKER";
    private static final String TAG = "ShopAdapter";
    
    // fields
    private final Context ctx;
    private final List<Object> items;
    private final DocumentReference userRef;
    private Map<String, Long> inventory = new HashMap<>();
    private Map<String, String> equipped = new HashMap<>();
    

    private final Map<String, Boolean> isShopItem = new HashMap<>();


    private final OnItemPurchaseListener purchaseHandler;
    private int userCoins;
    private int userLevel;

    private final Set<String> processingPurchases = new java.util.HashSet<>();

    public interface OnItemPurchaseListener {
        void onItemSelected(ShopItem item);
        boolean canUserAfford(ShopItem item);
        void onPurchaseAttempt(ShopItem item, PurchaseCallback callback);
    }
    
    public interface PurchaseCallback {
        void onSuccess();
        void onFailure(String reason);
    }

    public ShopAdapter(Context context, List<Object> items, DocumentReference userRef, OnItemPurchaseListener listener) {
        this.ctx = context;
        this.items = items;
        this.userRef = userRef;
        this.purchaseHandler = listener;
        
        // double check null
        if (this.items == null) {
            Log.e(TAG, "NULL ITEMS LIST in constructor!!!");
            throw new IllegalArgumentException("items cannot be null");
        }
    }

    public ShopAdapter(Context context, List<Object> items, DocumentReference userRef) {
        this(context, items, userRef, null);
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        
        if (item instanceof String) {
            if (EMPTY_SHOP_MARKER.equals(item)) {
                 return TYPE_EMPTY_SHOP;
            }
            return TYPE_HEADER;
        } 
        
        if (item instanceof ShopItem) {
            ShopItem shopItem = (ShopItem) item;
            boolean inShop = isShopItem.getOrDefault(shopItem.getId(), false);
            return inShop ? TYPE_SHOP_ITEM : TYPE_INVENTORY_ITEM;
        }
        

        Log.w(TAG, "Unknown item type at pos " + position + ": " + item);
        return TYPE_SHOP_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(ctx);

        switch (viewType) {
            case TYPE_HEADER:
                return new HeaderViewHolder(
                    inflater.inflate(R.layout.item_section_header, parent, false)
                );
                
            case TYPE_EMPTY_SHOP:
                return new EmptyShopViewHolder(
                    inflater.inflate(R.layout.item_empty_shop, parent, false)
                );
                
            case TYPE_SHOP_ITEM:
            case TYPE_INVENTORY_ITEM:
            default:
                return new ItemViewHolder(
                    inflater.inflate(R.layout.item_shop, parent, false)
                );
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        int viewType = getItemViewType(position);
        
        switch (viewType) {
            case TYPE_HEADER:

                HeaderViewHolder headerHolder = (HeaderViewHolder) holder;
                String headerText = (String) items.get(position);
                headerHolder.tvHeader.setText(headerText);
                break;
                
            case TYPE_EMPTY_SHOP:
                break;
                
            case TYPE_SHOP_ITEM:
            case TYPE_INVENTORY_ITEM:
                ItemViewHolder itemHolder = (ItemViewHolder) holder;
                ShopItem item = (ShopItem) items.get(position);
                boolean inShop = isShopItem.getOrDefault(item.getId(), false);
                      
                bindItemViewHolder(itemHolder, item, inShop);
                break;
        }
    }

    private void bindItemViewHolder(ItemViewHolder holder, ShopItem item, boolean inShop) {
        holder.tvItemName.setText(item.getName());
        holder.tvItemName.setTextColor(ctx.getResources().getColor(R.color.text_primary));
        holder.tvItemName.setTypeface(null, Typeface.NORMAL);
        holder.itemView.setBackgroundResource(android.R.color.transparent);
        holder.itemView.setOnClickListener(v -> tryConsumePotion(item, holder));
        holder.ivIcon.clearAnimation();

        long price = item.getCost();
        long owned = inventory.getOrDefault(item.getId(), 0L);
        String costText = price + " coins" + (owned > 0 && !inShop ? " (x" + owned + ")" : "");
        
        holder.tvItemCost.setText(costText);
        holder.tvItemName.setVisibility(View.VISIBLE);

        boolean isPotion = item.getId().startsWith("potion_");
        boolean isEquipment = "armor".equals(item.getSlot()) || 
                            "weapon".equals(item.getSlot()) ||
                            "helmet".equals(item.getSlot()) || 
                            "greaves".equals(item.getSlot()) ||
                            "boots".equals(item.getSlot());

        if (inShop) {
            holder.tvItemCost.setVisibility(View.VISIBLE);
            holder.btnBuy.setVisibility(View.VISIBLE);
        } else {
            holder.tvItemCost.setVisibility(View.GONE);
            holder.btnBuy.setVisibility(View.GONE);
        }

        if (isPotion) {
            String name = item.getName();
            holder.tvItemName.setText("✧ " + name + " ✧");
            holder.tvItemName.setTextColor(Color.rgb(128, 0, 128)); // Purple!
            holder.tvItemName.setTypeface(null, Typeface.BOLD_ITALIC);
            holder.tvItemName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);

            if (inShop && "Rare Alchemical Concoction".equals(item.getDescription())) {
                Animation pulse = new AlphaAnimation(0.7f, 1.0f);
                pulse.setDuration(1000);
                pulse.setRepeatMode(Animation.REVERSE);
                pulse.setRepeatCount(Animation.INFINITE);
                holder.ivIcon.startAnimation(pulse);
            }

            String drawableName;
            switch (item.getId()) {
                case "potion_2": drawableName = "potion002"; break;
                case "potion_3": drawableName = "potion003"; break;
                case "potion_4": drawableName = "potion004"; break;
                case "potion_5": drawableName = "potion005"; break;
                case "potion_6": drawableName = "potion006"; break;
                case "potion_7": drawableName = "potion007"; break;
                case "potion_8": drawableName = "potion008"; break;
                case "potion_9": drawableName = "potion009"; break;
                case "potion_10": drawableName = "potion000"; break;
                default: drawableName = "potion001"; break;
            }

            @SuppressLint("DiscouragedApi") 
            int resId = ctx.getResources().getIdentifier(drawableName, "drawable", ctx.getPackageName());
            if (resId != 0) {
                holder.ivIcon.setImageResource(resId);
            } else {
                Log.e(TAG, "Missing potion image: " + drawableName);
                holder.ivIcon.setImageResource(android.R.drawable.ic_dialog_info);
            }

            if (!inShop && owned > 0) {
                holder.itemView.setOnClickListener(v -> tryConsumePotion(item, holder));
                holder.itemView.setBackgroundResource(R.drawable.item_background_selectable);
            } 
        } else {
            if (item.getIconUrl() != null && !item.getIconUrl().isEmpty()) {
                if (item.getIconUrl().startsWith("http")) {
                    Glide.with(ctx).load(item.getIconUrl()).into(holder.ivIcon);
                } else {
                    @SuppressLint("DiscouragedApi") 
                    int resId = ctx.getResources().getIdentifier(
                        item.getIconUrl(), "drawable", ctx.getPackageName()
                    );
                    holder.ivIcon.setImageResource(resId != 0 ? resId : R.drawable.ic_baseline_save_24);
                }
            } else {
                @SuppressLint("DiscouragedApi") 
                int resId = ctx.getResources().getIdentifier(
                    item.getId(), "drawable", ctx.getPackageName()
                );
                holder.ivIcon.setImageResource(resId != 0 ? resId : R.drawable.ic_baseline_save_24);
            }
        }

        if (inShop) {
            holder.btnBuy.setOnClickListener(v -> {
                if (purchaseHandler != null) {
                    purchaseHandler.onPurchaseAttempt(item, new PurchaseCallback() {
                        @Override
                        public void onSuccess() {

                        }
                        
                        @Override
                        public void onFailure(String reason) {
                            Toast.makeText(ctx, "Purchase failed: " + reason, Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    handleDirectPurchase(item, holder);
                }
            });
        } else if (!inShop && isEquipment) {
            holder.itemView.setOnClickListener(v -> {
                FirebaseFirestore.getInstance();
                String fieldPath = "equipped." + item.getSlot();
                 userRef.update(fieldPath, item.getId())
                     .addOnSuccessListener(aVoid -> Toast.makeText(ctx, "Equipped " + item.getName(), Toast.LENGTH_SHORT).show())
                     .addOnFailureListener(e -> {
                         Log.e(TAG, "Failed to equip", e);
                         Toast.makeText(ctx, "Equip failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                     });
            });
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public void setInventory(Map<String, Long> inventory) {
        this.inventory.clear();
        this.inventory.putAll(inventory);
        notifyDataSetChanged();
    }

    public void setItems(List<ShopItem> shopItems, List<ShopItem> inventoryItems) {
        items.clear();
        isShopItem.clear();

        items.add("Shop Items");
        if (shopItems == null || shopItems.isEmpty()) {
             Log.d(TAG, "Shop items list is empty, adding empty marker.");
             items.add(EMPTY_SHOP_MARKER);
        } else {
            for (ShopItem item : shopItems) {
                isShopItem.put(item.getId(), true);
                items.add(item);
                Log.d(TAG, "Added shop item: " + item.getId() + ", isShop=" + isShopItem.get(item.getId()));
            }
        }

        if (!inventoryItems.isEmpty()) {
            List<ShopItem> equipmentItems = new ArrayList<>();
            List<ShopItem> potionItems = new ArrayList<>();
            
            for (ShopItem item : inventoryItems) {
                isShopItem.put(item.getId(), false);
                Log.d(TAG, "Processing inventory item: " + item.getId() + ", marked as isShop=false");

                if ("consumable".equals(item.getSlot())) {
                    potionItems.add(item);
                } else {
                    String equippedInSlot = equipped.get(item.getSlot());
                    if (equippedInSlot == null || !equippedInSlot.equals(item.getId())) {
                        equipmentItems.add(item);
                    }
                }
            }
            
            if (!potionItems.isEmpty()) {
                items.add("Support Items");
                for (ShopItem item : potionItems) {
                    items.add(item);
                    Log.d(TAG, "Added potion item: " + item.getId() + ", isShop=" + isShopItem.get(item.getId()));
                }
            }
            
            if (!equipmentItems.isEmpty()) {
                items.add("Your Gear");
                for (ShopItem item : equipmentItems) {
                    items.add(item);
                    Log.d(TAG, "Added gear item: " + item.getId() + ", isShop=" + isShopItem.get(item.getId()));
                }
            }
        }
        
        notifyDataSetChanged();
        Log.d(TAG, "DEBUG: Shop refreshed. shopItems=" + (shopItems == null ? 0 : shopItems.size()) + ", userRef=" + (userRef != null ? userRef.getId() : "null"));
    }

    public void setEquipped(String armorId, String weaponId, String helmetId, String greavesId, String bootsId, String supportId) {
        equipped.clear();
        if (armorId != null) equipped.put("armor", armorId);
        if (weaponId != null) equipped.put("weapon", weaponId);
        if (helmetId != null) equipped.put("helmet", helmetId);
        if (greavesId != null) equipped.put("greaves", greavesId);
        if (bootsId != null) equipped.put("boots", bootsId);
        if (supportId != null) equipped.put("support", supportId);
        notifyDataSetChanged();
    }

    public void removeItem(ShopItem item) {
        int index = items.indexOf(item);
        if (index != -1) {
            items.remove(index);
            notifyItemRemoved(index);
        }
    }
    private void tryConsumePotion(ShopItem item, ItemViewHolder holder) {
        Log.d(TAG, "Attempting to consume potion: " + item.getName());
        final String itemId = item.getId();

        holder.itemView.setEnabled(false);

        long currentOwned = inventory.getOrDefault(itemId, 0L);
        if (currentOwned <= 0) {
            Toast.makeText(ctx, "No " + item.getName() + " left!", Toast.LENGTH_SHORT).show();
            holder.itemView.setEnabled(true);
            return;
        }

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.runTransaction(transaction -> {
            DocumentSnapshot userSnap = transaction.get(userRef);

            Map<String, Object> inventory = new HashMap<>();
            if (userSnap.contains("inventory") && userSnap.get("inventory") instanceof Map) {
                try {
                    inventory = new HashMap<>((Map<String, Object>) userSnap.get("inventory"));
                } catch (ClassCastException e) { Log.e(TAG, "Inventory field is not a Map"); }
            }

            long countInDb = 0;
            if (inventory.containsKey(itemId)) {
                Object countObj = inventory.get(itemId);
                if (countObj instanceof Number) {
                    countInDb = ((Number) countObj).longValue();
                }
            }

            if (countInDb <= 0) {
                throw new FirebaseFirestoreException("Potion count in DB is already zero.",
                        FirebaseFirestoreException.Code.ABORTED);
            }

            Map<String, Object> effectsMap = new HashMap<>();
            if (userSnap.contains("activeEffects") && userSnap.get("activeEffects") instanceof Map) {
                try {
                    effectsMap = new HashMap<>((Map<String, Object>) userSnap.get("activeEffects"));
                } catch (ClassCastException e) { Log.e(TAG, "activeEffects field is not a Map"); }
            }

            activatePotionEffect(item, effectsMap);


            transaction.update(userRef, "inventory." + itemId, FieldValue.increment(-1));

            transaction.update(userRef, "equipped.support", FieldValue.delete());

            transaction.update(userRef, "activeEffects", effectsMap);

            Log.d(TAG, "Potion consumption transaction: Updated inventory, equipped slot, and activeEffects.");

            return countInDb - 1;
        }).addOnSuccessListener(newCountLong -> {
            Log.d(TAG, "Potion consumption transaction successful. New count: " + newCountLong);
            Toast.makeText(ctx, "You consumed the mysterious " + item.getName() + "...", Toast.LENGTH_LONG).show();
            inventory.put(itemId, newCountLong);
            equipped.remove("support");
            removeItem(item);
            notifyDataSetChanged();
            holder.itemView.setEnabled(true);
            Log.d(TAG, "DEBUG: Potion consumed. item=" + item.getId() + ", newCount=" + newCountLong + ", userRef=" + (userRef != null ? userRef.getId() : "null"));
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Potion consumption transaction failed", e);
            Toast.makeText(ctx, "Failed to use potion: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            holder.itemView.setEnabled(true);
        });
    }

    private void activatePotionEffect(ShopItem item, Map<String, Object> effectsMap) {
        String potionName = item.getName();
        long currentTime = System.currentTimeMillis();
        long oneHourInMillis = 3600 * 1000;
        long thirtyMinutesInMillis = 30 * 60 * 1000;

        Log.d(TAG, "DEBUG: Potion activated. item=" + item.getId() + ", effect=" + potionName + ", userRef=" + (userRef != null ? userRef.getId() : "null"));

        switch(potionName) {
            case "Strength Potion":
                effectsMap.put("strengthPotionActive", true);
                Log.d("EffectManager", "Strength Potion effect activated.");
                break;
            case "Rage Draught":
                effectsMap.put("rageDraughtExpiryTimestamp", currentTime + oneHourInMillis);
                Log.d("EffectManager", "Rage Draught effect activated. Expiry: " + effectsMap.get("rageDraughtExpiryTimestamp"));
                break;
            case "Dexterity Serum":
                effectsMap.put("dexterityTasksRemaining", 3);
                effectsMap.put("dexterityTimeReductionPercent", 0.1); // Example reduction, maybe fetch from item?
                Log.d("EffectManager", "Dexterity Serum effect activated. Tasks remaining: " + effectsMap.get("dexterityTasksRemaining"));
                break;
            case "Mindmeld Elixir":
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, 23);
                cal.set(Calendar.MINUTE, 59);
                cal.set(Calendar.SECOND, 59);
                cal.set(Calendar.MILLISECOND, 999);
                effectsMap.put("mindmeldXpBoostExpiryTimestamp", cal.getTimeInMillis());
                Log.d("EffectManager", "Mindmeld Elixir effect activated. Boost until: " + effectsMap.get("mindmeldXpBoostExpiryTimestamp"));
                break;
            case "Vigor Potion":
                effectsMap.put("vigorPotionUsedToRecoverStreak", true);
                Log.d("EffectManager", "Vigor Potion effect activated (flag set for recovery)." );
                break;
            case "Giant Strength Tonic":
                effectsMap.put("giantStrengthTonicActive", true);
                Log.d("EffectManager", "Giant Strength Tonic effect activated.");
                break;
            case "Clarity Elixir":
                effectsMap.put("clarityActive", true);
                Log.d("EffectManager", "Clarity Elixir effect activated.");
                break;
            case "Midas Brew":
                effectsMap.put("midasBrewExpiryTimestamp", currentTime + thirtyMinutesInMillis);
                 Log.d("EffectManager", "Midas Brew effect activated. Expiry: " + effectsMap.get("midasBrewExpiryTimestamp"));
                break;
            case "Efficiency Serum":
                effectsMap.put("questTimeReductionPercent", 0.1);
                Log.d("EffectManager", "Efficiency Serum effect activated.");
                break;
            case "Insight Infusion":
                effectsMap.put("insightInfusionPending", true);
                Log.d("EffectManager", "Insight Infusion effect activated (pending display).");
                break;
            default:
                Log.w("EffectManager", "Unknown potion name in activatePotionEffect: " + potionName);
                break;
        }
    }

    static class ItemViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvItemName;
        TextView tvItemCost;
        Button btnBuy;

        ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.itemIcon);
            tvItemName = itemView.findViewById(R.id.itemName);
            tvItemCost = itemView.findViewById(R.id.itemPrice);
            btnBuy = itemView.findViewById(R.id.buyButton);
        }
    }
    
    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView tvHeader;
        
        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvHeader = itemView.findViewById(R.id.tvSectionHeader);
        }
    }

    static class EmptyShopViewHolder extends RecyclerView.ViewHolder {
        EmptyShopViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    public void updateUserStatus(int coins, int level) {
        int oldCoins = this.userCoins;
        this.userLevel = level;
        this.userCoins = coins;
        
        // Only refresh if purchasing power actually changed
        if (oldCoins != coins) {
            notifyDataSetChanged();
        }
    }

    private void configurePurchaseButton(ItemViewHolder holder, ShopItem item, 
                                       boolean canPurchase, boolean isAffordable, 
                                       boolean meetsLevelReq, boolean isProcessing) {
        
        if (isProcessing) {
            holder.btnBuy.setText("Processing...");
            holder.btnBuy.setEnabled(false);
            return;
        }
        
        if (!meetsLevelReq) {
            holder.btnBuy.setText("Level " + item.getMinLevel() + " required");
            holder.btnBuy.setEnabled(false);
            return;
        }
        
        if (!isAffordable) {
            holder.btnBuy.setText("Need " + (item.getCost() - userCoins) + " more coins");
            holder.btnBuy.setEnabled(false);
            return;
        }

        holder.btnBuy.setText("Buy for " + item.getCost());
        holder.btnBuy.setEnabled(true);
        holder.btnBuy.setOnClickListener(v -> handlePurchaseClick(item));
    }
    
    private void handlePurchaseClick(ShopItem item) {
        // Prevent double-clicking during network requests
        if (processingPurchases.contains(item.getId())) {
            return;
        }
        
        // Double-check affordability (user might have spent coins elsewhere)
        if (!purchaseHandler.canUserAfford(item)) {
            notifyDataSetChanged(); // Refresh to show current state
            return;
        }
        
        processingPurchases.add(item.getId());
        notifyDataSetChanged(); // Show processing state
        
        purchaseHandler.onPurchaseAttempt(item, new PurchaseCallback() {
            @Override
            public void onSuccess() {
                processingPurchases.remove(item.getId());
                // Don't auto-refresh here - let the parent activity handle it
                // when it updates user coins
            }
            
            @Override
            public void onFailure(String reason) {
                processingPurchases.remove(item.getId());
                notifyDataSetChanged(); // Remove processing state
                // TODO: Show error message to user
            }
        });
    }

    private void handleDirectPurchase(ShopItem item, ItemViewHolder holder) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.runTransaction(tx -> {
            DocumentSnapshot snapshot = tx.get(userRef);
            Long coins = snapshot.getLong("goldCoins");
            if (coins == null) coins = 0L;
            
            long cost1 = item.getCost();
            if (coins < cost1) {
                throw new FirebaseFirestoreException("Not enough coins", FirebaseFirestoreException.Code.ABORTED);
            }
            
            Map<String, Object> inv = (Map<String, Object>) snapshot.get("inventory");
            boolean isFirstPurchase = inv == null || !inv.containsKey(item.getId()) || 
                    ((Number)inv.getOrDefault(item.getId(), 0)).longValue() <= 0;
            
            tx.update(userRef, "goldCoins", FieldValue.increment(-cost1));
            tx.update(userRef, "inventory." + item.getId(), FieldValue.increment(1));
            
            // Auto-equip support item only if it's a consumable purchased
            if ("consumable".equals(item.getSlot())) { 
                tx.update(userRef, "equipped.support", item.getId());
            }
            
            return isFirstPurchase;
        }).addOnSuccessListener(isFirstPurchaseObj -> {
            boolean isFirst = isFirstPurchaseObj;
            Toast.makeText(ctx, "Purchased " + item.getName(), Toast.LENGTH_SHORT).show();
            
            // Update local inventory count
            long newCount = inventory.getOrDefault(item.getId(), 0L) + 1;
            inventory.put(item.getId(), newCount);
            
            // Update equipped map if it was a consumable
            if ("consumable".equals(item.getSlot())) {
                equipped.put("support", item.getId());
            }
            
            // Find and remove the item from the adapter's displayed list
            int currentPosition = -1;
            for (int i = 0; i < items.size(); i++) {
                 if (items.get(i).equals(item)) {
                     currentPosition = i;
                     break;
                 }
            }
            
            if (currentPosition != -1) {
                items.remove(currentPosition);
                notifyItemRemoved(currentPosition);
                Log.d(TAG, "Removed purchased item " + item.getId() + " from adapter list");
                
                // Check if the shop section is now empty
                int shopHeaderIndex = items.indexOf("Shop Items");
                boolean shopEmpty = true;
                if (shopHeaderIndex != -1 && shopHeaderIndex + 1 < items.size()) {
                    Object nextItem = items.get(shopHeaderIndex + 1);
                    if (nextItem instanceof ShopItem) {
                        ShopItem nextShopItem = (ShopItem) nextItem;
                        if (isShopItem.getOrDefault(nextShopItem.getId(), false)) {
                            shopEmpty = false;
                        }
                    } else if (EMPTY_SHOP_MARKER.equals(nextItem)){
                        shopEmpty = false;
                    }
                }

                // If the shop section became empty, insert the marker
                if (shopEmpty && shopHeaderIndex != -1) {
                     items.add(shopHeaderIndex + 1, EMPTY_SHOP_MARKER);
                     notifyItemInserted(shopHeaderIndex + 1);
                     Log.d(TAG, "Shop section became empty, inserted marker.");
                }
            }
            
            // Badge increment logic
            if (isFirst) {
                userRef.update("uniqueItemTypes", FieldValue.increment(1));
            }
            // Add to boughtShopItems in Firestore
            userRef.update("boughtShopItems", FieldValue.arrayUnion(item.getId()));
            
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Purchase failed", e);
            Toast.makeText(ctx, "Purchase failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        });
    }
} 