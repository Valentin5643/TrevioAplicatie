package com.example.myapplication.aplicatiamea;

import java.util.HashMap;
import java.util.Map;
import android.graphics.Color;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents items in the shop - gear, potions, etc.
 * Items can be equipped in different slots, have different levels, rarities, and effects when used.
 */
public class ShopItem {
    // Core item data
    private String id;
    private String name;
    private long cost;
    private long minLevel;
    private int dailyLimit;
    private String iconUrl;
    private String description;
    private String slot;
    

    private String tier;
    private String effect;
    private long duration;

    private transient boolean isOwned = false;
    private transient int drawableResId = -1;
    private transient ItemRarity cachedRarity = null;
    private transient int cachedRarityColor = -1;

    private static final AtomicInteger TEMP_ID_COUNTER = new AtomicInteger(1000);

    public static final String SLOT_WEAPON = "weapon";
    public static final String SLOT_ARMOR = "armor";
    public static final String SLOT_HELMET = "helmet";
    public static final String SLOT_GREAVES = "greaves";
    public static final String SLOT_BOOTS = "boots";
    public static final String SLOT_CONSUMABLE = "consumable";
    public static final String SLOT_BADGE = "badge";

    private static final Map<ItemRarity, Integer> RARITY_COLORS = new HashMap<>();
    static {
        RARITY_COLORS.put(ItemRarity.COMMON, Color.parseColor("#9e9e9e"));      // Gray
        RARITY_COLORS.put(ItemRarity.UNCOMMON, Color.parseColor("#4CAF50"));    // Green  
        RARITY_COLORS.put(ItemRarity.RARE, Color.parseColor("#2196F3"));        // Blue
        RARITY_COLORS.put(ItemRarity.EPIC, Color.parseColor("#9C27B0"));        // Purple
        RARITY_COLORS.put(ItemRarity.LEGENDARY, Color.parseColor("#FFC107"));   // Gold
    }

    public ShopItem(String id, String name, long cost, long minLevel, int dailyLimit, 
                   String iconUrl, String description, String slot) {
        this.id = id;
        this.name = name;
        this.cost = cost;
        this.minLevel = minLevel;
        this.dailyLimit = dailyLimit;
        this.iconUrl = iconUrl;
        this.description = description;
        this.slot = slot;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getCost() {
        return cost;
    }

    public long getMinLevel() {
        return minLevel;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSlot() {
        return slot;
    }

    public ItemRarity getRarity() {
        if (cachedRarity != null) {
            return cachedRarity;
        }

        if (tier != null && !tier.isEmpty()) {
            if (tier.contains("Legendary")) {
                cachedRarity = ItemRarity.LEGENDARY;
            } else if (tier.contains("Superior")) {
                cachedRarity = ItemRarity.EPIC;
            } else if (tier.contains("Advanced")) {
                cachedRarity = ItemRarity.RARE;
            } else if (tier.contains("Improved")) {
                cachedRarity = ItemRarity.UNCOMMON;
            } else {
                cachedRarity = ItemRarity.COMMON;
            }
            return cachedRarity;
        }

        ItemRarity calculatedRarity;
        if (cost >= 2000 || minLevel >= 8) {
            calculatedRarity = ItemRarity.LEGENDARY;
        } else if (cost >= 1000 || minLevel >= 5) {
            calculatedRarity = ItemRarity.EPIC;  
        } else if (cost >= 500 || minLevel >= 3) {
            calculatedRarity = ItemRarity.RARE;
        } else if (cost >= 200) {
            calculatedRarity = ItemRarity.UNCOMMON;
        } else {
            calculatedRarity = ItemRarity.COMMON;
        }
        
        cachedRarity = calculatedRarity;
        return calculatedRarity;
    }
    
    public enum ItemRarity {
        COMMON(1.0f),
        UNCOMMON(1.5f),
        RARE(2.0f),
        EPIC(3.0f),
        LEGENDARY(5.0f);
        
        private float statBonus;
        
        ItemRarity(float statMultiplier) {
            this.statBonus = statMultiplier;
        }

    }
    
    @Override
    public String toString() {
        return name + " (" + cost + " coins, lvl " + minLevel + ")";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        ShopItem item = (ShopItem) o;
        return id != null && id.equals(item.id);
    }
    
    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}