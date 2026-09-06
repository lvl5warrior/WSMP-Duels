package com.warriorssmp.duels;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** A single purchasable entry in the honor store - a name, a price in
 *  honor, and the actual item (with whatever stack size it was created
 *  with) a buyer receives. Created by an admin holding the reward item
 *  and typing a name + price, the same way kits are captured from an
 *  admin's current inventory. */
public class StoreItem {

    private final UUID id;
    private String name;
    private ItemStack reward;
    private int price;

    public StoreItem(UUID id, String name, ItemStack reward, int price) {
        this.id = id;
        this.name = name;
        this.reward = reward;
        this.price = price;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ItemStack getReward() {
        return reward;
    }

    public void setReward(ItemStack reward) {
        this.reward = reward;
    }

    public int getPrice() {
        return price;
    }

    public void setPrice(int price) {
        this.price = price;
    }
}
