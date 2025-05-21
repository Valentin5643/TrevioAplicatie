package com.example.myapplication.aplicatiamea;

/**
 * Model representing an item available in the in-app shop.
 */
public class ShopItem {
    private String id;
    private String name;
    private long cost;
    private long minLevel;
    private int dailyLimit;
    private String iconUrl;
    private String description;
    private String slot;

    // Empty constructor required for Firestore deserialization

    /**
     * Full constructor including description and equipment slot.
     */
    public ShopItem(String id, String name, long cost, long minLevel, int dailyLimit, String iconUrl, String description, String slot) {
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

}