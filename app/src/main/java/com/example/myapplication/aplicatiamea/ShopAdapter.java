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

public class ShopAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_SHOP_ITEM = 1;
    private static final int TYPE_INVENTORY_ITEM = 2;
    private static final int TYPE_EMPTY_SHOP = 3; // New view type for empty shop message
    private static final String EMPTY_SHOP_MARKER = "SHOP_EMPTY_MARKER"; // Special marker object
    
    private final Context context;
    private final List<Object> items; // Can now contain ShopItem or String (for headers)
    private final DocumentReference userRef;
    private final Map<String, Long> inventoryMap = new HashMap<>();
    private final Map<String, String> equippedIds = new HashMap<>();
    
    // Track if an item belongs to the shop or inventory
    private final Map<String, Boolean> isShopItem = new HashMap<>();

    /**
     * Construct adapter with context, item list, and reference to the current user's document
     */
    public ShopAdapter(Context context, List<Object> items, DocumentReference userRef) {
        this.context = context;
        this.items = items;
        this.userRef = userRef;
    }

    @Override
    public int getItemViewType(int position) {
        Object currentItem = items.get(position);
        if (currentItem instanceof String) {
            // Check if it's our special empty shop marker
            if (EMPTY_SHOP_MARKER.equals(currentItem)) {
                 return TYPE_EMPTY_SHOP;
            }
            // Otherwise, it's a regular header
            return TYPE_HEADER;
        } else if (currentItem instanceof ShopItem) {
            ShopItem item = (ShopItem) currentItem;
            boolean isShop = isShopItem.getOrDefault(item.getId(), false);
            Log.d("ShopAdapter", "getItemViewType - Item: " + item.getId() + ", isShop: " + isShop);
            return isShop ? TYPE_SHOP_ITEM : TYPE_INVENTORY_ITEM;
        }
        // Fallback, though should not happen with proper list population
        Log.w("ShopAdapter", "getItemViewType encountered unexpected item type at position: " + position);
        return TYPE_SHOP_ITEM; 
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Log.d("ShopAdapter", "onCreateViewHolder - viewType: " + viewType);
        LayoutInflater inflater = LayoutInflater.from(context);
        View view;
        switch (viewType) {
            case TYPE_HEADER:
                view = inflater.inflate(R.layout.item_section_header, parent, false);
                return new HeaderViewHolder(view);
            case TYPE_EMPTY_SHOP:
                view = inflater.inflate(R.layout.item_empty_shop, parent, false);
                return new EmptyShopViewHolder(view);
            case TYPE_SHOP_ITEM:
            case TYPE_INVENTORY_ITEM: // Both use the same item layout
            default:
                view = inflater.inflate(R.layout.item_shop, parent, false);
                return new ItemViewHolder(view);
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
                // No specific binding needed for EmptyShopViewHolder currently
                // The layout itself contains the message
                break;
            case TYPE_SHOP_ITEM:
            case TYPE_INVENTORY_ITEM:
                ItemViewHolder itemHolder = (ItemViewHolder) holder;
                ShopItem item = (ShopItem) items.get(position);
                boolean isShop = isShopItem.getOrDefault(item.getId(), false);
                Log.d("ShopAdapter", "onBindViewHolder - Item: " + item.getId() + 
                      ", isShop: " + isShop + 
                      ", viewType: " + (viewType == TYPE_SHOP_ITEM ? "SHOP_ITEM" : "INVENTORY_ITEM"));
                bindItemViewHolder(itemHolder, item, isShop);
                break;
        }
    }

    private void bindItemViewHolder(ItemViewHolder holder, ShopItem item, boolean isShop) {
        holder.tvItemName.setText(item.getName());
        holder.tvItemName.setTextColor(context.getResources().getColor(R.color.text_primary)); // Default color
        holder.tvItemName.setTypeface(null, Typeface.NORMAL); // Default style
        holder.itemView.setBackgroundResource(android.R.color.transparent); // Default background
        holder.itemView.setOnClickListener(v -> tryConsumePotion(item, holder));
        holder.ivIcon.clearAnimation(); // Clear any previous animations

        long cost = item.getCost();
        long owned = inventoryMap.getOrDefault(item.getId(), 0L);
        String costText = cost + " coins" + (owned > 0 && !isShop ? " (x" + owned + ")" : ""); // Show owned count only for inventory items
        
        holder.tvItemCost.setText(costText);
        holder.tvItemName.setVisibility(View.VISIBLE);
        
        boolean isPotion = item.getId().startsWith("potion_");
        boolean isEquippable = "armor".equals(item.getSlot()) || "weapon".equals(item.getSlot())
                || "helmet".equals(item.getSlot()) || "greaves".equals(item.getSlot())
                || "boots".equals(item.getSlot());

        // --- Visibility of Cost and Buy Button ---
        if (isShop) {
            holder.tvItemCost.setVisibility(View.VISIBLE);
            holder.btnBuy.setVisibility(View.VISIBLE);
        } else {
            holder.tvItemCost.setVisibility(View.GONE);
            holder.btnBuy.setVisibility(View.GONE);
        }

        // --- Potion Specific Logic ---
        if (isPotion) {
            // Apply potion styling
            String potionName = item.getName();
            holder.tvItemName.setText("✧ " + potionName + " ✧");
            holder.tvItemName.setTextColor(Color.rgb(128, 0, 128)); // Purple color
            holder.tvItemName.setTypeface(null, Typeface.BOLD_ITALIC);
            holder.tvItemName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);

            // Handle animation for rare potions (only if in shop?)
            if (isShop && "Rare Alchemical Concoction".equals(item.getDescription())) {
                Animation shake = new AlphaAnimation(0.7f, 1.0f);
                shake.setDuration(1000);
                shake.setRepeatMode(Animation.REVERSE);
                shake.setRepeatCount(Animation.INFINITE);
                holder.ivIcon.startAnimation(shake);
            }

            // Set potion image
            String drawableName; // (Switch statement for potion drawables remains the same)
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
            @SuppressLint("DiscouragedApi") int resId = context.getResources().getIdentifier(drawableName, "drawable", context.getPackageName());
            if (resId != 0) {
                holder.ivIcon.setImageResource(resId);
            } else {
                Log.e("ShopAdapter", "Potion drawable not found: " + drawableName);
                holder.ivIcon.setImageResource(android.R.drawable.ic_dialog_info);
            }

            // Make inventory potions clickable to consume
            if (!isShop && owned > 0) {
                holder.itemView.setOnClickListener(v -> tryConsumePotion(item, holder));
                holder.itemView.setBackgroundResource(R.drawable.item_background_selectable);
            } 
            // Potions in the shop are handled by the generic shop logic below (buy button)

        } else { 
            // --- Regular Item Image Loading ---
            if (item.getIconUrl() != null && !item.getIconUrl().isEmpty()) {
                // ... (Glide/Resource loading logic remains the same)
                 if (item.getIconUrl().startsWith("http")) {
                     Glide.with(context).load(item.getIconUrl()).into(holder.ivIcon);
                 } else {
                     @SuppressLint("DiscouragedApi") int resId = context.getResources().getIdentifier(item.getIconUrl(), "drawable", context.getPackageName());
                     holder.ivIcon.setImageResource(resId != 0 ? resId : R.drawable.ic_baseline_save_24);
                 }
            } else {
                @SuppressLint("DiscouragedApi") int resId = context.getResources().getIdentifier(item.getId(), "drawable", context.getPackageName());
                holder.ivIcon.setImageResource(resId != 0 ? resId : R.drawable.ic_baseline_save_24);
            }
        }

        // --- Click Listeners ---
        if (isShop) {
            // Setup Buy Button Listener
            holder.btnBuy.setOnClickListener(v -> {
                // Purchase logic...
                // (Existing purchase transaction logic remains the same)
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
                     Toast.makeText(context, "Purchased " + item.getName(), Toast.LENGTH_SHORT).show();
                     
                     // --- Immediate UI Update for Shop ---                    
                     // 1. Update local inventory count (used by other logic if needed)
                     long newCount = inventoryMap.getOrDefault(item.getId(), 0L) + 1;
                     inventoryMap.put(item.getId(), newCount);
                     
                     // 2. Update equipped map if it was a consumable
                     if ("consumable".equals(item.getSlot())) {
                         equippedIds.put("support", item.getId());
                     }
                     
                     // 3. Find and remove the item from the adapter's displayed list
                     int currentPosition = -1;
                     for (int i = 0; i < items.size(); i++) {
                          if (items.get(i).equals(item)) { // Use equals() for ShopItem comparison if overridden, otherwise reference equality
                              currentPosition = i;
                              break;
                          }
                     }
                     
                     if (currentPosition != -1) {
                         items.remove(currentPosition);
                         notifyItemRemoved(currentPosition);
                         Log.d("ShopAdapter", "Removed purchased item " + item.getId() + " from adapter list at position " + currentPosition);
                         
                         // 4. Check if the shop section is now empty
                         int shopHeaderIndex = items.indexOf("Shop Items");
                         boolean shopEmpty = true; // Assume empty
                         if (shopHeaderIndex != -1 && shopHeaderIndex + 1 < items.size()) {
                             // Check if the item right after the header is NOT a ShopItem marked as belonging to the shop
                             Object nextItem = items.get(shopHeaderIndex + 1);
                             if (nextItem instanceof ShopItem) {
                                 ShopItem nextShopItem = (ShopItem) nextItem;
                                 if (isShopItem.getOrDefault(nextShopItem.getId(), false)) {
                                     shopEmpty = false; // Found another shop item
                                 }
                             } else if (EMPTY_SHOP_MARKER.equals(nextItem)){
                                 // It's already marked as empty, do nothing
                                 shopEmpty = false; // To prevent adding the marker again
                             }
                         }

                         // 5. If the shop section became empty, insert the marker
                         if (shopEmpty && shopHeaderIndex != -1) {
                              items.add(shopHeaderIndex + 1, EMPTY_SHOP_MARKER);
                              notifyItemInserted(shopHeaderIndex + 1);
                              Log.d("ShopAdapter", "Shop section became empty, inserted marker.");
                         }
                         
                     } else {
                         Log.w("ShopAdapter", "Could not find purchased item " + item.getId() + " in adapter list to remove.");
                     }
                     // --- End Immediate UI Update ---
                     
                     // Badge increment logic (can remain here)
                     if (isFirst) {
                         userRef.update("uniqueItemTypes", FieldValue.increment(1));
                     }
                     // Add to boughtShopItems in Firestore
                     userRef.update("boughtShopItems", FieldValue.arrayUnion(item.getId()));
                     // NOTE: The purchased item will appear in the correct inventory section
                     // automatically when the Firestore SnapshotListener triggers an update
                     // in ShopActivity, which calls adapter.setItems() again.
                     
                     Log.d("ShopAdapter", "DEBUG: Purchase success. item=" + item.getId() + ", cost=" + item.getCost() + ", userRef=" + (userRef != null ? userRef.getId() : "null"));
                     
                 }).addOnFailureListener(e -> {
                     Log.e("ShopAdapter", "Purchase failed", e);
                     Toast.makeText(context, "Purchase failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                     Log.d("ShopAdapter", "DEBUG: Purchase failed. item=" + item.getId() + ", reason=" + e.getMessage() + ", userRef=" + (userRef != null ? userRef.getId() : "null"));
                 });
            });
        } else if (!isShop && isEquippable) {
            // Setup Equip Click Listener for inventory items
            holder.itemView.setOnClickListener(v -> {
                // Equip logic...
                // (Existing equip logic remains the same)
                FirebaseFirestore.getInstance();
                String fieldPath = "equipped." + item.getSlot();
                 userRef.update(fieldPath, item.getId())
                     .addOnSuccessListener(aVoid -> Toast.makeText(context, "Equipped " + item.getName(), Toast.LENGTH_SHORT).show())
                     .addOnFailureListener(e -> {
                         Log.e("ShopAdapter", "Failed to equip", e);
                         Toast.makeText(context, "Equip failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                     });
            });
        } 
        // Note: Potion consumption click listener is handled within the isPotion block above
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** Update inventory counts and refresh UI */
    public void setInventory(Map<String, Long> inventory) {
        inventoryMap.clear();
        inventoryMap.putAll(inventory);
        notifyDataSetChanged();
    }

    /** Update the list of items and refresh UI */
    public void setItems(List<ShopItem> shopItems, List<ShopItem> inventoryItems) {
        items.clear();
        isShopItem.clear();
        
        // Add "Shop" header and items OR empty shop message
        items.add("Shop Items"); // Always add the header first
        if (shopItems == null || shopItems.isEmpty()) {
             Log.d("ShopAdapter", "Shop items list is empty, adding empty marker.");
             items.add(EMPTY_SHOP_MARKER); // Add the empty marker instead of items
        } else {
            for (ShopItem item : shopItems) {
                isShopItem.put(item.getId(), true);
                items.add(item);
                Log.d("ShopAdapter", "Added shop item: " + item.getId() + ", isShop=" + isShopItem.get(item.getId()));
            }
        }
        
        // Process inventory items separately and mark them as non-shop
        if (!inventoryItems.isEmpty()) {
            List<ShopItem> equipmentItems = new ArrayList<>();
            List<ShopItem> potionItems = new ArrayList<>();
            
            for (ShopItem item : inventoryItems) {
                isShopItem.put(item.getId(), false);
                Log.d("ShopAdapter", "Processing inventory item: " + item.getId() + ", marked as isShop=false");

                if ("consumable".equals(item.getSlot())) {
                    potionItems.add(item);
                } else {
                    String equippedInSlot = equippedIds.get(item.getSlot());
                    if (equippedInSlot == null || !equippedInSlot.equals(item.getId())) {
                        equipmentItems.add(item);
                    }
                }
            }
            
            if (!potionItems.isEmpty()) {
                items.add("Support Items");
                for (ShopItem item : potionItems) {
                    items.add(item);
                    Log.d("ShopAdapter", "Added potion item: " + item.getId() + ", isShop=" + isShopItem.get(item.getId()));
                }
            }
            
            if (!equipmentItems.isEmpty()) {
                items.add("Your Gear");
                for (ShopItem item : equipmentItems) {
                    items.add(item);
                    Log.d("ShopAdapter", "Added gear item: " + item.getId() + ", isShop=" + isShopItem.get(item.getId()));
                }
            }
        }
        
        notifyDataSetChanged();
        Log.d("ShopAdapter", "DEBUG: Shop refreshed. shopItems=" + (shopItems == null ? 0 : shopItems.size()) + ", userRef=" + (userRef != null ? userRef.getId() : "null"));
    }

    /** Update equipped gear and support IDs and refresh UI */
    public void setEquipped(String armorId, String weaponId, String helmetId, String greavesId, String bootsId, String supportId) {
        equippedIds.clear();
        if (armorId != null) equippedIds.put("armor", armorId);
        if (weaponId != null) equippedIds.put("weapon", weaponId);
        if (helmetId != null) equippedIds.put("helmet", helmetId);
        if (greavesId != null) equippedIds.put("greaves", greavesId);
        if (bootsId != null) equippedIds.put("boots", bootsId);
        if (supportId != null) equippedIds.put("support", supportId);
        // Maybe add visual indication on the item itself if equipped?
        notifyDataSetChanged();
    }

    /** Remove an item from this adapter's list */
    public void removeItem(ShopItem item) {
        int index = items.indexOf(item);
        if (index != -1) {
            items.remove(index);
            notifyItemRemoved(index);
        }
    }

    /**
     * Handles the logic to consume a potion when clicked in the support slot.
     */
    private void tryConsumePotion(ShopItem item, ItemViewHolder holder) {
        Log.d("ShopAdapter", "Attempting to consume potion: " + item.getName());
        final String itemId = item.getId();

        // Disable the item view to prevent double-clicks
        holder.itemView.setEnabled(false);

        // 1. Preliminary check using local inventory map
        long currentOwned = inventoryMap.getOrDefault(itemId, 0L);
        if (currentOwned <= 0) {
            Toast.makeText(context, "No " + item.getName() + " left!", Toast.LENGTH_SHORT).show();
            holder.itemView.setEnabled(true); // Re-enable if failed early
            return;
        }

        // 2. Run Firestore transaction to consume
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.runTransaction(transaction -> {
            DocumentSnapshot userSnap = transaction.get(userRef);

            // --- Get current inventory ---
            Map<String, Object> inventory = new HashMap<>();
            if (userSnap.contains("inventory") && userSnap.get("inventory") instanceof Map) {
                try {
                    inventory = new HashMap<>((Map<String, Object>) userSnap.get("inventory"));
                } catch (ClassCastException e) { Log.e("ShopAdapter", "Inventory field is not a Map"); }
            }

            long countInDb = 0;
            if (inventory.containsKey(itemId)) {
                Object countObj = inventory.get(itemId);
                if (countObj instanceof Number) {
                    countInDb = ((Number) countObj).longValue();
                }
            }

            // Verify count in DB before decrementing
            if (countInDb <= 0) {
                throw new FirebaseFirestoreException("Potion count in DB is already zero.",
                        FirebaseFirestoreException.Code.ABORTED);
            }

            // --- Get current effects map ---
            Map<String, Object> effectsMap = new HashMap<>();
            if (userSnap.contains("activeEffects") && userSnap.get("activeEffects") instanceof Map) {
                try {
                    effectsMap = new HashMap<>((Map<String, Object>) userSnap.get("activeEffects"));
                } catch (ClassCastException e) { Log.e("ShopAdapter", "activeEffects field is not a Map"); }
            }

            // --- Apply the effect logic to the map ---
            activatePotionEffect(item, effectsMap);

            // --- Update Firestore atomically ---
            // Decrement inventory
            transaction.update(userRef, "inventory." + itemId, FieldValue.increment(-1));
            // Always unequip support slot after consumption
            transaction.update(userRef, "equipped.support", FieldValue.delete());
            // Save the modified effects map
            transaction.update(userRef, "activeEffects", effectsMap);

            Log.d("ShopAdapter", "Potion consumption transaction: Updated inventory, equipped slot, and activeEffects.");

            return countInDb - 1; // Return the new inventory count
        }).addOnSuccessListener(newCountLong -> {
            Log.d("ShopAdapter", "Potion consumption transaction successful. New count: " + newCountLong);
            Toast.makeText(context, "You consumed the mysterious " + item.getName() + "...", Toast.LENGTH_LONG).show();
            inventoryMap.put(itemId, newCountLong);
            equippedIds.remove("support");
            removeItem(item);
            notifyDataSetChanged();
            holder.itemView.setEnabled(true); // Re-enable after success
            Log.d("ShopAdapter", "DEBUG: Potion consumed. item=" + item.getId() + ", newCount=" + newCountLong + ", userRef=" + (userRef != null ? userRef.getId() : "null"));
        }).addOnFailureListener(e -> {
            Log.e("ShopAdapter", "Potion consumption transaction failed", e);
            Toast.makeText(context, "Failed to use potion: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            holder.itemView.setEnabled(true); // Re-enable after failure
        });
    }

    /**
     * Activates the specific effect of a potion in EffectManager.
     * Called after a successful consumption transaction.
     */
    private void activatePotionEffect(ShopItem item, Map<String, Object> effectsMap) {
        String potionName = item.getName();
        long currentTime = System.currentTimeMillis();
        long oneHourInMillis = 3600 * 1000;
        long thirtyMinutesInMillis = 30 * 60 * 1000;

        Log.d("ShopAdapter", "DEBUG: Potion activated. item=" + item.getId() + ", effect=" + potionName + ", userRef=" + (userRef != null ? userRef.getId() : "null"));

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
        // NOTE: Saving the modified effectsMap happens in the calling method (tryConsumePotion)
    }

    static class ItemViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvItemName;
        TextView tvItemCost;
        Button btnBuy;

        ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvItemName = itemView.findViewById(R.id.tvItemName);
            tvItemCost = itemView.findViewById(R.id.tvItemCost);
            btnBuy = itemView.findViewById(R.id.btnBuy);
        }
    }
    
    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView tvHeader;
        
        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvHeader = itemView.findViewById(R.id.tvSectionHeader);
        }
    }

    // New ViewHolder for the empty shop message
    static class EmptyShopViewHolder extends RecyclerView.ViewHolder {
        EmptyShopViewHolder(@NonNull View itemView) {
            super(itemView);
            // No specific views to bind here as the layout holds the text
        }
    }
} 