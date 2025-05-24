package com.example.myapplication.aplicatiamea;

import android.graphics.drawable.Drawable;
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
    private String id;               // Unique ID (usually matches drawable name)
    private String name;             // What players see
    private long cost;               // Price in gold coins
    private long minLevel;           // Level gate to prevent newbies buying end-game gear
    private int dailyLimit;          // Purchase limit per day (0=unlimited, prevents economy abuse)
    private String iconUrl;          // Image URL (fallback to drawable if this fails)
    private String description;      // Flavor text or effect description
    private String slot;             // Equipment slot: "armor", "weapon", "helmet", etc.
    
    // Additional metadata
    private String tier;             // Quality tier: "Basic", "Improved", "Advanced", etc.
    private String effect;           // What happens when you use it (potions mainly)
    private long duration;           // Effect duration in milliseconds
    
    // Runtime/UI state - not saved to database
    private transient boolean isOwned = false;
    private transient int drawableResId = -1;
    private transient ItemRarity cachedRarity = null;
    private transient int cachedRarityColor = -1;
    
    // Auto-increment for temporary items created in code
    private static final AtomicInteger TEMP_ID_COUNTER = new AtomicInteger(1000);
    
    // Equipment slot constants - string constants to avoid typos
    public static final String SLOT_WEAPON = "weapon";
    public static final String SLOT_ARMOR = "armor";
    public static final String SLOT_HELMET = "helmet";
    public static final String SLOT_GREAVES = "greaves";
    public static final String SLOT_BOOTS = "boots";
    public static final String SLOT_CONSUMABLE = "consumable";
    public static final String SLOT_BADGE = "badge";
    
    // Rarity color scheme - cached for performance since this gets called a lot
    private static final Map<ItemRarity, Integer> RARITY_COLORS = new HashMap<>();
    static {
        RARITY_COLORS.put(ItemRarity.COMMON, Color.parseColor("#9e9e9e"));      // Gray
        RARITY_COLORS.put(ItemRarity.UNCOMMON, Color.parseColor("#4CAF50"));    // Green  
        RARITY_COLORS.put(ItemRarity.RARE, Color.parseColor("#2196F3"));        // Blue
        RARITY_COLORS.put(ItemRarity.EPIC, Color.parseColor("#9C27B0"));        // Purple
        RARITY_COLORS.put(ItemRarity.LEGENDARY, Color.parseColor("#FFC107"));   // Gold
    }

    /**
     * Empty constructor for Firebase deserialization
     */
    public ShopItem() {}

    /**
     * Main constructor for gear/equippable items
     */
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
    
    /**
     * Constructor with tier info (used for equipment)
     */
    public ShopItem(String id, String name, long cost, long minLevel, int dailyLimit, 
                   String iconUrl, String description, String slot, String tier) {
        this(id, name, cost, minLevel, dailyLimit, iconUrl, description, slot);
        this.tier = tier;
    }
    
    /**
     * Constructor for consumable items with effects
     */
    public ShopItem(String id, String name, long cost, long minLevel, String iconUrl, 
                   String description, String effect, long duration) {
        this(id, name, cost, minLevel, 0, iconUrl, description, SLOT_CONSUMABLE);
        this.effect = effect;
        this.duration = duration;
    }
    
    /**
     * Creates a basic temporary item with generated ID
     */
    public static ShopItem createTempItem(String name, long cost, String slot) {
        String tempId = "temp_" + TEMP_ID_COUNTER.getAndIncrement();
        return new ShopItem(tempId, name, cost, 1, 0, "", "", slot);
    }

    // Getters and setters for basic properties

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
    
    public void setCost(long cost) {
        this.cost = cost;
        // Clear cached rarity since cost affects rarity calculation
        cachedRarity = null;
    }

    public long getMinLevel() {
        return minLevel;
    }
    
    public void setMinLevel(long level) {
        this.minLevel = level;
        // Clear cached rarity since level affects rarity too
        cachedRarity = null;
    }

    public String getIconUrl() {
        return iconUrl;
    }
    
    public void setIconUrl(String url) {
        this.iconUrl = url;
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
    
    public void setSlot(String slot) {
        this.slot = slot;
    }
    
    public String getTier() {
        return tier;
    }
    
    public void setTier(String tier) {
        this.tier = tier;
    }
    
    public int getDailyLimit() {
        return dailyLimit;
    }
    
    public void setDailyLimit(int limit) {
        this.dailyLimit = limit;
    }
    
    public String getEffect() {
        return effect;
    }
    
    public void setEffect(String effect) {
        this.effect = effect;
    }
    
    public long getDuration() {
        return duration;
    }
    
    public void setDuration(long duration) {
        this.duration = duration;
    }

    // Client-side helpers that don't get stored in the DB
    
    public boolean isOwned() {
        return isOwned;
    }
    
    public void setOwned(boolean owned) {
        this.isOwned = owned;
    }
    
    public int getDrawableResId() {
        return drawableResId;
    }
    
    public void setDrawableResId(int resId) {
        this.drawableResId = resId;
    }
    
    /**
     * Checks if item is equippable (goes in an equipment slot)
     */
    public boolean isEquippable() {
        return SLOT_WEAPON.equals(slot) || 
               SLOT_ARMOR.equals(slot) || 
               SLOT_HELMET.equals(slot) ||
               SLOT_GREAVES.equals(slot) || 
               SLOT_BOOTS.equals(slot);
    }
    
    /**
     * Checks if item is consumable (can be used)
     */
    public boolean isConsumable() {
        return SLOT_CONSUMABLE.equals(slot);
    }
    
    /**
     * Checks if item is a badge (display only)
     */
    public boolean isBadge() {
        return SLOT_BADGE.equals(slot);
    }
    
    /**
     * Gets item rarity based on cost, level, and tier
     * Caches result for performance since this gets called a lot
     */
    public ItemRarity getRarity() {
        if (cachedRarity != null) {
            return cachedRarity;
        }
        
        // Tier-based rarity is most accurate when available
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
        
        // Fallback: estimate rarity from cost and level requirements
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
    
    /**
     * Gets the color associated with item's rarity
     */
    public int getRarityColor() {
        if (cachedRarityColor != -1) {
            return cachedRarityColor;
        }
        
        ItemRarity rarity = getRarity();
        Integer color = RARITY_COLORS.get(rarity);
        
        if (color != null) {
            cachedRarityColor = color;
            return color;
        }
        
        // Safety fallback if somehow rarity isn't found
        return Color.GRAY;
    }
    
    /**
     * Gets display text for rarity (for tooltips/item cards)
     */
    public String getRarityDisplayName() {
        return getRarity().name();
    }
    
    /**
     * Checks if this item is valid for player based on level requirement
     */
    public boolean isAvailableForLevel(long playerLevel) {
        return playerLevel >= minLevel;
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
        
        public float getStatMultiplier() {
            return statBonus;
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